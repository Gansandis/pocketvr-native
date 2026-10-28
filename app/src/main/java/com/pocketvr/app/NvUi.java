package com.pocketvr.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.ArrayList;
import java.util.List;

/**
 * Quest-Home-style menu (dark glass panel, side tabs, coloured cards, bottom dock) drawn with the Android Canvas into a transparent picture.
 * The renderer lays that picture over the camera view. Pointing = fingertip, click = pinch. The picture is only rebuilt when something changes.
 */
public class NvUi implements NvContent {
    public interface Txt { String get(); }
    public interface Flag { boolean get(); }

    /** ความทึบของกระจกเมนู/หน้าต่าง 0..255 */
    public static volatile int glass = 0xF0;

    public static class Btn {
        public String emoji = "";
        public Txt label, sub;
        public Flag on;           // null = plain card
        public Runnable act;
        public Bitmap icon;       // app cards
        public int c1 = 0xFF5A5A63, c2 = 0xFF3A3A42;
        final RectF r = new RectF();
        public Btn(String e, Txt l, Txt s, Flag o, int a, int b, Runnable r) { emoji = e; label = l; sub = s; on = o; c1 = a; c2 = b; act = r; }
    }

    public final List<Btn> home = new ArrayList<>();   // tab 0 cards
    public final List<Btn> apps = new ArrayList<>();   // tab 1 cards (installed apps)
    public final List<Btn> dock = new ArrayList<>();   // always-visible round buttons at the bottom
    public volatile boolean open = false;
    public volatile boolean dragging = false;
    private final Btn handle = new Btn("", () -> "", null, null, 0, 0, null);
    private Object lastH = null;
    private long lastHT = 0;
    public boolean handleHover() { return hover == handle; }
    @Override public Bitmap bitmap() { return bmp; }
    @Override public void setDragging(boolean d) { if (d != dragging) { dragging = d; dirty = true; } }
    @Override public boolean inGrip(float x, float y) {
        if (!open || pH <= 0) return false;
        float gx = 0.08f * pW, gy = 0.12f * pH;
        return y >= pY + pH - gy && y <= pY + pH + 0.05f * H && ((x >= pX - 0.04f * W && x <= pX + gx) || (x <= pX + pW + 0.04f * W && x >= pX + pW - gx));
    }
    @Override public boolean covers(float x, float y) {
        if (handle.r.contains(x, y) || dockR.contains(x, y)) return true;
        return open && pH > 0 && x >= pX && x <= pX + pW && y >= pY && y <= pY + pH;
    }
    public boolean handleRecent() { return hover == handle || (lastH == handle && System.currentTimeMillis() - lastHT < 300); }

    private final List<Btn> tabs = new ArrayList<>();
    private final List<Btn> visible = new ArrayList<>();
    private Btn prevB, nextB;
    private int tab = 0, page = 0, pages = 1;

    private int W = 0, H = 0;
    public Bitmap bmp;
    private Canvas cv;
    private boolean dirty = true;
    private Object hover = null;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean wasPinch = false;
    private long lastAct = 0;
    private String toast = "";
    private long toastUntil = 0;

    private float safe = 1f;
    private float pX, pY, pW, pH, sbW;
    private final RectF dockR = new RectF();

    public NvUi() {
        tabs.add(new Btn("🏠", () -> "หน้าแรก", null, null, 0, 0, () -> { tab = 0; page = 0; layout(); }));
        tabs.add(new Btn("📱", () -> "แอป", null, null, 0, 0, () -> { tab = 1; page = 0; layout(); }));
        prevB = new Btn("", () -> "‹", null, null, 0, 0, () -> { page = (page + pages - 1) % pages; layout(); });
        nextB = new Btn("", () -> "›", null, null, 0, 0, () -> { page = (page + 1) % pages; layout(); });
    }

    public void toggleOpen() { open = !open; layout(); dirty = true; }
    public void toast(String s) { toast = s; toastUntil = System.currentTimeMillis() + 2000; dirty = true; }
    public void markDirty() { dirty = true; }
    public void setOpen(boolean o) { open = o; if (W > 0) layout(); dirty = true; }
    public void relayout() { if (W > 0) layout(); dirty = true; }

    public void resize(int w, int h) {
        if (w == W && h == H) return;
        W = w; H = h;
        bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        cv = new Canvas(bmp);
        layout();
        dirty = true;
    }

    public void setSafe(float vis) {
        float sf = Math.max(0.3f, Math.min(1f, vis * 0.88f));
        if (Math.abs(sf - safe) > 0.01f) { safe = sf; if (W > 0) { layout(); dirty = true; } }
    }

    public boolean takeDirty() {
        long now = System.currentTimeMillis();
        if (toastUntil != 0 && now > toastUntil) { toastUntil = 0; toast = ""; dirty = true; }
        if (!dirty) return false;
        dirty = false;
        draw();
        return true;
    }

