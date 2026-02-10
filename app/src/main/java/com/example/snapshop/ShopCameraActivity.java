package com.example.snapshop;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Vision API called via VisionApiHelper.analyzeImageBlocking() on background thread

/**
 * ShopCameraActivity - Shopping camera with two-layer AI
 *
 * Layer 1 (Real-time, FREE): YOLO26 runs locally for bounding boxes and
 *     rough category labels. Helps user aim at objects. Tappable chips
 *     provide a quick-search shortcut via YOLO class name mapping.
 *
 * Layer 2 (On-demand, 1 API call): When user taps "Capture & Identify",
 *     the current frame is frozen and sent to Google Vision API for
 *     precise brand/model/product identification. Works even when YOLO
 *     detects nothing (e.g. a bag of dried blueberries).
 *
 * This design keeps Vision API usage to ~1 call per user action,
 * staying well within the 1000 free calls/month quota.
 */
public class ShopCameraActivity extends AppCompatActivity {

    private static final String TAG = "ShopCamera";
    private static final int REQUEST_PERMISSION = 101;

    private Yolo26Ncnn yolo26Ncnn = new Yolo26Ncnn();
    private PreviewView previewView;
    private OverlayView overlayView;
    private ChipGroup chipGroup;
    private Button btnCaptureSearch;
    private Button btnBack;
    private TextView tvStatus;

    // Freeze frame UI
    private ImageView ivFrozenFrame;
    private LinearLayout loadingOverlay;
    private TextView tvLoadingStatus;

    private boolean isDetecting = true;
    private boolean isCapturing = false; // Lock to prevent double-tap
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;

    private long lastDetectTime = 0;
    private static final long DETECT_INTERVAL = 150; // ms between YOLO frames

