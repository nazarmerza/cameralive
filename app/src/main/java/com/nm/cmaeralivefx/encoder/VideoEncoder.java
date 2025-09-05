package com.nm.cmaeralivefx.encoder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

public class VideoEncoder {
    private static final String TAG = "VideoEncoder";

    private static final String MIME_TYPE = "video/avc";
    private static final int BIT_RATE = 2000_000;
    private static final int FRAME_RATE = 30;
    private static final int I_FRAME_INTERVAL = 1;

    private final MediaCodec encoder;
    private final MediaFormat format;
    private final MediaMuxerWrapper muxerWrapper;

    private int trackIndex = -1;
    private boolean isMuxerStarted = false;

    public VideoEncoder(MediaMuxerWrapper muxerWrapper, int width, int height) throws IOException {
        this.muxerWrapper = muxerWrapper;

        format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);

        encoder = MediaCodec.createEncoderByType(MIME_TYPE);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    public void start() {
        encoder.start();
    }

    public void encodeFrame(byte[] data, long presentationTimeUs) {
        ByteBuffer[] inputBuffers = encoder.getInputBuffers();
        int inputBufferIndex = encoder.dequeueInputBuffer(10000);
        if (inputBufferIndex >= 0) {
            ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
            inputBuffer.clear();
            inputBuffer.put(data);
            encoder.queueInputBuffer(inputBufferIndex, 0, data.length, presentationTimeUs, 0);
        }

        drainEncoder();
    }

    private void drainEncoder() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (true) {
            int outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0);
            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (isMuxerStarted) {
                    throw new IllegalStateException("Format changed twice");
                }
                MediaFormat newFormat = encoder.getOutputFormat();
                trackIndex = muxerWrapper.addTrack(newFormat);
//                muxerWrapper.startMuxerIfReady();
                isMuxerStarted = true;
            } else if (outputBufferIndex >= 0) {
                if (!isMuxerStarted) {
                    throw new IllegalStateException("Muxer not started yet");
                }

                ByteBuffer[] outputBuffers = encoder.getOutputBuffers();
                ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }

                if (bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset);
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                    muxerWrapper.writeSampleData(trackIndex, outputBuffer, bufferInfo);
                }

                encoder.releaseOutputBuffer(outputBufferIndex, false);
            }
        }
    }

    public void stop() {
        try {
            encoder.signalEndOfInputStream();
        } catch (Exception e) {
            Log.w(TAG, "signalEndOfInputStream failed", e);
        }

        drainEncoder();

        try {
            encoder.stop();
        } catch (Exception e) {
            Log.w(TAG, "MediaCodec stop failed", e);
        }

        encoder.release();
    }
}