    private void layout() {
        float u = H / 540f;
        visible.clear();
        // dock
        int n = dock.size();
        float d = 50 * u, gap = 10 * u;
        float dw = n * d + (n + 1) * gap, dh = d + 2 * gap * 0.8f;
        float dx = (W - dw) / 2f, dy = H - 38 * u - dh;
        float hbw = 150 * u;
        handle.r.set(W / 2f - hbw / 2f, H - 44 * u, W / 2f + hbw / 2f, H - 6 * u);
        visible.add(handle);
        dockR.set(dx, dy, dx + dw, dy + dh);
        for (int i = 0; i < n; i++) {
            float x = dx + gap + i * (d + gap), y = dy + (dh - d) / 2f;
            dock.get(i).r.set(x, y, x + d, y + d);
            visible.add(dock.get(i));
        }
        if (!open) { pH = 0; return; }
        pW = W * safe;
        pX = (W - pW) / 2f;
        pY = H * 0.04f;
        pH = dy - 12 * u - pY;
        sbW = 78 * u;
        for (int i = 0; i < tabs.size(); i++) {
            float y = pY + 54 * u + i * 70 * u;
            tabs.get(i).r.set(pX + 8 * u, y, pX + sbW - 4 * u, y + 62 * u);
            visible.add(tabs.get(i));
        }
        float cx0 = pX + sbW + 8 * u, cy0 = pY + 52 * u;
        float cw = pW - sbW - 18 * u, ch = pH - 52 * u - 40 * u;
        int cols = Math.max(2, (int) (cw / (122 * u)));
        int rows = Math.max(1, (int) (ch / (114 * u)));
        float gw = 8 * u;
        float cardW = (cw - (cols - 1) * gw) / cols, cardH = Math.min(ch / rows - gw, 124 * u);
        List<Btn> list = tab == 0 ? home : apps;
        int per = cols * rows;
        pages = Math.max(1, (list.size() + per - 1) / per);
        if (page >= pages) page = 0;
        for (Btn b : list) b.r.setEmpty();
        for (int i = 0; i < per && page * per + i < list.size(); i++) {
            int c = i % cols, rw = i / cols;
            float x = cx0 + c * (cardW + gw), y = cy0 + rw * (cardH + gw);
            Btn b = list.get(page * per + i);
            b.r.set(x, y, x + cardW, y + cardH);
            visible.add(b);
        }
        float fy = pY + pH - 36 * u;
        float mx = cx0 + cw / 2f;
        prevB.r.set(mx - 70 * u, fy, mx - 34 * u, fy + 30 * u);
        nextB.r.set(mx + 34 * u, fy, mx + 70 * u, fy + 30 * u);
        if (pages > 1) { visible.add(prevB); visible.add(nextB); } else { prevB.r.setEmpty(); nextB.r.setEmpty(); }
    }

