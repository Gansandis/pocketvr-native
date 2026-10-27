package com.pocketvr.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/**
 * Menu drawn with the normal Android Canvas into a transparent picture; the renderer lays that picture over the camera view.
 * Pointing = fingertip, click = pinch (thumb + index). The picture is only rebuilt when something changes.
 */
public class NvUi {
    public interface Txt { String get(); }
    public interface Flag { boolean get(); }

    public static class Btn {
        public Txt label;
        public Flag on;          // null = plain button
        public Runnable act;
        public Bitmap icon;      // app tiles only
        final RectF r = new RectF();
        public Btn(Txt l, Flag o, Runnable a) { label = l; on = o; act = a; }
    }

    public final List<Btn> toggles = new ArrayList<>();
    public final List<Btn> apps = new ArrayList<>();
    public volatile boolean open = false;

    private int W = 0, H = 0;
    public Bitmap bmp;
    private Canvas cv;
    private boolean dirty = true;
    private Object hover = null;
    private final RectF menuBtn = new RectF();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean wasPinch = false;
    private long lastAct = 0;
    private int appPage = 0;
    private String toast = "";
    private long toastUntil = 0;

    public void resize(int w, int h) {
        if (w == W && h == H) return;
        W = w; H = h;
        bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        cv = new Canvas(bmp);
        layout();
        dirty = true;
    }

    public boolean takeDirty() {
        long now = System.currentTimeMillis();
        if (toastUntil != 0 && now > toastUntil) { toastUntil = 0; toast = ""; dirty = true; }
        if (!dirty) return false;
        dirty = false;
        draw();
        return true;
    }

    public void markDirty() { dirty = true; }

    private void layout() {
        float u = H / 540f;
        float r = 36 * u;
        menuBtn.set(W / 2f - r, H - 18 * u - 2 * r, W / 2f + r, H - 18 * u);
        float px = W * 0.12f, pw = W * 0.76f, py = H * 0.10f;
        float bw = (pw - 50 * u) / 4f, bh = 54 * u;
        for (int i = 0; i < toggles.size(); i++) {
            int c = i % 4, rw = i / 4;
            float x = px + 10 * u + c * (bw + 10 * u), y = py + 58 * u + rw * (bh + 10 * u);
            toggles.get(i).r.set(x, y, x + bw, y + bh);
        }
        float ty = py + 58 * u + 2 * (bh + 10 * u) + 24 * u;
        int per = 6;
        float tw = (pw - 20 * u) / per, th = 104 * u;
        int start = appPage * per;
        for (int i = 0; i < apps.size(); i++) apps.get(i).r.setEmpty();
        for (int i = 0; i < per && start + i < apps.size(); i++) {
            float x = px + 10 * u + i * tw;
            apps.get(start + i).r.set(x + 4 * u, ty, x + tw - 4 * u, ty + th);
        }
    }

    public void relayout() { layout(); dirty = true; }

    private float[] panel() {
        float px = W * 0.12f, pw = W * 0.76f, py = H * 0.10f;
        float ph = H * 0.80f;
        return new float[]{px, py, px + pw, py + ph};
    }

    private void round(RectF r, int color, float rad) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        cv.drawRoundRect(r, rad, rad, p);
    }

    private void text(String s, float cx, float cy, float size, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.CENTER);
        cv.drawText(s, cx, cy + size * 0.35f, p);
    }

    private void draw() {
        cv.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
        float u = H / 540f;
        round(menuBtn, hover == menuBtn ? 0xFF22B8CF : 0xAA101820, menuBtn.width() / 2);
        text(open ? "✕" : "☰", menuBtn.centerX(), menuBtn.centerY(), 34 * u, 0xFFFFFFFF);
        if (!toast.isEmpty()) {
            text(toast, W / 2f, H * 0.06f, 22 * u, 0xFFFFFFFF);
        }
        if (!open) return;
        float[] pn = panel();
        RectF pr = new RectF(pn[0], pn[1], pn[2], pn[3]);
        round(pr, 0xE0101820, 26 * u);
        text("เมนู", (pn[0] + pn[2]) / 2, pn[1] + 28 * u, 26 * u, 0xFF9EE7F2);
        for (Btn b : toggles) {
            boolean on = b.on != null && b.on.get();
            int col = hover == b ? 0xFF22B8CF : (on ? 0xFF0B7285 : 0xFF2B3640);
            round(b.r, col, 14 * u);
            text(b.label.get(), b.r.centerX(), b.r.centerY(), 19 * u, 0xFFFFFFFF);
        }
        for (Btn b : apps) {
            if (b.r.isEmpty()) continue;
            round(b.r, hover == b ? 0xFF22B8CF : 0xFF2B3640, 14 * u);
            if (b.icon != null) {
                float s = Math.min(b.r.width() * 0.6f, b.r.height() * 0.55f);
                RectF d = new RectF(b.r.centerX() - s / 2, b.r.top + 8 * u, b.r.centerX() + s / 2, b.r.top + 8 * u + s);
                cv.drawBitmap(b.icon, null, d, p);
            }
            String l = b.label.get();
            if (l.length() > 9) l = l.substring(0, 8) + "…";
            text(l, b.r.centerX(), b.r.bottom - 14 * u, 15 * u, 0xFFFFFFFF);
        }
        if (apps.size() > 6) {
            text((appPage + 1) + "/" + ((apps.size() + 5) / 6) , (pn[0] + pn[2]) / 2, pn[3] - 14 * u, 15 * u, 0xFFB0BEC5);
        }
    }

    public void toast(String s) { toast = s; toastUntil = System.currentTimeMillis() + 2000; dirty = true; }

    /** cursor in picture pixels; pinch = thumb and index together. Called from the drawing thread. */
    public void update(boolean valid, float x, float y, boolean pinch) {
        if (bmp == null) return;
        Object h = null;
        if (valid) {
            float cx = menuBtn.centerX(), cy = menuBtn.centerY(), rr = menuBtn.width() * 0.75f;
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) < rr * rr) h = menuBtn;
            else if (open) {
                for (Btn b : toggles) if (b.r.contains(x, y)) { h = b; break; }
                if (h == null) for (Btn b : apps) if (!b.r.isEmpty() && b.r.contains(x, y)) { h = b; break; }
            }
        }
        if (h != hover) { hover = h; dirty = true; }
        boolean edge = pinch && !wasPinch;
        wasPinch = pinch;
        long now = System.currentTimeMillis();
        if (edge && h != null && now - lastAct > 400) {
            lastAct = now;
            if (h == menuBtn) { open = !open; dirty = true; }
            else if (h instanceof Btn) {
                Btn b = (Btn) h;
                try { if (b.act != null) b.act.run(); } catch (Throwable ignored) { }
                dirty = true;
            }
        }
    }

    public void nextPage() {
        int pages = Math.max(1, (apps.size() + 5) / 6);
        appPage = (appPage + 1) % pages;
        layout();
        dirty = true;
    }
}
