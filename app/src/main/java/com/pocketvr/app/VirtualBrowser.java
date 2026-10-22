package com.pocketvr.app;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * A real browser (WebView) that lives on a private virtual display owned by this app. Its pictures are turned into
 * JPEG frames for the VR page, and touches / typing from the page are injected into it. Because the WebView belongs
 * to this app, Android allows all of it without any special permission.
 */
public class VirtualBrowser {
    public static volatile byte[] jpeg = null;

    private final Activity act;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler bg;
    private Presentation presentation;
    private WebView web;
    private Bitmap bmp;
    private long lastFrame = 0, downTime = 0;
    private int w = 1280, h = 720;

    public VirtualBrowser(Activity a) { act = a; }

    public boolean isOpen() { return web != null; }

    /** Must be called on the UI thread. Returns null on success, otherwise an error text. */
    public String open(String url, int width, int height) {
        try {
            if (web == null) {
                w = width; h = height;
                thread = new HandlerThread("pvr-browser");
                thread.start();
                bg = new Handler(thread.getLooper());
                reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
                reader.setOnImageAvailableListener(this::onFrame, bg);
                DisplayManager dm = (DisplayManager) act.getSystemService(Context.DISPLAY_SERVICE);
                int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
                display = dm.createVirtualDisplay("PocketVR-browser", w, h, 240, reader.getSurface(), flags);
                if (display == null) throw new IllegalStateException("createVirtualDisplay returned null");
                presentation = new Presentation(act, display.getDisplay()) {
                    @Override
                    protected void onCreate(Bundle b) {
                        super.onCreate(b);
                        web = new WebView(getContext());
                        WebSettings s = web.getSettings();
                        s.setJavaScriptEnabled(true);
                        s.setDomStorageEnabled(true);
                        s.setMediaPlaybackRequiresUserGesture(false);
                        s.setLoadWithOverviewMode(true);
                        s.setUseWideViewPort(true);
                        s.setSupportZoom(false);
                        CookieManager.getInstance().setAcceptCookie(true);
                        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
                        web.setWebViewClient(new WebViewClient());      // links stay inside this browser
                        setContentView(web, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    }
                };
                presentation.show();
            }
            if (url != null && !url.isEmpty()) web.loadUrl(url);
            return null;
        } catch (Throwable t) {
            close();
            return String.valueOf(t);
        }
    }

    private void onFrame(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastFrame < 50) return;           // about 20 pictures per second
            lastFrame = now;
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int iw = img.getWidth(), ih = img.getHeight();
            int bw = p.getRowStride() / p.getPixelStride();
            if (bmp == null || bmp.getWidth() != bw || bmp.getHeight() != ih) bmp = Bitmap.createBitmap(bw, ih, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
            Bitmap src = bw == iw ? bmp : Bitmap.createBitmap(bmp, 0, 0, iw, ih);
            ByteArrayOutputStream out = new ByteArrayOutputStream(90000);
            src.compress(Bitmap.CompressFormat.JPEG, 72, out);
            jpeg = out.toByteArray();
        } catch (Throwable ignored) {
        } finally {
            if (img != null) img.close();
        }
    }

    /** action 0 = down, 1 = up, 2 = move; fx / fy are 0..1 across the page. UI thread. */
    public void touch(int action, float fx, float fy) {
        if (web == null) return;
        int a = action == 0 ? MotionEvent.ACTION_DOWN : (action == 1 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE);
        long t = SystemClock.uptimeMillis();
        if (a == MotionEvent.ACTION_DOWN) downTime = t;
        MotionEvent ev = MotionEvent.obtain(downTime, t, a, Math.max(0, Math.min(1, fx)) * w, Math.max(0, Math.min(1, fy)) * h, 0);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(ev);
        ev.recycle();
    }

    /** Types text into whatever field the page has focused. */
    public void text(String t) {
        if (web == null || t == null) return;
        web.evaluateJavascript("(function(){document.execCommand('insertText',false," + JSONObject.quote(t) + ");})()", null);
    }

    /** key: "backspace" or "enter". */
    public void key(String k) {
        if (web == null) return;
        if ("backspace".equals(k)) {
            web.evaluateJavascript("(function(){document.execCommand('delete');})()", null);
        } else if ("enter".equals(k)) {
            web.evaluateJavascript("(function(){var e=document.activeElement;if(!e)return;"
                    + "var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};"
                    + "var ok=e.dispatchEvent(new KeyboardEvent('keydown',o));e.dispatchEvent(new KeyboardEvent('keypress',o));e.dispatchEvent(new KeyboardEvent('keyup',o));"
                    + "if(ok&&e.form){if(e.form.requestSubmit)e.form.requestSubmit();else e.form.submit();}"
                    + "else if(ok&&e.tagName==='TEXTAREA'||e.isContentEditable){document.execCommand('insertText',false,'\\n');}})()", null);
        }
    }

    /** nav: back, forward, reload. */
    public void nav(String a) {
        if (web == null) return;
        if ("back".equals(a)) { if (web.canGoBack()) web.goBack(); }
        else if ("forward".equals(a)) { if (web.canGoForward()) web.goForward(); }
        else if ("reload".equals(a)) web.reload();
    }

    public String url() { return web == null ? "" : String.valueOf(web.getUrl()); }

    public boolean canGoBack() { return web != null && web.canGoBack(); }

    public void pause(boolean p) { if (web != null) { if (p) web.onPause(); else web.onResume(); } }

    public void close() {
        try { if (presentation != null) presentation.dismiss(); } catch (Throwable ignored) { }
        try { if (web != null) web.destroy(); } catch (Throwable ignored) { }
        try { if (display != null) display.release(); } catch (Throwable ignored) { }
        try { if (reader != null) reader.close(); } catch (Throwable ignored) { }
        try { if (thread != null) thread.quitSafely(); } catch (Throwable ignored) { }
        presentation = null; web = null; display = null; reader = null; thread = null; bmp = null; jpeg = null;
    }
}
