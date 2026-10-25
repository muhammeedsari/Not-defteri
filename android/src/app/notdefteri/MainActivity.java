package app.notdefteri;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** WebView shell: the interface lives in assets/www (web/ in the repository), phone features come through the AND bridge. */
public class MainActivity extends Activity implements Asr.Listener, Tasks.Listener {
    /** Widget, Quick Settings tile and launcher shortcut: start recording. Notifications: open a task. */
    static final String RECORD = "app.notdefteri.RECORD", OPEN = "app.notdefteri.OPEN";

    /* Pages are served from this https origin out of the APK's assets: a secure context with no network involved. */
    static final String HOST = "appassets.androidplatform.net";
    static final String START = "https://" + HOST + "/index.html";
    private static final int REQ_MIC = 11, REQ_NOTIF = 12, REQ_EXPORT = 21, REQ_IMPORT = 22;
    private static final int BG_LIGHT = 0xFFF3F1EC, BG_DARK = 0xFF121418;

    private WebView web;
    private boolean pageReady = false;
    private Intent pending;   // an intent that came before the page was ready

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(isDark() ? BG_DARK : BG_LIGHT);
        setContentView(web);
        applyBars();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        s.setSupportZoom(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return serve(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return !HOST.equals(req.getUrl().getHost());   // the app never leaves its own pages
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                if (pending != null) { Intent i = pending; pending = null; handle(i); }
            }
        });

        web.addJavascriptInterface(new Bridge(), "NotDefteriAndroid");
        web.loadUrl(START);
        Asr.setListener(this);
        Asr.kick(this);   // notes left waiting by an earlier run
        Tasks.listener = this;
        pending = getIntent();
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() { Tasks.restoreAll(app); }   // reminders are always rebuilt from the database
        }).start();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (pageReady) handle(i); else pending = i;
    }

    /** Recording from the lock screen shows only the recorder; nothing else is visible until the phone is unlocked. */
    private void handle(Intent i) {
        if (i == null) return;
        if (RECORD.equals(i.getAction())) {
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            boolean locked = km != null && km.isKeyguardLocked();
            if (locked) overLock(true);
            js("window.__ndRecord && window.__ndRecord(" + locked + ")");
        } else if (OPEN.equals(i.getAction())) {
            js("window.__ndOpen && window.__ndOpen(" + i.getLongExtra("task", 0) + ")");
        }
        i.setAction(Intent.ACTION_MAIN);   // a later restart must not record again
    }

    private void overLock(boolean on) {
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(on); setTurnScreenOn(on); }
        else if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    }

    @Override
    public void onTasksChanged() {
        js("window.__ndRefresh && window.__ndRefresh()");
    }

    /* ---------- speech-to-text events ---------- */

    @Override
    public void onNote(long id, String state, double p, String text) {
        js("window.__ndAsr && window.__ndAsr(" + id + "," + JSONObject.quote(state) + "," + p + "," + (text == null ? "null" : JSONObject.quote(text)) + ")");
    }

    @Override
    public void onModel(String state, double p, String error) {
        js("window.__ndModel && window.__ndModel(" + JSONObject.quote(state) + "," + p + "," + (error == null ? "null" : JSONObject.quote(error)) + ")");
    }

    private void js(final String code) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() { if (web != null && pageReady) web.evaluateJavascript(code, null); }
        });
    }

    /* ---------- asset server (+ recordings under /__audio/) ---------- */

    private WebResourceResponse serve(Uri u) {
        if (u == null || !HOST.equals(u.getHost())) return null;
        String path = u.getPath();
        if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
        if (path.contains("..")) return notFound();
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-cache");
        try {
            if (path.startsWith("/__audio/")) {
                File f = new File(Recorder.dir(this), path.substring("/__audio/".length()));
                if (!f.isFile()) return notFound();
                return new WebResourceResponse("audio/mp4", null, 200, "OK", headers, new FileInputStream(f));
            }
            InputStream in = getAssets().open("www" + path);
            String mime = mimeFor(path);
            String enc = mime.startsWith("text/") || mime.endsWith("json") ? "utf-8" : null;
            return new WebResourceResponse(mime, enc, 200, "OK", headers, in);
        } catch (IOException e) {
            return notFound();
        }
    }

    private WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<String, String>(), null);
    }

    private static String mimeFor(String p) {
        String l = p.toLowerCase();
        if (l.endsWith(".html")) return "text/html";
        if (l.endsWith(".js")) return "text/javascript";
        if (l.endsWith(".css")) return "text/css";
        if (l.endsWith(".json")) return "application/json";
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    /* ---------- permissions ---------- */

    private boolean asked;

    /** The system shows the notification question at most twice; after that only the settings page helps. */
    private boolean shouldShowOrFirst() {
        if (!asked) { asked = true; return true; }
        return shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS);
    }

    private boolean has(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMic() {
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, REQ_MIC);
        else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_MIC) {
            boolean ok = has(Manifest.permission.RECORD_AUDIO);
            boolean blocked = !ok && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO);
            js("window.__ndPerm && window.__ndPerm('mic'," + ok + "," + blocked + ")");
        } else if (code == REQ_NOTIF) {
            js("window.__ndPerm && window.__ndPerm('notif'," + notifOk() + ",false)");
        }
    }

    private boolean notifOk() {
        return Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS);
    }

    private void open(String action, boolean withPackage) {
        try {
            Intent i = new Intent(action);
            if (withPackage) i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try { startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) { }
        }
    }

    /* ---------- backup files (the user picks where) ---------- */

    @Override
    protected void onActivityResult(int code, int result, final Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_EXPORT && code != REQ_IMPORT) return;
        final String kind = code == REQ_EXPORT ? "export" : "import";
        if (result != RESULT_OK || data == null || data.getData() == null) { js("window.__ndBackup && window.__ndBackup('" + kind + "',false,'cancel')"); return; }
        final Uri u = data.getData();
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    if ("export".equals(kind)) Backup.export(app, u); else Backup.restore(app, u);
                } catch (Exception e) {
                    err = String.valueOf(e.getMessage());
                }
                js("window.__ndBackup && window.__ndBackup('" + kind + "'," + (err == null) + "," + (err == null ? "null" : JSONObject.quote(err)) + ")");
            }
        }).start();
    }

    /* ---------- theme (follows the phone) ---------- */

    private boolean isDark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyBars() {
        boolean dark = isDark();
        int color = dark ? BG_DARK : BG_LIGHT;
        Window w = getWindow();
        w.setStatusBarColor(color);
        w.setNavigationBarColor(color);
        View d = w.getDecorView();
        int flags = d.getSystemUiVisibility();
        if (dark) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        d.setSystemUiVisibility(flags);
    }

    @Override
    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        web.setBackgroundColor(isDark() ? BG_DARK : BG_LIGHT);
        applyBars();
        js("document.documentElement.dataset.theme='" + (isDark() ? "dark" : "light") + "'");
    }

    @Override
    protected void onResume() {
        super.onResume();
        js("window.__ndResume && window.__ndResume()");
    }

    /* ---------- back button: the page closes its open sheet first ---------- */

    @Override
    public void onBackPressed() {
        if (web == null || !pageReady) { moveTaskToBack(true); return; }
        web.evaluateJavascript("window.__ndBack ? window.__ndBack() : false", new ValueCallback<String>() {
            @Override public void onReceiveValue(String v) { if (!"true".equals(v)) moveTaskToBack(true); }
        });
    }

    @Override
    protected void onDestroy() {
        Asr.setListener(null);
        if (Tasks.listener == this) Tasks.listener = null;
        if (web != null) {
            web.removeJavascriptInterface("NotDefteriAndroid");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /* ---------- JavaScript bridge (AND in the page) ---------- */

    private void keepScreenOn(final boolean on) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
    }

    class Bridge {
        @JavascriptInterface public String caps() {
            JSONObject o = new JSONObject();
            try {
                o.put("native", true);
                o.put("sdk", Build.VERSION.SDK_INT);
                o.put("mic", has(Manifest.permission.RECORD_AUDIO));
                o.put("dark", MainActivity.this.isDark());
            } catch (Exception ignored) { }
            return o.toString();
        }

        @JavascriptInterface public void requestMic() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (has(Manifest.permission.RECORD_AUDIO)) { js("window.__ndPerm && window.__ndPerm('mic',true,false)"); return; }
                    MainActivity.this.requestMic();
                }
            });
        }

        @JavascriptInterface public void openSettings() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) { }
                }
            });
        }

        @JavascriptInterface public void vibrate() {
            runOnUiThread(new Runnable() {
                @Override public void run() { if (web != null) web.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); }
            });
        }

        /* -- recorder -- */
        @JavascriptInterface public String recStart() {
            if (!has(Manifest.permission.RECORD_AUDIO)) return "{\"ok\":false,\"error\":\"perm\"}";
            String res = Recorder.start(MainActivity.this);
            keepScreenOn(true);
            return res;
        }
        @JavascriptInterface public boolean recPause() { return Recorder.pause(); }
        @JavascriptInterface public boolean recResume() { return Recorder.resumeManual(); }
        @JavascriptInterface public String recInfo() { return Recorder.info(); }
        @JavascriptInterface public float recLevel() { return Recorder.level(); }
        /** Stops and files the recording as a note: {"ok":true, "item":{name, ms}, "id": note id} */
        @JavascriptInterface public String recStop() {
            keepScreenOn(false);
            String res = Recorder.stop(MainActivity.this);
            try {
                JSONObject o = new JSONObject(res);
                if (!o.optBoolean("ok")) return res;
                JSONObject item = o.getJSONObject("item");
                o.put("id", Db.get(MainActivity.this).addVoiceNote(item.getString("name"), item.getLong("ms")));
                Asr.kick(MainActivity.this);
                return o.toString();
            } catch (Exception e) {
                return res;
            }
        }
        @JavascriptInterface public void recCancel() {
            keepScreenOn(false);
            Recorder.cancel(MainActivity.this);
        }

        /* -- database -- */
        @JavascriptInterface public String notes() { return Db.get(MainActivity.this).notes(); }
        @JavascriptInterface public boolean noteDelete(long id) { return Db.get(MainActivity.this).deleteNote(id); }
        @JavascriptInterface public String categories() { return Db.get(MainActivity.this).categories(); }
        @JavascriptInterface public String board() { return Db.get(MainActivity.this).board(); }
        @JavascriptInterface public String search(long cat, String filter, String q) { return Db.get(MainActivity.this).search(cat, filter, q); }
        @JavascriptInterface public String task(long id) { JSONObject t = Db.get(MainActivity.this).task(id); return t == null ? "null" : t.toString(); }
        /** A typed note goes the same way as a spoken one: {"id", "card"} */
        @JavascriptInterface public String addText(String text) {
            long id = Db.get(MainActivity.this).addTextNote(text);
            boolean card = false;
            try { card = Brain.process(MainActivity.this, id); } catch (Throwable ignored) { }
            Tasks.changed(MainActivity.this);
            return "{\"id\":" + id + ",\"card\":" + card + "}";
        }
        @JavascriptInterface public void noteCategory(long id, long cat) {
            Db db = Db.get(MainActivity.this);
            try { db.learn("kategori", new JSONObject().put("note", id).put("to", cat).put("words", db.noteText(id))); } catch (Exception ignored) { }
            db.setNote(id, "category_id", cat);
            Tasks.changed(MainActivity.this);
        }

        /* -- confirmation card -- */
        @JavascriptInterface public long nextReview() { return Db.get(MainActivity.this).nextReview(); }
        @JavascriptInterface public String review(long id) { return Brain.review(MainActivity.this, id); }
        @JavascriptInterface public boolean reviewSave(String json) {
            try { Brain.save(MainActivity.this, json); return true; } catch (Exception e) { return false; }
        }
        @JavascriptInterface public String reparse(long id, String text) {
            try { return Brain.reparse(MainActivity.this, id, text); } catch (Exception e) { return "null"; }
        }

        /* -- tasks -- */
        @JavascriptInterface public void taskDone(long id) { Tasks.done(MainActivity.this, id, "ekran"); }
        /** mode: hour | evening | tomorrow | at */
        @JavascriptInterface public void taskSnooze(long id, String mode, long at) { Tasks.snooze(MainActivity.this, id, mode, at); }
        @JavascriptInterface public void taskDelete(long id) { Tasks.cancel(MainActivity.this, id); }
        @JavascriptInterface public void taskUndo(long id) { Tasks.undo(MainActivity.this, id); }
        @JavascriptInterface public boolean taskSave(String json) {
            try { Tasks.save(MainActivity.this, new JSONObject(json)); return true; } catch (Exception e) { return false; }
        }
        @JavascriptInterface public void taskSplit(long id, String lines) { Tasks.split(MainActivity.this, id, lines); }
        /** "Hâlâ geçerli mi?" answered yes: the question goes away for 30 days without changing anything else. */
        @JavascriptInterface public void taskKeep(long id) {
            Db.get(MainActivity.this).updateTask(id, new android.content.ContentValues());
            Tasks.changed(MainActivity.this);
        }

        /* -- settings -- */
        @JavascriptInterface public String settings() { return Db.get(MainActivity.this).settings(); }
        @JavascriptInterface public void setSetting(String key, String value) {
            Db.get(MainActivity.this).setSetting(key, value);
            if ("morning_summary".equals(key)) Alarms.morning(MainActivity.this);
        }
        @JavascriptInterface public long addCategory(String name) {
            try { return Db.get(MainActivity.this).addCategory(name); } catch (Exception e) { return -1; }
        }
        @JavascriptInterface public boolean deleteCategory(long id) { return Db.get(MainActivity.this).deleteCategory(id); }

        /* -- what reminders need: notifications, exact alarms, no battery limits -- */
        @JavascriptInterface public String perms() {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return "{\"notif\":" + notifOk() + ",\"exact\":" + Alarms.exact(MainActivity.this)
                + ",\"battery\":" + (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) + "}";
        }
        @JavascriptInterface public void requestNotif() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (Build.VERSION.SDK_INT >= 33 && !notifOk() && shouldShowOrFirst()) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                    else if (!notifOk()) open(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, false);
                    else js("window.__ndPerm && window.__ndPerm('notif',true,false)");
                }
            });
        }
        @JavascriptInterface public void openExact() {
            runOnUiThread(new Runnable() {
                @Override public void run() { if (Build.VERSION.SDK_INT >= 31) open(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, true); }
            });
        }
        @JavascriptInterface public void requestBattery() {
            runOnUiThread(new Runnable() {
                @Override public void run() { open(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, true); }
            });
        }

        /* -- backup -- */
        @JavascriptInterface public void backupExport() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/zip").putExtra(Intent.EXTRA_TITLE, Backup.fileName());
                    try { startActivityForResult(i, REQ_EXPORT); } catch (Exception e) { js("window.__ndBackup && window.__ndBackup('export',false,'picker')"); }
                }
            });
        }
        @JavascriptInterface public void backupImport() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
                    try { startActivityForResult(i, REQ_IMPORT); } catch (Exception e) { js("window.__ndBackup && window.__ndBackup('import',false,'picker')"); }
                }
            });
        }
        @JavascriptInterface public long backupLast() { return Backup.lastAuto(MainActivity.this); }
        @JavascriptInterface public boolean backupRestoreAuto() {
            try { Backup.restoreAuto(MainActivity.this); return true; } catch (Exception e) { return false; }
        }

        /** The lock-screen recording is filed: the app steps back behind the lock screen. */
        @JavascriptInterface public void lockDone() {
            runOnUiThread(new Runnable() {
                @Override public void run() { overLock(false); moveTaskToBack(true); }
            });
        }

        /* -- speech-to-text -- */
        @JavascriptInterface public String asrStatus() { return Asr.status(MainActivity.this); }
        @JavascriptInterface public void asrDownload() { Asr.download(MainActivity.this); }
        @JavascriptInterface public void asrCancel() { Asr.cancelDownload(); }
    }
}
