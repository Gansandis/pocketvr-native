package com.pocketvr.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

/**
 * Video window ("cinema"). The picture of the video is drawn by the renderer straight from the player (no copying through Java);
 * this class owns the player, the control bar picture below the video, and the pointer input.
 */
public class NvVideo implements NvContent {
    public Runnable onClose;

    public static final int W = 1280, VH = 720, H = 870;       // video area is W x VH, the control panel is under it
    private static final int PLAY = 0, BACK10 = 1, FWD10 = 2, SEEK = 3, CLOSE = 4, MUTE = 5;

    private static class B { int type; String label; final RectF r = new RectF(); }

    private final Context ctx;
    private final Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Canvas cv = new Canvas(bmp);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<B> btns = new ArrayList<>();
    private final RectF handle = new RectF(W / 2f - 120, H - 34, W / 2f + 120, H - 4);
    private final RectF bar = new RectF(40, 738, W - 40, 762);

    // ---- used by the renderer (graphics thread) ----
    public SurfaceTexture st;
    public int oes = 0;
    public volatile boolean frameAvail = false;
    public final float[] stm = new float[16];
    public volatile int vw = 0, vh = 0;
    private Surface surface;

    private MediaPlayer mp;
    private Uri pending;
    private volatile boolean prepared = false, playing = false, muted = false, ended = false;
    private int durMs = 0;
    private float pos = 0;

    private boolean dirty = true, dragging = false, wasPinch = false;
    private B hover = null, lastH = null, pressed = null;
    private boolean hoverHandle = false, lastHandle = false;
    private long lastHT = 0, pressT = 0, lastAct = 0, lastPoll = 0;

    public NvVideo(Context c) { ctx = c.getApplicationContext(); layout(); }

    private B mk(int type, String label, float x, float y, float w, float h) {
        B b = new B(); b.type = type; b.label = label; b.r.set(x, y, x + w, y + h); btns.add(b); return b;
    }

    private void layout() {
        btns.clear();
        float y = 774, h = 8;
        mk(BACK10, "⏪ 10", 40, y, 130, 46);
        mk(PLAY, "⏯", 180, y, 130, 46);
        mk(FWD10, "10 ⏩", 320, y, 130, 46);
        mk(MUTE, "🔊", 460, y, 100, 46);
        mk(CLOSE, "✕", W - 40 - 110, y, 110, 46);
        dirty = true;
    }

    // ---------- player ----------
    public synchronized void open(Uri u) {
        pending = u; prepared = false; playing = false; ended = false; durMs = 0; pos = 0; vw = vh = 0;
        releasePlayer();
        attach();
        dirty = true;
    }

    /** graphics thread: the renderer made the texture */
    public synchronized void glReady(SurfaceTexture s, int tex) {
        st = s; oes = tex;
        s.setOnFrameAvailableListener(x -> frameAvail = true);
        if (surface != null) surface.release();
        surface = new Surface(s);
        releasePlayer();
        attach();
    }

    /** graphics thread: the graphics context was lost, everything made there is gone */
    public synchronized void glLost() {
        releasePlayer();
        st = null; oes = 0; surface = null;
    }

    private void attach() {
        if (surface == null || pending == null || mp != null) return;
        try {
            mp = new MediaPlayer();
            mp.setSurface(surface);
            mp.setDataSource(ctx, pending);
            mp.setOnPreparedListener(m -> { prepared = true; durMs = m.getDuration(); m.setVolume(muted ? 0f : 1f, muted ? 0f : 1f); m.start(); playing = true; dirty = true; });
            mp.setOnVideoSizeChangedListener((m, w, h) -> { vw = w; vh = h; dirty = true; });
            mp.setOnCompletionListener(m -> { playing = false; ended = true; dirty = true; });
            mp.setOnErrorListener((m, what, extra) -> { prepared = false; playing = false; dirty = true; return true; });
            mp.prepareAsync();
        } catch (Throwable t) { releasePlayer(); }
    }

