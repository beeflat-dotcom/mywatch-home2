package jp.beeflat.beetransceiver;



import android.media.MediaCodec;

import android.media.MediaCodecInfo;

import android.media.MediaFormat;

import android.media.MediaMuxer;

import java.io.File;

import java.io.FileDescriptor;

import java.nio.ByteBuffer;



/** PCM 16-bit / mono / 16 kHz -> AAC-LC 24 kbps M4A. */

final class AacM4aWriter implements AutoCloseable {

    private static final String MIME = MediaFormat.MIMETYPE_AUDIO_AAC;

    private static final int SAMPLE_RATE = 16000;

    private static final int CHANNELS = 1;

    private static final int BIT_RATE = 24000;

    private final MediaCodec codec;

    private final MediaMuxer muxer;

    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    private boolean muxerStarted = false;

    private boolean closed = false;

    private int trackIndex = -1;

    private long samplesSubmitted = 0;

    private long encodedBytesWritten = 0;



    AacM4aWriter(File outputFile) throws Exception {

        File parent = outputFile.getParentFile();

        if (parent != null && !parent.exists() && !parent.mkdirs())

            throw new IllegalStateException("保存フォルダを作成できません: " + parent);

        codec = createCodec();

        muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

    }

    AacM4aWriter(FileDescriptor outputFd) throws Exception {

        codec = createCodec();

        muxer = new MediaMuxer(outputFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

    }

    private static MediaCodec createCodec() throws Exception {

        MediaFormat format = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, CHANNELS);

        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);

        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);

        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);

        MediaCodec codec = MediaCodec.createEncoderByType(MIME);

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        codec.start();

        return codec;

    }

    long getEncodedBytesWritten() { return encodedBytesWritten; }

    void writePcm(byte[] pcm, int length) throws Exception {

        if (closed || pcm == null || length <= 0) return;

        int offset = 0;

        while (offset < length) {

            int inputIndex = codec.dequeueInputBuffer(10000);

            if (inputIndex < 0) { drain(false); continue; }

            ByteBuffer input = codec.getInputBuffer(inputIndex);

            if (input == null) continue;

            input.clear();

            int chunk = Math.min(input.remaining(), length - offset);

            input.put(pcm, offset, chunk);

            long ptsUs = samplesSubmitted * 1_000_000L / SAMPLE_RATE;

            codec.queueInputBuffer(inputIndex, 0, chunk, ptsUs, 0);

            samplesSubmitted += chunk / 2L;

            offset += chunk;

            drain(false);

        }

    }

    private void drain(boolean endOfStream) throws Exception {

        if (endOfStream) {

            boolean queued = false;

            while (!queued) {

                int inputIndex = codec.dequeueInputBuffer(10000);

                if (inputIndex >= 0) {

                    long ptsUs = samplesSubmitted * 1_000_000L / SAMPLE_RATE;

                    codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);

                    queued = true;

                } else drainOutputOnce();

            }

        }

        boolean eos = false;

        while (!eos) {

            int result = drainOutputOnce();

            if (result == 0 && !endOfStream) break;

            if (result == 2) eos = true;

        }

    }

    /** 0=no output, 1=output handled, 2=EOS handled */

    private int drainOutputOnce() throws Exception {

        int outputIndex = codec.dequeueOutputBuffer(info, 10000);

        if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) return 0;

        if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

            if (muxerStarted) throw new IllegalStateException("出力形式が二度変更されました");

            trackIndex = muxer.addTrack(codec.getOutputFormat());

            muxer.start(); muxerStarted = true; return 1;

        }

        if (outputIndex < 0) return 1;

        ByteBuffer output = codec.getOutputBuffer(outputIndex);

        if (output == null) { codec.releaseOutputBuffer(outputIndex, false); return 1; }

        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;

        if (info.size > 0) {

            if (!muxerStarted) throw new IllegalStateException("M4A muxerが未開始です");

            output.position(info.offset); output.limit(info.offset + info.size);

            int size = info.size;

            muxer.writeSampleData(trackIndex, output, info);

            encodedBytesWritten += size;

        }

        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;

        codec.releaseOutputBuffer(outputIndex, false);

        return eos ? 2 : 1;

    }

    @Override public void close() throws Exception {

        if (closed) return; closed = true;

        Exception first = null;

        try { drain(true); } catch (Exception e) { first = e; }

        try { codec.stop(); } catch (Exception e) { if (first == null) first = e; }

        try { codec.release(); } catch (Exception e) { if (first == null) first = e; }

        if (muxerStarted) try { muxer.stop(); } catch (Exception e) { if (first == null) first = e; }

        try { muxer.release(); } catch (Exception e) { if (first == null) first = e; }

        if (first != null) throw first;

    }

}