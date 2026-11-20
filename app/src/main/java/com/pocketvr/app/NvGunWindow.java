package com.pocketvr.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * The window of the gun game: the game's numbers (hp, ammo, score, wave) on top and a row of glass buttons under them.
 * It is a normal window (NvContent) shown by the renderer; the 3D world itself is NvGunWorld, drawn by the renderer's draw3D().
 * Buttons: start / stop, take the gun out / put it away (for when the hands are not seen), reload, scene on / off, close.
 */
public class NvGunWindow implements NvContent {
    public final NvGunWorld world;
    public Runnable onClose;

    private static final int W = 640, H = 300;
    private final Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Canvas cv = new Canvas(bmp);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF startR = new RectF(16f, 180f, 216f, 236f);
    private final RectF drawR = new RectF(228f, 180f, 428f, 236f);
    private final RectF reloadR = new RectF(440f, 180f, 624f, 236f);
    private final RectF envR = new RectF(16f, 244f, 196f, 290f);
    private final RectF handleR = new RectF(208f, 244f, 432f, 290f);
    private final RectF closeR = new RectF(444f, 244f, 624f, 290f);
    private final Object START = new Object(), DRAW = new Object(), RELOAD = new Object(), ENV = new Object(), HANDLE = new Object(), CLOSE = new Object();
    private boolean dirty = true, dragging = false, wasPinch = false;
    private long lastAct = 0, lastHT = 0;
    private Object hover = null, lastH = null;
    private int lState = -1;
    private boolean lEnv = true;

    public NvGunWindow(NvGunWorld w) { world = w; }

    @Override public Bitmap bitmap() { return bmp; }
    @Override public void markDirty() { dirty = true; world.hud.markDirty(); }
    @Override public void setDragging(boolean d) { if (d != dragging) { dragging = d; dirty = true; } }
    @Override public boolean covers(float x, float y) { return true; }
    @Override public boolean inGrip(float x, float y) { return y > H * 0.78f && (x < W * 0.07f || x > W * 0.93f); }
    @Override public boolean handleRecent() { return hover == HANDLE || (lastH == HANDLE && System.currentTimeMillis() - lastHT < 300); }

    @Override public boolean takeDirty() {
        boolean hudNew = world.hud.takeDirty();
        int st = world.core.state;
        if (st != lState || world.envOn != lEnv) dirty = true;
        if (!dirty && !hudNew) return false;
        lState = st; lEnv = world.envOn; dirty = false;
        draw();
        return true;
    }

    private void text(String s, float cx, float cy, float size, int color) {
        s = Tr.t(s);
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(color); p.setTextSize(size); p.setTextAlign(Paint.Align.CENTER);
        cv.drawText(s, cx, cy + size * 0.35f, p);
    }

    private void button(RectF r, Object id, String label, int base, int hot) {
        NvGlass.fill(cv, p, r, hover == id ? hot : base, r.height() / 2);
        NvGlass.font(p, true);
        text(label, r.centerX(), r.centerY(), 22f, 0xFFFFFFFF);
    }

    private void draw() {
        cv.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
        cv.drawBitmap(world.hud.bitmap(), 0f, 0f, null);                 // the numbers, drawn by the game
        boolean playing = world.core.state == NvGunCore.PLAYING;
        button(startR, START, playing ? "หยุดเกม" : (world.core.state == NvGunCore.OVER ? "เล่นอีกครั้ง" : "เริ่มเล่น"), playing ? 0xFF8A3A44 : 0xFF3558B8, playing ? 0xFFB5505C : 0xFF4C7CE3);
        button(drawR, DRAW, "🔫 หยิบ/เก็บปืน", 0x33FFFFFF, 0x66FFFFFF);
        button(reloadR, RELOAD, "รีโหลด", 0x33FFFFFF, 0x66FFFFFF);
        button(envR, ENV, world.envOn ? "ฉาก: เปิด" : "ฉาก: ปิด", 0x33FFFFFF, 0x66FFFFFF);
        button(closeR, CLOSE, "✕ ปิด", 0x33FFFFFF, 0xFFE5484D);
        RectF hb = new RectF(handleR.left + 30f, handleR.centerY() - 7f, handleR.right - 30f, handleR.centerY() + 7f);
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(dragging ? 0xFFFFA24D : (hover == HANDLE ? 0xFFFFFFFF : 0xA6FFFFFF));
        cv.drawRoundRect(hb, 7f, 7f, p);
    }

    @Override public void update(boolean valid, float x, float y, boolean pinch) {
        long now = System.currentTimeMillis();
        Object h = null;
        if (valid) {
            if (closeR.contains(x, y)) h = CLOSE;
            else if (startR.contains(x, y)) h = START;
            else if (drawR.contains(x, y)) h = DRAW;
            else if (reloadR.contains(x, y)) h = RELOAD;
            else if (envR.contains(x, y)) h = ENV;
            else if (handleR.contains(x, y)) h = HANDLE;
        }
        if (h != null) { lastH = h; lastHT = now; }
        if (h != hover) { hover = h; dirty = true; }
        boolean edge = pinch && !wasPinch;
        wasPinch = pinch;
        Object tgt = h != null ? h : ((lastH != null && now - lastHT < 250) ? lastH : null);
        if (edge && tgt != null && now - lastAct > 400) {
            lastAct = now; dirty = true;
            if (tgt == CLOSE) { stop(); if (onClose != null) onClose.run(); }
            else if (tgt == START) { if (world.core.state == NvGunCore.PLAYING) stop(); else world.requestStart(); }
            else if (tgt == DRAW) world.requestDraw();
            else if (tgt == RELOAD) world.requestReload();
            else if (tgt == ENV) world.envOn = !world.envOn;
        }
    }

    /** end the round and put the gun away */
    public void stop() {
        world.core.stop();
        world.hands.reset();
        dirty = true;
    }
}
