package com.nm.cameralivefx;

import static com.nm.cameralivefx.utils.CubeTo3DLUT.loadCubeAsset;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
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
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.nm.cmaeralivefx.encoder.AudioEncoder;
import com.nm.cmaeralivefx.encoder.MediaMuxerWrapper;
import com.nm.cmaeralivefx.encoder.VideoEncoder;

import java.io.File;
import java.io.IOException;
import android.os.ParcelFileDescriptor;

public class MainActivity extends AppCompatActivity {

    static {
        System.loadLibrary("cameralivefx");
    }

    public native void nativeSetSurface(Surface surface);
    public static native void nativeSetJavaContext(MainActivity activity);
//    public static native void nativeSetRotationDegrees(int degrees);


    private SurfaceView surfaceView;
    private Button recordBtn;
    private volatile boolean isRecording = false;

    private CameraHandler cameraHandler;
    private VideoEncoder videoEncoder;
    private AudioEncoder audioEncoder;
    private MediaMuxerWrapper muxerWrapper;

    private static final int REQUEST_PERMISSIONS = 1001;

    // Keep track of output for post-stop actions
    private String outputPath = null;   // used on API <= 28 (app-private file)
    private Uri outputUri = null;       // used on API >= 29 (MediaStore)

    private int previewDegrees = 0;  // store here for reuse

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_landscape);

        surfaceView = findViewById(R.id.surfaceView);
        recordBtn   = findViewById(R.id.recrod_btn);

        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    REQUEST_PERMISSIONS
            );
        }

        previewDegrees = computePreviewRotationDegrees();
        nativeSetJavaContext(this);

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                nativeSetSurface(holder.getSurface());


//                nativeSetRotationDegrees(previewDegrees);

                if (cameraHandler == null) {
                    cameraHandler = new CameraHandler(MainActivity.this, holder.getSurface());
                }
                cameraHandler.startCamera();
            }

            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                if (cameraHandler != null) {
                    cameraHandler.shutdown();
                }
            }
        });

        recordBtn.setOnClickListener(v -> {
            if (!isRecording) {
                startRecording();
            } else {
                stopRecording();
            }
        });
    }

    private void startRecording() {
        try {
            // Reset outputs for this session
            outputPath = null;
            outputUri  = null;

            if (Build.VERSION.SDK_INT >= 29) {
                // === API 29+: create an item in MediaStore (Movies/CameraLiveFX) and open FD ===
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, "my_video_" + System.currentTimeMillis() + ".mp4");
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraLiveFX");

                outputUri = getContentResolver().insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (outputUri == null) throw new IOException("Failed to insert MediaStore item");

                ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(outputUri, "w");
                if (pfd == null) throw new IOException("Failed to open PFD for MediaMuxer");

                MediaMuxer platformMuxer = new MediaMuxer(
                        pfd.getFileDescriptor(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                );

                // Requires your MediaMuxerWrapper to have a constructor that accepts (MediaMuxer, Uri, PFD)
                muxerWrapper = new MediaMuxerWrapper(platformMuxer, outputUri, pfd);
                muxerWrapper.setOrientationHint(previewDegrees);

            } else {
                // === API 28 and below: write to app-private external Movies dir (no storage perm) ===
                File file = new File(
                        getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                        "my_video_" + System.currentTimeMillis() + ".mp4"
                );
                outputPath = file.getAbsolutePath();

                muxerWrapper = new MediaMuxerWrapper(outputPath);
                // Apply orientation hint BEFORE adding tracks / starting encoders
                muxerWrapper.setOrientationHint(previewDegrees);
            }

            Size chosenSize = cameraHandler.getChosenSize();
            // Start encoders
            videoEncoder = new VideoEncoder(muxerWrapper, chosenSize.getWidth(), chosenSize.getHeight());
            audioEncoder = new AudioEncoder(muxerWrapper);

            audioEncoder.start();
            videoEncoder.start();

            isRecording = true;
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();

        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopRecording() {
        isRecording = false;

        if (audioEncoder != null) {
            try { audioEncoder.stop(); } catch (Exception ignored) {}
            audioEncoder = null;
        }
        if (videoEncoder != null) {
            try { videoEncoder.stop(); } catch (Exception ignored) {}
            videoEncoder = null;
        }
        if (muxerWrapper != null) {
            try { muxerWrapper.stop(); } catch (Exception ignored) {}
            muxerWrapper = null;
        }

        // For legacy path-based output (API <= 28) let MediaScanner index the file so it's visible.
        if (outputPath != null) {
            MediaScannerConnection.scanFile(
                    this,
                    new String[]{ outputPath },
                    new String[]{ "video/mp4" },
                    null
            );
        }
        // For MediaStore (API 29+), no scan needed.

        Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show();
    }

    public void onProcessedFrameFromNative(byte[] data, long timestampUs) {
        if (isRecording && videoEncoder != null) {
            videoEncoder.encodeFrame(data, timestampUs);
        }
    }

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private int computePreviewRotationDegrees() {
        try {
            android.hardware.camera2.CameraManager mgr =
                    (android.hardware.camera2.CameraManager) getSystemService(CAMERA_SERVICE);
            String cameraId = mgr.getCameraIdList()[0]; // back camera for now
            android.hardware.camera2.CameraCharacteristics cc =
                    mgr.getCameraCharacteristics(cameraId);

            Integer sensorOrientation = cc.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION);
            if (sensorOrientation == null) sensorOrientation = 0;

            int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
            int deviceDegrees = displayRotation == android.view.Surface.ROTATION_90 ? 90
                    : displayRotation == android.view.Surface.ROTATION_180 ? 180
                    : displayRotation == android.view.Surface.ROTATION_270 ? 270 : 0;

            // Back camera preview rotation to look upright
            return (sensorOrientation - deviceDegrees + 360) % 360;
        } catch (Exception e) {
            return 90; // safe default on many devices
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRecording();
        if (cameraHandler != null) {
            cameraHandler.shutdown();
        }
    }
}
