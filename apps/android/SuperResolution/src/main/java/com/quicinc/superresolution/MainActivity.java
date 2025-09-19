package com.quicinc.superresolution;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.widget.Button;
import android.widget.RadioGroup;
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
import com.quicinc.tflite.AIHubDefaults;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "SuperResolution";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = {Manifest.permission.CAMERA};

    private enum Backend { CPU, GPU, NPU }

    // UI Elements
    private PreviewView cameraPreview;
    private GlowImageView upscaledImageView;
    private TextView inferenceTimeView;
    private TextView predictionTimeView;
    private RadioGroup delegateSelectionGroup;
    private Button plusButton, minusButton;
    private TextView cropSizeTextView;

    // Model and Execution
    private SuperResolution cpuUpscaler, gpuUpscaler, npuUpscaler;
    private Backend currentBackend = Backend.NPU;
    private final ExecutorService backgroundTaskExecutor = Executors.newSingleThreadExecutor();

    // State
    private int cropSize = 128;
    private final int CROP_SIZE_STEP = 32;
    private final int MIN_CROP_SIZE = 64;
    private final int MAX_CROP_SIZE = 128;
    private final NumberFormat timeFormatter = new DecimalFormat("0.00");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);

        // Initialize Views
        cameraPreview = findViewById(R.id.camera_preview);
        upscaledImageView = findViewById(R.id.upscaled_image_view);
        inferenceTimeView = findViewById(R.id.inferenceTimeResultText);
        predictionTimeView = findViewById(R.id.endToEndTimeResultText);
        delegateSelectionGroup = findViewById(R.id.delegateSelectionGroup);
        plusButton = findViewById(R.id.plus_button);
        minusButton = findViewById(R.id.minus_button);
        cropSizeTextView = findViewById(R.id.crop_size_text);

        // Setup
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        setupControls();
        createTFLiteUpscalersAsync();
        upscaledImageView.startGlowEffect();
    }

    private void setupControls() {
        // Backend Selection
        delegateSelectionGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.cpu_radio_button) currentBackend = Backend.CPU;
            else if (checkedId == R.id.gpu_radio_button) currentBackend = Backend.GPU;
            else if (checkedId == R.id.npu_radio_button) currentBackend = Backend.NPU;
        });

        // Crop Size Selection
        updateCropSizeDisplay(); // Set initial text
        minusButton.setOnClickListener(v -> {
            cropSize = Math.max(MIN_CROP_SIZE, cropSize - CROP_SIZE_STEP);
            updateCropSizeDisplay();
        });
        plusButton.setOnClickListener(v -> {
            cropSize = Math.min(MAX_CROP_SIZE, cropSize + CROP_SIZE_STEP);
            updateCropSizeDisplay();
        });
    }

    private void updateCropSizeDisplay() {
        cropSizeTextView.setText(cropSize + "x" + cropSize);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(backgroundTaskExecutor, image -> {
                    Bitmap bitmap = toBitmap(image);
                    if (bitmap != null) {
                        Matrix matrix = new Matrix();
                        matrix.postRotate(image.getImageInfo().getRotationDegrees());
                        Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

                        // Use the dynamic cropSize variable
                        int currentCropSize = this.cropSize;
                        int x = (rotatedBitmap.getWidth() - currentCropSize) / 2;
                        int y = (rotatedBitmap.getHeight() - currentCropSize) / 2;

                        if (rotatedBitmap.getWidth() >= currentCropSize && rotatedBitmap.getHeight() >= currentCropSize) {
                            Bitmap croppedBitmap = Bitmap.createBitmap(rotatedBitmap, x, y, currentCropSize, currentCropSize);
                            updatePredictionData(croppedBitmap);
                        }
                    }
                    image.close();
                });

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Use case binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private Bitmap toBitmap(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        int ySize = yBuffer.remaining(), uSize = uBuffer.remaining(), vSize = vBuffer.remaining();
        byte[] nv21 = new byte[ySize + uSize + vSize];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight()), 100, out);
        byte[] imageBytes = out.toByteArray();
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    }

    void updatePredictionData(Bitmap image) {
        SuperResolution activeUpscaler = null;
        switch (currentBackend) {
            case CPU: activeUpscaler = cpuUpscaler; break;
            case GPU: activeUpscaler = gpuUpscaler; break;
            case NPU: activeUpscaler = npuUpscaler; break;
        }
        if (activeUpscaler == null) return;

        Bitmap result = activeUpscaler.generateUpscaledImage(image);
        long inferenceTime = activeUpscaler.getLastInferenceTime();
        long predictionTime = activeUpscaler.getLastPostprocessingTime() + inferenceTime + activeUpscaler.getLastPreprocessingTime();
        String inferenceTimeText = timeFormatter.format((double) inferenceTime / 1000000);
        String predictionTimeText = timeFormatter.format((double) predictionTime / 1000000);

        runOnUiThread(() -> {
            upscaledImageView.setImageBitmap(result);
            inferenceTimeView.setText(inferenceTimeText + " ms");
            predictionTimeView.setText(predictionTimeText + " ms");
        });
    }

    void createTFLiteUpscalersAsync() {
        backgroundTaskExecutor.execute(() -> {
            String tfLiteModelAsset = this.getResources().getString(R.string.tfLiteModelAsset);
            try {
                npuUpscaler = new SuperResolution(this, tfLiteModelAsset, AIHubDefaults.npuDelegatePriorityOrder);
                gpuUpscaler = new SuperResolution(this, tfLiteModelAsset, AIHubDefaults.gpuDelegatePriorityOrder);
                cpuUpscaler = new SuperResolution(this, tfLiteModelAsset, AIHubDefaults.cpuDelegatePriorityOrder);
            } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
                Log.e(TAG, "Error initializing TFLite upscalers", e);
                runOnUiThread(() -> Toast.makeText(this, "Error initializing models: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
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
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        backgroundTaskExecutor.shutdown();
        if (cpuUpscaler != null) cpuUpscaler.close();
        if (gpuUpscaler != null) gpuUpscaler.close();
        if (npuUpscaler != null) npuUpscaler.close();
        upscaledImageView.stopGlowEffect();
    }
}