package com.nm.cameralivefx;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class CameraHandler {
    static {
        System.loadLibrary("cameralivefx");
    }

    private native void processFrameYUV(byte[] yData, byte[] uData, byte[] vData,
                                        int yRowStride, int uRowStride, int vRowStride,
                                        int uPixelStride, int vPixelStride, int width, int height);

    private static final String TAG = CameraHandler.class.getSimpleName();

    public static final String FRONT_CAMERA_ID = "1";
    public static final String BACK_CAMERA_ID = "0";

    private Size chosenSize;
    private static final int MAX_W = 960;
    private static final int MAX_H = 540;
    private static final double TARGET_ASPECT = 16.0 / 9.0;
    private static final double ASPECT_TOL = 0.05;

    private final MainActivity mActivity; // Changed from Context to MainActivity
    private final Surface previewSurface; // only used by native to draw; not fed to camera
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Handler backgroundHandler;

    // 1. UPDATED CONSTRUCTOR to take MainActivity reference
    public CameraHandler(MainActivity activity, Surface surface) {
        this.mActivity = activity;
        this.previewSurface = surface;

        HandlerThread backgroundThread = new HandlerThread("CameraThread");
        backgroundThread.start();
        this.backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    @SuppressLint("MissingPermission")
    public void startCamera(String cameraId) {
        CameraManager manager = (CameraManager) mActivity.getSystemService(Context.CAMERA_SERVICE); // Use mActivity to get service
        try {
            String cameraIdToOpen = cameraId;
            CameraCharacteristics cc = manager.getCameraCharacteristics(cameraIdToOpen);
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] choices = map.getOutputSizes(ImageFormat.YUV_420_888);

            chosenSize = chooseOptimalYuvSize(choices, MAX_W, MAX_H, TARGET_ASPECT);
            Log.d(TAG, "Chosen YUV size: " + chosenSize.getWidth() + "x" + chosenSize.getHeight());

            // 2. IMPORTANT FIX: Pass the chosen frame size to MainActivity
            // The photo capture happens on this same stream, so these are the dimensions
            // that the C++ code uses when it captures and returns the BGRA buffer.
            mActivity.setPhotoCaptureSize(chosenSize.getWidth(), chosenSize.getHeight());

            // Use a slightly deeper queue to reduce “Failed to lock window” bursts under load
            imageReader = ImageReader.newInstance(chosenSize.getWidth(), chosenSize.getHeight(),
                    ImageFormat.YUV_420_888, /*maxImages*/3);

            imageReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireLatestImage();
                if (image != null) {
                    // ... (Frame processing logic remains the same) ...

                    Image.Plane[] planes = image.getPlanes();

                    // Y
                    ByteBuffer yBuffer = planes[0].getBuffer();
                    byte[] yData = new byte[yBuffer.remaining()];
                    yBuffer.get(yData);
                    int yRowStride = planes[0].getRowStride();

                    // U
                    ByteBuffer uBuffer = planes[1].getBuffer();
                    byte[] uData = new byte[uBuffer.remaining()];
                    uBuffer.get(uData);
                    int uRowStride = planes[1].getRowStride();
                    int uPixelStride = planes[1].getPixelStride();

                    // V
                    ByteBuffer vBuffer = planes[2].getBuffer();
                    byte[] vData = new byte[vBuffer.remaining()];
                    vBuffer.get(vData);
                    int vRowStride = planes[2].getRowStride();
                    int vPixelStride = planes[2].getPixelStride();

                    // Native processing + preview drawing
                    processFrameYUV(
                            yData, uData, vData,
                            yRowStride, uRowStride, vRowStride,
                            uPixelStride, vPixelStride,
                            image.getWidth(), image.getHeight()
                    );

                    image.close();
                }
            }, backgroundHandler);

            manager.openCamera(cameraIdToOpen, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;

                    try {
                        // Single-output session (only ImageReader) to keep camera path light.
                        // NOTE: previewSurface is not used as a target for the capture session,
                        // it's only provided to the native layer to draw the processed frame.
                        camera.createCaptureSession(
                                Collections.singletonList(imageReader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override public void onConfigured(CameraCaptureSession session) {
                                        captureSession = session;
                                        try {
                                            CaptureRequest.Builder builder =
                                                    camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                            builder.addTarget(imageReader.getSurface());

                                            // Try to keep FPS modest and stable
                                            Range<Integer>[] fpsRanges =
                                                    cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                                            Range<Integer> preferred = pickFpsRange(fpsRanges, 24, 30);
                                            if (preferred != null) {
                                                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, preferred);
                                            }

                                            session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                                        } catch (CameraAccessException e) {
                                            e.printStackTrace();
                                        }
                                    }
                                    @Override public void onConfigureFailed(CameraCaptureSession session) {}
                                },
                                backgroundHandler
                        );
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override public void onDisconnected(CameraDevice camera) { camera.close(); }
                @Override public void onError(CameraDevice camera, int error) { camera.close(); }
            }, backgroundHandler);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // ... (rest of the methods remain the same) ...

    public Size getChosenSize() {
        return chosenSize;
    }

    public void shutdown() {
        if (captureSession != null) {
            try { captureSession.stopRepeating(); } catch (Exception ignore) {}
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    private static Size chooseOptimalYuvSize(Size[] choices, int maxW, int maxH, double targetAspect) {
        List<Size> candidates = new ArrayList<>();
        for (Size s : choices) {
            if (s.getWidth() <= maxW && s.getHeight() <= maxH) {
                double aspect = (double) s.getWidth() / s.getHeight();
                if (Math.abs(aspect - targetAspect) <= ASPECT_TOL) {
                    candidates.add(s);
                }
            }
        }
        if (candidates.isEmpty()) {
            // fallback: largest that fits caps (ignore aspect)
            for (Size s : choices) {
                if (s.getWidth() <= maxW && s.getHeight() <= maxH) {
                    candidates.add(s);
                }
            }
        }
        if (candidates.isEmpty()) {
            // last resort: smallest available
            return Collections.min(Arrays.asList(choices), new AreaComparator());
        }
        // pick largest area among candidates
        return Collections.max(candidates, new AreaComparator());
    }

    private static class AreaComparator implements Comparator<Size> {
        @Override public int compare(Size a, Size b) {
            long areaA = (long) a.getWidth() * a.getHeight();
            long areaB = (long) b.getWidth() * b.getHeight();
            return Long.compare(areaA, areaB);
        }
    }

    private static Range<Integer> pickFpsRange(Range<Integer>[] ranges, int min, int max) {
        if (ranges == null) return null;
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            if (r.getLower() <= min && r.getUpper() >= max) {
                // Prefer tight ranges around our target
                if (best == null || (r.getUpper() - r.getLower()) < (best.getUpper() - best.getLower())) {
                    best = r;
                }
            }
        }
        if (best != null) return best;
        // fallback: highest upper under/near target
        for (Range<Integer> r : ranges) {
            if (best == null || r.getUpper() > best.getUpper()) {
                best = r;
            }
        }
        return best;
    }
}