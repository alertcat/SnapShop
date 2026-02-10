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

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// LLM Vision called via LlmVisionHelper.identifyProductBlocking() on background thread

/**
 * ShopCameraActivity - Shopping camera with AI product identification
 *
 * YOLO26 runs locally for real-time bounding boxes only (visual aid).
 * YOLO labels are NOT shown to users because COCO 80-class labels are
 * too coarse for shopping (e.g. "cell phone" instead of "iPhone 16 Pro Max",
 * or misidentifying a phone as "remote").
 *
 * When user taps "Capture & Identify", the frame is sent to a multimodal LLM
 * (via OpenRouter) for precise brand/model/product identification.
 *
 * Uses tiered cascade strategy:
 *   Tier 1: gemini-2.5-flash-lite (cheap, fast, ~$0.10/1000 images)
 *   Tier 2: gemini-3-flash-preview (stronger, auto-upgrade if Tier 1 uncertain)
 *
 * Image is resized to ≤384px before sending → 258 tokens → minimal cost.
 */
public class ShopCameraActivity extends AppCompatActivity {

    private static final String TAG = "ShopCamera";
    private static final int REQUEST_PERMISSION = 101;

    private Yolo26Ncnn yolo26Ncnn = new Yolo26Ncnn();
    private PreviewView previewView;
    private OverlayView overlayView;
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

    // Track YOLO labels silently for LLM fallback hint
    private final Set<String> currentLabels = new HashSet<>();
    private Bitmap lastCaptureBitmap = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shop_camera);

        previewView = findViewById(R.id.previewView);
        overlayView = findViewById(R.id.overlayView);
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

            // Update UI on main thread — bounding boxes only, no label chips
            // (YOLO COCO labels are too coarse for shopping, LLM handles identification)
            runOnUiThread(() -> {
                int previewWidth = bitmap.getWidth();
                int previewHeight = bitmap.getHeight();
                overlayView.setPreviewSize(previewWidth, previewHeight);
                overlayView.setResults(objects);
                updateYoloHints(objects); // Track labels silently for LLM fallback
            });

            bitmap.recycle();
        } catch (Exception e) {
            Log.e(TAG, "Detection failed", e);
        } finally {
            image.close();
        }
    }

    /**
     * Silently track YOLO labels as a hint for LLM fallback.
     * No chips or labels shown to user — YOLO COCO 80-class names are too
     * coarse and error-prone for shopping (e.g. phone → "remote").
     */
    private void updateYoloHints(Yolo26Ncnn.Obj[] objects) {
        currentLabels.clear();
        if (objects != null) {
            for (Yolo26Ncnn.Obj obj : objects) {
                if (obj.label != null && obj.prob > 0.5f) {
                    currentLabels.add(obj.label);
                }
            }
        }
    }

    // ==================== Layer 2: Capture & LLM Vision ====================

    /**
     * Handle "Capture & Identify Product" button.
     *
     * Works regardless of whether YOLO detected anything:
     * 1. Freeze the camera frame
     * 2. Show loading overlay
     * 3. Send image to LLM via OpenRouter (tiered cascade)
     * 4. Use LLM-generated searchQuery (+ YOLO hint as fallback)
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

        // Step 3: Call LLM Vision via OpenRouter in background
        new Thread(() -> {
            try {
                // Check if API key is configured
                String apiKey = BuildConfig.OPENROUTER_API_KEY;
                if (apiKey == null || apiKey.isEmpty()) {
                    runOnUiThread(() -> {
                        handleLlmFallback(yoloHint, "No OpenRouter API key configured");
                    });
                    return;
                }

                // LLM progress callback → update UI status text
                LlmVisionHelper.ProgressCallback progressCallback = message ->
                        runOnUiThread(() -> tvLoadingStatus.setText(message));

                // Call LLM (synchronous on this background thread)
                // Tiered cascade: tries cheap model first, upgrades if uncertain
                LlmVisionHelper.ProductInfo productInfo =
                        LlmVisionHelper.identifyProductBlocking(capturedBitmap, progressCallback);

                runOnUiThread(() -> {
                    if (productInfo != null) {
                        handleLlmSuccess(productInfo, yoloHint);
                    } else {
                        handleLlmFallback(yoloHint, "AI could not identify the product");
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "LLM Vision call failed", e);
                runOnUiThread(() -> {
                    handleLlmFallback(yoloHint, "Network error: " + e.getMessage());
                });
            } finally {
                capturedBitmap.recycle();
            }
        }).start();
    }

    /**
     * LLM returned a product identification — build search query and navigate.
     */
    private void handleLlmSuccess(LlmVisionHelper.ProductInfo productInfo, String yoloHint) {
        String searchQuery = LlmVisionHelper.buildSearchQuery(productInfo, yoloHint);

        if (searchQuery.isEmpty()) {
            handleLlmFallback(yoloHint, "Could not identify the product");
            return;
        }

        // Show identification details
        String displayText = "Found: " + searchQuery;
        if (!productInfo.getBrand().isEmpty() && !productInfo.getModel().isEmpty()) {
            displayText = "Found: " + productInfo.getBrand() + " " + productInfo.getModel();
        }
        String tierLabel = productInfo.getTier() == 1 ? "fast" : "deep";
        displayText += " (" + tierLabel + " scan, " +
                String.format("%.0f%%", productInfo.getConfidence() * 100) + " confident)";

        tvLoadingStatus.setText(displayText);
        Log.d(TAG, "LLM search query: " + searchQuery +
                " (tier=" + productInfo.getTier() + ", confidence=" + productInfo.getConfidence() + ")");

        // Brief delay to show the result, then navigate
        String finalQuery = searchQuery;
        tvLoadingStatus.postDelayed(() -> {
            resetCaptureUI();
            openProductSearch(finalQuery, "llm_tier" + productInfo.getTier());
        }, 1000);
    }

    /**
     * LLM failed or returned nothing — fall back gracefully.
     * If YOLO had a detection, use that. Otherwise tell the user.
     */
    private void handleLlmFallback(String yoloHint, String reason) {
        Log.w(TAG, "LLM fallback: " + reason);

        if (yoloHint != null && !yoloHint.isEmpty()) {
            // Use YOLO label as fallback
            String searchQuery = ShopHelper.INSTANCE.mapDetectionToSearchQuery(yoloHint);
            Toast.makeText(this, "Using AI detection: " + searchQuery, Toast.LENGTH_SHORT).show();
            resetCaptureUI();
            openProductSearch(searchQuery, "yolo_fallback");
        } else {
            // No YOLO, no LLM — ask user to try again
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
