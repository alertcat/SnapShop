package com.example.snapshop;

import android.Manifest;
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
import android.widget.Button;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DetectActivity extends AppCompatActivity {
    private static final String TAG = "DetectActivity";
    private static final int REQUEST_PERMISSION = 100;
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA
    };

    private Yolo26Ncnn yolo26Ncnn = new Yolo26Ncnn();
    private PreviewView previewView;
    private OverlayView overlayView;
    private TextView tvResult;
    private Button btnSwitchCamera;
    private Button btnStartStop;
    private Button btnConnectWallet;
    private Button btnMemoOnChain;
    private TextView tvWalletStatus;

    // Store current detection results for on-chain memo
    private Yolo26Ncnn.Obj[] currentObjects = null;

    // Store last transaction info
    private String lastTxSignature = null;
    private String lastExplorerUrl = null;

    private int currentModel = 0;
    private int currentDevice = 0; // Use CPU only (Seeker GPU performance is insufficient)
    private boolean isDetecting = false;
    private boolean isFrontCamera = false;

    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;

    private long lastDetectTime = 0;
    private static final long DETECT_INTERVAL = 100; // Detection interval 100ms

    // Solana Wallet Helper (Kotlin)
    private WalletHelper walletHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detect);

        previewView = findViewById(R.id.previewView);
        overlayView = findViewById(R.id.overlayView);
        tvResult = findViewById(R.id.tvResult);
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera);
        btnStartStop = findViewById(R.id.btnStartStop);
        btnConnectWallet = findViewById(R.id.btnConnectWallet);
        btnMemoOnChain = findViewById(R.id.btnMemoOnChain);
        tvWalletStatus = findViewById(R.id.tvWalletStatus);

        // Set PreviewView to fill the container (crop to fit)
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);

        cameraExecutor = Executors.newSingleThreadExecutor();

        // Initialize Solana Wallet Helper
        walletHelper = new WalletHelper(this);

        // Connect Wallet button
        btnConnectWallet.setOnClickListener(v -> handleWalletClick());

        // Memo on-chain button (send detection results to blockchain)
        btnMemoOnChain.setOnClickListener(v -> handleMemoOnChain());

        // Switch camera button
        btnSwitchCamera.setOnClickListener(v -> {
            isFrontCamera = !isFrontCamera;
            overlayView.setFrontCamera(isFrontCamera);
            startCamera();
        });

        // Start/Stop detection button
        btnStartStop.setOnClickListener(v -> {
            isDetecting = !isDetecting;
            btnStartStop.setText(isDetecting ? "Stop Detect" : "Start Detect");
            if (!isDetecting) {
                overlayView.clearResults();
                tvResult.setText("Detection stopped");
            }
        });

        // Check permissions
        if (allPermissionsGranted()) {
            reloadModel();
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSION);
        }
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION) {
            if (allPermissionsGranted()) {
                reloadModel();
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void reloadModel() {
        // Use CPU only, Seeker GPU performance causes detection boxes to drift
        boolean ret = yolo26Ncnn.loadModel(getAssets(), currentModel, 0);
        if (!ret) {
            Log.e(TAG, "Failed to load model");
            Toast.makeText(this, "Failed to load model", Toast.LENGTH_SHORT).show();
        } else {
            Log.d(TAG, "Model loaded: yolo26n on CPU");
        }
    }

    /**
     * Handle wallet button click
     */
    private void handleWalletClick() {
        if (walletHelper.isConnected()) {
            // Already connected, disconnect
            walletHelper.disconnect(walletCallback);
        } else {
            // Not connected, connect
            btnConnectWallet.setEnabled(false);
            btnConnectWallet.setText("Connecting...");
            walletHelper.connect(walletCallback);
        }
    }

    /**
     * Wallet callback
     */
    private final WalletHelper.WalletCallback walletCallback = new WalletHelper.WalletCallback() {
        @Override
        public void onConnected(@NonNull String address) {
            updateWalletUI(true);
            Toast.makeText(DetectActivity.this, "Wallet connected!", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onDisconnected() {
            updateWalletUI(false);
            Toast.makeText(DetectActivity.this, "Wallet disconnected", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onError(@NonNull String message) {
            btnConnectWallet.setEnabled(true);
            btnConnectWallet.setText("Connect Wallet");
            Toast.makeText(DetectActivity.this, "Error: " + message, Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onNoWalletFound() {
            btnConnectWallet.setEnabled(true);
            btnConnectWallet.setText("Connect Wallet");
            Toast.makeText(DetectActivity.this, "No wallet app found. Please install Seed Vault Wallet.", Toast.LENGTH_LONG).show();
        }
    };

    /**
     * Update wallet UI state
     */
    private void updateWalletUI(boolean connected) {
        if (connected) {
            btnConnectWallet.setEnabled(true);
            btnConnectWallet.setText("Disconnect");
            btnConnectWallet.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF4CAF50)); // Green

            tvWalletStatus.setText(walletHelper.getShortAddress());
            tvWalletStatus.setTextColor(0xFF4CAF50); // Green
        } else {
            btnConnectWallet.setEnabled(true);
            btnConnectWallet.setText("Connect Wallet");
            btnConnectWallet.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF512DA8)); // Purple
            tvWalletStatus.setText("Not Connected");
            tvWalletStatus.setTextColor(0xFF888888); // Gray
        }
    }

    /**
     * Handle Memo on-chain button click
     * Send detection results (class, confidence, bbox) on-chain, no image upload (privacy protection)
     */
    private void handleMemoOnChain() {
        // Check if wallet is connected
        if (!walletHelper.isConnected()) {
            Toast.makeText(this, "Please connect wallet first", Toast.LENGTH_SHORT).show();
            return;
        }

        // Check if we have detection results
        if (currentObjects == null || currentObjects.length == 0) {
            Toast.makeText(this, "No objects detected. Start detection first.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Convert detection results to DetectionData list
        java.util.List<WalletHelper.DetectionData> detections = new java.util.ArrayList<>();
        for (Yolo26Ncnn.Obj obj : currentObjects) {
            if (obj.label != null) {
                detections.add(new WalletHelper.DetectionData(
                        obj.label,
                        obj.prob,
                        obj.x,
                        obj.y,
                        obj.w,
                        obj.h
                ));
            }
        }

        if (detections.isEmpty()) {
            Toast.makeText(this, "No valid detections", Toast.LENGTH_SHORT).show();
            return;
        }

        // Disable button during transaction
        btnMemoOnChain.setEnabled(false);
        btnMemoOnChain.setText("Sending...");

        // Send detection data on-chain via Memo
        walletHelper.sendDetectionMemo(detections, memoCallback);
    }

    /**
     * Memo on-chain callback
     */
    private final WalletHelper.MemoCallback memoCallback = new WalletHelper.MemoCallback() {
        @Override
        public void onMemoStarted() {
            runOnUiThread(() -> {
                btnMemoOnChain.setText("Preparing...");
            });
        }

        @Override
        public void onMemoProgress(@NonNull String status) {
            runOnUiThread(() -> {
                btnMemoOnChain.setText(status.length() > 10 ? status.substring(0, 10) + ".." : status);
            });
        }

        @Override
        public void onMemoSuccess(@NonNull String signature, @NonNull String explorerUrl, @NonNull String memoData) {
            runOnUiThread(() -> {
                lastTxSignature = signature;
                lastExplorerUrl = explorerUrl;

                btnMemoOnChain.setEnabled(true);
                btnMemoOnChain.setText("On-Chain");

                String shortSig = signature.length() > 8 ? signature.substring(0, 8) + "..." : signature;

                Toast.makeText(DetectActivity.this,
                        "Success! TX: " + shortSig,
                        Toast.LENGTH_LONG).show();

                // Update result display
                tvResult.setText("TX sent: " + shortSig + "\nView on Solana Explorer");

                Log.d(TAG, "Memo on-chain success!");
                Log.d(TAG, "Signature: " + signature);
                Log.d(TAG, "Explorer: " + explorerUrl);
                Log.d(TAG, "Memo data: " + memoData);
            });
        }

        @Override
        public void onMemoError(@NonNull String message) {
            runOnUiThread(() -> {
                btnMemoOnChain.setEnabled(true);
                btnMemoOnChain.setText("On-Chain");
                Toast.makeText(DetectActivity.this,
                        "Failed: " + message,
                        Toast.LENGTH_SHORT).show();
            });
        }
    };

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases();
            } catch (Exception e) {
                Log.e(TAG, "Camera initialization failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;

        // Unbind previous use cases
        cameraProvider.unbindAll();

        // Select camera
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(isFrontCamera ?
                        CameraSelector.LENS_FACING_FRONT :
                        CameraSelector.LENS_FACING_BACK)
                .build();

        // Preview
        Preview preview = new Preview.Builder()
                .setTargetResolution(new Size(640, 480))
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        // Image analysis
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

        try {
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
            runOnUiThread(() -> tvResult.setText("Camera ready. Tap \"Start Detect\" to begin."));
        } catch (Exception e) {
            Log.e(TAG, "Use case binding failed", e);
        }
    }

    private void analyzeImage(ImageProxy image) {
        if (!isDetecting) {
            image.close();
            return;
        }

        // Control detection frequency
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastDetectTime < DETECT_INTERVAL) {
            image.close();
            return;
        }
        lastDetectTime = currentTime;

        try {
            // Convert ImageProxy to Bitmap
            Bitmap bitmap = imageProxyToBitmap(image);
            if (bitmap == null) {
                image.close();
                return;
            }

            // Set preview size
            int previewWidth = bitmap.getWidth();
            int previewHeight = bitmap.getHeight();

            // Run detection
            long startTime = System.currentTimeMillis();
            Yolo26Ncnn.Obj[] objects = yolo26Ncnn.detect(bitmap);
            long inferenceTime = System.currentTimeMillis() - startTime;

            // Store current detection results for memo
            currentObjects = objects;

            // Update UI
            final int objectCount = objects != null ? objects.length : 0;
            final long fps = 1000 / Math.max(inferenceTime, 1);

            runOnUiThread(() -> {
                overlayView.setPreviewSize(previewWidth, previewHeight);
                overlayView.setResults(objects);

                if (objectCount > 0) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(String.format("Detected %d objects | %dms | %d FPS\n", objectCount, inferenceTime, fps));
                    for (int i = 0; i < Math.min(objectCount, 3); i++) {
                        if (objects[i].label != null) {
                            sb.append(String.format("%s: %.1f%% ", objects[i].label, objects[i].prob * 100));
                        }
                    }
                    if (objectCount > 3) {
                        sb.append("...");
                    }
                    tvResult.setText(sb.toString());
                } else {
                    tvResult.setText(String.format("No objects detected | %dms | %d FPS", inferenceTime, fps));
                }
            });

            bitmap.recycle();

        } catch (Exception e) {
            Log.e(TAG, "Detection failed", e);
        } finally {
            image.close();
        }
    }

    /**
     * Convert ImageProxy (YUV_420_888) to Bitmap correctly
     * Key: Handle rowStride and pixelStride properly
     */
    private Bitmap imageProxyToBitmap(ImageProxy image) {
        try {
            ImageProxy.PlaneProxy[] planes = image.getPlanes();
            int width = image.getWidth();
            int height = image.getHeight();

            // Y plane
            ByteBuffer yBuffer = planes[0].getBuffer();
            int yRowStride = planes[0].getRowStride();
            int yPixelStride = planes[0].getPixelStride();

            // U plane
            ByteBuffer uBuffer = planes[1].getBuffer();
            int uvRowStride = planes[1].getRowStride();
            int uvPixelStride = planes[1].getPixelStride();

            // V plane
            ByteBuffer vBuffer = planes[2].getBuffer();

            // NV21 format: Y plane + VU interleaved plane
            // Total size = width * height * 1.5
            int nv21Size = width * height + (width * height / 2);
            byte[] nv21 = new byte[nv21Size];

            // Copy Y plane (considering rowStride)
            int yPos = 0;
            if (yRowStride == width && yPixelStride == 1) {
                // Fast path: direct copy
                yBuffer.get(nv21, 0, width * height);
                yPos = width * height;
            } else {
                // Slow path: copy row by row
                for (int row = 0; row < height; row++) {
                    yBuffer.position(row * yRowStride);
                    for (int col = 0; col < width; col++) {
                        nv21[yPos++] = yBuffer.get(row * yRowStride + col * yPixelStride);
                    }
                }
                yBuffer.rewind();
            }

            // Copy VU interleaved plane
            // NV21 requires V first, U second, interleaved
            int uvHeight = height / 2;
            int uvWidth = width / 2;
            int uvPos = width * height;

            if (uvPixelStride == 2 && uvRowStride == width) {
                // Fast path: UV already interleaved (common case)
                // planes[2] (V) is already VUVU... format
                vBuffer.position(0);
                int uvSize = Math.min(vBuffer.remaining(), width * height / 2);
                vBuffer.get(nv21, uvPos, uvSize);
            } else if (uvPixelStride == 1) {
                // Slow path: U and V are separate planes, need manual interleave
                for (int row = 0; row < uvHeight; row++) {
                    for (int col = 0; col < uvWidth; col++) {
                        int uvIndex = row * uvRowStride + col * uvPixelStride;
                        // NV21: V first, U second
                        nv21[uvPos++] = vBuffer.get(uvIndex);  // V
                        nv21[uvPos++] = uBuffer.get(uvIndex);  // U
                    }
                }
            } else {
                // Generic path
                for (int row = 0; row < uvHeight; row++) {
                    for (int col = 0; col < uvWidth; col++) {
                        int uvIndex = row * uvRowStride + col * uvPixelStride;
                        nv21[uvPos++] = vBuffer.get(uvIndex);  // V
                        nv21[uvPos++] = uBuffer.get(uvIndex);  // U
                    }
                }
            }

            // Use YuvImage to convert to JPEG, then decode to Bitmap
            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), 95, out);
            byte[] imageBytes = out.toByteArray();

            Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

            // Rotate image
            Matrix matrix = new Matrix();
            int rotationDegrees = image.getImageInfo().getRotationDegrees();
            if (rotationDegrees != 0) {
                matrix.postRotate(rotationDegrees);
            }

            // Mirror flip for front camera
            if (isFrontCamera) {
                matrix.postScale(-1, 1);
            }

            if (rotationDegrees != 0 || isFrontCamera) {
                Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (rotatedBitmap != bitmap) {
                    bitmap.recycle();
                }
                return rotatedBitmap;
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
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}
