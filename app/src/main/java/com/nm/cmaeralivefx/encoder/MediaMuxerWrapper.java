package com.nm.cmaeralivefx.encoder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

public class MediaMuxerWrapper {
    private final Object muxerLock = new Object();

    private final MediaMuxer mediaMuxer;
    private final AtomicInteger trackCount = new AtomicInteger(0);
    private volatile boolean isStarted = false;

    // How many tracks we wait for before starting the muxer (default: audio + video)
    private int expectedTrackCount = 2;

    // Only used for the MediaStore/FD constructor (API 29+)
    private final ParcelFileDescriptor pfd;   // may be null (path constructor)
    private final Uri outputUri;              // may be null (path constructor)

    // orientation hint must be set before start()
    private Integer orientationHintDegrees = null;

    // ===== Constructor for classic path (API ≤ 28 or your own file path) =====
    public MediaMuxerWrapper(String outputPath) throws IOException {
        this.mediaMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        this.pfd = null;
        this.outputUri = null;
    }

    /**
     * Must be called BEFORE the muxer starts (i.e., before the last track triggers start()).
     * Valid values: 0, 90, 180, 270.
     */
    public void setOrientationHint(int degrees) {
        synchronized (muxerLock) {
            if (isStarted) {
                throw new IllegalStateException("setOrientationHint must be called before muxer start()");
            }
            orientationHintDegrees = degrees;
            mediaMuxer.setOrientationHint(degrees);
        }
    }
    // ===== Constructor for MediaStore FD flow (API ≥ 29) =====
    // You create MediaMuxer with a FileDescriptor in MainActivity and pass it in.
    public MediaMuxerWrapper(MediaMuxer mediaMuxer, Uri outputUri, ParcelFileDescriptor pfd) {
        this.mediaMuxer = mediaMuxer;
        this.outputUri  = outputUri;
        this.pfd        = pfd;
    }

    /** Register a new track and (when all expected tracks are added) start the muxer. */
    public int addTrack(MediaFormat format) {
        synchronized (muxerLock) {
            int trackIndex = mediaMuxer.addTrack(format);
            int registered = trackCount.incrementAndGet();
            if (registered == expectedTrackCount && !isStarted) {
                mediaMuxer.start();
                isStarted = true;
            }
            return trackIndex;
        }
    }

    /** Write sample data if muxer has started. */
    public void writeSampleData(int trackIndex, ByteBuffer buffer, MediaCodec.BufferInfo info) {
        synchronized (muxerLock) {
            if (isStarted && info != null && info.size > 0) {
                mediaMuxer.writeSampleData(trackIndex, buffer, info);
            }
        }
    }

    /** Stop and release the muxer safely (and close FD if provided). */
    public void stop() {
        synchronized (muxerLock) {
            try {
                if (isStarted) {
                    try {
                        mediaMuxer.stop();
                    } catch (IllegalStateException ignored) {
                        // If muxer never started or already stopped
                    }
                }
            } finally {
                try {
                    mediaMuxer.release();
                } catch (Exception ignored) {}
                if (pfd != null) {
                    try { pfd.close(); } catch (Exception ignored) {}
                }
                isStarted = false;
            }
        }
    }

    /** Optional: set how many tracks to wait for before starting (default 2). */
    public void setExpectedTrackCount(int count) {
        synchronized (muxerLock) {
            expectedTrackCount = Math.max(1, count);
        }
    }

    public boolean isStarted() {
        return isStarted;
    }

    // Optional getters (useful if you want to do something with the uri/path after stop)
    public Uri getOutputUri() { return outputUri; }
}
