package app.notdefteri;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Local backup (plan 9): a zip with data.json (every table) and audio/ (the recordings), written to a place the user
 * picks; plus a daily automatic copy of the database in the app's own folder, last 7 kept. Nothing leaves the phone.
 */
final class Backup {
    private static final int KEEP = 7;

    private Backup() { }

    static String fileName() {
        return "not-defteri-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".zip";
    }

    static void export(Context c, Uri to) throws Exception {
        OutputStream os = c.getContentResolver().openOutputStream(to, "w");
        if (os == null) throw new IllegalStateException("no file");
        ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(os));
        try {
            z.putNextEntry(new ZipEntry("data.json"));
            z.write(Db.get(c).dump().toString().getBytes("UTF-8"));
            z.closeEntry();
            File[] fs = Recorder.dir(c).listFiles();
            byte[] buf = new byte[65536];
            for (int i = 0; fs != null && i < fs.length; i++) {
                if (!fs[i].isFile()) continue;
                z.putNextEntry(new ZipEntry("audio/" + fs[i].getName()));
                InputStream in = new FileInputStream(fs[i]);
                try { int n; while ((n = in.read(buf)) > 0) z.write(buf, 0, n); } finally { in.close(); }
                z.closeEntry();
            }
        } finally {
            z.close();
        }
    }

    /** Everything in the zip replaces what is on the phone; the current state is kept as an automatic copy first. */
    static void restore(Context c, Uri from) throws Exception {
        InputStream is = c.getContentResolver().openInputStream(from);
        if (is == null) throw new IllegalStateException("no file");
        JSONObject data = null;
        File audio = Recorder.dir(c);
        ZipInputStream z = new ZipInputStream(is);
        try {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName();
                if (e.isDirectory() || n.contains("..")) continue;
                if ("data.json".equals(n)) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    int k; while ((k = z.read(buf)) > 0) b.write(buf, 0, k);
                    data = new JSONObject(b.toString("UTF-8"));
                } else if (n.startsWith("audio/") && n.indexOf('/', 6) < 0) {
                    OutputStream o = new FileOutputStream(new File(audio, n.substring(6)));
                    try { int k; while ((k = z.read(buf)) > 0) o.write(buf, 0, k); } finally { o.close(); }
                }
            }
        } finally {
            z.close();
        }
        if (data == null) throw new IllegalArgumentException("not a backup");
        auto(c);
        Db.get(c).restore(data);
        Tasks.restoreAll(c);
        Tasks.changed(c);
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "backup");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** Daily copy (with the morning summary and before a restore). Recordings stay where they are. */
    static void auto(Context c) {
        try {
            File f = new File(dir(c), "oto-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".json");
            OutputStream o = new FileOutputStream(f);
            try { o.write(Db.get(c).dump().toString().getBytes("UTF-8")); } finally { o.close(); }
            File[] fs = list(c);
            for (int i = KEEP; i < fs.length; i++) fs[i].delete();
        } catch (Exception ignored) { }
    }

    /** Automatic copies, newest first. */
    static File[] list(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs == null) return new File[0];
        Arrays.sort(fs, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) { return b.getName().compareTo(a.getName()); }
        });
        return fs;
    }

    static long lastAuto(Context c) {
        File[] fs = list(c);
        return fs.length == 0 ? 0 : fs[0].lastModified();
    }

    /** Back to the newest automatic copy (audio files are not part of it and were never removed). */
    static void restoreAuto(Context c) throws Exception {
        File[] fs = list(c);
        if (fs.length == 0) throw new IllegalStateException("none");
        InputStream in = new FileInputStream(fs[0]);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try { byte[] buf = new byte[65536]; int k; while ((k = in.read(buf)) > 0) b.write(buf, 0, k); } finally { in.close(); }
        Db.get(c).restore(new JSONObject(b.toString("UTF-8")));
        Tasks.restoreAll(c);
        Tasks.changed(c);
    }
}
