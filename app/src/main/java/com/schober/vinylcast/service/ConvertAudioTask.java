package com.schober.vinylcast.service;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Process;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;

/**
 * Runnable used to convert raw PCM audio data from audioRecorderInputStream to an AAC DTS input stream.
 * Based on https://stackoverflow.com/questions/18862715/how-to-generate-the-aac-adts-elementary-stream-with-android-mediacodec
 */
public class ConvertAudioTask implements Runnable {
    private static final String TAG = "ConvertAudioTask";

    private static final String CODEC_MIME_TYPE = "audio/mp4a-latm";
    private static final int CODEC_BIT_RATE = 192000;
    private static final long CODEC_TIMEOUT = 10000;
    private static final boolean CODEC_VERBOSE = false;

    // ADTS Header Information from https://wiki.multimedia.cx/index.php/ADTS
    private static final int ADTS_HEADER_AUDIO_OBJECT_TYPE = 2; //AAC LC
    private static final int ADTS_HEADER_SAMPLE_RATE_INDEX = 3; // 3 = 48000, 4 = 44100
    private static final int ADTS_HEADER_CHANNEL_CONFIG = 2; //2 Channel

    private InputStream audioRecorderInputStream;
    private int sampleRate;
    private int channelCount;

    private PipedOutputStream pipedOutputStream;
    private PipedInputStream convertedInputStream;

    /**
     * Get InputStream that provides converted audio output. Must be called before starting Runnable.
     * @param audioRecorderInputStream
     * @param sampleRate
     * @return
     */
    public InputStream getConvertedInputStream(InputStream audioRecorderInputStream, int sampleRate, int channelCount) {
        this.audioRecorderInputStream = audioRecorderInputStream;
        this.sampleRate = sampleRate;
        this.channelCount = channelCount;
        this.convertedInputStream = new PipedInputStream();
        try {
            pipedOutputStream = new PipedOutputStream(this.convertedInputStream);
            return convertedInputStream;
        } catch (IOException e) {
            Log.e(TAG, "Exception creating output stream", e);
            return null;
        }
    }

    private int queueInputBuffer(MediaCodec codec, int inputBufferId) throws IOException {
        ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferId);
        inputBuffer.clear();

        int bytesAvailable = audioRecorderInputStream.available();
        int bytesRead = bytesAvailable < inputBuffer.limit() ? bytesAvailable : inputBuffer.limit();
        byte[] audioRecorderBytes = new byte[bytesRead];
        audioRecorderInputStream.read(audioRecorderBytes);
        inputBuffer.put(audioRecorderBytes);
        codec.queueInputBuffer(inputBufferId, 0, bytesRead, 0, 0);
        return bytesRead;
    }

    private int dequeueOutputBuffer(MediaCodec codec, int outputBufferId, MediaCodec.BufferInfo info) throws IOException {
        int outBitsSize = info.size;
        int outPacketSize = outBitsSize + 7;    // 7 is ADTS header size
        ByteBuffer outBuf = codec.getOutputBuffer(outputBufferId);

        outBuf.position(info.offset);
        outBuf.limit(info.offset + outBitsSize);
        byte[] data = new byte[outPacketSize];
        addADTStoPacket(data, outPacketSize);
        outBuf.get(data, 7, outBitsSize);
        outBuf.position(info.offset);
        pipedOutputStream.write(data, 0, outPacketSize);
        pipedOutputStream.flush();

        outBuf.clear();
        codec.releaseOutputBuffer(outputBufferId, false);

        return outBitsSize;
    }

    /**
     * Add ADTS header at the beginning of each and every AAC packet.
     * This is needed as MediaCodec encoder generates a packet of raw
     * AAC data.
     * <p>
     * Note the packetLen must count in the ADTS header itself.
     **/
    private void addADTStoPacket(byte[] packet, int packetLen) {
        int profile = ADTS_HEADER_AUDIO_OBJECT_TYPE;
        int freqIdx = ADTS_HEADER_SAMPLE_RATE_INDEX;
        int chanCfg = ADTS_HEADER_CHANNEL_CONFIG;

        // fill in ADTS data
        packet[0] = (byte) 0xFF;
        packet[1] = (byte) 0xF9;
        packet[2] = (byte) (((profile - 1) << 6) + (freqIdx << 2) + (chanCfg >> 2));
        packet[3] = (byte) (((chanCfg & 3) << 6) + (packetLen >> 11));
        packet[4] = (byte) ((packetLen & 0x7FF) >> 3);
        packet[5] = (byte) (((packetLen & 7) << 5) + 0x1F);
        packet[6] = (byte) 0xFC;
    }

    @Override
    public void run() {
        Log.d(TAG, "starting...");
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

        MediaFormat format = MediaFormat.createAudioFormat(CODEC_MIME_TYPE, sampleRate, channelCount);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, CODEC_BIT_RATE);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CODEC_BIT_RATE);

        MediaCodec codec;
        try {
            codec = MediaCodec.createEncoderByType(CODEC_MIME_TYPE);
        } catch (IOException e) {
            Log.e(TAG, "Exception creating codec", e);
            return;
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        codec.start();
        int numBytesSubmitted = 0;
        int numBytesDequeued = 0;
        int bufferId;

        while (!Thread.currentThread().isInterrupted()) {
            // MediaCodec InputBuffer
            bufferId = codec.dequeueInputBuffer(CODEC_TIMEOUT);
            try {
                if (bufferId != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    int size = queueInputBuffer(codec, bufferId);
                    numBytesSubmitted += size;
                    if (CODEC_VERBOSE && size > 0) {
                        Log.d(TAG, "queued " + size + " bytes of input data.");
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Exception queuing input buffer. Queuing End Of Stream.", e);
                codec.queueInputBuffer(bufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }

            // MediaCodec OutputBuffer
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            bufferId = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                Log.d(TAG, "Dequeued End Of Stream.");
                break;
            } else {
                try {
                    if (bufferId >= 0) {
                        int outBitsSize = dequeueOutputBuffer(codec, bufferId, info);
                        numBytesDequeued += outBitsSize;
                        if (CODEC_VERBOSE) {
                            Log.d(TAG, "  dequeued " + outBitsSize + " bytes of output data.");
                        }
                    }
                } catch (InterruptedIOException e) {
                    Log.d(TAG, "interrupted");
                    break;
                } catch (IOException e) {
                    Log.e(TAG, "Exception dequeuing output buffer", e);
                    break;
                }
            }
        }

        if (CODEC_VERBOSE) {
            Log.d(TAG, "queued a total of " + numBytesSubmitted + "bytes, "
                    + "dequeued " + numBytesDequeued + " bytes.");
        }
        int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int inBitrate = sampleRate * channelCount * 16;  // bit/sec
        int outBitrate = format.getInteger(MediaFormat.KEY_BIT_RATE);
        float desiredRatio = (float) outBitrate / (float) inBitrate;
        float actualRatio = (float) numBytesDequeued / (float) numBytesSubmitted;
        if (actualRatio < 0.9 * desiredRatio || actualRatio > 1.1 * desiredRatio) {
            Log.w(TAG, "desiredRatio = " + desiredRatio
                    + ", actualRatio = " + actualRatio);
        }
        codec.release();

        Log.d(TAG, "stopping...");
        try {
            pipedOutputStream.close();
        } catch (IOException e) {
            Log.e(TAG, "Exception closing streams", e);
        }
    }
}