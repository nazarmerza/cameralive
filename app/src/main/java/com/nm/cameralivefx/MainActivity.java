package com.nm.cameralivefx;

import static com.nm.cameralivefx.utils.CubeTo3DLUT.loadCubeAsset;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

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

    static { System.loadLibrary("cameralivefx"); }

    // ---- NATIVE ----
    public native void nativeSetSurface(Surface surface);
    public static native void nativeSetJavaContext(MainActivity activity);
    public static native void nativeSetRotationDegrees(int degrees);
    public native void nativeInitializeFilters();
    public native void nativeSetCurrentFilter(String filterName);
    public native void nativeCapturePhoto();

    // ---- UI ----
    private SurfaceView cameraPreview;
    private HorizontalScrollView filterScrollView;
    private LinearLayout filterListContainer;
    private Button photoModeButton;
    private Button videoModeButton;
    private ImageView thumbnailButton;
    private ImageView captureButton;
    private ImageView cameraSwitchButton;

    // ---- App state ----
    private volatile boolean isRecording = false;
    private boolean isPhotoMode = true;
    private String[] filterNames = {
            "Blue Architecture","HardBoost","LongBeachMorning","LushGreen",
            "MagicHour","NaturalBoost","OrangeAndBlue","SoftBlackAndWhite",
            "Waves","BlueHour","ColdChrome","CrispAutumn","DarkAndSomber"
    };
    private String currentFilterName = "None";

    private CameraHandler cameraHandler;
    private VideoEncoder videoEncoder;
    private AudioEncoder audioEncoder;
    private MediaMuxerWrapper muxerWrapper;

    private static final int REQUEST_PERMISSIONS = 1001;
    private String currentCameraId;

    // last media for thumbnail
    private Uri lastMediaUri = null;

    // photo buffer dimensions for native callback
    private int photoWidth = 0;
    private int photoHeight = 0;

    // video muxer resources
    private ParcelFileDescriptor videoPfd = null;
    private Uri videoUri = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        nativeSetJavaContext(this);

        // ---- views ----
        cameraPreview = findViewById(R.id.camera_preview);
        filterScrollView = findViewById(R.id.filter_scroll_view);
        filterListContainer = findViewById(R.id.filter_list_container);
        photoModeButton = findViewById(R.id.photo_mode_button);
        videoModeButton = findViewById(R.id.video_mode_button);
        thumbnailButton  = findViewById(R.id.thumbnail_button);
        captureButton    = findViewById(R.id.capture_button);
        cameraSwitchButton = findViewById(R.id.camera_switch_button);

        // permissions
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    REQUEST_PERMISSIONS
            );
        }

        // surface
        cameraPreview.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                nativeSetSurface(holder.getSurface());
                if (cameraHandler == null) {
                    cameraHandler = new CameraHandler(MainActivity.this, holder.getSurface());
                }
                currentCameraId = CameraHandler.BACK_CAMERA_ID;
                cameraHandler.startCamera(currentCameraId);

                int previewDegrees = computePreviewRotationDegrees(currentCameraId);
                nativeSetRotationDegrees(previewDegrees);
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                if (cameraHandler != null) cameraHandler.shutdown();
            }
        });

        nativeInitializeFilters();
        nativeSetCurrentFilter("None");

        setupModeButtons();
        setupIconTintSelectorsAndListeners();
        updateModeUI(true);
        setupFilterThumbnails();
    }

    // === Recording wiring =====================================================

    private MediaMuxer createMediaStoreMuxer() throws IOException {
        long now = System.currentTimeMillis();
        String name = "VID_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(now)) + ".mp4";

        ContentResolver cr = getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= 29) {
            cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera");
        }
        videoUri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
        if (videoUri == null) throw new IOException("Failed to insert MediaStore video row");
        videoPfd = cr.openFileDescriptor(videoUri, "rw");
        if (videoPfd == null) throw new IOException("Failed to open PFD for video");

        return new MediaMuxer(videoPfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    private void startRecording() {
        if (isRecording) return;
        try {
            // 1) Muxer via MediaStore FD
            MediaMuxer mm = createMediaStoreMuxer();
            muxerWrapper = new MediaMuxerWrapper(mm, videoUri, videoPfd);
            muxerWrapper.setExpectedTrackCount(2); // video + audio

            // 2) Orientation hint (affects playback rotation)
            int degrees = computePreviewRotationDegrees(currentCameraId);
            muxerWrapper.setOrientationHint(degrees);

            // 3) Encoders (configure with chosen size from CameraHandler)
            if (cameraHandler == null || cameraHandler.getChosenSize() == null) {
                Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show();
                return;
            }
            int w = cameraHandler.getChosenSize().getWidth();
            int h = cameraHandler.getChosenSize().getHeight();

            // Use sane defaults; adjust if you already expose UI for these
            int fps = 30;
            int bitrate = Math.max(3_000_000, w*h*5); // rough heuristic

            videoEncoder = new VideoEncoder(muxerWrapper, w, h);
            videoEncoder.start();

            audioEncoder = new AudioEncoder(muxerWrapper);
            audioEncoder.start();

            isRecording = true;
            captureButton.setColorFilter(Color.RED);
            Toast.makeText(this, "Recording started…", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Log.e("MainActivity", "startRecording failed", e);
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show();
            isRecording = false;
            // cleanup if partially created
            if (audioEncoder != null) try { audioEncoder.stop(); } catch (Exception ignored) {}
            if (videoEncoder != null) try { videoEncoder.stop(); } catch (Exception ignored) {}
            if (muxerWrapper != null) try { muxerWrapper.stop(); } catch (Exception ignored) {}
            videoEncoder = null;
            audioEncoder = null;
            muxerWrapper = null;
            safeCloseVideoPfd();
        }
    }

    private void stopRecording() {
        if (!isRecording) return;
        isRecording = false;
        captureButton.clearColorFilter();
        Toast.makeText(this, "Stopping…", Toast.LENGTH_SHORT).show();

        try { if (audioEncoder != null) audioEncoder.stop(); } catch (Exception ignored) {}
        try { if (videoEncoder != null) videoEncoder.stop(); } catch (Exception ignored) {}
        try { if (muxerWrapper != null) muxerWrapper.stop(); } catch (Exception ignored) {}

        // Update gallery & thumbnail
        if (videoUri != null) {
            lastMediaUri = videoUri;
            runOnUiThread(() -> updateLastItemThumb(lastMediaUri));
        }


        videoEncoder = null;
        audioEncoder = null;
        muxerWrapper = null;
        safeCloseVideoPfd();

        Toast.makeText(this, "Video saved", Toast.LENGTH_SHORT).show();
    }

    private void safeCloseVideoPfd() {
        if (videoPfd != null) {
            try { videoPfd.close(); } catch (Exception ignored) {}
            videoPfd = null;
        }
    }

    // Called from native each preview/record frame (NV21 expected)
    public void onProcessedFrameFromNative(byte[] data, long timestampUs) {
        if (isRecording && videoEncoder != null && data != null) {
            // Your VideoEncoder implementation from the Java project typically accepts NV21 + PTS
            // If your method is named differently, change the call below accordingly.
            try {
                videoEncoder.encodeFrame(data, timestampUs);
            } catch (Throwable t) {
                Log.w("MainActivity", "queue frame error", t);
            }
        }
    }

    // === Photo saving & thumbnail ============================================

    public void setPhotoCaptureSize(int width, int height) {
        this.photoWidth = width;
        this.photoHeight = height;
        Log.d("MainActivity", "Photo capture size set: " + width + "x" + height);
    }

    public void onProcessedPhotoFromNative(byte[] bgraData) {
        if (bgraData == null || bgraData.length == 0 || photoWidth == 0 || photoHeight == 0) {
            Log.e("MainActivity", "Empty BGRA or size=0");
            return;
        }
        new Thread(() -> {
            try {
                Bitmap bmp = createBitmapFromBgra(bgraData, photoWidth, photoHeight);
                int rotationDegrees = computePreviewRotationDegrees(currentCameraId);
                Bitmap rotated = rotateBitmap(bmp, rotationDegrees, currentCameraId.equals(CameraHandler.FRONT_CAMERA_ID));
                Uri photoUri = savePhotoToGallery(rotated);
                lastMediaUri = photoUri;
                runOnUiThread(() -> {
                    updateLastItemThumb(photoUri);
                    Toast.makeText(this, "Photo saved", Toast.LENGTH_SHORT).show();
                });
                if (rotated != null && rotated != bmp) rotated.recycle();
                if (bmp != null) bmp.recycle();
            } catch (Exception e) {
                Log.e("MainActivity","photo save failed", e);
                runOnUiThread(() -> Toast.makeText(this, "Failed to save photo", Toast.LENGTH_SHORT).show());
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
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private Bitmap rotateBitmap(Bitmap source, int degrees, boolean isFrontCamera) {
        if (degrees == 0 && !isFrontCamera) return source;
        Matrix m = new Matrix();
        if (isFrontCamera) m.postScale(-1, 1);
        m.postRotate(degrees);
        try {
            Bitmap out = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), m, true);
            if (out != source) source.recycle();
            return out;
        } catch (OutOfMemoryError e) {
            Log.e("MainActivity", "OOM rotating bitmap", e);
            return source;
        }
    }

    private Uri savePhotoToGallery(Bitmap bitmap) throws IOException {
        long ts = System.currentTimeMillis();
        String name = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(ts)) + ".jpg";

        Uri targetUri = null;
        OutputStream out = null;
        String legacyPath = null;

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CameraLiveFX");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);

                targetUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (targetUri == null) throw new IOException("insert photo failed");
                out = getContentResolver().openOutputStream(targetUri);
                if (out == null) throw new IOException("openOutputStream null");
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);

                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(targetUri, values, null, null);

            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera");
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, name);
                out = new FileOutputStream(file);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
                legacyPath = file.getAbsolutePath();
                targetUri = Uri.fromFile(file);
            }
        } finally {
            if (out != null) try { out.close(); } catch (IOException ignored) {}
            if (legacyPath != null) {
                MediaScannerConnection.scanFile(this, new String[]{legacyPath}, new String[]{"image/jpeg"}, null);
            }
        }
        return targetUri;
    }

    private void updateLastItemThumb(Uri uri) {
        if (uri == null) return;

        // Try to detect mime type
        String mime = null;
        try {
            mime = getContentResolver().getType(uri);
        } catch (Exception ignored) {}

        if (mime != null && mime.startsWith("video/")) {
            // For videos: extract a frame bitmap asynchronously and show it
            loadVideoThumbAsync(uri);
        } else {
            // For photos: simple is fine
            thumbnailButton.setImageURI(uri);
        }

        // Open on click (works for both photo and video)
        thumbnailButton.setOnClickListener(v -> {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, getContentResolver().getType(uri));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(this, "Cannot open media", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadVideoThumbAsync(Uri videoUri) {
        new Thread(() -> {
            android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
            Bitmap frame = null;
            try {
                retriever.setDataSource(this, videoUri);
                // Grab a frame near the start; FALLBACK to any available frame
                // -1 means “any frame”, but some devices prefer a positive time.
                frame = retriever.getFrameAtTime(1_000_000); // ~1s
                if (frame == null) {
                    frame = retriever.getFrameAtTime(-1); // fallback to any
                }
            } catch (Throwable ignored) {
            } finally {
                try { retriever.release(); } catch (Exception ignored) {}
            }

            final Bitmap bmp = frame;
            runOnUiThread(() -> {
                if (bmp != null) {
                    thumbnailButton.setImageBitmap(bmp);
                } else {
                    // As a last resort, keep previous image or set a placeholder
                    // thumbnailButton.setImageResource(R.drawable.ic_video_placeholder);
                }
            });
        }).start();
    }


    // === UI wiring ============================================================

    private void setupModeButtons() {
        photoModeButton.setOnClickListener(v -> {
            if (!isPhotoMode) {
                isPhotoMode = true;
                updateModeUI(true);
                if (isRecording) stopRecording();
                Toast.makeText(MainActivity.this, "Photo Mode", Toast.LENGTH_SHORT).show();
            }
        });

        videoModeButton.setOnClickListener(v -> {
            if (isPhotoMode) {
                isPhotoMode = false;
                updateModeUI(false);
                Toast.makeText(MainActivity.this, "Video Mode", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupIconTintSelectorsAndListeners() {
        ColorStateList tintSelector = ContextCompat.getColorStateList(this, R.color.btn_icon_tint_selector);
        ImageViewCompat.setImageTintList(cameraSwitchButton, tintSelector);

        cameraSwitchButton.setOnClickListener(v -> switchCamera());

        thumbnailButton.setOnClickListener(v ->
                Toast.makeText(MainActivity.this, "No media yet", Toast.LENGTH_SHORT).show()
        );

        captureButton.setOnClickListener(v -> {
            if (isPhotoMode) {
                nativeCapturePhoto();
            } else {
                if (!isRecording) startRecording(); else stopRecording();
            }
        });
    }

    private void updateModeUI(boolean isPhoto) {
        int activeColor = ContextCompat.getColor(this, R.color.white);
        int inactiveColor = ContextCompat.getColor(this, R.color.dark_gray);
        if (isPhoto) {
            photoModeButton.setTextColor(activeColor);
            videoModeButton.setTextColor(inactiveColor);
            captureButton.setImageResource(R.drawable.btn_photo_capture_selector);
        } else {
            photoModeButton.setTextColor(inactiveColor);
            videoModeButton.setTextColor(activeColor);
            captureButton.setImageResource(R.drawable.btn_video_capture_selector);
        }
        filterScrollView.setVisibility(View.VISIBLE);
    }

    private void setupFilterThumbnails() {
        filterListContainer.removeAllViews();
        // Insert “None” at position 0 for a neutral option
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(20, 0, 20, 0);

        addFilterChip("None", lp);
        for (String name : filterNames) addFilterChip(name, lp);
        // default highlight
        highlightSelectedFilter("None");
    }

    private void addFilterChip(String name, LinearLayout.LayoutParams lp) {
        TextView chip = new TextView(this);
        chip.setLayoutParams(lp);
        chip.setText(name);
        chip.setTextColor(Color.WHITE);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(30, 15, 30, 15);
        chip.setBackgroundResource(R.drawable.filter_button_background);
        chip.setOnClickListener(v -> {
            currentFilterName = name;
            nativeSetCurrentFilter(name);
            highlightSelectedFilter(name);
            Toast.makeText(this, "Filter: " + name, Toast.LENGTH_SHORT).show();
        });
        filterListContainer.addView(chip);
    }

    private void highlightSelectedFilter(String name) {
        for (int i = 0; i < filterListContainer.getChildCount(); i++) {
            TextView child = (TextView) filterListContainer.getChildAt(i);
            child.setBackgroundResource(child.getText().toString().equals(name)
                    ? R.drawable.filter_button_background_selected
                    : R.drawable.filter_button_background);
        }
    }

    // === Camera switching =====================================================

    private void switchCamera() {
        Toast.makeText(this, "Switching camera…", Toast.LENGTH_SHORT).show();
        if (cameraHandler == null) return;
        cameraHandler.shutdown();
        if (CameraHandler.BACK_CAMERA_ID.equals(currentCameraId)) {
            currentCameraId = CameraHandler.FRONT_CAMERA_ID;
        } else {
            currentCameraId = CameraHandler.BACK_CAMERA_ID;
        }
        cameraHandler.startCamera(currentCameraId);
        int previewDegrees = computePreviewRotationDegrees(currentCameraId);
        nativeSetRotationDegrees(previewDegrees);
    }

    // === Rotation helper ======================================================

    private int computePreviewRotationDegrees(String cameraId) {
        int deviceRotation = Surface.ROTATION_0;
        try {
            Display d = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
            deviceRotation = d.getRotation();
        } catch (Throwable ignored) {}

        int degrees = 0;
        switch (deviceRotation) {
            case Surface.ROTATION_0:   degrees = 0; break;
            case Surface.ROTATION_90:  degrees = 90; break;
            case Surface.ROTATION_180: degrees = 180; break;
            case Surface.ROTATION_270: degrees = 270; break;
        }

        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            CameraCharacteristics cc = cm.getCameraCharacteristics(cameraId);
            Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
            int sensor = (so != null) ? so : 90;
            boolean front = (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT);

            // Preview buffer rotation for native to draw upright
            int result;
            if (front) {
                result = (sensor + degrees) % 360;       // mirror handled in native if needed
            } else {
                result = (sensor - degrees + 360) % 360;
            }
            // Snap to 0/90/180/270 (native path expects quarter turns)
            if (result < 45) return 0;
            if (result < 135) return 90;
            if (result < 225) return 180;
            if (result < 315) return 270;
            return 0;
        } catch (Exception e) {
            return 90;
        }
    }

    // === perms / lifecycle ====================================================

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { stopRecording(); } catch (Throwable ignored) {}
        if (cameraHandler != null) cameraHandler.shutdown();
        safeCloseVideoPfd();
    }
}