    private void releasePlayer() {
        MediaPlayer m = mp; mp = null; prepared = false; playing = false;
        if (m != null) { try { m.setSurface(null); } catch (Throwable ignored) { } try { m.release(); } catch (Throwable ignored) { } }
    }

    public synchronized void release() {
        pending = null;
        releasePlayer();
        dirty = true;
    }

    public boolean isOpen() { return pending != null; }

    private synchronized void toggle() {
        MediaPlayer m = mp;
        if (m == null || !prepared) return;
        try {
            if (ended) { m.seekTo(0); m.start(); playing = true; ended = false; }
            else if (m.isPlaying()) { m.pause(); playing = false; }
            else { m.start(); playing = true; }
        } catch (Throwable ignored) { }
        dirty = true;
    }

    private synchronized void skip(int ms) {
        MediaPlayer m = mp;
        if (m == null || !prepared) return;
        try { int t = Math.max(0, Math.min(durMs, m.getCurrentPosition() + ms)); m.seekTo(t); ended = false; } catch (Throwable ignored) { }
        dirty = true;
    }

    private synchronized void seekTo(float f) {
        MediaPlayer m = mp;
        if (m == null || !prepared) return;
        try { m.seekTo((int) (durMs * Math.max(0f, Math.min(1f, f)))); ended = false; } catch (Throwable ignored) { }
        dirty = true;
    }

    private synchronized void mute() {
        muted = !muted;
        try { if (mp != null) mp.setVolume(muted ? 0f : 1f, muted ? 0f : 1f); } catch (Throwable ignored) { }
        dirty = true;
    }

    private synchronized void poll() {
        MediaPlayer m = mp;
        if (m != null && prepared && durMs > 0) {
            try { float np = m.getCurrentPosition() / (float) durMs; if (Math.abs(np - pos) > 0.002f) { pos = np; dirty = true; } } catch (Throwable ignored) { }
        }
    }

    // ---------- NvContent ----------
    @Override public Bitmap bitmap() { return bmp; }
    @Override public void markDirty() { dirty = true; }
    @Override public void setDragging(boolean d) { if (d != dragging) { dragging = d; dirty = true; } }
    @Override public boolean covers(float x, float y) { return true; }
    @Override public boolean inGrip(float x, float y) { return y > H * 0.955f && (x < W * 0.10f || x > W * 0.90f); }
    @Override public boolean handleRecent() { return hoverHandle || (lastHandle && System.currentTimeMillis() - lastHT < 300); }

    @Override public boolean takeDirty() {
        long now = System.currentTimeMillis();
        if (playing && now - lastPoll > 500) { lastPoll = now; poll(); }
        if (pressed != null && now - pressT > 150) { pressed = null; dirty = true; }
        if (!dirty) return false;
        dirty = false;
        draw();
        return true;
    }