    // Track currently detected labels to avoid chip flickering
    private final Set<String> currentLabels = new HashSet<>();
    private Bitmap lastCaptureBitmap = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shop_camera);

        previewView = findViewById(R.id.previewView);
        overlayView = findViewById(R.id.overlayView);
        chipGroup = findViewById(R.id.chipGroup);
        btnCaptureSearch = findViewById(R.id.btnCaptureSearch);
        btnBack = findViewById(R.id.btnBack);
        tvStatus = findViewById(R.id.tvStatus);

        // Freeze frame views
        ivFrozenFrame = findViewById(R.id.ivFrozenFrame);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        tvLoadingStatus = findViewById(R.id.tvLoadingStatus);

        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Back button
        btnBack.setOnClickListener(v -> {
            if (ivFrozenFrame.getVisibility() == View.VISIBLE) {
                // If frozen, unfreeze and resume camera
                unfreezeCamera();
            } else {
                finish();
            }
        });

        // Capture & Search — always enabled, no YOLO requirement
        btnCaptureSearch.setOnClickListener(v -> handleCaptureSearch());

        // Check permissions and start
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            reloadModel();
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            reloadModel();
            startCamera();
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void reloadModel() {
        boolean ret = yolo26Ncnn.loadModel(getAssets(), 0, 0); // CPU only
        if (!ret) {
            Log.e(TAG, "Failed to load YOLO model");
            Toast.makeText(this, "Failed to load model", Toast.LENGTH_SHORT).show();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (Exception e) {
                Log.e(TAG, "Camera init failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        cameraProvider.unbindAll();

        CameraSelector selector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        Preview preview = new Preview.Builder()
                .setTargetResolution(new Size(640, 480))
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(cameraExecutor, this::analyzeImage);

        try {
            cameraProvider.bindToLifecycle(this, selector, preview, analysis);
            tvStatus.setText("Point camera at any product, then tap capture");
        } catch (Exception e) {
            Log.e(TAG, "Use case binding failed", e);
        }
    }

    // ==================== Layer 1: YOLO Real-time Detection ====================

    private void analyzeImage(ImageProxy image) {
        if (!isDetecting) {
            image.close();
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastDetectTime < DETECT_INTERVAL) {
            image.close();
            return;
        }
        lastDetectTime = currentTime;

        try {
            Bitmap bitmap = imageProxyToBitmap(image);
            if (bitmap == null) {
                image.close();
                return;
            }

            // Store last bitmap for capture (thread-safe)
            synchronized (this) {
                if (lastCaptureBitmap != null) {
                    lastCaptureBitmap.recycle();
                }
                lastCaptureBitmap = bitmap.copy(bitmap.getConfig(), false);
            }

            // Run YOLO detection
            Yolo26Ncnn.Obj[] objects = yolo26Ncnn.detect(bitmap);

            // Update UI on main thread
            runOnUiThread(() -> {
                int previewWidth = bitmap.getWidth();
                int previewHeight = bitmap.getHeight();
                overlayView.setPreviewSize(previewWidth, previewHeight);
                overlayView.setResults(objects);
                updateChips(objects);
            });

            bitmap.recycle();
        } catch (Exception e) {
            Log.e(TAG, "Detection failed", e);
        } finally {
            image.close();
        }
    }

    /**
     * Update detection label chips (YOLO quick-search shortcuts)
     */
    private void updateChips(Yolo26Ncnn.Obj[] objects) {
        Set<String> newLabels = new HashSet<>();
        if (objects != null) {
            for (Yolo26Ncnn.Obj obj : objects) {
                if (obj.label != null && obj.prob > 0.5f) {
                    newLabels.add(obj.label);
                }
            }
        }

        // Only update if labels changed (avoid flickering)
        if (!newLabels.equals(currentLabels)) {
            currentLabels.clear();
            currentLabels.addAll(newLabels);
            chipGroup.removeAllViews();

            for (String label : newLabels) {
                Chip chip = new Chip(this);
                chip.setText("\uD83D\uDD0D " + label);
                chip.setClickable(true);
                chip.setCheckable(false);
                chip.setChipBackgroundColor(
                        android.content.res.ColorStateList.valueOf(0xFF555555));
                chip.setTextColor(getResources().getColor(android.R.color.white));

                // Tap chip → hint to use Capture for precise identification
                chip.setOnClickListener(v -> {
                    Toast.makeText(ShopCameraActivity.this,
                            "Detected: " + label + "\nTap 'Capture & Identify' for precise product match",
                            Toast.LENGTH_LONG).show();
                    // Flash the capture button to draw attention
                    btnCaptureSearch.setBackgroundTintList(
                            android.content.res.ColorStateList.valueOf(0xFFFF9800));
                    btnCaptureSearch.postDelayed(() ->
                            btnCaptureSearch.setBackgroundTintList(
                                    android.content.res.ColorStateList.valueOf(0xFF4CAF50)), 600);
                });

                chipGroup.addView(chip);
            }

            if (newLabels.isEmpty()) {
                tvStatus.setText("Point camera at any product, then tap capture");
            } else {
                String firstLabel = newLabels.iterator().next();
                tvStatus.setText("Detected: " + firstLabel + " \u2192 Tap 'Capture & Identify' for exact product");
            }
        }
    }

    // ==================== Layer 2: Capture & Vision API ====================

    /**
     * Handle "Capture & Identify Product" button.
     *
     * Works regardless of whether YOLO detected anything:
     * 1. Freeze the camera frame
     * 2. Show loading overlay
     * 3. Send image to Google Vision API
     * 4. Build search query from Vision result (+ YOLO hint if available)
     * 5. Navigate to ProductResultsActivity
     */
    private void handleCaptureSearch() {
        if (isCapturing) return; // Prevent double-tap

        // Grab the latest frame
        Bitmap capturedBitmap;
        synchronized (this) {
            if (lastCaptureBitmap == null) {
                Toast.makeText(this, "Camera is starting, please wait...", Toast.LENGTH_SHORT).show();
                return;
            }
            capturedBitmap = lastCaptureBitmap.copy(lastCaptureBitmap.getConfig(), false);
        }

        isCapturing = true;
        isDetecting = false; // Pause YOLO detection

        // Step 1: Freeze camera — show captured frame
        ivFrozenFrame.setImageBitmap(capturedBitmap);
        ivFrozenFrame.setVisibility(View.VISIBLE);
        overlayView.clearResults();
        overlayView.setVisibility(View.GONE);

        // Step 2: Show loading overlay
        loadingOverlay.setVisibility(View.VISIBLE);
        tvLoadingStatus.setText("Analyzing product...");
        btnCaptureSearch.setEnabled(false);
        btnCaptureSearch.setText("Analyzing...");

        // Grab YOLO hint (might be empty — that's fine)
        String yoloHint = currentLabels.isEmpty() ? null : currentLabels.iterator().next();

        // Step 3: Call Google Vision API in background
        new Thread(() -> {
            try {
                // Check if API key is configured
                String apiKey = BuildConfig.GOOGLE_VISION_API_KEY;
                if (apiKey == null || apiKey.isEmpty()) {
                    runOnUiThread(() -> {
                        handleVisionFallback(yoloHint, "No Vision API key configured");
                    });
                    return;
                }

                runOnUiThread(() -> tvLoadingStatus.setText("Sending to Google Vision AI..."));

                // Call Vision API (synchronous on this background thread)
                VisionApiHelper.VisionResult visionResult = callVisionApiSync(capturedBitmap);

                runOnUiThread(() -> {
                    if (visionResult != null) {
                        handleVisionSuccess(visionResult, yoloHint);
                    } else {
                        handleVisionFallback(yoloHint, "Vision API returned no results");
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Vision API call failed", e);
                runOnUiThread(() -> {
                    handleVisionFallback(yoloHint, "Network error: " + e.getMessage());
                });
            } finally {
                capturedBitmap.recycle();
            }
        }).start();
    }

    /**
     * Synchronously call Vision API (runs on background thread).
     * Uses the @JvmStatic blocking method — no coroutines needed from Java.
     */
    private VisionApiHelper.VisionResult callVisionApiSync(Bitmap bitmap) {
        try {
            return VisionApiHelper.analyzeImageBlocking(bitmap);
        } catch (Exception e) {
            Log.e(TAG, "Vision API sync call failed", e);
            return null;
        }
    }

    /**
     * Vision API returned a result — build precise search query and navigate.
     */
    private void handleVisionSuccess(VisionApiHelper.VisionResult result, String yoloHint) {
        // Build multi-signal search query
        String searchQuery = VisionApiHelper.INSTANCE.buildSearchQueryFromResult(result, yoloHint);

        if (searchQuery.isEmpty()) {
            // Vision API returned data but no useful labels
            handleVisionFallback(yoloHint, "Could not identify the product");
            return;
        }

        tvLoadingStatus.setText("Found: " + searchQuery);
        Log.d(TAG, "Vision search query: " + searchQuery);

        // Brief delay to show the result, then navigate
        tvLoadingStatus.postDelayed(() -> {
            resetCaptureUI();
            openProductSearch(searchQuery, "vision_api");
        }, 800);
    }

    /**
     * Vision API failed or returned nothing — fall back gracefully.
     * If YOLO had a detection, use that. Otherwise tell the user.
     */
    private void handleVisionFallback(String yoloHint, String reason) {
        Log.w(TAG, "Vision fallback: " + reason);

        if (yoloHint != null && !yoloHint.isEmpty()) {
            // Use YOLO label as fallback
            String searchQuery = ShopHelper.INSTANCE.mapDetectionToSearchQuery(yoloHint);
            Toast.makeText(this, "Using AI detection: " + searchQuery, Toast.LENGTH_SHORT).show();
            resetCaptureUI();
            openProductSearch(searchQuery, "yolo_fallback");
        } else {
            // No YOLO, no Vision — ask user to try again
            Toast.makeText(this,
                    reason + "\nPlease try again with better lighting or angle.",
                    Toast.LENGTH_LONG).show();
            unfreezeCamera();
        }
    }

    /**
     * Unfreeze camera: hide frozen frame, resume YOLO detection.
     */
    private void unfreezeCamera() {
        resetCaptureUI();
        ivFrozenFrame.setVisibility(View.GONE);
        overlayView.setVisibility(View.VISIBLE);
        isDetecting = true;
        isCapturing = false;
    }

    /**
     * Reset button/overlay state after capture flow completes.
     */
    private void resetCaptureUI() {
        loadingOverlay.setVisibility(View.GONE);
        ivFrozenFrame.setVisibility(View.GONE);
        overlayView.setVisibility(View.VISIBLE);
        btnCaptureSearch.setEnabled(true);
        btnCaptureSearch.setText("\uD83D\uDCF8  Capture & Identify Product");
        isDetecting = true;
        isCapturing = false;
    }

    // ==================== Navigation ====================

    /**
     * Open product results page with the search query.
     */
    private void openProductSearch(String query, String source) {
        Intent intent = new Intent(this, ProductResultsActivity.class);
        intent.putExtra("search_query", query);
        intent.putExtra("search_source", source);
        startActivity(intent);
    }

    // ==================== Image Conversion ====================

    /**
     * Convert ImageProxy (YUV_420_888) to Bitmap
     */
    private Bitmap imageProxyToBitmap(ImageProxy image) {
        try {
            ImageProxy.PlaneProxy[] planes = image.getPlanes();
            int width = image.getWidth();
            int height = image.getHeight();

            ByteBuffer yBuffer = planes[0].getBuffer();
            int yRowStride = planes[0].getRowStride();
            int yPixelStride = planes[0].getPixelStride();

            ByteBuffer uBuffer = planes[1].getBuffer();
            int uvRowStride = planes[1].getRowStride();
            int uvPixelStride = planes[1].getPixelStride();

            ByteBuffer vBuffer = planes[2].getBuffer();

            int nv21Size = width * height + (width * height / 2);
            byte[] nv21 = new byte[nv21Size];

            int yPos = 0;
            if (yRowStride == width && yPixelStride == 1) {
                yBuffer.get(nv21, 0, width * height);
                yPos = width * height;
            } else {
                for (int row = 0; row < height; row++) {
                    yBuffer.position(row * yRowStride);
                    for (int col = 0; col < width; col++) {
                        nv21[yPos++] = yBuffer.get(row * yRowStride + col * yPixelStride);
                    }
                }
                yBuffer.rewind();
            }

            int uvHeight = height / 2;
            int uvWidth = width / 2;
            int uvPos = width * height;

            if (uvPixelStride == 2 && uvRowStride == width) {
                vBuffer.position(0);
                int uvSize = Math.min(vBuffer.remaining(), width * height / 2);
                vBuffer.get(nv21, uvPos, uvSize);
            } else if (uvPixelStride == 1) {
                for (int row = 0; row < uvHeight; row++) {
                    for (int col = 0; col < uvWidth; col++) {
                        int uvIndex = row * uvRowStride + col * uvPixelStride;
                        nv21[uvPos++] = vBuffer.get(uvIndex);
                        nv21[uvPos++] = uBuffer.get(uvIndex);
                    }
                }
            } else {
                for (int row = 0; row < uvHeight; row++) {
                    for (int col = 0; col < uvWidth; col++) {
                        int uvIndex = row * uvRowStride + col * uvPixelStride;
                        nv21[uvPos++] = vBuffer.get(uvIndex);
                        nv21[uvPos++] = uBuffer.get(uvIndex);
                    }
                }
            }

            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), 90, out);
            byte[] imageBytes = out.toByteArray();

            Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

            Matrix matrix = new Matrix();
            int rotationDegrees = image.getImageInfo().getRotationDegrees();
            if (rotationDegrees != 0) {
                matrix.postRotate(rotationDegrees);
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                        bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (rotated != bitmap) bitmap.recycle();
                return rotated;
            }

            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Image conversion failed", e);
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        synchronized (this) {
            if (lastCaptureBitmap != null) {
                lastCaptureBitmap.recycle();
                lastCaptureBitmap = null;
            }
        }
    }
}
