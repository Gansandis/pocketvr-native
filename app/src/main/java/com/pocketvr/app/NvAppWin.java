package com.pocketvr.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A real phone app as a floating window. The app is started on a private virtual display that belongs to PocketVR
 * (Shizuku gives the shell rights needed for that), the display's picture is shown here, and pinches are sent back
 * to that display as taps / swipes. Experimental: not every app accepts running on a second display.
 */
public class NvAppWin implements NvContent {
    public interface KbHost { void showKeyboard(NvKeyboard.Sink s); }

    public KbHost kbHost;
    public Runnable onClose;
    public String err = "";

    static final int PW = 540, PH = 960, TB = 72, W = PW, H = TB + PH + 44;
    private static final int BACK = 0, HOMEK = 1, RESTART = 2, KEYB = 3, CLOSE = 4;

    private static class B { int type; String label; final RectF r = new RectF(); }

    private final Activity act;
    private final Handler ui;
    private final Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Canvas cv = new Canvas(bmp);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<B> btns = new ArrayList<>();
    private final RectF handle = new RectF(W / 2f - 100, H - 36, W / 2f + 100, H - 6);
    private final Object lock = new Object();
    private Bitmap page, frameBmp;
    private volatile boolean frameDirty = false;
    private boolean dirty = true, dragging = false, wasPinch = false;
    private B hover = null, lastH = null, pressed = null;
    private boolean hoverHandle = false, lastHandle = false;
    private long lastHT = 0, lastAct = 0, pressT = 0, lastStatusT = 0;

    // the app
    private volatile boolean opened = false;
    private String pkg = "", comp = "", label = "";
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler bg;
    private int displayId = -1;
    private volatile long gapMs = 50;
    private long lastFrame = 0;

    // touch
    private boolean touching = false, moved = false;
    private float tx0, ty0, tlx, tly;
    private long tDown = 0;
    private static final int HN = 16;
    private final float[] hx = new float[HN], hy = new float[HN];
    private final long[] ht = new long[HN];
    private int hi = 0;

    public NvAppWin(Activity a, Handler uiHandler) {
        act = a; ui = uiHandler;
        float x = 10;
        mk(BACK, "◀", x); x += 66; mk(HOMEK, "⌂", x); x += 66; mk(RESTART, "⟳", x);
        x = W - 70;
        mk(CLOSE, "✕", x); x -= 66; mk(KEYB, "⌨", x);
    }

    private void mk(int t, String l, float x) { B b = new B(); b.type = t; b.label = l; b.r.set(x, 6, x + 60, 66); btns.add(b); }

    // ---------- app control ----------
    public boolean isOpen() { return opened; }
    public String pkg() { return pkg; }

    /** start (or restart) an app on this window's virtual display. `component` is "package/activity". */
    public void open(final String packageName, final String component, final String name) {
        pkg = packageName; comp = component; label = name; err = "";
        ui.post(() -> {
            try {
                if (display == null) {
                    thread = new HandlerThread("pvr-app");
                    thread.start();
                    bg = new Handler(thread.getLooper());
                    reader = ImageReader.newInstance(PW, PH, PixelFormat.RGBA_8888, 2);
                    reader.setOnImageAvailableListener(this::onFrame, bg);
                    DisplayManager dm = (DisplayManager) act.getSystemService(Context.DISPLAY_SERVICE);
                    int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
                    display = dm.createVirtualDisplay("PocketVR-app", PW, PH, 240, reader.getSurface(), flags);
                    if (display == null) throw new IllegalStateException("createVirtualDisplay returned null");
                    displayId = display.getDisplay().getDisplayId();
                }
                opened = true;
                launch();
            } catch (Throwable t) {
                err = String.valueOf(t);
                opened = false;
                release();
            }
            dirty = true;
        });
    }

    private void launch() {
        NvShizuku.clearLog();
        NvShizuku.run("settings put global force_resizable_activities 1");              // lets apps that are not made for a small window run in one
        NvShizuku.run("am start --display " + displayId + " -n " + comp);
        dirty = true;
    }

    public void setActive(boolean on) { gapMs = on ? 50 : 1000; }

    /** quit the app and free the display */
    public void close() {
        final String pk = pkg;
        opened = false;
        if (!pk.isEmpty()) NvShizuku.run("am force-stop " + pk);
        ui.postDelayed(() -> { release(); }, 600);
        synchronized (lock) { page = null; }
        dirty = true;
    }

    private void release() {
        try { if (display != null) display.release(); } catch (Throwable ignored) { }
        display = null; displayId = -1;
        try { if (reader != null) reader.close(); } catch (Throwable ignored) { }
        reader = null;
        try { if (thread != null) thread.quitSafely(); } catch (Throwable ignored) { }
        thread = null; bg = null;
    }

