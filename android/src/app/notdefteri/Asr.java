package app.notdefteri;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background speech-to-text queue: every note with audio and no transcript is decoded and written down, one at a time,
 * off the UI thread. The model stays loaded only while the queue has work. Notes recorded before the model is
 * downloaded wait and are handled right after the download.
 */
final class Asr {
    interface Listener {
        /** state: start | progress | done | error; p: 0..1 */
        void onNote(long id, String state, double p, String text);
        /** state: progress | done | error */
        void onModel(String state, double p, String error);
    }

    private static final ExecutorService work = Executors.newSingleThreadExecutor();
    private static final ExecutorService net = Executors.newSingleThreadExecutor();
    private static volatile Listener listener;
    private static volatile boolean busy, downloading;
    private static volatile double dlProgress;
    private static AtomicBoolean dlCancel;
    private static volatile Boolean libs;

    private Asr() { }

    static void setListener(Listener l) { listener = l; }

    static synchronized boolean available() {
        if (libs == null) {
            try { System.loadLibrary("sherpa-onnx-jni"); libs = true; } catch (Throwable e) { libs = false; }
        }
        return libs;
    }

    /** {"ready": model on the phone, "downloading", "p": download progress, "libs": native code present} */
    static String status(Context c) {
        return "{\"ready\":" + AsrModels.ready(c) + ",\"downloading\":" + downloading + ",\"p\":" + dlProgress + ",\"libs\":" + available() + "}";
    }

    /** Writes down every waiting note (call after a recording, on start and after the model download). */
    static synchronized void kick(Context c) {
        final Context app = c.getApplicationContext();
        if (busy || !AsrModels.ready(app) || !available()) return;
        busy = true;
        work.execute(new Runnable() {
            @Override public void run() {
                try { drain(app); } finally { busy = false; }
            }
        });
    }

    private static void drain(Context c) {
        Transcriber tr = null;
        try {
            long[] ids;
            while ((ids = Db.get(c).pendingTranscripts()).length > 0) {
                if (tr == null) {
                    int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
                    try {
                        tr = new Transcriber(AsrModels.dir(c).getAbsolutePath(), threads);
                    } catch (Throwable e) {
                        notifyModel("error", 0, "load: " + e.getMessage());
                        return;
                    }
                }
                for (long id : ids) one(c, tr, id);
            }
        } finally {
            if (tr != null) tr.release();
        }
    }

    private static void one(Context c, Transcriber tr, final long id) {
        String audio = Db.get(c).audioOf(id);
        File f = audio == null ? null : new File(Recorder.dir(c), audio);
        if (f == null || !f.isFile()) { Db.get(c).setTranscript(id, ""); notifyNote(id, "done", 1, ""); return; }
        notifyNote(id, "start", 0, null);
        try {
            decode(f, tr, id);
            String text = tr.finish();
            Db.get(c).setTranscript(id, text);
            notifyNote(id, "done", 1, text);
        } catch (Throwable e) {
            tr.finish();
            // left without a transcript would retry forever: mark it empty and say so
            Db.get(c).setTranscript(id, "");
            notifyNote(id, "error", 0, String.valueOf(e.getMessage()));
        }
    }

    /** m4a → 16 kHz mono float into the transcriber; progress by decoded time. */
    private static void decode(File f, Transcriber tr, long id) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        try {
            ex.setDataSource(f.getAbsolutePath());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat m = ex.getTrackFormat(i);
                String mime = m.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = m; break; }
            }
            if (track < 0) throw new IllegalStateException("no audio");
            ex.selectTrack(track);
            long dur = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            dec.configure(fmt, null, null, 0);
            dec.start();
            int ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT), rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            float[] buf = new float[0];
            double pos = 0;   // resampling position, in input samples
            float prev = 0;
            long lastNote = 0;
            while (!outDone) {
                if (!inDone) {
                    int ii = dec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer in = dec.getInputBuffer(ii);
                        int n = ex.readSampleData(in, 0);
                        if (n < 0) { dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                        else { dec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                    }
                }
                int oi = dec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat o = dec.getOutputFormat();
                    ch = o.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    rate = o.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                } else if (oi >= 0) {
                    if (info.size > 0) {
                        ByteBuffer ob = dec.getOutputBuffer(oi);
                        ob.position(info.offset).limit(info.offset + info.size);
                        ShortBuffer sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer();
                        int frames = sb.remaining() / ch;
                        if (buf.length < frames) buf = new float[frames];
                        for (int i = 0; i < frames; i++) {   // mono mix
                            int s = 0;
                            for (int k = 0; k < ch; k++) s += sb.get();
                            buf[i] = s / (32768f * ch);
                        }
                        if (rate == Transcriber.RATE) {
                            tr.accept(buf, frames);
                        } else {   // linear resampling (recordings are already 16 kHz; this is a fallback)
                            double step = rate / (double) Transcriber.RATE;
                            int n = 0;
                            float[] o = new float[(int) (frames / step) + 2];
                            for (; pos < frames && n < o.length; pos += step) {
                                int i = (int) pos;
                                float a = i == 0 ? prev : buf[i - 1], b = buf[i];
                                double fr = pos - i;
                                o[n++] = (float) (a + (b - a) * fr);
                            }
                            pos -= frames;
                            prev = buf[frames - 1];
                            tr.accept(o, n);
                        }
                        long now = System.currentTimeMillis();
                        if (dur > 0 && now - lastNote > 400) { lastNote = now; notifyNote(id, "progress", Math.min(0.99, info.presentationTimeUs / (double) dur), null); }
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
        } finally {
            if (dec != null) { try { dec.stop(); } catch (Exception ignored) { } dec.release(); }
            ex.release();
        }
    }

    /* ---------- model download ---------- */

    static void download(Context c) {
        final Context app = c.getApplicationContext();
        if (downloading || AsrModels.ready(app)) return;
        downloading = true;
        dlProgress = 0;
        dlCancel = new AtomicBoolean(false);
        final AtomicBoolean cancel = dlCancel;
        net.execute(new Runnable() {
            @Override public void run() {
                try {
                    AsrModels.download(app, new AsrModels.Progress() {
                        @Override public void on(double f) { dlProgress = f; notifyModel("progress", f, null); }
                    }, cancel);
                    downloading = false;
                    notifyModel("done", 1, null);
                    kick(app);
                } catch (Throwable e) {
                    downloading = false;
                    notifyModel("error", dlProgress, cancel.get() ? "cancel" : String.valueOf(e.getMessage()));
                }
            }
        });
    }

    static void cancelDownload() {
        if (dlCancel != null) dlCancel.set(true);
    }

    private static void notifyNote(long id, String state, double p, String text) {
        Listener l = listener;
        if (l != null) l.onNote(id, state, p, text);
    }

    private static void notifyModel(String state, double p, String error) {
        Listener l = listener;
        if (l != null) l.onModel(state, p, error);
    }
}
