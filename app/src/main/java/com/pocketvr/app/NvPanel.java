package com.pocketvr.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.ArrayList;
import java.util.List;

/** A small floating window: dark glass, title, close button, grab bar, a drawn body and buttons. */
public class NvPanel implements NvContent {
    public interface Body { void draw(Canvas cv, Paint p, float w, float h); }

    public final List<NvUi.Btn> btns = new ArrayList<>();
    public Runnable onClose;
    public Body body;
    public String title = "";
    public long refreshMs = 0;                 // > 0: redraw this often (clock, numbers)

    private final int W, H;
    private final Bitmap bmp;
    private final Canvas cv;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final NvUi.Btn closeB, handleB;
    private boolean dirty = true, dragging = false, wasPinch = false;
    private long lastDraw = 0, lastAct = 0, lastHT = 0;
    private Object hover = null, lastH = null;

    public NvPanel(int w, int h, String title) {
        W = w; H = h; this.title = title;
        bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        cv = new Canvas(bmp);
        float u = H / 300f;
        closeB = new NvUi.Btn("✕", () -> "", null, null, 0, 0, () -> { if (onClose != null) onClose.run(); });
        closeB.r.set(W - 52 * u, 10 * u, W - 14 * u, 48 * u);
        handleB = new NvUi.Btn("", () -> "", null, null, 0, 0, null);
        handleB.r.set(W / 2f - 80 * u, H - 40 * u, W / 2f + 80 * u, H - 4 * u);
    }

    public void add(NvUi.Btn b, float x, float y, float w, float h) { b.r.set(x, y, x + w, y + h); btns.add(b); dirty = true; }
    public void invalidate() { dirty = true; }
    @Override public void markDirty() { dirty = true; }

    @Override public Bitmap bitmap() { return bmp; }
    @Override public void setDragging(boolean d) { if (d != dragging) { dragging = d; dirty = true; } }
    @Override public boolean covers(float x, float y) { return true; }
    @Override public boolean handleRecent() { return hover == handleB || (lastH == handleB && System.currentTimeMillis() - lastHT < 300); }

    @Override public boolean takeDirty() {
        long now = System.currentTimeMillis();
        if (refreshMs > 0 && now - lastDraw >= refreshMs) dirty = true;
        if (!dirty) return false;
        dirty = false;
        lastDraw = now;
        draw();
        return true;
    }

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
        while (maxW > 0 && p.measureText(s) > maxW && size > 8) { size -= 1; p.setTextSize(size); }
        cv.drawText(s, cx, cy + size * 0.35f, p);
    }

    private static int lighten(int c) {
        int r = Math.min(255, ((c >> 16) & 0xFF) + 40), g = Math.min(255, ((c >> 8) & 0xFF) + 40), b = Math.min(255, (c & 0xFF) + 40);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void draw() {
        cv.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
        float u = H / 300f;
        RectF pr = new RectF(2, 2, W - 2, H - 2);
        p.setShader(new LinearGradient(0, 0, 0, H, 0xF02A2C36, 0xF0121319, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        cv.drawRoundRect(pr, 26 * u, 26 * u, p);
        p.setShader(null);
        stroke(pr, 0x40FFFFFF, 1.5f * u, 26 * u);
        text(title, 24 * u, 29 * u, 20 * u, 0xFFFFFFFF, Paint.Align.LEFT, W - 100 * u);
        round(closeB.r, hover == closeB ? 0xFFE5484D : 0xFF2B2E38, closeB.r.width() / 2);
        text("✕", closeB.r.centerX(), closeB.r.centerY(), 17 * u, 0xFFFFFFFF, Paint.Align.CENTER, 0);
        if (body != null) { try { body.draw(cv, p, W, H); } catch (Throwable ignored) { } }
        for (NvUi.Btn b : btns) {
            if (b.r.isEmpty()) continue;
            boolean on = b.on != null && b.on.get();
            int c1 = b.c1 == 0 ? 0xFF5A5A63 : b.c1, c2 = b.c2 == 0 ? 0xFF38383E : b.c2;
            if (hover == b) { c1 = lighten(c1); c2 = lighten(c2); }
            RectF r = b.r;
            p.setShader(new LinearGradient(0, r.top, 0, r.bottom, c1, c2, Shader.TileMode.CLAMP));
            p.setStyle(Paint.Style.FILL);
            cv.drawRoundRect(r, 18 * u, 18 * u, p);
            p.setShader(null);
            if (on) stroke(r, 0xFF9ED8FF, 3 * u, 18 * u);
            if (hover == b) stroke(r, 0xFFFFFFFF, 3 * u, 18 * u);
            float h = r.height();
            if (b.icon != null || !b.emoji.isEmpty()) {
                if (b.icon != null) {
                    float s = h * 0.46f;
                    RectF d = new RectF(r.centerX() - s / 2, r.top + h * 0.10f, r.centerX() + s / 2, r.top + h * 0.10f + s);
                    cv.drawBitmap(b.icon, null, d, p);
                } else text(b.emoji, r.centerX(), r.top + h * 0.34f, h * 0.34f, 0xFFFFFFFF, Paint.Align.CENTER, 0);
                text(b.label.get(), r.centerX(), r.top + h * 0.72f, 14 * u, 0xFFFFFFFF, Paint.Align.CENTER, r.width() - 10 * u);
                if (b.sub != null) text(b.sub.get(), r.centerX(), r.top + h * 0.88f, 11 * u, 0xCCFFFFFF, Paint.Align.CENTER, r.width() - 10 * u);
            } else {
                text(b.label.get(), r.centerX(), r.centerY(), 22 * u, 0xFFFFFFFF, Paint.Align.CENTER, r.width() - 12 * u);
            }
        }
        float hp = 6 * u;
        RectF hb = new RectF(handleB.r.left + hp, handleB.r.centerY() - 5 * u, handleB.r.right - hp, handleB.r.centerY() + 5 * u);
        round(hb, dragging ? 0xFFFFA24D : (hover == handleB ? 0xFF22B8CF : 0xE6FFFFFF), hb.height() / 2);
    }

    @Override public void update(boolean valid, float x, float y, boolean pinch) {
        Object h = null;
        if (valid) {
            if (closeB.r.contains(x, y)) h = closeB;
            else {
                for (int i = btns.size() - 1; i >= 0; i--) {
                    NvUi.Btn b = btns.get(i);
                    if (!b.r.isEmpty() && b.r.contains(x, y)) { h = b; break; }
                }
                if (h == null && handleB.r.contains(x, y)) h = handleB;
            }
        }
        long now = System.currentTimeMillis();
        if (h != null) { lastH = h; lastHT = now; }
        if (h != hover) { hover = h; dirty = true; }
        boolean edge = pinch && !wasPinch;
        wasPinch = pinch;
        Object tgt = h != null ? h : ((lastH != null && now - lastHT < 250) ? lastH : null);
        if (edge && tgt != null && tgt != handleB && now - lastAct > 400) {
            lastAct = now;
            NvUi.Btn b = (NvUi.Btn) tgt;
            try { if (b.act != null) b.act.run(); } catch (Throwable ignored) { }
            dirty = true;
        }
    }
}
