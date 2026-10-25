package app.notdefteri;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/** Voice note recorder (taken from Ses Defteri): 16 kHz mono AAC, the rate Whisper reads, kept in the app's private
    files/audio folder so nothing leaves the phone. RecordService keeps it alive with the screen off. */
final class Recorder {
    private static MediaRecorder mr;
    private static File file;
    private static long startedAt, pausedAt, pausedTotal;
    private static String state = "idle";

    private Recorder() { }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "audio");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static synchronized String start(Context c) {
        JSONObject res = new JSONObject();
        try {
            if (!"idle".equals(state)) { res.put("ok", false); res.put("error", "busy"); return res.toString(); }
            String name = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".m4a";
            file = new File(dir(c), name);
            try {
                // VOICE_RECOGNITION: the source tuned for speech recognisers (no automatic gain)
                open(c, MediaRecorder.AudioSource.VOICE_RECOGNITION);
            } catch (Exception e) {
                release();
                open(c, MediaRecorder.AudioSource.MIC);   // some phones refuse it
            }
            startedAt = System.currentTimeMillis();
            pausedTotal = 0;
            state = "rec";
            startService(c, true);
            res.put("ok", true);
            res.put("name", name);
        } catch (Exception e) {
            cleanup(c);
            try { res.put("ok", false); res.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) { }
        }
        return res.toString();
    }

    private static void open(Context c, int source) throws Exception {
        mr = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(c) : new MediaRecorder();
        mr.setAudioSource(source);
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mr.setAudioSamplingRate(16000);
        mr.setAudioEncodingBitRate(32000);
        mr.setAudioChannels(1);
        mr.setOutputFile(file.getAbsolutePath());
        mr.prepare();
        mr.start();
    }

    private static void release() {
        if (mr != null) {
            try { mr.stop(); } catch (Exception ignored) { }
            try { mr.release(); } catch (Exception ignored) { }
        }
        mr = null;
    }

    static synchronized boolean pause() {
        if (!"rec".equals(state) || mr == null) return false;
        try { mr.pause(); state = "paused"; pausedAt = System.currentTimeMillis(); return true; } catch (Exception e) { return false; }
    }

    static synchronized boolean resume() {
        if (!"paused".equals(state) || mr == null) return false;
        try { mr.resume(); state = "rec"; pausedTotal += System.currentTimeMillis() - pausedAt; return true; } catch (Exception e) { return false; }
    }

    /** Peak since the previous call, 0..1. */
    static synchronized float level() {
        if (mr == null || !"rec".equals(state)) return 0f;
        try { return Math.min(1f, mr.getMaxAmplitude() / 32767f); } catch (Exception e) { return 0f; }
    }

    private static long elapsed() {
        long now = "paused".equals(state) ? pausedAt : System.currentTimeMillis();
        return Math.max(0, now - startedAt - pausedTotal);
    }

    /** {"ok":true, "item":{name, ms, size}} */
    static synchronized String stop(Context c) {
        JSONObject res = new JSONObject();
        try {
            if (mr == null) { res.put("ok", false); res.put("error", "idle"); return res.toString(); }
            long ms = elapsed();
            boolean ok = true;
            try {
                if ("paused".equals(state)) { try { mr.resume(); } catch (Exception ignored) { } }
                mr.stop();
            } catch (RuntimeException e) {
                ok = false;   // stopped before any audio arrived
            }
            try { mr.release(); } catch (Exception ignored) { }
            mr = null;
            state = "idle";
            startService(c, false);
            if (!ok) {
                file.delete();
                file = null;
                res.put("ok", false);
                res.put("error", "empty");
                return res.toString();
            }
            res.put("ok", true);
            res.put("item", item(file, ms));
            file = null;
        } catch (Exception e) {
            try { res.put("ok", false); res.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) { }
        }
        return res.toString();
    }

    static synchronized void cancel(Context c) {
        cleanup(c);
    }

    static synchronized boolean active() {
        return !"idle".equals(state);
    }

    private static void cleanup(Context c) {
        release();
        if (file != null) file.delete();
        file = null;
        state = "idle";
        startService(c, false);
    }

    private static JSONObject item(File f, long ms) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", f.getName());
        o.put("ms", ms);
        o.put("size", f.length());
        return o;
    }

    /** Saved recordings, newest first. Duration comes from the file header. */
    static String list(Context c) {
        JSONArray a = new JSONArray();
        File[] fs = dir(c).listFiles();
        if (fs == null) return a.toString();
        Arrays.sort(fs, new Comparator<File>() {
            @Override public int compare(File x, File y) { return y.getName().compareTo(x.getName()); }
        });
        for (File f : fs) {
            if (!f.getName().endsWith(".m4a") || f.equals(file)) continue;
            long ms = 0;
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            try {
                r.setDataSource(f.getAbsolutePath());
                String d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) ms = Long.parseLong(d);
            } catch (Exception ignored) {
            } finally {
                try { r.release(); } catch (Exception ignored) { }
            }
            try { a.put(item(f, ms)); } catch (Exception ignored) { }
        }
        return a.toString();
    }

    static boolean delete(Context c, String name) {
        if (name == null || name.contains("/") || name.contains("..")) return false;
        return new File(dir(c), name).delete();
    }

    /* ---------- pause during phone calls (also VoIP calls), resume afterwards ---------- */
    private static boolean auto;
    private static android.os.Handler calls;
    private static Runnable poll;

    private static void watchCalls(final Context c, boolean on) {
        if (calls == null) calls = new android.os.Handler(android.os.Looper.getMainLooper());
        if (poll != null) calls.removeCallbacks(poll);
        poll = null;
        auto = false;
        if (!on) return;
        final AudioManager am = (AudioManager) c.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        poll = new Runnable() {
            @Override public void run() {
                int m = am.getMode();
                boolean call = m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE;
                synchronized (Recorder.class) {
                    if (mr == null) return;
                    if (call && "rec".equals(state)) { if (pause()) auto = true; }
                    else if (!call && auto && "paused".equals(state)) { resume(); auto = false; }
                }
                if (poll == this) calls.postDelayed(this, 700);
            }
        };
        calls.postDelayed(poll, 700);
    }

    /** {"state": rec|paused|idle, "call": paused because of a call, "ms": recorded so far} */
    static synchronized String info() {
        return "{\"state\":\"" + state + "\",\"call\":" + auto + ",\"ms\":" + ("idle".equals(state) ? 0 : elapsed()) + "}";
    }

    static synchronized boolean resumeManual() { auto = false; return resume(); }

    private static void startService(Context c, boolean on) {
        watchCalls(c, on);
        Intent svc = new Intent(c, RecordService.class);
        try {
            if (on) {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(svc);
                else c.startService(svc);
            } else {
                c.stopService(svc);
            }
        } catch (Exception ignored) { }
    }
}
