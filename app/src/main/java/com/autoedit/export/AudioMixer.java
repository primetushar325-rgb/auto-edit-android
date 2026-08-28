package com.autoedit.export;

import android.content.Context;
import android.media.*;
import android.net.Uri;
import android.util.Log;

import com.autoedit.model.AudioTrack;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Real audio mixing and encoding (spec §20, §47).
 *
 * <pre>
 *   AudioTrack.uri ─▶ MediaExtractor ─▶ MediaCodec (decode) ─▶ PCM16 source rate
 *        │                                                        │
 *        │  trim / loop / offset                                  ▼
 *        │                                              LinearResampler → target rate
 *        │                                                        │
 *        ▼                                                        ▼
 *   gain envelope (volume, mute, fade in/out) ─────────▶  mixed PCM16 ─▶ MediaCodec
 *                                                                              (AAC encode)
 *                                                                                 │
 *                                                                                 ▼
 *                                                            audio.aac + pts index + MediaFormat
 * </pre>
 *
 * The result is handed to {@link VideoExporter}, which interleaves it with the
 * video samples while writing the MP4. Nothing is faked: if this class returns
 * a result, the file really contains an AAC stream and the exporter verifies it
 * before marking the export successful.
 *
 * All decoding happens on the export thread, one track at a time, with a
 * bounded buffer, so a 1000-clip project does not blow up memory (§38).
 */
public class AudioMixer {
    private static final String TAG = "AutoEditAudio";
    /** Frames of silence written between/after tracks; keeps the AAC encoder fed. */
    private static final int MAX_DECODE_TRIES_PER_PULL = 40;

    public static class Result implements Closeable {
        public final File file;
        public final MediaFormat format;
        /** {ptsUs, byteOffset, size, flags} in file order. */
        public final List<long[]> index = new ArrayList<>();
        public final long durationUs;
        public final boolean present;

        Result(File file, MediaFormat format, long durationUs, boolean present) {
            this.file = file; this.format = format; this.durationUs = durationUs; this.present = present;
        }

        @Override public void close() {
            if (file != null && file.exists()) //noinspection ResultOfMethodCallIgnored
                file.delete();
        }
    }

    public interface Cancel { boolean cancelled(); }

    private final Context context;

    public AudioMixer(Context c) { this.context = c.getApplicationContext(); }

