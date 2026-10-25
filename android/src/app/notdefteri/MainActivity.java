package app.notdefteri;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
public class MainActivity extends Activity implements Asr.Listener {

    /* Pages are served from this https origin out of the APK's assets: a secure context with no network involved. */
    static final String HOST = "appassets.androidplatform.net";
    static final String START = "https://" + HOST + "/index.html";
    private static final int REQ_MIC = 11;
    private static final int BG_LIGHT = 0xFFF3F1EC, BG_DARK = 0xFF121418;

    private WebView web;
    private boolean pageReady = false;

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
            }
        });

        web.addJavascriptInterface(new Bridge(), "NotDefteriAndroid");
        web.loadUrl(START);
        Asr.setListener(this);
        Asr.kick(this);   // notes left waiting by an earlier run
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
        }
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

        /* -- speech-to-text -- */
        @JavascriptInterface public String asrStatus() { return Asr.status(MainActivity.this); }
        @JavascriptInterface public void asrDownload() { Asr.download(MainActivity.this); }
        @JavascriptInterface public void asrCancel() { Asr.cancelDownload(); }
    }
}
