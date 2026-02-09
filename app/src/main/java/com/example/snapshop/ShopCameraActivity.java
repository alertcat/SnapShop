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
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
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
import androidx.lifecycle.LifecycleOwner;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ShopCameraActivity - Shopping camera page
 *
 * Uses YOLO26 for real-time detection, displays detected object labels as
 * tappable chips. User can tap a chip to search for that object on Amazon/eBay.
 *
 * Also provides a "Capture & Search" button that uses Google Vision API
 * for more accurate product identification.
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

    private boolean isDetecting = true;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;

    private long lastDetectTime = 0;
    private static final long DETECT_INTERVAL = 150; // Slightly slower for shopping mode

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

        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Back button
        btnBack.setOnClickListener(v -> finish());

        // Capture & Search button - uses Vision API for deeper analysis
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
            tvStatus.setText("Point camera at an object to detect");
        } catch (Exception e) {
            Log.e(TAG, "Use case binding failed", e);
        }
    }

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

            // Store last bitmap for Vision API capture
            synchronized (this) {
                if (lastCaptureBitmap != null) {
                    lastCaptureBitmap.recycle();
                }
                lastCaptureBitmap = bitmap.copy(bitmap.getConfig(), false);
            }

            // Run YOLO detection
            Yolo26Ncnn.Obj[] objects = yolo26Ncnn.detect(bitmap);

            // Update UI
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
     * Update detection label chips
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
                chip.setText(label);
                chip.setClickable(true);
                chip.setCheckable(false);
                chip.setChipBackgroundColorResource(android.R.color.holo_green_dark);
                chip.setTextColor(getResources().getColor(android.R.color.white));

                // Tap chip → search on Amazon
                chip.setOnClickListener(v -> {
                    String searchQuery = ShopHelper.INSTANCE.mapDetectionToSearchQuery(label);
                    openProductSearch(searchQuery, "chip");
                });

                chipGroup.addView(chip);
            }

            if (newLabels.isEmpty()) {
                tvStatus.setText("No objects detected. Move camera closer.");
            } else {
                tvStatus.setText("Tap a label to search, or capture for deeper analysis");
            }
        }
    }

    /**
     * Handle capture & search button
     * Takes current frame and opens product search
     */
    private void handleCaptureSearch() {
        if (currentLabels.isEmpty()) {
            Toast.makeText(this, "No objects detected yet", Toast.LENGTH_SHORT).show();
            return;
        }

        // Use the first detected label for search
        String topLabel = currentLabels.iterator().next();
        String searchQuery = ShopHelper.INSTANCE.mapDetectionToSearchQuery(topLabel);

        // TODO: In future, send captured image to Google Vision API for deeper analysis
        // For now, use YOLO label directly
        openProductSearch(searchQuery, "capture");
    }

    /**
     * Open product results page
     */
    private void openProductSearch(String query, String source) {
        Intent intent = new Intent(this, ProductResultsActivity.class);
        intent.putExtra("search_query", query);
        intent.putExtra("search_source", source);
        startActivity(intent);
    }

    /**
     * Convert ImageProxy (YUV_420_888) to Bitmap
     * Reused from DetectActivity with minor optimization
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
