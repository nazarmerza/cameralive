package com.nm.cmaeralivefx.encoder;

import android.media.*;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

public class AudioEncoder {
    private static final String TAG = "AudioEncoder";
    private static final String MIME_TYPE = "audio/mp4a-latm";
    private static final int BIT_RATE = 64000;
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_COUNT = 1;

    private final MediaCodec codec;
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private final AudioRecord audioRecord;
    private final MediaMuxerWrapper muxer;
    private int trackIndex = -1;
    private boolean isEncoding = false;

    public AudioEncoder(MediaMuxerWrapper muxerWrapper) throws IOException {
        this.muxer = muxerWrapper;

        MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNEL_COUNT);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        codec = MediaCodec.createEncoderByType(MIME_TYPE);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
    }

    public void start() {
        codec.start();
        audioRecord.startRecording();
        isEncoding = true;

        new Thread(this::recordLoop).start();
    }

    private void recordLoop() {
        ByteBuffer[] inputBuffers = codec.getInputBuffers();
        byte[] buffer = new byte[2048];

        while (isEncoding) {
            int inputBufferIndex = codec.dequeueInputBuffer(10000);
            if (inputBufferIndex >= 0) {
                int length = audioRecord.read(buffer, 0, buffer.length);
                if (length > 0) {
                    ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                    inputBuffer.clear();
                    inputBuffer.put(buffer, 0, length);
                    codec.queueInputBuffer(inputBufferIndex, 0, length,
                            System.nanoTime() / 1000, 0);
                }
            }

            drain();
        }
    }

    private void drain() {
        ByteBuffer[] outputBuffers = codec.getOutputBuffers();
        while (true) {
            int outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0);
            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            if (outputBufferIndex >= 0) {
                ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }

                if (bufferInfo.size != 0) {
                    outputBuffer.position(bufferInfo.offset);
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                    muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo);
                }

                codec.releaseOutputBuffer(outputBufferIndex, false);
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = codec.getOutputFormat();
                trackIndex = muxer.addTrack(newFormat);
//                muxer.start();
            }
        }
    }

    public void stop() {
        isEncoding = false;
        audioRecord.stop();
        audioRecord.release();
        codec.stop();
        codec.release();
    }
}