    private void round(RectF r, int color, float rad) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        cv.drawRoundRect(r, rad, rad, p);
    }

    private void stroke(RectF r, int color, float w, float rad) {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setColor(color);
        cv.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void text(String s, float cx, float cy, float size, int color, float maxW) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.CENTER);
        while (maxW > 0 && p.measureText(s) > maxW && size > 8) { size -= 1; p.setTextSize(size); }
        cv.drawText(s, cx, cy + size * 0.35f, p);
    }

    private void draw() {
        cv.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
        float u = H / 540f;
        if (!toast.isEmpty()) text(toast, W / 2f, H * 0.05f, 20 * u, 0xFFFFFFFF, 0);
        // handle bar (grab it with a pinch and move the whole menu)
        float hp = 6 * u;
        RectF hb = new RectF(handle.r.left + hp, handle.r.centerY() - 5 * u, handle.r.right - hp, handle.r.centerY() + 5 * u);
        round(hb, dragging ? 0xFFFFA24D : (hover == handle ? 0xFF22B8CF : 0xE6FFFFFF), hb.height() / 2);
        // dock
        round(dockR, 0xD0161820, dockR.height() / 2);
        stroke(dockR, 0x33FFFFFF, 1.5f * u, dockR.height() / 2);
        for (Btn b : dock) {
            boolean on = b.on != null && b.on.get();
            round(b.r, hover == b ? 0xFF22B8CF : (on ? 0xFF2E6BFF : 0xFF2B2E38), b.r.width() / 2);
            text(b.emoji, b.r.centerX(), b.r.centerY(), 24 * u, 0xFFFFFFFF, 0);
        }
        if (!open) return;
        // glass panel
        RectF pr = new RectF(pX, pY, pX + pW, pY + pH);
        p.setShader(new LinearGradient(0, pY, 0, pY + pH, (NvUi.glass << 24) | 0x2A2C36, (NvUi.glass << 24) | 0x121319, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        cv.drawRoundRect(pr, 28 * u, 28 * u, p);
        p.setShader(null);
        stroke(pr, 0x40FFFFFF, 1.5f * u, 28 * u);
        NvPanel.grips(cv, p, pr, 28 * u, u);
        text(tab == 0 ? "หน้าแรก" : "แอปในเครื่อง", pX + sbW + (pW - sbW) / 2f, pY + 28 * u, 22 * u, 0xFFFFFFFF, pW - sbW - 20 * u);
        // sidebar
        for (int i = 0; i < tabs.size(); i++) {
            Btn t = tabs.get(i);
            boolean act = i == tab;
            if (act) round(t.r, 0x38FFFFFF, 16 * u);
            if (hover == t) stroke(t.r, 0xFF22B8CF, 3 * u, 16 * u);
            text(t.emoji, t.r.centerX(), t.r.top + 22 * u, 24 * u, 0xFFFFFFFF, 0);
            text(t.label.get(), t.r.centerX(), t.r.bottom - 14 * u, 12 * u, act ? 0xFFFFFFFF : 0xFFB8BEC8, t.r.width() - 4 * u);
            if (act) round(new RectF(pX + 3 * u, t.r.top + 14 * u, pX + 6.5f * u, t.r.bottom - 14 * u), 0xFF4D8DFF, 2 * u);
        }
        // cards
        List<Btn> list = tab == 0 ? home : apps;
        for (Btn b : list) {
            if (b.r.isEmpty()) continue;
            boolean on = b.on != null && b.on.get();
            RectF r = b.r;
            int c1 = b.c1, c2 = b.c2;
            if (hover == b) { c1 = lighten(c1); c2 = lighten(c2); }
            p.setShader(new LinearGradient(0, r.top, 0, r.bottom, c1, c2, Shader.TileMode.CLAMP));
            p.setStyle(Paint.Style.FILL);
            cv.drawRoundRect(r, 20 * u, 20 * u, p);
            p.setShader(null);
            if (on) stroke(r, 0xFF9ED8FF, 3 * u, 20 * u);
            if (hover == b) stroke(r, 0xFFFFFFFF, 3 * u, 20 * u);
            float h = r.height();
            if (b.icon != null) {
                float s = h * 0.46f;
                RectF d = new RectF(r.centerX() - s / 2, r.top + h * 0.10f, r.centerX() + s / 2, r.top + h * 0.10f + s);
                cv.drawBitmap(b.icon, null, d, p);
            } else {
                text(b.emoji, r.centerX(), r.top + h * 0.34f, h * 0.34f, 0xFFFFFFFF, 0);
            }
            text(b.label.get(), r.centerX(), r.top + h * 0.70f, 14 * u, 0xFFFFFFFF, r.width() - 10 * u);
            if (b.sub != null) text(b.sub.get(), r.centerX(), r.top + h * 0.87f, 11 * u, 0xCCFFFFFF, r.width() - 10 * u);
        }
        if (pages > 1) {
            for (Btn b : new Btn[]{prevB, nextB}) {
                round(b.r, hover == b ? 0xFF22B8CF : 0xFF2B2E38, 15 * u);
                text(b.label.get(), b.r.centerX(), b.r.centerY(), 22 * u, 0xFFFFFFFF, 0);
            }
            text((page + 1) + "/" + pages, (prevB.r.right + nextB.r.left) / 2f, prevB.r.centerY(), 14 * u, 0xFFB8BEC8, 0);
        }
    }

    private static int lighten(int c) {
        int r = Math.min(255, ((c >> 16) & 0xFF) + 40), g = Math.min(255, ((c >> 8) & 0xFF) + 40), b = Math.min(255, (c & 0xFF) + 40);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** cursor in picture pixels; pinch = thumb and index together. Called from the drawing thread. */
    public void update(boolean valid, float x, float y, boolean pinch) {
        if (bmp == null) return;
        Object h = null;
        if (valid) {
            for (int i = visible.size() - 1; i >= 0; i--) {
                Btn b = visible.get(i);
                if (!b.r.isEmpty() && b.r.contains(x, y)) { h = b; break; }
            }
        }
        if (h != null) { lastH = h; lastHT = System.currentTimeMillis(); }
        if (h != hover) { hover = h; dirty = true; }
        boolean edge = pinch && !wasPinch;
        wasPinch = pinch;
        long now = System.currentTimeMillis();
        // the pointer often slips a little just as the fingers close: use what was under it a moment ago
        Object tgt = h != null ? h : ((lastH != null && now - lastHT < 250) ? lastH : null);
        if (edge && tgt != null && now - lastAct > 400) {
            lastAct = now;
            Btn b = (Btn) tgt;
            try { if (b.act != null) b.act.run(); } catch (Throwable ignored) { }
            dirty = true;
        }
    }
}