    private void onFrame(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastFrame < gapMs) return;
            lastFrame = now;
            Image.Plane pl = img.getPlanes()[0];
            ByteBuffer buf = pl.getBuffer();
            int iw = img.getWidth(), ih = img.getHeight();
            int bw = pl.getRowStride() / pl.getPixelStride();
            if (frameBmp == null || frameBmp.getWidth() != bw || frameBmp.getHeight() != ih) frameBmp = Bitmap.createBitmap(bw, ih, Bitmap.Config.ARGB_8888);
            frameBmp.copyPixelsFromBuffer(buf);
            synchronized (lock) {
                if (page == null || page.getWidth() != PW || page.getHeight() != PH) page = Bitmap.createBitmap(PW, PH, Bitmap.Config.ARGB_8888);
                new Canvas(page).drawBitmap(frameBmp, 0, 0, null);
            }
            frameDirty = true;
        } catch (Throwable ignored) {
        } finally {
            if (img != null) img.close();
        }
    }

    // ---------- sending input to the app's display ----------
    private void tap(float x, float y) { NvShizuku.run("input -d " + displayId + " tap " + Math.round(x) + " " + Math.round(y)); }
    private void swipe(float x0, float y0, float x1, float y1, long ms) {
        NvShizuku.run("input -d " + displayId + " swipe " + Math.round(x0) + " " + Math.round(y0) + " " + Math.round(x1) + " " + Math.round(y1) + " " + Math.max(80, Math.min(600, ms)));
    }
    private void key(int code) { NvShizuku.run("input -d " + displayId + " keyevent " + code); }

    public final NvKeyboard.Sink sink = new NvKeyboard.Sink() {
        @Override public void onText(final String s) {
            if (displayId < 0 || s == null || s.isEmpty()) return;
            if (s.equals(" ")) { key(62); return; }
            if (s.length() == 1 && Character.isLetterOrDigit(s.charAt(0)) && s.charAt(0) < 128) { NvShizuku.run("input -d " + displayId + " text " + s); return; }
            ui.post(() -> {                                       // anything else (Thai, symbols): copy it and paste it
                try {
                    ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("pocketvr", s));
                } catch (Throwable ignored) { }
                key(279);
            });
        }
        @Override public void onBackspace() { if (displayId >= 0) key(67); }
        @Override public void onEnter(String all) { if (displayId >= 0) key(66); }
    };

    private void act(B b) {
        switch (b.type) {
            case BACK: if (displayId >= 0) key(4); break;
            case HOMEK: if (displayId >= 0) launch(); break;                       // bring the app's first screen back
            case RESTART: if (!pkg.isEmpty()) { NvShizuku.run("am force-stop " + pkg); ui.postDelayed(this::launch, 500); } break;
            case KEYB: if (kbHost != null) kbHost.showKeyboard(sink); break;
            case CLOSE: if (onClose != null) onClose.run(); break;
            default: break;
        }
    }

    // ---------- NvContent ----------
    @Override public Bitmap bitmap() { return bmp; }
    @Override public void markDirty() { dirty = true; }
    @Override public void setDragging(boolean d) { if (d != dragging) { dragging = d; dirty = true; } }
    @Override public boolean covers(float x, float y) { return true; }
    @Override public boolean inGrip(float x, float y) { return y > H * 0.95f && (x < W * 0.12f || x > W * 0.88f); }
    @Override public boolean handleRecent() { return hoverHandle || (lastHandle && System.currentTimeMillis() - lastHT < 300); }

    @Override public boolean takeDirty() {
        long now = System.currentTimeMillis();
        if (pressed != null && now - pressT > 150) { pressed = null; dirty = true; }
        if (page == null && opened && now - lastStatusT > 700) { lastStatusT = now; dirty = true; }       // keeps the "starting…" message (and any error the shell printed) fresh
        if (!dirty && !frameDirty) return false;
        boolean onlyPage = !dirty && page != null;
        dirty = false; frameDirty = false;
        if (onlyPage) {
            synchronized (lock) {
                if (page != null) { cv.save(); cv.clipRect(0, TB, PW, TB + PH); cv.drawBitmap(page, 0, TB, null); cv.restore(); }
            }
            drawTouch();
        } else draw();
        return true;
    }

    private void drawTouch() {
        if (!touching) return;
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setColor(0x9922B8CF);
        cv.drawCircle(tlx, tly + TB, 16, p);
        p.setStyle(Paint.Style.FILL);
    }

    @Override public void update(boolean valid, float x, float y, boolean pinch) {
        long now = System.currentTimeMillis();
        boolean inPage = valid && y >= TB && y < TB + PH && x >= 0 && x < PW;
        boolean edge = pinch && !wasPinch;
        if (valid && !pinch) { hx[hi] = x; hy[hi] = y; ht[hi] = now; hi = (hi + 1) % HN; }
        if (!touching && edge && inPage && opened) {
            float sx = x, sy = y;
            for (int i = 0; i < HN; i++) {                            // closing the fingers moves the pointer a little: tap where it was just before
                int k = (hi + i) % HN;
                if (ht[k] != 0 && now - ht[k] <= 300 && now - ht[k] >= 70 && Math.hypot(hx[k] - x, hy[k] - y) < 90) { sx = hx[k]; sy = hy[k]; break; }
            }
            touching = true; moved = false; tx0 = tlx = sx; ty0 = tly = sy - TB; tDown = now;
            dirty = true;
        } else if (touching && pinch) {
            // a tap is the default: only a clear move becomes a swipe
            float cx = x, cy = y - TB;
            if (valid && !moved && Math.hypot(cx - tx0, cy - ty0) > 60) moved = true;
            if (valid && moved) { tlx = Math.max(0, Math.min(PW, cx)); tly = Math.max(0, Math.min(PH, cy)); }
            dirty = true;
        } else if (touching && !pinch) {
            touching = false;
            long held = now - tDown;
            if (moved) swipe(tx0, ty0, tlx, tly, Math.min(400, held));
            else if (held > 700) swipe(tx0, ty0, tx0 + 1, ty0 + 1, 800);       // held without moving: long press
            else tap(tx0, ty0);
            dirty = true;
        }
        B h = null; boolean hh = false;
        if (valid && !touching) {
            for (B b : btns) if (b.r.contains(x, y)) { h = b; break; }
            if (h == null && handle.contains(x, y)) hh = true;
        }
        if (h != null) { lastH = h; lastHT = now; lastHandle = false; }
        else if (hh) { lastHandle = true; lastHT = now; }
        if (h != hover || hh != hoverHandle) { hover = h; hoverHandle = hh; dirty = true; }
        B tgt = h != null ? h : ((lastH != null && !lastHandle && now - lastHT < 250) ? lastH : null);
        if (edge && !touching && tgt != null && now - lastAct > 350) {
            lastAct = now; pressed = tgt; pressT = now; dirty = true;
            act(tgt);
        }
        wasPinch = pinch;
    }

    // ---------- drawing ----------
    private void round(RectF r, int color, float rad) {
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(color);
        cv.drawRoundRect(r, rad, rad, p);
    }

    private void stroke(RectF r, int color, float w, float rad) {
        p.setShader(null); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(w); p.setColor(color);
        cv.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void text(String s, float cx, float cy, float size, int color, Paint.Align al, float maxW) {
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(color); p.setTextSize(size); p.setTextAlign(al);
        while (maxW > 0 && p.measureText(s) > maxW && size > 10) { size -= 1; p.setTextSize(size); }
        cv.drawText(s, cx, cy + size * 0.35f, p);
    }

    private void draw() {
        cv.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
        RectF pr = new RectF(2, 2, W - 2, H - 2);
        p.setShader(new LinearGradient(0, 0, 0, H, (NvUi.glass << 24) | 0x2A2C36, (NvUi.glass << 24) | 0x121319, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        cv.drawRoundRect(pr, 30, 30, p);
        p.setShader(null);
        stroke(pr, 0x40FFFFFF, 2f, 30);
        NvPanel.grips(cv, p, pr, 30, 1.4f);

        for (B b : btns) {
            int c1 = 0xFF454A58, c2 = 0xFF2B2E38;
            if (b.type == CLOSE) { c1 = 0xFF6A3036; c2 = 0xFF4A2024; }
            if (pressed == b) { c1 = 0xFF6FA8FF; c2 = 0xFF4D8DFF; }
            else if (hover == b) { c1 = 0xFF5F6675; c2 = 0xFF454B59; }
            p.setShader(new LinearGradient(0, b.r.top, 0, b.r.bottom, c1, c2, Shader.TileMode.CLAMP));
            p.setStyle(Paint.Style.FILL);
            cv.drawRoundRect(b.r, 16, 16, p);
            p.setShader(null);
            if (hover == b) stroke(b.r, 0xFF22B8CF, 3f, 16);
            text(b.label, b.r.centerX(), b.r.centerY(), 28, 0xFFFFFFFF, Paint.Align.CENTER, b.r.width() - 8);
        }
        float tl = 10 + 66 * 3 + 6, tr = W - 70 - 66 - 6;
        text(label.isEmpty() ? "แอป" : label, (tl + tr) / 2f, TB / 2f, 24, 0xFFFFFFFF, Paint.Align.CENTER, tr - tl);

        RectF pg = new RectF(0, TB, PW, TB + PH);
        synchronized (lock) {
            if (page != null) cv.drawBitmap(page, 0, TB, null);
            else {
                round(pg, 0xFF101218, 0);
                String m;
                if (!err.isEmpty()) m = "เปิดไม่ได้: " + err;
                else if (!NvShizuku.ready()) m = "Shizuku ยังไม่พร้อม";
                else if (opened) { m = "กำลังเปิดแอป…"; String lg = NvShizuku.lastLog.trim(); if (!lg.isEmpty()) m += "\n" + lg; }
                else m = "ยังไม่ได้เปิดแอป";
                float yy = TB + PH / 2f - 20;
                for (String line : m.split("\n")) { text(line, PW / 2f, yy, 24, 0xCCFFFFFF, Paint.Align.CENTER, PW - 40); yy += 34; if (yy > TB + PH - 30) break; }
            }
        }
        drawTouch();

        float hp = 6;
        RectF hb = new RectF(handle.left + hp, handle.centerY() - 5, handle.right - hp, handle.centerY() + 5);
        round(hb, dragging ? 0xFFFFA24D : (hoverHandle ? 0xFF22B8CF : 0xE6FFFFFF), 5);
    }
}
