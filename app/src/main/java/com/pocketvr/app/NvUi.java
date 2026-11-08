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
        float ah = 0f, ap = 0f;      // pointed-at / pressed, eased 0..1 for the little animations
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

    private static class Tab { Btn b; List<Btn> items; String title; boolean grid; }
    private final List<Tab> tabs = new ArrayList<>();
    private final List<Btn> visible = new ArrayList<>();
    private Btn prevB, nextB;
    private int tab = 0, page = 0, pages = 1;
    private float scroll = 0f, maxScroll = 0f, pressScroll = 0f;
    private boolean scrolling = false;
    private final RectF cRect = new RectF();                // where the list of this category is shown (what is scrolled out of it is cut off)

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
        addTabInternal("🏠", "หน้าแรก", "หน้าแรก", home, false);
        addTabInternal("📱", "แอป", "แอปในเครื่อง", apps, false);
        tabs.get(tabs.size() - 1).grid = true;
        prevB = new Btn("", () -> "‹", null, null, 0, 0, () -> { page = (page + pages - 1) % pages; layout(); });
        nextB = new Btn("", () -> "›", null, null, 0, 0, () -> { page = (page + 1) % pages; layout(); });
    }

    private void addTabInternal(String emoji, final String name, String title, List<Btn> items, boolean beforeLast) {
        final Tab t = new Tab();
        t.items = items; t.title = title;
        t.b = new Btn(emoji, () -> name, null, null, 0, 0, () -> { tab = tabs.indexOf(t); page = 0; scroll = 0f; layout(); });
        if (beforeLast && !tabs.isEmpty()) tabs.add(tabs.size() - 1, t); else tabs.add(t);
    }

    /** A new category in the side bar (before the apps tab). Returns the list to put its cards in. */
    public List<Btn> addTab(String emoji, String name, String title) {
        List<Btn> l = new ArrayList<>();
        addTabInternal(emoji, name, title, l, false);          // order: Home, Apps, Web apps, Tools
        return l;
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

    private boolean isItem(Btn b) {
        for (Tab t : tabs) if (t.items.contains(b)) return true;
        return false;
    }

    private Btn pressTgt = null;
    private float pressX, pressY;
    private long pressT = 0;
    private boolean swiped = false;

    /** turn the page (or move to the next category when there is just one page) */
    private void flip(int dir) {
        tab = Math.max(0, Math.min(tabs.size() - 1, tab + dir)); page = 0; scroll = 0f;
        layout();
        dirty = true;
    }

    private void layout() {
        float u = H / 540f;
        visible.clear();
        // dock
        int n = dock.size();
        float d = 50 * u, gap = 10 * u, bw = 104 * u;                       // dock buttons are words in capsules (no pictures)
        float dw = n * bw + (n + 1) * gap, dh = d + 2 * gap * 0.8f;
        float dx = (W - dw) / 2f, dy = H - 38 * u - dh;
        float hbw = 150 * u;
        handle.r.set(W / 2f - hbw / 2f, H - 44 * u, W / 2f + hbw / 2f, H - 6 * u);
        visible.add(handle);
        dockR.set(dx, dy, dx + dw, dy + dh);
        for (int i = 0; i < n; i++) {
            float x = dx + gap + i * (bw + gap), y = dy + (dh - d) / 2f;
            dock.get(i).r.set(x, y, x + bw, y + d);
            visible.add(dock.get(i));
        }
        if (!open) { pH = 0; return; }
        pW = W * safe;
        pX = (W - pW) / 2f;
        pY = H * 0.04f;
        float ov = dh * 0.5f + 2 * u;                       // the dock floats over the bottom edge of the panel, like a visionOS ornament
        pH = dy - 12 * u - pY + ov;
        sbW = Math.min(214 * u, pW * 0.27f);
        float tabH = Math.min(56 * u, (pH - 76 * u) / Math.max(1, tabs.size()) - 6 * u);
        for (int i = 0; i < tabs.size(); i++) {
            float y = pY + 62 * u + i * (tabH + 6 * u);
            tabs.get(i).b.r.set(pX + 12 * u, y, pX + sbW - 6 * u, y + tabH);
            visible.add(tabs.get(i).b);
        }
        float cx0 = pX + sbW + 6 * u, cy0 = pY + 62 * u;
        float cw = pW - sbW - 6 * u - 16 * u, ch = pH - 62 * u - 46 * u - ov;
        if (tab >= tabs.size()) tab = 0;
        Tab ct = tabs.get(tab);
        List<Btn> list = ct.items;
        float gw = 8 * u, cardW, cardH;
        int cols, rows;
        if (ct.grid) {
            cols = Math.max(2, (int) (cw / (122 * u)));
            rows = Math.max(1, (int) (ch / (114 * u)));
            cardW = (cw - (cols - 1) * gw) / cols; cardH = Math.min(ch / rows - gw, 124 * u);
        } else {
            cols = cw > 560 * u ? 2 : 1;
            float rowH = 64 * u;
            rows = Math.max(1, (int) ((ch + gw) / (rowH + gw)));
            cardW = (cw - (cols - 1) * gw) / cols; cardH = rowH;
        }
        pages = 1;
        cRect.set(cx0, cy0, cx0 + cw, cy0 + ch);
        for (Tab tt : tabs) for (Btn b : tt.items) b.r.setEmpty();
        int nrows = (list.size() + cols - 1) / cols;
        float total = nrows * (cardH + gw) - gw;
        maxScroll = Math.max(0f, total - ch);
        scroll = Math.max(0f, Math.min(maxScroll, scroll));
        for (int i = 0; i < list.size(); i++) {
            int c = i % cols, rw = i / cols;
            float x = cx0 + c * (cardW + gw), y = cy0 + rw * (cardH + gw) - scroll;
            if (y + cardH < cy0 - 2 || y > cy0 + ch + 2) continue;           // out of sight: neither drawn nor pointed at
            Btn b = list.get(i);
            b.r.set(x, y, x + cardW, y + cardH);
            visible.add(b);
        }
        prevB.r.setEmpty(); nextB.r.setEmpty();
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

    private void text(String s, float cx, float cy, float size, int color, float maxW) { textA(s, cx, cy, size, color, maxW, Paint.Align.CENTER); }

    private void textA(String s, float cx, float cy, float size, int color, float maxW, Paint.Align al) {
        s = Tr.t(s);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        p.setTextSize(size);
        p.setTextAlign(al);
        while (maxW > 0 && p.measureText(s) > maxW && size > 8) { size -= 1; p.setTextSize(size); }
        cv.drawText(s, cx, cy + size * 0.35f, p);
    }

    private boolean animating = false;

    /** glide the pointed-at / pressed amounts towards their targets; keeps redrawing while something is still moving */
    private void ease(Btn b) {
        float th = hover == b ? 1f : 0f;
        float tp = (pressTgt == b && wasPinch && !scrolling && !swiped) ? 1f : 0f;
        b.ah += (th - b.ah) * 0.4f;
        b.ap += (tp - b.ap) * 0.5f;
        if (Math.abs(th - b.ah) > 0.02f || Math.abs(tp - b.ap) > 0.02f) animating = true; else { b.ah = th; b.ap = tp; }
    }

    private void draw() {
        animating = false;
        cv.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
        float u = H / 540f;
        NvGlass.font(p, true);
        if (!toast.isEmpty()) {
            p.setTextSize(20 * u);
            float tw = Math.min(W * 0.8f, p.measureText(Tr.t(toast)) + 44 * u);
            RectF tr = new RectF(W / 2f - tw / 2f, H * 0.05f - 22 * u, W / 2f + tw / 2f, H * 0.05f + 22 * u);
            NvGlass.fill(cv, p, tr, 0xD0262932, tr.height() / 2);
            NvGlass.ring(cv, p, tr, 0x55FFFFFF, 1.2f * u, tr.height() / 2);
            text(toast, W / 2f, H * 0.05f, 20 * u, NvGlass.TEXT, tw - 24 * u);
        }
        // handle bar (grab it with a pinch and move the whole menu)
        float hp = 6 * u;
        RectF hb = new RectF(handle.r.left + hp, handle.r.centerY() - 4.5f * u, handle.r.right - hp, handle.r.centerY() + 4.5f * u);
        NvGlass.fill(cv, p, hb, dragging ? 0xFFFFA24D : (hover == handle ? 0xFFFFFFFF : 0xA6FFFFFF), hb.height() / 2);
        // dock: a glass capsule with round buttons
        NvGlass.panel(cv, p, dockR, dockR.height() / 2, 0.8f * u);
        for (Btn b : dock) {
            boolean on = b.on != null && b.on.get();
            ease(b);
            float sc = 1f + 0.12f * b.ah - 0.10f * b.ap;
            cv.save();
            cv.scale(sc, sc, b.r.centerX(), b.r.centerY());
            NvGlass.fill(cv, p, b.r, NvGlass.lerp(on ? 0xF2FFFFFF : 0x26FFFFFF, on ? 0xFFFFFFFF : 0x59FFFFFF, b.ah), b.r.height() / 2);
            if (b.ah > 0.02f) NvGlass.ring(cv, p, b.r, NvGlass.lerp(0x00FFFFFF, NvGlass.RING, b.ah), 2.4f * u, b.r.height() / 2);
            NvGlass.font(p, true);
            text(b.label.get(), b.r.centerX(), b.r.centerY(), 17 * u, on ? 0xFF1C1E26 : NvGlass.TEXT, b.r.width() - 16 * u);
            cv.restore();
        }
        if (!open) return;
        // glass panel
        RectF pr = new RectF(pX, pY, pX + pW, pY + pH);
        NvGlass.panel(cv, p, pr, 34 * u, u);
        NvPanel.grips(cv, p, pr, 34 * u, u);
        NvGlass.font(p, true);
        textA("PocketVR", pX + 26 * u, pY + 32 * u, 25 * u, NvGlass.TEXT, sbW - 34 * u, Paint.Align.LEFT);
        Tab ct = tabs.get(Math.min(tab, tabs.size() - 1));
        textA(ct.title, pX + sbW + 16 * u, pY + 32 * u, 26 * u, NvGlass.TEXT, pW - sbW - 44 * u, Paint.Align.LEFT);
        // a thin line between the side bar and the content
        NvGlass.fill(cv, p, new RectF(pX + sbW - 1 * u, pY + 56 * u, pX + sbW + 0.5f * u, pY + pH - 24 * u), 0x1FFFFFFF, 1);
        // categories: capsules; the chosen one is a brighter piece of glass
        for (int i = 0; i < tabs.size(); i++) {
            Btn t = tabs.get(i).b;
            boolean act = i == tab;
            ease(t);
            float rad = t.r.height() / 2f;
            float sc = 1f + 0.03f * t.ah - 0.05f * t.ap;
            cv.save();
            cv.scale(sc, sc, t.r.centerX(), t.r.centerY());
            if (act || t.ah > 0.02f || t.ap > 0.02f) NvGlass.controlA(cv, p, t.r, rad, u, act, t.ah, t.ap);
            NvGlass.font(p, act);
            textA(t.label.get(), t.r.left + 24 * u, t.r.centerY(), 21 * u, act ? NvGlass.TEXT : 0xCCFFFFFF, t.r.width() - 40 * u, Paint.Align.LEFT);
            cv.restore();
        }
        // content (cut off at the edges of the list, so it can be scrolled)
        List<Btn> list = ct.items;
        cv.save();
        cv.clipRect(cRect);
        for (Btn b : list) {
            if (b.r.isEmpty()) continue;
            boolean on = b.on != null && b.on.get();
            RectF r = b.r;
            float h = r.height();
            float cr = ct.grid ? 24 * u : h / 2.6f;
            ease(b);
            float sc = 1f + 0.025f * b.ah - 0.05f * b.ap;
            cv.save();
            cv.scale(sc, sc, r.centerX(), r.centerY());
            NvGlass.controlA(cv, p, r, cr, u, on, b.ah, b.ap);
            if (ct.grid) {
                if (b.icon != null) {
                    float s2 = h * 0.46f;
                    RectF d = new RectF(r.centerX() - s2 / 2, r.top + h * 0.10f, r.centerX() + s2 / 2, r.top + h * 0.10f + s2);
                    cv.drawBitmap(b.icon, null, d, p);
                }
                NvGlass.font(p, true);
                if (b.icon != null) text(b.label.get(), r.centerX(), r.top + h * (b.sub != null ? 0.70f : 0.76f), 14 * u, NvGlass.TEXT, r.width() - 10 * u);
                else text(b.label.get(), r.centerX(), r.top + h * (b.sub != null ? 0.42f : 0.5f), 17 * u, NvGlass.TEXT, r.width() - 14 * u);
                NvGlass.font(p, false);
                if (b.sub != null) text(b.sub.get(), r.centerX(), r.top + h * (b.icon != null ? 0.89f : 0.68f), 12 * u, 0xFFA9D4FF, r.width() - 10 * u);
                cv.restore();
                continue;
            }
            // list row: round coloured badge, name + hint, arrow
            float right = 34 * u;
            float lx = r.left + 24 * u, lw = r.right - lx - right;
            NvGlass.font(p, true);
            if (b.sub != null) {
                textA(b.label.get(), lx, r.centerY() - h * 0.16f, 22 * u, NvGlass.TEXT, lw, Paint.Align.LEFT);
                NvGlass.font(p, false);
                textA(b.sub.get(), lx, r.centerY() + h * 0.22f, 15 * u, NvGlass.TEXT2, lw, Paint.Align.LEFT);
            } else textA(b.label.get(), lx, r.centerY(), 22 * u, NvGlass.TEXT, lw, Paint.Align.LEFT);
            text("›", r.right - 22 * u, r.centerY(), 30 * u, on ? 0xFFA9D4FF : NvGlass.TEXT2, 0);
            cv.restore();
        }
        cv.restore();
        if (maxScroll > 0f) {                                       // a thin bar shows where you are in a long list
            float th = Math.max(36 * u, cRect.height() * cRect.height() / (cRect.height() + maxScroll));
            float ty = cRect.top + (cRect.height() - th) * (scroll / maxScroll);
            NvGlass.fill(cv, p, new RectF(cRect.right + 4 * u, ty, cRect.right + 8 * u, ty + th), scrolling ? 0xFFFFFFFF : 0x80FFFFFF, 3 * u);
        }
        if (animating) dirty = true;
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
                if (b.r.isEmpty() || !b.r.contains(x, y)) continue;
                if (isItem(b) && !cRect.contains(x, y)) continue;                 // the part of a card cut off by the list edge is not there
                h = b; break;
            }
        }
        if (h != null) { lastH = h; lastHT = System.currentTimeMillis(); }
        if (h != hover) { hover = h; dirty = true; }
        boolean edge = pinch && !wasPinch;
        boolean released = !pinch && wasPinch;
        wasPinch = pinch;
        long now = System.currentTimeMillis();
        // the pointer often slips a little just as the fingers close: use what was under it a moment ago
        Object tgt = h != null ? h : ((lastH != null && now - lastHT < 250) ? lastH : null);
        float u = H / 540f;
        if (edge) { pressTgt = (Btn) tgt; pressX = x; pressY = y; pressT = now; swiped = false; scrolling = false; pressScroll = scroll; }
        if (pinch && valid && !swiped && open && pH > 0 && pressT != 0) {
            // drag left / right with the fingers closed: next / previous page (or category when there is only one page)
            float dx = x - pressX, dy = y - pressY;
            if (!scrolling && Math.abs(dx) > 70 * u && Math.abs(dx) > 1.6f * Math.abs(dy)) {
                swiped = true; pressTgt = null;
                if (dx < 0) flip(1); else flip(-1);
            } else if (!swiped && cRect.contains(pressX, pressY) && maxScroll > 0f) {
                // drag up / down: the list follows the hand
                if (!scrolling && Math.abs(dy) > 30 * u && Math.abs(dy) > 1.2f * Math.abs(dx)) { scrolling = true; pressTgt = null; }
                if (scrolling) {
                    float ns = Math.max(0f, Math.min(maxScroll, pressScroll + (pressY - y)));
                    if (Math.abs(ns - scroll) > 0.5f) { scroll = ns; layout(); dirty = true; }
                }
            }
        }
        if (released) {
            Btn b = pressTgt; pressTgt = null;
            scrolling = false; dirty = true;
            if (b != null && !swiped && (valid || now - lastHT < 300) && now - pressT < 1800 && now - lastAct > 300) {
                lastAct = now;
                try { if (b.act != null) b.act.run(); } catch (Throwable ignored) { }
                dirty = true;
            }
            pressT = 0;
        }
        if (!valid && !pinch) pressTgt = null;
    }
}