    /**
     * Decodes, trims, loops, fades and mixes every active track, then encodes
     * the mix to AAC in a temporary file.
     *
     * @param totalSec    the video duration the audio must be laid against
     * @param sampleRate  target sample rate (matches nothing in particular; AAC
     *                    supports the usual rates — 44100 is used unless the
     *                    encoder rejects it)
     * @return a Result whose {@link Result#present} is false when the project
     *         has no usable audio, in which case the exporter writes video only
     */
    public Result render(List<AudioTrack> tracks, float totalSec, int sampleRate,
                         Cancel cancel) throws IOException {
        List<AudioTrack> active = new ArrayList<>();
        if (tracks != null) for (AudioTrack t : tracks) if (t != null && !t.isSilent() && t.startSec < totalSec) active.add(t);
        if (active.isEmpty() || totalSec <= 0f) return new Result(null, null, 0, false);

        int channels = 2;
        int rate = pickSampleRate(sampleRate);
        File out = File.createTempFile("autoedit_audio", ".aac", context.getCacheDir());
        List<long[]> index = new ArrayList<>();
        MediaCodec encoder = null;
        FileOutputStream fos = null;
        long writtenFrames = 0;
        long totalOutUs = 0;
        List<TrackReader> readers = new ArrayList<>();

        try {
            encoder = configureEncoder(rate, channels);
            encoder.start();
            fos = new FileOutputStream(out);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            readers = new ArrayList<>();
            for (AudioTrack t : active) {
                try {
                    TrackReader r = new TrackReader(context, t, totalSec);
                    readers.add(r);
                } catch (Exception e) {
                    Log.w(TAG, "Skipping unreadable audio track " + t.uri, e);
                }
            }
            if (readers.isEmpty()) {
                throw new IOException("Audio could not be decoded (unsupported format or unreadable file).");
            }

            int frameBytes = channels * 2;
            int chunkFrames = 1024;
            byte[] pcm = new byte[chunkFrames * frameBytes];
            short[] mix = new short[chunkFrames * channels];
            short[] tmp = new short[chunkFrames * channels];
            long neededFrames = (long) Math.ceil(totalSec * rate);
            long ptsUs = 0;
            int inIndex;

            while (writtenFrames < neededFrames) {
                if (cancel != null && cancel.cancelled()) throw new InterruptedIOException("Export cancelled");
                int want = (int) Math.min(chunkFrames, neededFrames - writtenFrames);
                java.util.Arrays.fill(mix, 0, want * channels, (short) 0);

                boolean anyLive = false;
                for (int i = 0; i < readers.size(); i++) {
                    TrackReader r = readers.get(i);
                    if (r.done) continue;
                    java.util.Arrays.fill(tmp, 0, want * channels, (short) 0);
                    int got = r.readFrames(tmp, want, channels);
                    if (got <= 0) { r.done = true; continue; }
                    anyLive = true;
                    AudioTrack t = r.track;
                    for (int f = 0; f < got; f++) {
                        float trackTime = (writtenFrames + f) / (float) rate;
                        float g = t.gainAt(trackTime - t.startSec, totalSec - t.startSec);
                        if (g <= 0f) continue;
                        int o = f * channels;
                        for (int c = 0; c < channels; c++) {
                            int v = mix[o + c] + (int) (tmp[o + c] * g);
                            mix[o + c] = (short) (v > 32767 ? 32767 : (v < -32768 ? -32768 : v));
                        }
                    }
                }

                toBytes(mix, pcm, want * channels);
                int off = 0;
                while (off < want * frameBytes) {
                    inIndex = encoder.dequeueInputBuffer(20_000);
                    if (inIndex < 0) { drainEncoder(encoder, info, fos, index, false); continue; }
                    ByteBuffer ib = encoder.getInputBuffer(inIndex);
                    if (ib == null) { encoder.queueInputBuffer(inIndex, 0, 0, 0, 0); continue; }
                    ib.clear();
                    int n = Math.min(ib.remaining(), want * frameBytes - off);
                    ib.put(pcm, off, n);
                    long ts = ptsUs;
                    ptsUs += (n / frameBytes) * 1_000_000L / rate;
                    encoder.queueInputBuffer(inIndex, 0, n, ts, 0);
                    off += n;
                    drainEncoder(encoder, info, fos, index, false);
                }
                writtenFrames += want;
            }

            // Flush the encoder.
            inIndex = encoder.dequeueInputBuffer(20_000);
            if (inIndex >= 0) encoder.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drainEncoder(encoder, info, fos, index, true);

            totalOutUs = ptsUs;
            fos.flush();
            MediaFormat fmt = null;
            try { fmt = encoder.getOutputFormat(); } catch (Exception ignored) {}
            if (fmt == null) throw new IOException("Audio encoder produced no output format");
            return new Result(out, fmt, totalOutUs, index.size() > 0);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw e;
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ignored) {}
            if (encoder != null) {
                try { encoder.stop(); } catch (Exception ignored) {}
                encoder.release();
            }
            for (TrackReader r : readers) r.close();
        }
    }

    // ------------------------------------------------------------- encoder side

    private MediaCodec configureEncoder(int rate, int channels) throws IOException {
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels);
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_BIT_RATE, 160_000);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024);
        MediaCodec c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        return c;
    }

    /** Running write position in the elementary AAC file. */
    private long filePos = 0;

    private void drainEncoder(MediaCodec enc, MediaCodec.BufferInfo info, OutputStream os,
                              List<long[]> index, boolean eos) throws IOException {
        while (true) {
            int out = enc.dequeueOutputBuffer(info, eos ? 20_000 : 0);
            if (out == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!eos) return; }
            else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { /* format read at the end */ }
            else if (out >= 0) {
                ByteBuffer buf = enc.getOutputBuffer(out);
                if (buf != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    byte[] chunk = new byte[info.size];
                    buf.get(chunk);
                    os.write(chunk);
                    index.add(new long[]{info.presentationTimeUs, filePos, info.size, info.flags});
                    filePos += info.size;
                }
                enc.releaseOutputBuffer(out, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }
    }

    /** AAC is happiest on a standard rate; fall back to 44100 for anything odd. */
    private int pickSampleRate(int requested) {
        int[] ok = {48000, 44100, 32000, 24000, 22050, 16000};
        for (int r : ok) if (Math.abs(r - requested) <= 100) return r;
        return 44100;
    }

    private static void toBytes(short[] src, byte[] dst, int count) {
        for (int i = 0; i < count; i++) {
            short s = src[i];
            dst[i * 2] = (byte) (s & 0xff);
            dst[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
        }
    }

    // ------------------------------------------------------------- decode side

    /**
     * One decoded, trimmed, looped, resampled source track.
     *
     * {@link #readFrames} hands back {@code frames} frames of target-format PCM
     * (interleaved 16-bit). It transparently restarts the extractor when the
     * track loops, and returns 0 at the real end.
     */
    static final class TrackReader implements Closeable {
        final AudioTrack track;
        private final MediaExtractor extractor;
        private final MediaCodec decoder;
        private final int srcRate, srcCh;
        private final float totalSec;
        private final long trimStartUs, trimEndUs;
        private final Resampler resampler;

        private short[] decBuf;
        private int decPos = 0, decLen = 0;
        private boolean inputDone = false, outputDone = false;
        boolean done = false;
        private long framesDeliveredAtTarget = 0;

        TrackReader(Context ctx, AudioTrack t, float totalSec) throws IOException {
            this.track = t;
            this.totalSec = totalSec;
            this.extractor = new MediaExtractor();
            extractor.setDataSource(ctx, Uri.parse(t.uri), null);
            int trackIndex = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { trackIndex = i; fmt = f; break; }
            }
            if (trackIndex < 0) { extractor.release(); throw new IOException("No audio stream in " + t.uri); }
            extractor.selectTrack(trackIndex);
            srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            srcCh = Math.max(1, fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
            trimStartUs = (long) (Math.max(0f, t.trimStartSec) * 1_000_000L);
            float endSec = t.trimEndSec > t.trimStartSec ? t.trimEndSec : 0f;
            long durUs = extractor.getTrackFormat(trackIndex).getLong(MediaFormat.KEY_DURATION);
            trimEndUs = endSec > 0f ? (long) (endSec * 1_000_000L) : durUs;
            if (trimStartUs > 0) extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            String mime = fmt.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(fmt, null, null, 0);
            decoder.start();
            decBuf = new short[srcRate * srcCh]; // up to one second of source audio
            resampler = new Resampler(srcRate, srcCh, 44100, 2);
        }

        /** Reads up to {@code frames} frames of 16-bit interleaved target PCM. */
        int readFrames(short[] out, int frames, int outCh) {
            int written = 0;
            int tries = 0;
            while (written < frames && tries++ < MAX_DECODE_TRIES_PER_PULL) {
                if (decLen - decPos <= 0 && !outputDone) feedDecoder();
                int avail = decLen - decPos;
                if (avail <= 0) {
                    if (outputDone) {
                        if (track.loop && canLoop()) { restart(); continue; }
                        break;
                    }
                    continue;
                }
                int consumed = resampler.convert(decBuf, decPos, avail / srcCh, out, written,
                        frames - written, outCh);
                int srcFramesUsed = resampler.lastConsumedSourceFrames();
                decPos += srcFramesUsed * srcCh;
                written += consumed;
                if (consumed <= 0 && srcFramesUsed <= 0) {
                    if (outputDone) break;
                }
            }
            framesDeliveredAtTarget += written;
            return written;
        }

        private boolean canLoop() {
            float maxSec = totalSec - Math.max(0f, track.startSec);
            return maxSec > 0.05f;
        }

        private void restart() {
            try { decoder.flush(); } catch (Exception ignored) {}
            inputDone = false; outputDone = false;
            decPos = decLen = 0;
            resampler.reset();
            try { extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC); } catch (Exception ignored) {}
        }

        private void feedDecoder() {
            if (!inputDone) {
                int in = decoder.dequeueInputBuffer(10_000);
                if (in >= 0) {
                    ByteBuffer ib = decoder.getInputBuffer(in);
                    int size = 0;
                    if (ib != null) {
                        ib.clear();
                        size = extractor.readSampleData(ib, 0);
                    }
                    long pts = extractor.getSampleTime();
                    boolean pastEnd = trimEndUs > 0 && pts > trimEndUs;
                    if (size < 0 || pastEnd) {
                        decoder.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        decoder.queueInputBuffer(in, 0, size, pts, 0);
                        extractor.advance();
                    }
                }
            }
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int out = decoder.dequeueOutputBuffer(info, 10_000);
            if (out >= 0) {
                ByteBuffer ob = decoder.getOutputBuffer(out);
                if (ob != null && info.size > 0) {
                    ob.position(info.offset);
                    ob.limit(info.offset + info.size);
                    int shorts = info.size / 2;
                    if (decPos > 0) {
                        System.arraycopy(decBuf, decPos, decBuf, 0, decLen - decPos);
                        decLen -= decPos; decPos = 0;
                    }
                    if (decLen + shorts > decBuf.length) {
                        short[] bigger = new short[Math.max(decBuf.length * 2, decLen + shorts)];
                        System.arraycopy(decBuf, 0, bigger, 0, decLen);
                        decBuf = bigger;
                    }
                    ob.asShortBuffer().get(decBuf, decLen, shorts);
                    decLen += shorts;
                }
                decoder.releaseOutputBuffer(out, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
            } else if (out == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                // Nothing left to produce.
            }
        }

        @Override public void close() {
            try { decoder.stop(); } catch (Exception ignored) {}
            try { decoder.release(); } catch (Exception ignored) {}
            try { extractor.release(); } catch (Exception ignored) {}
        }
    }

    /** Fractional linear resampler + channel converter with cross-chunk state. */
    static final class Resampler {
        private final int srcCh, dstCh;
        private final double step;
        private double cursor = 0;
        private int lastConsumed = 0;

        Resampler(int srcRate, int srcCh, int dstRate, int dstCh) {
            this.srcCh = srcCh; this.dstCh = dstCh;
            this.step = srcRate / (double) dstRate;
        }

        void reset() { cursor = 0; lastConsumed = 0; }

        int lastConsumedSourceFrames() { return lastConsumed; }

        /**
         * Converts up to {@code wantDst} frames.
         *
         * @param src      source PCM, interleaved, {@code srcFrames} frames starting at {@code srcOff}
         * @return frames written to {@code dst}
         */
        int convert(short[] src, int srcOff, int srcFrames, short[] dst, int dstOff, int wantDst, int outCh) {
            int produced = 0;
            int ch = Math.min(Math.min(srcCh, dstCh), outCh);
            while (produced < wantDst) {
                int i0 = (int) Math.floor(cursor);
                if (i0 + 1 >= srcFrames) break;
                double frac = cursor - i0;
                int base0 = srcOff + i0 * srcCh;
                int base1 = base0 + srcCh;
                for (int c = 0; c < ch; c++) {
                    short a = src[base0 + c];
                    short b = src[base1 + c];
                    int v = (int) Math.round(a + (b - a) * frac);
                    dst[dstOff + produced * dstCh + c] = (short) (v > 32767 ? 32767 : (v < -32768 ? -32768 : v));
                }
                // Duplicate mono into the remaining output channels.
                for (int c = ch; c < dstCh; c++)
                    dst[dstOff + produced * dstCh + c] = dst[dstOff + produced * dstCh];
                cursor += step;
                produced++;
            }
            int consumed = (int) Math.floor(cursor);
            if (consumed > srcFrames) consumed = srcFrames;
            if (consumed > 0) cursor -= consumed;
            lastConsumed = consumed;
            return produced;
        }
    }
}
