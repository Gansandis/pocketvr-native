package com.pocketvr.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PocketVR native shell: shows the PocketVR web app full screen in a WebView and gives it
 * three things a normal web page cannot have: the list of installed apps, launching them,
 * and a camera that works without any permission prompt inside the page.
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String ORIGIN = "https://" + HOST;
    private static final int REQ_FILE = 11;
    private static final int REQ_CAMERA = 12;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingCameraRequest;
    private VirtualBrowser vb;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        hideSystemBars();

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/browser/", path -> {     // latest picture of the browser shown inside VR
                    Map<String, String> hd = new HashMap<>();
                    hd.put("Cache-Control", "no-store");
                    if (path.startsWith("seq")) {                 // tiny answer: lets the page skip downloading a picture that has not changed
                        return new WebResourceResponse("text/plain", "utf-8", 200, "OK", hd, new ByteArrayInputStream(String.valueOf(VirtualBrowser.seq).getBytes()));
                    }
                    byte[] b = VirtualBrowser.jpeg;
                    if (b == null) return new WebResourceResponse("image/jpeg", null, 404, "No frame", hd, new ByteArrayInputStream(new byte[0]));
                    return new WebResourceResponse("image/jpeg", null, 200, "OK", hd, new ByteArrayInputStream(b));
                })
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse r = loader.shouldInterceptRequest(request.getUrl());
                if (r != null) {   // wasm and ES modules must carry the right type or the hand-tracking engine will not start
                    String path = request.getUrl().getPath();
                    if (path != null && path.endsWith(".wasm")) r.setMimeType("application/wasm");
                    else if (path != null && (path.endsWith(".mjs") || path.endsWith(".js"))) r.setMimeType("text/javascript");
                }
                return r;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith(ORIGIN)) return false;
                if (!request.isForMainFrame()) return false;       // pages inside the in-app browser frame load normally
                openExternal(url);
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    if (!HOST.equals(request.getOrigin().getHost())) { request.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.getResources());
                    } else {
                        pendingCameraRequest = request;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        // The bridge is only injected into pages from our own origin, never into websites shown in the browser frame.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Set<String> allowed = new HashSet<>();
            allowed.add(ORIGIN);
            WebViewCompat.addWebMessageListener(web, "AndroidBridge", allowed,
                    (WebView view, WebMessageCompat message, Uri sourceOrigin, boolean isMainFrame, JavaScriptReplyProxy reply) -> {
                        if (!isMainFrame || message.getData() == null) return;
                        handleMessage(message.getData(), reply);
                    });
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }

        web.loadUrl(ORIGIN + "/assets/index.html");
    }

    // ---- messages from the page: {"id":1,"op":"apps"|"icon"|"launch"|"url", ...} ----
    private void handleMessage(final String raw, final JavaScriptReplyProxy reply) {
        final JSONObject req;
        try {
            req = new JSONObject(raw);
        } catch (Exception e) {
            return;
        }
        final int id = req.optInt("id");
        final String op = req.optString("op");
        final String pkg = req.optString("pkg");

        if ("launch".equals(op)) {
            String err = launchPackage(pkg);
            respond(reply, id, err == null, err);
        } else if ("clip".equals(op)) {
            String text = "";
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                    if (cs != null) text = cs.toString();
                }
            } catch (Throwable ignored) {
            }
            respond(reply, id, true, text);
        } else if ("clip".equals(op)) {
            String txt = "";
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                    if (cs != null) txt = cs.toString();
                }
            } catch (Throwable ignored) {
            }
            respond(reply, id, true, txt);
        } else if (op.startsWith("nb_")) {
            handleBrowser(op, req, reply, id);
        } else if ("url".equals(op)) {
            String u = req.optString("url");
            boolean ok = u.startsWith("http://") || u.startsWith("https://");
            if (ok) openExternal(u);
            respond(reply, id, ok, null);
        } else if ("apps".equals(op)) {
            worker.execute(() -> respond(reply, id, true, listApps()));
        } else if ("icon".equals(op)) {
            worker.execute(() -> respond(reply, id, true, iconBase64(pkg)));
        } else {
            respond(reply, id, false, null);
        }
    }

    private void respond(final JavaScriptReplyProxy reply, int id, boolean ok, Object data) {
        final JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("ok", ok);
            if (data != null) o.put("data", data);
        } catch (Exception ignored) {
        }
        final String text = o.toString();
        runOnUiThread(() -> reply.postMessage(text));
    }

    private JSONArray listApps() {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN);
        q.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(q, 0);
        final List<String[]> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : infos) {
            String p = ri.activityInfo.packageName;
            if (p.equals(getPackageName()) || !seen.add(p)) continue;
            items.add(new String[]{String.valueOf(ri.loadLabel(pm)), p});
        }
        Collections.sort(items, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        JSONArray arr = new JSONArray();
        try {
            for (String[] it : items) {
                JSONObject o = new JSONObject();
                o.put("n", it[0]);
                o.put("p", it[1]);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        return arr;
    }

    private String iconBase64(String pkg) {
        try {
            Drawable d = getPackageManager().getApplicationIcon(pkg);
            int size = 96;
            Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.PNG, 100, out);
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    // returns null on success, otherwise a short error text
    private String launchPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "no package";
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return "no launch intent";
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            return null;
        } catch (Exception e) {
            return String.valueOf(e);
        }
    }

    // ---- the browser that is drawn inside VR ----
    private void handleBrowser(String op, JSONObject req, JavaScriptReplyProxy reply, int id) {
        if (vb == null) vb = new VirtualBrowser(this);
        switch (op) {
            case "nb_open": {
                String err = vb.open(req.optString("url"), req.optInt("w", 1280), req.optInt("h", 720));
                respond(reply, id, err == null, err);
                break;
            }
            case "nb_fps":
                vb.setGap(req.optInt("ms", 66));
                break;
            case "nb_desktop":
                vb.setDesktop(req.optBoolean("on"));
                break;
            case "nb_touch":
                vb.touch(req.optInt("a"), (float) req.optDouble("x"), (float) req.optDouble("y"));
                break;
            case "nb_text":
                vb.text(req.optString("t"));
                break;
            case "nb_key":
                vb.key(req.optString("k"));
                break;
            case "nb_nav":
                vb.nav(req.optString("a"));
                break;
            case "nb_url":
                respond(reply, id, true, vb.url());
                break;
            case "nb_tabs":
                respond(reply, id, true, vb.tabsInfo());
                break;
            case "nb_newtab":
                vb.newTab(req.optString("url"));
                break;
            case "nb_switch":
                vb.switchTab(req.optInt("i"));
                break;
            case "nb_closetab":
                vb.closeTab(req.optInt("i"));
                break;
            case "nb_close":
                vb.close();
                respond(reply, id, true, null);
                break;
            default:
                respond(reply, id, false, "unknown op");
        }
    }

    private void openExternal(String url) {
        try {
            if (url.startsWith("intent:")) {
                Intent i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                i.setComponent(null);
                i.setSelector(null);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(i);
                } catch (ActivityNotFoundException e) {
                    String fb = i.getStringExtra("browser_fallback_url");
                    if (fb != null && (fb.startsWith("https://") || fb.startsWith("http://"))) {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fb)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    }
                }
            } else {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && pendingCameraRequest != null) {
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (ok) pendingCameraRequest.grant(pendingCameraRequest.getResources());
            else pendingCameraRequest.deny();
            pendingCameraRequest = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.pvrBack && window.pvrBack()) ? 'true' : 'false'", value -> {
            if (!"\"true\"".equals(value) && !"true".equals(value)) finish();
        });
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (vb != null) vb.close();
        worker.shutdown();
        web.destroy();
        super.onDestroy();
    }
}
