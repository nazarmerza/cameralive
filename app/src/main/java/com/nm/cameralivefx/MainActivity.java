package com.nm.cameralivefx;

import static com.nm.cameralivefx.utils.CubeTo3DLUT.loadCubeAsset;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView; // IMPORTANT: Re-included
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraCharacteristics;
import android.view.Display;
import android.view.WindowManager;
import android.widget.TextView;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.SurfaceView; // Added SurfaceView import

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

// Assuming these are external classes and must be imported
import com.nm.cmaeralivefx.encoder.AudioEncoder;
import com.nm.cmaeralivefx.encoder.MediaMuxerWrapper;
import com.nm.cmaeralivefx.encoder.VideoEncoder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import android.os.ParcelFileDescriptor;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    static {
        System.loadLibrary("cameralivefx");
    }

    public native void nativeSetSurface(Surface surface);
    public static native void nativeSetJavaContext(MainActivity activity);
    public static native void nativeSetRotationDegrees(int degrees);
    public native void nativeInitializeFilters();
    public native void nativeSetCurrentFilter(String filterName);
    public native void nativeCapturePhoto();

    private View cameraPreview;
    private HorizontalScrollView filterScrollView; // REINSTATED
    private LinearLayout filterListContainer;      // REINSTATED
    private Button photoModeButton;
    private Button videoModeButton;
    private ImageView thumbnailButton;
    private ImageView captureButton;
    private ImageView cameraSwitchButton;

    private volatile boolean isRecording = false;
    private boolean isPhotoMode = true;

    // Filter Names array (REINSTATED)
    private String[] filterNames = {
            "Blue Architecture", "HardBoost", "LongBeachMorning", "LushGreen",
            "MagicHour", "NaturalBoost", "OrangeAndBlue", "SoftBlackAndWhite",
            "Waves", "BlueHour", "ColdChrome", "CrispAutumn", "DarkAndSomber"
    };

    // Last selected filter name to maintain state (NEW)
    private String currentFilterName = "None";

    private CameraHandler cameraHandler;
    private VideoEncoder videoEncoder;
    private AudioEncoder audioEncoder;
    private MediaMuxerWrapper muxerWrapper;

    private static final int REQUEST_PERMISSIONS = 1001;
    private String currentCameraId;

    private String outputPath = null;
    private Uri outputUri = null;

    private int photoWidth = 0;
    private int photoHeight = 0;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. View Initialization (Filter views are present)
        cameraPreview = findViewById(R.id.camera_preview);
        filterScrollView = findViewById(R.id.filter_scroll_view);      // REINSTATED
        filterListContainer = findViewById(R.id.filter_list_container);// REINSTATED
        photoModeButton = findViewById(R.id.photo_mode_button);
        videoModeButton = findViewById(R.id.video_mode_button);
        thumbnailButton = findViewById(R.id.thumbnail_button);
        captureButton = findViewById(R.id.capture_button);
        cameraSwitchButton = findViewById(R.id.camera_switch_button);

        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_PERMISSIONS
            );
        }

        nativeSetJavaContext(this);

        if (cameraPreview instanceof SurfaceView) {
            SurfaceView surfaceView = (SurfaceView) cameraPreview;
            surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    nativeSetSurface(holder.getSurface());

                    if (cameraHandler == null) {
                        // Assuming CameraHandler constructor takes context and surface
                        // NOTE: You must update CameraHandler to call setPhotoCaptureSize
                        cameraHandler = new CameraHandler(MainActivity.this, holder.getSurface());
                    }
                    currentCameraId = CameraHandler.BACK_CAMERA_ID; // Assuming a static constant exists
                    cameraHandler.startCamera(currentCameraId);

                    int previewDegrees = computePreviewRotationDegrees(currentCameraId);
                    nativeSetRotationDegrees(previewDegrees);
                }

                @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
                @Override public void surfaceDestroyed(SurfaceHolder holder) {
                    if (cameraHandler != null) {
                        cameraHandler.shutdown();
                    }
                }
            });
        }

        nativeInitializeFilters();
        nativeSetCurrentFilter("None");

        setupModeButtons();
        setupIconTintSelectorsAndListeners();
        updateModeUI(true);
        setupFilterThumbnails(); // REINSTATED
    }

    // --- PHOTO CAPTURE AND SAVING LOGIC (AS PREVIOUSLY EXTENDED) ---

    public void setPhotoCaptureSize(int width, int height) {
        this.photoWidth = width;
        this.photoHeight = height;
        Log.d("MainActivity", "Photo capture size set to: " + width + "x" + height);
    }

    public void onProcessedPhotoFromNative(byte[] bgraData) {
        if (bgraData == null || bgraData.length == 0 || photoWidth == 0 || photoHeight == 0) {
            Log.e("MainActivity", "Received empty BGRA data or size is 0.");
            return;
        }

        new Thread(() -> {
            Bitmap processedBitmap = null;
            try {
                processedBitmap = createBitmapFromBgra(bgraData, photoWidth, photoHeight);

                int rotationDegrees = computePreviewRotationDegrees(currentCameraId);
                Bitmap rotatedBitmap = rotateBitmap(processedBitmap, rotationDegrees, currentCameraId.equals(CameraHandler.FRONT_CAMERA_ID));

                savePhotoToGallery(rotatedBitmap);

                if (processedBitmap != null) processedBitmap.recycle();
                if (rotatedBitmap != null && rotatedBitmap != processedBitmap) rotatedBitmap.recycle();

            } catch (IOException e) {
                Log.e("MainActivity", "Failed to save photo to gallery", e);
                runOnUiThread(() -> Toast.makeText(this, "Failed to save photo!", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Log.e("MainActivity", "General error during photo processing", e);
                runOnUiThread(() -> Toast.makeText(this, "Photo processing error!", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private Bitmap createBitmapFromBgra(byte[] bgraData, int width, int height) {
        int[] pixels = new int[width * height];

        for (int i = 0; i < pixels.length; i++) {
            int b = bgraData[i * 4] & 0xFF;
            int g = bgraData[i * 4 + 1] & 0xFF;
            int r = bgraData[i * 4 + 2] & 0xFF;
            int a = bgraData[i * 4 + 3] & 0xFF;

            // Map B, G, R, A to Android's ARGB_8888 (0xAARRGGBB)
            pixels[i] = Color.argb(a, r, g, b);
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);

        return bitmap;
    }

    private Bitmap rotateBitmap(Bitmap source, int degrees, boolean isFrontCamera) {
        if (degrees == 0 && !isFrontCamera) {
            return source;
        }

        Matrix matrix = new Matrix();

        if (isFrontCamera) {
            matrix.postScale(-1, 1);
        }

        matrix.postRotate(degrees);

        try {
            Bitmap rotatedBitmap = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
            if (source != rotatedBitmap) {
                source.recycle();
            }
            return rotatedBitmap;
        } catch (OutOfMemoryError e) {
            Log.e("MainActivity", "Out of memory error while rotating bitmap", e);
            return source;
        }
    }

    private void savePhotoToGallery(Bitmap bitmap) throws IOException {
        long currentTime = System.currentTimeMillis();
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(currentTime));
        String fileName = "IMG_" + timestamp + ".jpg";

        Uri targetUri = null;
        OutputStream outputStream = null;
        String localOutputPath = null;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.MediaColumns.DATE_TAKEN, currentTime);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CameraLiveFX");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);

                targetUri = getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

                if (targetUri == null) throw new IOException("Failed to insert MediaStore photo item");

                outputStream = getContentResolver().openOutputStream(targetUri);
                if (outputStream == null) throw new IOException("Failed to open output stream for photo");

                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream);

                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(targetUri, values, null, null);

                outputUri = targetUri;
            } else {
                File picturesDir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "CameraLiveFX");
                if (!picturesDir.exists()) picturesDir.mkdirs();

                File file = new File(picturesDir, fileName);
                outputStream = new FileOutputStream(file);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream);

                localOutputPath = file.getAbsolutePath();
            }

            runOnUiThread(() -> {
                Toast.makeText(this, "Photo saved: " + fileName, Toast.LENGTH_SHORT).show();
            });

        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    Log.e("MainActivity", "Error closing output stream", e);
                }
            }

            if (localOutputPath != null) {
                MediaScannerConnection.scanFile(
                        this,
                        new String[]{ localOutputPath },
                        new String[]{ "image/jpeg" },
                        null
                );
            }
        }
    }

    // --- REINSTATED HELPER METHODS ---

    private void setupModeButtons() {
        photoModeButton.setOnClickListener(v -> {
            if (!isPhotoMode) {
                isPhotoMode = true;
                updateModeUI(true);
                if (isRecording) stopRecording();
                Toast.makeText(MainActivity.this, "Switched to Photo Mode", Toast.LENGTH_SHORT).show();
            }
        });

        videoModeButton.setOnClickListener(v -> {
            if (isPhotoMode) {
                isPhotoMode = false;
                updateModeUI(false);
                Toast.makeText(MainActivity.this, "Switched to Video Mode", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupIconTintSelectorsAndListeners() {
        ColorStateList tintSelector = ContextCompat.getColorStateList(this, R.color.btn_icon_tint_selector); // R.color.btn_icon_tint_selector must exist
        ImageViewCompat.setImageTintList(cameraSwitchButton, tintSelector);

        cameraSwitchButton.setOnClickListener(v -> switchCamera());

        thumbnailButton.setOnClickListener(v -> {
            Toast.makeText(MainActivity.this, "Opening Gallery...", Toast.LENGTH_SHORT).show();
        });

        captureButton.setOnClickListener(v -> handleCaptureButtonClick());
    }

    private void updateModeUI(boolean isPhoto) {
        int activeColor = ContextCompat.getColor(this, R.color.white);
        int inactiveColor = ContextCompat.getColor(this, R.color.dark_gray); // R.color.dark_gray must exist

        if (isPhoto) {
            photoModeButton.setTextColor(activeColor);
            videoModeButton.setTextColor(inactiveColor);
            captureButton.setImageResource(R.drawable.btn_photo_capture_selector); // Drawable must exist
        } else {
            photoModeButton.setTextColor(inactiveColor);
            videoModeButton.setTextColor(activeColor);
            captureButton.setImageResource(R.drawable.btn_video_capture_selector); // Drawable must exist
        }

        // Control visibility of the filter scroll view based on mode (common design pattern)
        filterScrollView.setVisibility(isPhoto ? View.VISIBLE : View.VISIBLE); // Kept visible for both modes for now
    }

    private void handleCaptureButtonClick() {
        if (isPhotoMode) {
            nativeCapturePhoto();
            Toast.makeText(this, "Capturing Photo...", Toast.LENGTH_SHORT).show();

        } else {
            if (!isRecording) {
                startRecording();
            } else {
                stopRecording();
            }
        }
    }

    // REINSTATED: Filter thumbnail generation logic
    private void setupFilterThumbnails() {
        filterListContainer.removeAllViews(); // Clear previous views

        for (String name : filterNames) {
            TextView filterButton = new TextView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(20, 0, 20, 0);
            filterButton.setLayoutParams(params);
            filterButton.setText(name);
            filterButton.setTextColor(Color.WHITE);
            filterButton.setTypeface(Typeface.DEFAULT_BOLD);
            filterButton.setGravity(Gravity.CENTER);
            filterButton.setBackgroundResource(R.drawable.filter_button_background); // Drawable must exist
            filterButton.setPadding(30, 15, 30, 15);

            // Set up click listener to apply filter
            filterButton.setOnClickListener(v -> {
                currentFilterName = name;
                nativeSetCurrentFilter(name);
                Toast.makeText(this, "Filter: " + name, Toast.LENGTH_SHORT).show();

                // Highlight the selected filter (simple change, adjust as needed)
                for (int i = 0; i < filterListContainer.getChildCount(); i++) {
                    TextView child = (TextView) filterListContainer.getChildAt(i);
                    child.setBackgroundResource(child.getText().toString().equals(name) ?
                            R.drawable.filter_button_background_selected : R.drawable.filter_button_background);
                }
            });

            filterListContainer.addView(filterButton);

            // Initial selection highlight
            if (name.equals("None")) {
                filterButton.setBackgroundResource(R.drawable.filter_button_background_selected);
            }
        }
    }

    // REINSTATED: Placeholder/Assumed implementations for required methods
    private void switchCamera() {
        Toast.makeText(this, "Switching Camera...", Toast.LENGTH_SHORT).show();
        // Implementation would involve:
        // 1. cameraHandler.stopCamera()
        // 2. Toggle currentCameraId (FRONT/BACK)
        // 3. cameraHandler.startCamera(newId)
        // 4. Update nativeSetRotationDegrees
    }
    private void startRecording() {
        isRecording = true;
        captureButton.setColorFilter(Color.RED);
        Toast.makeText(this, "Recording started...", Toast.LENGTH_SHORT).show();
        // Implementation would start MediaMuxerWrapper, VideoEncoder, AudioEncoder
    }
    private void stopRecording() {
        isRecording = false;
        captureButton.clearColorFilter();
        Toast.makeText(this, "Recording stopped. Saving file...", Toast.LENGTH_SHORT).show();
        // Implementation would stop Muxer, and clean up encoders
    }

    // Callback for video encoding (NV21 data)
    public void onProcessedFrameFromNative(byte[] data, long timestampUs) {
        // This is where you feed the NV21 data to the VideoEncoder
        // if (isRecording && videoEncoder != null) {
        //     videoEncoder.feedFrame(data, timestampUs);
        // }
    }

    // Permission check placeholders
    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    // Rotation logic placeholder
    private int computePreviewRotationDegrees(String cameraId) {
        // This logic is complex and device-specific, but typically 90 or 270 for portrait
        Display display = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
        int rotation = display.getRotation();

        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0: degrees = 0; break;
            case Surface.ROTATION_90: degrees = 90; break;
            case Surface.ROTATION_180: degrees = 180; break;
            case Surface.ROTATION_270: degrees = 270; break;
        }

        // Assume back camera is sensor orientation 90 degrees clockwise (common for phones)
        int sensorOrientation = 90;

        // This is a simplification; actual Camera2 API requires checking CameraCharacteristics.SENSOR_ORIENTATION
        int previewDegrees = (sensorOrientation - degrees + 360) % 360;

        // The JNI code is set up to handle 90, 270 rotation internally.
        if (previewDegrees == 0 || previewDegrees == 180) {
            // Since we rely on the native side to draw/rotate to fit the Surface,
            // we should pass the rotation needed for the image buffer itself.
            // For example, if sensor is 90 and screen is 0, we need to rotate 90.
            return previewDegrees;
        }
        return 90; // Defaulting to the most common portrait rotation for the buffer
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRecording();
        if (cameraHandler != null) {
            cameraHandler.shutdown();
        }
        // Cleanup global JNI references if necessary (not implemented here, but good practice)
    }
}