    @Override public void update(boolean valid, float x, float y, boolean pinch) {
        B h = null; boolean hh = false;
        if (valid) {
            for (B b : btns) if (b.r.contains(x, y)) { h = b; break; }
            if (h == null && handle.contains(x, y)) hh = true;
        }
        long now = System.currentTimeMillis();
        if (h != null) { lastH = h; lastHT = now; lastHandle = false; }
        else if (hh) { lastHandle = true; lastHT = now; }
        if (h != hover || hh != hoverHandle) { hover = h; hoverHandle = hh; dirty = true; }
        boolean edge = pinch && !wasPinch;
        wasPinch = pinch;
        if (edge && valid && h == null && x >= bar.left - 10 && x <= bar.right + 10 && y >= bar.top - 22 && y <= bar.bottom + 22) {
            seekTo((x - bar.left) / bar.width());
            return;
        }
        B tgt = h != null ? h : ((lastH != null && !lastHandle && now - lastHT < 250) ? lastH : null);
        if (edge && tgt != null && now - lastAct > 300) {
            lastAct = now; pressed = tgt; pressT = now; dirty = true;
            switch (tgt.type) {
                case PLAY: toggle(); break;
                case BACK10: skip(-10000); break;
                case FWD10: skip(10000); break;
                case MUTE: mute(); break;
                case CLOSE: if (onClose != null) onClose.run(); break;
                default: break;
            }
        } else if (valid && pinch && h == null && y >= bar.top - 22 && y <= bar.bottom + 22 && x >= bar.left && x <= bar.right && now - lastAct > 120) {
            // dragging along the bar scrubs
            if (now - lastPoll > 150) { lastPoll = now; seekTo((x - bar.left) / bar.width()); }
        }
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

    private static String t(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    private void draw() {
        cv.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);          // the top part stays see-through: the video is drawn behind it
        RectF panel = new RectF(2, VH, W - 2, H - 2);
        p.setShader(new LinearGradient(0, VH, 0, H, (NvUi.glass << 24) | 0x2A2C36, (NvUi.glass << 24) | 0x121319, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        cv.drawRoundRect(panel, 26, 26, p);
        p.setShader(null);
        stroke(panel, 0x40FFFFFF, 2f, 26);
        if (!prepared) {
            RectF mid = new RectF(W / 2f - 220, VH / 2f - 40, W / 2f + 220, VH / 2f + 40);
            round(mid, 0xAA000000, 20);
            text(pending == null ? "ยังไม่ได้เลือกวิดีโอ" : "กำลังเปิดวิดีโอ...", mid.centerX(), mid.centerY(), 30, 0xFFFFFFFF, Paint.Align.CENTER, mid.width() - 20);
        }
        // progress
        round(bar, 0x55FFFFFF, 12);
        if (durMs > 0) {
            RectF fill = new RectF(bar.left, bar.top, bar.left + bar.width() * Math.max(0f, Math.min(1f, pos)), bar.bottom);
            round(fill, 0xFF4D8DFF, 12);
            p.setColor(0xFFFFFFFF); cv.drawCircle(fill.right, bar.centerY(), 16, p);
        }
        for (B b : btns) {
            int c1 = 0xFF454A58, c2 = 0xFF2B2E38;
            if (b.type == CLOSE) { c1 = 0xFF6A3036; c2 = 0xFF4A2024; }
            if (b.type == MUTE && muted) { c1 = 0xFF4D8DFF; c2 = 0xFF2E6BFF; }
            if (pressed == b) { c1 = 0xFF6FA8FF; c2 = 0xFF4D8DFF; }
            else if (hover == b) { c1 = 0xFF5F6675; c2 = 0xFF454B59; }
            p.setShader(new LinearGradient(0, b.r.top, 0, b.r.bottom, c1, c2, Shader.TileMode.CLAMP));
            p.setStyle(Paint.Style.FILL);
            cv.drawRoundRect(b.r, 16, 16, p);
            p.setShader(null);
            if (hover == b) stroke(b.r, 0xFF22B8CF, 3f, 16);
            String lab = b.label;
            if (b.type == PLAY) lab = playing ? "⏸" : "▶";
            if (b.type == MUTE) lab = muted ? "🔇" : "🔊";
            text(lab, b.r.centerX(), b.r.centerY(), 26, 0xFFFFFFFF, Paint.Align.CENTER, b.r.width() - 8);
        }
        if (durMs > 0) text(t((long) (pos * durMs)) + " / " + t(durMs), 600, 797, 24, 0xCCFFFFFF, Paint.Align.LEFT, 330);
        float hp = 6;
        RectF hb = new RectF(handle.left + hp, handle.centerY() - 5, handle.right - hp, handle.centerY() + 5);
        round(hb, dragging ? 0xFFFFA24D : (hoverHandle ? 0xFF22B8CF : 0xE6FFFFFF), 5);
    }
}
