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
    @Override public float visFrac() { return open ? safe : 1f; }
    @Override public boolean occupies() { return open; }
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
    // ---- status shown on the dock (filled in by the app) ----
    public volatile int battery = -1;          // percent, -1 unknown
    public volatile boolean charging = false;
    public volatile int net = -1;              // signal bars 0..4, -1 = no network
    public volatile String netKind = "";       // "Wi-Fi", "4G" ...
    public volatile boolean micOn = false;     // listening for voice commands
    public Runnable onMic;                     // tap on the microphone symbol
    public Runnable onSearch;                  // tap on the search field
    public volatile String filter = "";        // text typed into the search field: only matching cards are shown
    private final Btn micB = new Btn("", () -> "", null, null, 0, 0, null);
    private final Btn searchB = new Btn("", () -> "", null, null, 0, 0, null);
    private final Btn clearB = new Btn("", () -> "", null, null, 0, 0, null);
    private final RectF statR = new RectF();
    private int lastMin = -1;
    private static final int[][] PAL = {{0xFF4C8DFF, 0xFF2F5FD0}, {0xFFA06BFF, 0xFF6A3FD0}, {0xFF23B5A6, 0xFF147F76}, {0xFFFF8A4C, 0xFFD0561F}, {0xFFE0507A, 0xFFA82B52}, {0xFF52B04A, 0xFF2F7A2A}, {0xFF3FB6E8, 0xFF1F7FB0}, {0xFFE8B13C, 0xFFB07A14}};

    public NvUi() {
        addTabInternal("🏠", "หน้าแรก", "หน้าแรก", home, false);
        addTabInternal("📱", "แอป", "แอปในเครื่อง", apps, false);
        tabs.get(tabs.size() - 1).grid = true;
        searchB.act = () -> { if (onSearch != null) onSearch.run(); };
        clearB.act = () -> { filter = ""; scroll = 0f; layout(); dirty = true; };
        micB.act = () -> { if (onMic != null) onMic.run(); };
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

    /** every button of every tab (home, apps, web apps, tools...): the voice commands search all of them */
    public List<Btn> allButtons() { List<Btn> o = new ArrayList<>(); for (Tab t : tabs) o.addAll(t.items); return o; }

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
        int mn = (int) ((now / 60000L) % 1440L);
        if (mn != lastMin) { lastMin = mn; dirty = true; }
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

    private List<Btn> shown(Tab t) {
        String f = filter == null ? "" : filter.trim().toLowerCase(java.util.Locale.ROOT);
        if (f.isEmpty()) return t.items;
        List<Btn> out = new ArrayList<>();
        for (Btn b : t.items) {
            String a = b.label.get(), c = Tr.t(a);
            if (a.toLowerCase(java.util.Locale.ROOT).contains(f) || c.toLowerCase(java.util.Locale.ROOT).contains(f)) out.add(b);
        }
        return out;
    }

    private float railW, topH;

    private void layout() {
        float u = H / 540f;
        visible.clear();
        // dock: a floating pill. left: clock, battery, signal, microphone. right: round-cornered icons
        int n = dock.size();
        float d = 44 * u, gap = 10 * u, pad = 14 * u, sw = 252 * u;
        float dh = d + 2 * 10 * u, dw = pad + sw + 14 * u + n * d + Math.max(0, n - 1) * gap + pad;
        float dx = (W - dw) / 2f, dy = H - 38 * u - dh;
        float hbw = 150 * u;
        handle.r.set(W / 2f - hbw / 2f, H - 44 * u, W / 2f + hbw / 2f, H - 6 * u);
        visible.add(handle);
        dockR.set(dx, dy, dx + dw, dy + dh);
        statR.set(dx + pad, dy, dx + pad + sw, dy + dh);
        micB.r.set(statR.right - 40 * u, dy + (dh - 36 * u) / 2f, statR.right - 4 * u, dy + (dh + 36 * u) / 2f);
        visible.add(micB);
        for (int i = 0; i < n; i++) {
            float x = dx + pad + sw + 14 * u + i * (d + gap), y = dy + (dh - d) / 2f;
            dock.get(i).r.set(x, y, x + d, y + d);
            visible.add(dock.get(i));
        }
        searchB.r.setEmpty(); clearB.r.setEmpty();
        if (!open) { pH = 0; return; }
        pW = W * safe;
        pX = (W - pW) / 2f;
        pY = H * 0.04f;
        pH = dy - 16 * u - pY;
        railW = 66 * u; topH = 74 * u;
        sbW = railW;
        // round buttons down the left side
        float bs = Math.min(46 * u, (pH - topH - 20 * u) / Math.max(1, tabs.size()) - 8 * u);
        for (int i = 0; i < tabs.size(); i++) {
            float y = pY + topH + 6 * u + i * (bs + 8 * u);
            tabs.get(i).b.r.set(pX + (railW - bs) / 2f + 4 * u, y, pX + (railW - bs) / 2f + 4 * u + bs, y + bs);
            visible.add(tabs.get(i).b);
        }
        float cx0 = pX + railW + 22 * u, cy0 = pY + topH;
        float cw = pW - railW - 22 * u - 24 * u, ch = pH - topH - 18 * u;
        // search field (top right of the content), with a small clear button when something is typed
        float sfw = Math.min(250 * u, cw * 0.42f), sfh = 38 * u;
        searchB.r.set(cx0 + cw - sfw, pY + 18 * u, cx0 + cw, pY + 18 * u + sfh);
        visible.add(searchB);
        if (filter != null && !filter.isEmpty()) { clearB.r.set(searchB.r.right - 34 * u, searchB.r.top + 4 * u, searchB.r.right - 4 * u, searchB.r.bottom - 4 * u); visible.add(clearB); }
        if (tab >= tabs.size()) tab = 0;
        Tab ct = tabs.get(tab);
        List<Btn> list = shown(ct);
        float gw = 14 * u;
        int cols = Math.max(2, (int) ((cw + gw) / (150 * u + gw)));       // bigger tiles: easier to point at and read from the phone in the box
        float tw = (cw - (cols - 1) * gw) / cols, th = tw * 0.62f, lab = 52 * u;
        pages = 1;
        cRect.set(cx0 - 6 * u, cy0, cx0 + cw + 6 * u, cy0 + ch);
        for (Tab tt : tabs) for (Btn b : tt.items) b.r.setEmpty();
        int nrows = (list.size() + cols - 1) / cols;
        float pitch = th + lab + 4 * u;
        float total = nrows * pitch - 4 * u;
        maxScroll = Math.max(0f, total - ch);
        scroll = Math.max(0f, Math.min(maxScroll, scroll));
        for (int i = 0; i < list.size(); i++) {
            int c = i % cols, rw = i / cols;
            float x = cx0 + c * (tw + gw), y = cy0 + rw * pitch - scroll;
            if (y + th + lab < cy0 - 2 || y > cy0 + ch + 2) continue;           // out of sight: neither drawn nor pointed at
            Btn b = list.get(i);
            b.r.set(x, y, x + tw, y + th + lab - 6 * u);                          // pointing area covers the tile and its name
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

    /** text that is not run through the translator (emoji must survive it) */
    private void raw(String s, float cx, float cy, float size, int color, Paint.Align al) {
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(color); p.setTextSize(size); p.setTextAlign(al);
        cv.drawText(s, cx, cy + size * 0.35f, p);
    }

    /** the picture on a tile: the emoji (Thai mode) or the first letter of the name */
    /** simple pictures drawn with strokes (original, plain), so a tile says what it is in every language */
    private boolean pict(String e, float cx, float cy, float s, int color) {
        if (e == null || e.isEmpty()) return false;
        int cp = e.codePointAt(0);
        p.setShader(null); p.setColor(color); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        float r = s * 0.62f, sw = Math.max(2.2f, s * 0.085f);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(sw);
        RectF b = new RectF(cx - r, cy - r, cx + r, cy + r);
        switch (cp) {
            case 0x1F310: cv.drawCircle(cx, cy, r, p); cv.drawOval(new RectF(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r), p); cv.drawLine(cx - r, cy, cx + r, cy, p); cv.drawLine(cx - r * 0.85f, cy - r * 0.5f, cx + r * 0.85f, cy - r * 0.5f, p); cv.drawLine(cx - r * 0.85f, cy + r * 0.5f, cx + r * 0.85f, cy + r * 0.5f, p); break;      // globe
            case 0x1F5BC: cv.drawRoundRect(new RectF(cx - r, cy - r * 0.8f, cx + r, cy + r * 0.8f), 6, 6, p); { android.graphics.Path q = new android.graphics.Path(); q.moveTo(cx - r, cy + r * 0.55f); q.lineTo(cx - r * 0.3f, cy - r * 0.05f); q.lineTo(cx + r * 0.15f, cy + r * 0.4f); q.lineTo(cx + r * 0.5f, cy + r * 0.1f); q.lineTo(cx + r, cy + r * 0.55f); cv.drawPath(q, p); } cv.drawCircle(cx + r * 0.5f, cy - r * 0.35f, r * 0.14f, p); break;       // picture
            case 0x1F3AC: cv.drawRoundRect(new RectF(cx - r, cy - r * 0.45f, cx + r, cy + r * 0.8f), 6, 6, p); cv.drawRect(new RectF(cx - r, cy - r * 0.85f, cx + r, cy - r * 0.45f), p); for (int k = -1; k <= 1; k++) cv.drawLine(cx + k * r * 0.6f - r * 0.2f, cy - r * 0.85f, cx + k * r * 0.6f + r * 0.1f, cy - r * 0.45f, p); break;                  // clapper
            case 0x2328: cv.drawRoundRect(new RectF(cx - r, cy - r * 0.6f, cx + r, cy + r * 0.6f), 8, 8, p); p.setStyle(Paint.Style.FILL); for (int k = -2; k <= 2; k++) { cv.drawCircle(cx + k * r * 0.36f, cy - r * 0.22f, sw * 0.55f, p); cv.drawCircle(cx + k * r * 0.36f, cy + r * 0.12f, sw * 0.55f, p); } cv.drawRect(new RectF(cx - r * 0.5f, cy + r * 0.28f, cx + r * 0.5f, cy + r * 0.38f), p); break;   // keyboard
            case 0x2699: cv.drawCircle(cx, cy, r * 0.5f, p); cv.drawCircle(cx, cy, r * 0.2f, p); for (int k = 0; k < 8; k++) { double a = k * Math.PI / 4; cv.drawLine(cx + (float) Math.cos(a) * r * 0.62f, cy + (float) Math.sin(a) * r * 0.62f, cx + (float) Math.cos(a) * r * 0.95f, cy + (float) Math.sin(a) * r * 0.95f, p); } break;   // gear
            case 0x1F306: p.setStyle(Paint.Style.FILL); { float[] hh = {0.5f, 0.9f, 0.65f, 1.1f, 0.7f}; for (int k = 0; k < 5; k++) { float x0 = cx - r + k * r * 0.4f; cv.drawRect(new RectF(x0, cy + r * 0.7f - hh[k] * r, x0 + r * 0.34f, cy + r * 0.7f), p); } } break;     // skyline
            case 0x1F399: cv.drawRoundRect(new RectF(cx - r * 0.28f, cy - r * 0.95f, cx + r * 0.28f, cy + r * 0.15f), 14, 14, p); cv.drawArc(new RectF(cx - r * 0.6f, cy - r * 0.5f, cx + r * 0.6f, cy + r * 0.55f), 20, 140, false, p); cv.drawLine(cx, cy + r * 0.55f, cx, cy + r * 0.95f, p); cv.drawLine(cx - r * 0.35f, cy + r * 0.95f, cx + r * 0.35f, cy + r * 0.95f, p); break;       // microphone
            case 0x1F5E3: cv.drawRoundRect(new RectF(cx - r, cy - r * 0.75f, cx + r, cy + r * 0.35f), 14, 14, p); cv.drawLine(cx - r * 0.4f, cy + r * 0.35f, cx - r * 0.6f, cy + r * 0.85f, p); cv.drawLine(cx - r * 0.6f, cy + r * 0.85f, cx, cy + r * 0.35f, p); for (int k = -1; k <= 1; k++) cv.drawLine(cx + k * r * 0.45f, cy - r * 0.25f, cx + k * r * 0.45f, cy - r * 0.05f, p); break;   // speech
            case 0x2694: cv.drawLine(cx - r * 0.85f, cy - r * 0.85f, cx + r * 0.85f, cy + r * 0.85f, p); cv.drawLine(cx + r * 0.85f, cy - r * 0.85f, cx - r * 0.85f, cy + r * 0.85f, p); cv.drawLine(cx - r * 0.55f, cy + r * 0.2f, cx - r * 0.2f, cy + r * 0.55f, p); cv.drawLine(cx + r * 0.55f, cy + r * 0.2f, cx + r * 0.2f, cy + r * 0.55f, p); break;   // swords
            case 0x1F349: cv.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), 0, 180, true, p); cv.drawLine(cx - r * 0.9f, cy, cx + r * 0.9f, cy, p); p.setStyle(Paint.Style.FILL); for (int k = -1; k <= 1; k++) cv.drawCircle(cx + k * r * 0.4f, cy + r * 0.38f, sw * 0.6f, p); break;       // watermelon slice
            case 0x23F1: cv.drawCircle(cx, cy + r * 0.1f, r * 0.8f, p); cv.drawLine(cx, cy + r * 0.1f, cx, cy - r * 0.45f, p); cv.drawLine(cx, cy + r * 0.1f, cx + r * 0.35f, cy + r * 0.3f, p); cv.drawLine(cx - r * 0.2f, cy - r * 0.9f, cx + r * 0.2f, cy - r * 0.9f, p); break;    // timer
            case 0x1F4CD: cv.drawCircle(cx, cy - r * 0.25f, r * 0.55f, p); cv.drawLine(cx - r * 0.45f, cy + r * 0.1f, cx, cy + r * 0.95f, p); cv.drawLine(cx + r * 0.45f, cy + r * 0.1f, cx, cy + r * 0.95f, p); cv.drawCircle(cx, cy - r * 0.25f, r * 0.2f, p); break;    // pin
            case 0x1F4C8: cv.drawLine(cx - r, cy - r * 0.8f, cx - r, cy + r * 0.8f, p); cv.drawLine(cx - r, cy + r * 0.8f, cx + r, cy + r * 0.8f, p); { android.graphics.Path q = new android.graphics.Path(); q.moveTo(cx - r * 0.8f, cy + r * 0.5f); q.lineTo(cx - r * 0.2f, cy); q.lineTo(cx + r * 0.2f, cy + r * 0.25f); q.lineTo(cx + r * 0.9f, cy - r * 0.6f); cv.drawPath(q, p); } break;       // chart
            case 0x1F3E0: { android.graphics.Path q = new android.graphics.Path(); q.moveTo(cx - r, cy); q.lineTo(cx, cy - r * 0.9f); q.lineTo(cx + r, cy); cv.drawPath(q, p); cv.drawRect(new RectF(cx - r * 0.72f, cy, cx + r * 0.72f, cy + r * 0.85f), p); } break;       // home
            case 0x1F97D: case 0x1F453: cv.drawRoundRect(new RectF(cx - r, cy - r * 0.5f, cx + r, cy + r * 0.5f), 18, 18, p); cv.drawLine(cx, cy - r * 0.5f, cx, cy + r * 0.5f, p); cv.drawCircle(cx - r * 0.5f, cy, r * 0.22f, p); cv.drawCircle(cx + r * 0.5f, cy, r * 0.22f, p); break;       // goggles
            case 0x270B: for (int k = 0; k < 4; k++) cv.drawLine(cx - r * 0.5f + k * r * 0.33f, cy - r * (0.55f + (k == 1 || k == 2 ? 0.35f : 0.1f)), cx - r * 0.5f + k * r * 0.33f, cy, p); cv.drawRoundRect(new RectF(cx - r * 0.62f, cy - r * 0.1f, cx + r * 0.62f, cy + r * 0.85f), 16, 16, p); cv.drawLine(cx - r * 0.62f, cy + r * 0.1f, cx - r * 0.95f, cy - r * 0.15f, p); break;     // hand
            case 0x23FB: cv.drawArc(new RectF(cx - r * 0.8f, cy - r * 0.8f, cx + r * 0.8f, cy + r * 0.8f), -60, 300, false, p); cv.drawLine(cx, cy - r * 0.95f, cx, cy - r * 0.15f, p); break;        // power
            case 0x1F98D: cv.drawCircle(cx, cy, r * 0.35f, p); for (int k = 0; k < 4; k++) cv.drawCircle(cx - r * 0.75f + k * r * 0.5f, cy - r * (k == 0 || k == 3 ? 0.55f : 0.9f), r * 0.17f, p); break;     // paw (plain)
            case 0x1F578: for (int k = 0; k < 6; k++) { double a = k * Math.PI / 3; cv.drawLine(cx, cy, cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, p); } cv.drawCircle(cx, cy, r * 0.45f, p); cv.drawCircle(cx, cy, r * 0.85f, p); break;     // web
            default: p.setStyle(Paint.Style.FILL); return false;
        }
        p.setStyle(Paint.Style.FILL);
        return true;
    }

    private void glyph(Btn b, float cx, float cy, float size, int color) {
        String e = b.emoji == null ? "" : b.emoji;
        if (pict(e, cx, cy, size, 0xFFFFFFFF)) return;
        if (!e.isEmpty()) { NvGlass.font(p, false); raw(e, cx, cy, size, 0xFFFFFFFF, Paint.Align.CENTER); return; }          // the picture (emoji) of the card in every language, not only the first letter
        String t = Tr.t(b.label.get()).trim();
        String g = t.isEmpty() ? "•" : t.substring(0, t.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.ROOT);
        NvGlass.font(p, true);
        raw(g, cx, cy, size * 0.95f, color, Paint.Align.CENTER);
    }

    private int[] tileColors(Btn b, int idx) {
        if (b.c1 == 0 || b.c1 == 0xFF5A5A63) return PAL[idx % PAL.length];
        return new int[]{b.c1, b.c2};
    }

    private void tooltip(String s, float cx, float bottom, float u) {
        NvGlass.font(p, true);
        p.setTextSize(15 * u);
        String t = Tr.t(s);
        float w = Math.min(W * 0.5f, p.measureText(t) + 26 * u);
        RectF r = new RectF(cx - w / 2f, bottom - 30 * u, cx + w / 2f, bottom);
        NvGlass.fill(cv, p, r, 0xEE0E1426, r.height() / 2);
        NvGlass.ring(cv, p, r, 0x33FFFFFF, 1f * u, r.height() / 2);
        text(s, cx, r.centerY(), 15 * u, NvGlass.TEXT, w - 14 * u);
    }

    private void drawStatus(float u) {
        RectF r = statR;
        float cy = r.centerY();
        // clock
        java.util.Calendar c = java.util.Calendar.getInstance();
        String tm = String.format(java.util.Locale.US, "%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
        NvGlass.font(p, true);
        raw(tm, r.left + 4 * u, cy, 21 * u, NvGlass.TEXT, Paint.Align.LEFT);
        // battery
        float bx = r.left + 82 * u;
        int bt = battery;
        RectF body = new RectF(bx, cy - 8 * u, bx + 28 * u, cy + 8 * u);
        NvGlass.ring(cv, p, body, 0xCCFFFFFF, 1.6f * u, 4 * u);
        NvGlass.fill(cv, p, new RectF(body.right + 1 * u, cy - 3.5f * u, body.right + 3.5f * u, cy + 3.5f * u), 0xCCFFFFFF, 1 * u);
        if (bt >= 0) {
            RectF fl = new RectF(body.left + 2.6f * u, body.top + 2.6f * u, body.left + 2.6f * u + (body.width() - 5.2f * u) * Math.max(0.06f, bt / 100f), body.bottom - 2.6f * u);
            NvGlass.fill(cv, p, fl, charging ? 0xFF34C759 : (bt <= 15 ? 0xFFFF5A52 : 0xFFFFFFFF), 2 * u);
        }
        NvGlass.font(p, false);
        raw(bt >= 0 ? bt + "%" : "–", bx + 36 * u, cy, 14 * u, NvGlass.TEXT2, Paint.Align.LEFT);
        // signal bars
        float sx = r.left + 168 * u;
        for (int i = 0; i < 4; i++) {
            float bh = (5 + i * 3.4f) * u;
            RectF br = new RectF(sx + i * 6.2f * u, cy + 8 * u - bh, sx + i * 6.2f * u + 4.2f * u, cy + 8 * u);
            NvGlass.fill(cv, p, br, i < net ? 0xFFFFFFFF : 0x40FFFFFF, 1.2f * u);
        }
        NvGlass.font(p, false);
        raw(net < 0 ? "ออฟไลน์" : netKind, sx + 27 * u, cy - 0.5f * u, 11.5f * u, NvGlass.TEXT2, Paint.Align.LEFT);
        // microphone (voice commands)
        ease(micB);
        float mcx = micB.r.centerX(), mcy = micB.r.centerY(), mr = 15 * u * (1f + 0.1f * micB.ah);
        RectF mc = new RectF(mcx - mr, mcy - mr, mcx + mr, mcy + mr);
        NvGlass.fill(cv, p, mc, micOn ? 0xFFE0506A : NvGlass.lerp(0x26FFFFFF, 0x55FFFFFF, micB.ah), mr);
        // a small microphone drawn with shapes (no emoji needed)
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(0xFFFFFFFF);
        cv.drawRoundRect(new RectF(mcx - 3 * u, mcy - 8 * u, mcx + 3 * u, mcy + 2.5f * u), 3 * u, 3 * u, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.7f * u);
        cv.drawArc(new RectF(mcx - 6 * u, mcy - 6 * u, mcx + 6 * u, mcy + 5.5f * u), 20, 140, false, p);
        cv.drawLine(mcx, mcy + 5.5f * u, mcx, mcy + 9 * u, p);
        p.setStyle(Paint.Style.FILL);
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
        // dock: a dark pill, status on the left, icons on the right
        int ga = Math.min(255, glass + 6);
        NvGlass.fill(cv, p, dockR, (ga << 24) | 0x0F1628, dockR.height() / 2);
        NvGlass.ring(cv, p, dockR, 0x30FFFFFF, 1.3f * u, dockR.height() / 2);
        drawStatus(u);
        NvGlass.fill(cv, p, new RectF(statR.right + 5 * u, dockR.top + 14 * u, statR.right + 6.2f * u, dockR.bottom - 14 * u), 0x30FFFFFF, 1);
        Btn tipB = null;
        int di = 0;
        for (Btn b : dock) {
            boolean on = b.on != null && b.on.get();
            ease(b);
            float sc = 1f + 0.12f * b.ah - 0.10f * b.ap;
            int[] col = tileColors(b, di + 1);
            cv.save();
            cv.scale(sc, sc, b.r.centerX(), b.r.centerY());
            p.setShader(new LinearGradient(0, b.r.top, 0, b.r.bottom, on ? col[0] : NvGlass.lerp(col[0], 0xFF2A3350, 0.55f), on ? col[1] : NvGlass.lerp(col[1], 0xFF1B2240, 0.55f), Shader.TileMode.CLAMP));
            p.setStyle(Paint.Style.FILL);
            cv.drawRoundRect(b.r, 13 * u, 13 * u, p);
            p.setShader(null);
            if (on || b.ah > 0.02f) NvGlass.ring(cv, p, b.r, NvGlass.lerp(on ? 0x99FFFFFF : 0x00FFFFFF, NvGlass.RING, b.ah), 2f * u, 13 * u);
            glyph(b, b.r.centerX(), b.r.centerY(), 22 * u, 0xFFFFFFFF);
            cv.restore();
            if (hover == b) tipB = b;
            di++;
        }
        if (hover == micB) tooltip(micOn ? "สั่งด้วยเสียง: เปิดอยู่ (แตะเพื่อปิด)" : "สั่งด้วยเสียง: แตะเพื่อเปิด", micB.r.centerX(), dockR.top - 6 * u, u);
        else if (tipB != null) tooltip(tipB.label.get(), tipB.r.centerX(), dockR.top - 6 * u, u);
        if (!open) return;
        // main panel: dark navy
        RectF pr = new RectF(pX, pY, pX + pW, pY + pH);
        p.setShader(new LinearGradient(0, pr.top, 0, pr.bottom, (ga << 24) | 0x16213D, (ga << 24) | 0x0D1528, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        cv.drawRoundRect(pr, 30 * u, 30 * u, p);
        p.setShader(null);
        NvGlass.ring(cv, p, pr, 0x2EFFFFFF, 1.4f * u, 30 * u);
        NvPanel.grips(cv, p, pr, 30 * u, u);
        // left rail: logo and round category buttons
        RectF logo = new RectF(pX + (railW - 38 * u) / 2f + 4 * u, pY + 18 * u, pX + (railW - 38 * u) / 2f + 4 * u + 38 * u, pY + 18 * u + 38 * u);
        p.setShader(new LinearGradient(0, logo.top, logo.right, logo.bottom, 0xFF6AB0FF, 0xFFA06BFF, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        cv.drawOval(logo, p);
        p.setShader(null);
        NvGlass.font(p, true);
        raw("P", logo.centerX(), logo.centerY(), 22 * u, 0xFFFFFFFF, Paint.Align.CENTER);
        NvGlass.fill(cv, p, new RectF(pX + railW + 3 * u, pY + 24 * u, pX + railW + 4.2f * u, pY + pH - 24 * u), 0x1CFFFFFF, 1);
        Btn tabTip = null;
        for (int i = 0; i < tabs.size(); i++) {
            Btn t = tabs.get(i).b;
            boolean act = i == tab;
            ease(t);
            float sc = 1f + 0.06f * t.ah - 0.08f * t.ap;
            cv.save();
            cv.scale(sc, sc, t.r.centerX(), t.r.centerY());
            NvGlass.fill(cv, p, t.r, act ? 0x44FFFFFF : NvGlass.lerp(0x00FFFFFF, 0x30FFFFFF, t.ah), t.r.width() / 2f);
            if (act) NvGlass.ring(cv, p, t.r, 0x99FFFFFF, 1.6f * u, t.r.width() / 2f);
            glyph(t, t.r.centerX(), t.r.centerY(), 21 * u, 0xFFFFFFFF);
            cv.restore();
            if (hover == t) tabTip = t;
        }
        // top bar: category name and how many cards, search field
        Tab ct = tabs.get(Math.min(tab, tabs.size() - 1));
        List<Btn> list = shown(ct);
        float tx = pX + railW + 22 * u;
        NvGlass.font(p, true);
        textA(ct.title, tx, pY + 37 * u, 25 * u, NvGlass.TEXT, searchB.r.left - tx - 90 * u, Paint.Align.LEFT);
        p.setTextSize(25 * u);
        float tl = Math.min(p.measureText(Tr.t(ct.title)), searchB.r.left - tx - 90 * u);
        NvGlass.font(p, false);
        textA("(" + list.size() + ")", tx + tl + 12 * u, pY + 38 * u, 16 * u, NvGlass.TEXT2, 0, Paint.Align.LEFT);
        ease(searchB);
        NvGlass.fill(cv, p, searchB.r, NvGlass.lerp(0x1FFFFFFF, 0x38FFFFFF, searchB.ah), searchB.r.height() / 2);
        NvGlass.ring(cv, p, searchB.r, filter.isEmpty() ? 0x26FFFFFF : 0x99FFFFFF, 1.2f * u, searchB.r.height() / 2);
        // magnifier
        p.setShader(null); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.9f * u); p.setColor(0xCCFFFFFF);
        float mx = searchB.r.left + 22 * u, my = searchB.r.centerY() - 1 * u;
        cv.drawCircle(mx, my, 6.5f * u, p);
        cv.drawLine(mx + 4.8f * u, my + 4.8f * u, mx + 10 * u, my + 10 * u, p);
        p.setStyle(Paint.Style.FILL);
        NvGlass.font(p, false);
        float rightPad = filter.isEmpty() ? 14 * u : 42 * u;
        textA(filter.isEmpty() ? "ค้นหา" : filter, searchB.r.left + 40 * u, searchB.r.centerY(), 17 * u, filter.isEmpty() ? NvGlass.TEXT2 : NvGlass.TEXT, searchB.r.width() - 40 * u - rightPad, Paint.Align.LEFT);
        if (!filter.isEmpty()) {
            ease(clearB);
            NvGlass.fill(cv, p, clearB.r, NvGlass.lerp(0x33FFFFFF, 0x77FFFFFF, clearB.ah), clearB.r.height() / 2);
            text("✕", clearB.r.centerX(), clearB.r.centerY(), 14 * u, NvGlass.TEXT, 0);
        }
        // the tiles (cut off at the edges of the list, so it can be scrolled)
        cv.save();
        cv.clipRect(cRect);
        int idx = 0;
        for (Btn b : list) {
            idx++;
            if (b.r.isEmpty()) continue;
            boolean on = b.on != null && b.on.get();
            RectF r = b.r;
            float tw = r.width(), th = tw * 0.66f;
            RectF tile = new RectF(r.left, r.top, r.right, r.top + th);
            ease(b);
            float sc = 1f + 0.035f * b.ah - 0.05f * b.ap;
            cv.save();
            cv.scale(sc, sc, tile.centerX(), tile.centerY());
            int[] col = tileColors(b, idx);
            int c1 = b.icon != null ? 0xFF2B3556 : col[0], c2 = b.icon != null ? 0xFF1A2240 : col[1];
            p.setShader(new LinearGradient(0, tile.top, 0, tile.bottom, NvGlass.lerp(c1, 0xFFFFFFFF, 0.10f * b.ah), c2, Shader.TileMode.CLAMP));
            p.setStyle(Paint.Style.FILL);
            cv.drawRoundRect(tile, 20 * u, 20 * u, p);
            p.setShader(new LinearGradient(0, tile.top, 0, tile.top + th * 0.5f, 0x2AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            cv.drawRoundRect(tile, 20 * u, 20 * u, p);
            p.setShader(null);
            if (b.ah > 0.02f || b.ap > 0.02f) NvGlass.ring(cv, p, tile, NvGlass.lerp(0x00FFFFFF, 0xFFFFFFFF, b.ah), 2.6f * u, 20 * u);
            if (b.icon != null) {
                float s2 = th * 0.66f;
                cv.drawBitmap(b.icon, null, new RectF(tile.centerX() - s2 / 2, tile.centerY() - s2 / 2, tile.centerX() + s2 / 2, tile.centerY() + s2 / 2), p);
            } else glyph(b, tile.centerX(), tile.centerY(), th * 0.46f, 0xFFFFFFFF);
            if (on) {                                                  // switched on: a small green tick in the corner
                RectF dot = new RectF(tile.right - 30 * u, tile.top + 8 * u, tile.right - 8 * u, tile.top + 30 * u);
                p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(NvGlass.GREEN);
                cv.drawOval(dot, p);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.2f * u); p.setColor(0xFFFFFFFF);
                cv.drawLine(dot.left + 6 * u, dot.centerY(), dot.left + 10 * u, dot.centerY() + 4 * u, p);
                cv.drawLine(dot.left + 10 * u, dot.centerY() + 4 * u, dot.right - 5.5f * u, dot.top + 6 * u, p);
                p.setStyle(Paint.Style.FILL);
            }
            cv.restore();
            NvGlass.font(p, true);
            text(b.label.get(), tile.centerX(), tile.bottom + 15 * u, 15 * u, NvGlass.TEXT, tw - 6 * u);
            if (b.sub != null) { NvGlass.font(p, false); text(b.sub.get(), tile.centerX(), tile.bottom + 33 * u, 12 * u, 0xFF9DB4DD, tw - 4 * u); }
        }
        cv.restore();
        if (list.isEmpty()) { NvGlass.font(p, false); text(filter.isEmpty() ? "ยังไม่มีรายการ" : "ไม่พบ \"" + filter + "\"", cRect.centerX(), cRect.centerY(), 20 * u, NvGlass.TEXT2, cRect.width() - 40 * u); }
        if (maxScroll > 0f) {                                       // a thin bar shows where you are in a long list
            float th2 = Math.max(36 * u, cRect.height() * cRect.height() / (cRect.height() + maxScroll));
            float ty = cRect.top + (cRect.height() - th2) * (scroll / maxScroll);
            NvGlass.fill(cv, p, new RectF(cRect.right + 6 * u, ty, cRect.right + 10 * u, ty + th2), scrolling ? 0xFFFFFFFF : 0x80FFFFFF, 3 * u);
        }
        if (tabTip != null) {                                       // the name of the category the pointer is on
            NvGlass.font(p, true);
            String nm = Tr.t(tabTip.label.get());
            p.setTextSize(15 * u);
            float w = p.measureText(nm) + 26 * u;
            RectF tr = new RectF(tabTip.r.right + 10 * u, tabTip.r.centerY() - 15 * u, tabTip.r.right + 10 * u + w, tabTip.r.centerY() + 15 * u);
            NvGlass.fill(cv, p, tr, 0xEE0E1426, 15 * u);
            NvGlass.ring(cv, p, tr, 0x33FFFFFF, 1f * u, 15 * u);
            text(tabTip.label.get(), tr.centerX(), tr.centerY(), 15 * u, NvGlass.TEXT, w - 12 * u);
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
