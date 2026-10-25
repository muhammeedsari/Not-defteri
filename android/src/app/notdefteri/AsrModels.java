package app.notdefteri;

import android.content.Context;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whisper base int8 (≈95 MB) is downloaded once from Hugging Face (taken from Ses Defteri; the only time the app uses
 * the internet); afterwards everything runs offline. The APK carries only the small voice detector.
 * The "small" model is a v2 option and is not offered here.
 */
final class AsrModels {
    private AsrModels() { }

    interface Progress { void on(double f); }

    static final String[] FILES = { "base-encoder.int8.onnx", "base-decoder.int8.onnx", "base-tokens.txt" };
    private static final String[] HOSTS = {
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/",
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-whisper-base/resolve/main/" };

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "asr-base");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static boolean ready(Context c) {
        File d = new File(c.getFilesDir(), "asr-base");
        for (String f : FILES) if (!new File(d, f).isFile()) return false;
        return new File(d, "silero_vad.onnx").isFile();
    }

    /** Resumes an interrupted download where it stopped. */
    static void download(Context c, Progress p, AtomicBoolean cancel) throws Exception {
        File d = dir(c);
        long[] sizes = new long[FILES.length];
        String host = null;
        for (String h : HOSTS) {
            try { for (int i = 0; i < FILES.length; i++) sizes[i] = size(h + FILES[i]); host = h; break; } catch (Exception ignored) { }
        }
        if (host == null) throw new IllegalStateException("unreachable");
        long total = 0, done = 0;
        for (long s : sizes) total += s;
        for (int i = 0; i < FILES.length; i++) {
            File f = new File(d, FILES[i]);
            if (f.isFile() && f.length() == sizes[i]) { done += sizes[i]; continue; }
            File part = new File(d, FILES[i] + ".part");
            long have = part.isFile() ? part.length() : 0;
            HttpURLConnection con = (HttpURLConnection) new URL(host + FILES[i]).openConnection();
            con.setConnectTimeout(20000);
            con.setReadTimeout(30000);
            if (have > 0) con.setRequestProperty("Range", "bytes=" + have + "-");
            int code = con.getResponseCode();
            if (code != 200 && code != 206) throw new IllegalStateException("http-" + code);
            if (code == 200) have = 0;   // the server ignored the range: start over
            InputStream is = con.getInputStream();
            RandomAccessFile out = new RandomAccessFile(part, "rw");
            try {
                out.setLength(have);
                out.seek(have);
                byte[] b = new byte[1 << 16];
                int n;
                long last = 0;
                while ((n = is.read(b)) > 0) {
                    if (cancel.get()) throw new InterruptedException("cancel");
                    out.write(b, 0, n);
                    have += n;
                    if (have - last > (1 << 20)) { last = have; p.on((double) (done + have) / total); }
                }
            } finally {
                out.close();
                is.close();
                con.disconnect();
            }
            if (part.length() != sizes[i]) throw new IllegalStateException("short");
            if (!part.renameTo(f)) throw new IllegalStateException("rename");
            done += sizes[i];
        }
        // the voice detector goes next to the model so both load from files
        File vad = new File(d, "silero_vad.onnx");
        if (!vad.isFile()) {
            InputStream is = c.getAssets().open("asr/silero_vad.onnx");
            OutputStream os = new java.io.FileOutputStream(vad);
            try { byte[] b = new byte[1 << 16]; int n; while ((n = is.read(b)) > 0) os.write(b, 0, n); } finally { is.close(); os.close(); }
        }
        p.on(1);
    }

    private static long size(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("HEAD");
        con.setConnectTimeout(15000);
        con.setReadTimeout(15000);
        con.setInstanceFollowRedirects(true);
        try {
            int code = con.getResponseCode();
            if (code != 200) throw new IllegalStateException("head-" + code);
            long n = con.getContentLengthLong();
            if (n <= 0) throw new IllegalStateException("size");
            return n;
        } finally {
            con.disconnect();
        }
    }
}
