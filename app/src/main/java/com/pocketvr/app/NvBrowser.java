package com.pocketvr.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** A web browser as a floating window: toolbar + the page (a WebView on a private virtual display) + grab bar. Pinch = tap, pinch and move = scroll/drag. */
public class NvBrowser implements NvContent {
    public interface KbHost { void showKeyboard(NvKeyboard.Sink s); }

    public KbHost kbHost;
    public Runnable onClose;
    public Runnable onNewWindow;
    public String err = "";

    static final int PW = 1024, PH = 576, TB = 84, W = PW, H = TB + PH + 44;
    private static final int BACK = 0, FWD = 1, RELOAD = 2, NEWTAB = 3, TABS = 4, DESK = 5, KEYB = 6, CLOSE = 7, URL = 8, NEWWIN = 9;

    private static class B { int type; String label; final RectF r = new RectF(); }

    private final Activity act;
    private final Handler ui;
    private final VirtualBrowser vb;
    private final Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Canvas cv = new Canvas(bmp);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<B> btns = new ArrayList<>();
    private final RectF handle = new RectF(W / 2f - 110, H - 36, W / 2f + 110, H - 6);
    private final Object lock = new Object();
    private Bitmap page;
    private volatile boolean frameDirty = false;
    private float sx0, sy0;
    private boolean dirty = true, dragging = false, wasPinch = false;
    private B hover = null, lastH = null;
    private boolean hoverHandle = false, lastHandle = false;
    private long lastHT = 0, lastAct = 0, pressT = 0;
    private B pressed = null;

    // page touch state
    private boolean touching = false, moved = false;
    private float tx0, ty0, tlx, tly;
    private static final int HN = 16;
    private final float[] hx = new float[HN], hy = new float[HN];
    private final long[] ht = new long[HN];
    private int hi = 0;

    // info (filled on the UI thread by poll())
    private volatile String title = "", url = "";
    private volatile int tabCount = 1, tabCur = 0;
    private volatile boolean desktopMode = false;
    private String urlEdit = null;          // not null = the address is being typed
    private boolean opened = false;

    public NvBrowser(Activity a, Handler uiHandler) {
        act = a; ui = uiHandler; vb = new VirtualBrowser(a);
        mk(BACK, "◀", 10); mk(FWD, "▶", 76); mk(RELOAD, "⟳", 142);
        float x = W - 70;
        mkr(CLOSE, "✕", x); x -= 66; mkr(KEYB, "⌨", x); x -= 66; mkr(DESK, "🖥", x); x -= 66; mkr(TABS, "1", x); x -= 66; mkr(NEWTAB, "＋", x); x -= 66; mkr(NEWWIN, "⧉", x);
        B u = new B(); u.type = URL; u.label = ""; u.r.set(212, 10, x - 6, 74); btns.add(u);
    }

    private void mk(int t, String l, float x) { B b = new B(); b.type = t; b.label = l; b.r.set(x, 10, x + 60, 74); btns.add(b); }
    private void mkr(int t, String l, float x) { mk(t, l, x); }

    // ---------- browser control ----------
    public boolean isOpen() { return opened; }

    public void open(final String start) {
        vb.inst = this::onFrame;
        vb.myGap = 50;
        ui.post(() -> {
            String e = vb.open(start, PW, PH);
            if (e != null) { err = e; opened = false; } else { err = ""; opened = true; }
            dirty = true;
        });
    }

    public void setActive(boolean on) { vb.myGap = on ? 50 : 1000; final boolean a = on; ui.post(() -> vb.pause(!a)); }

    public void close() {
        vb.inst = null;
        opened = false;
        ui.post(() -> { try { vb.close(); } catch (Throwable ignored) { } });
        synchronized (lock) { page = null; }
        urlEdit = null; title = ""; url = ""; dirty = true;
    }

    private void onFrame(Bitmap src) {                      // browser picture thread
        synchronized (lock) {
            if (page == null || page.getWidth() != PW || page.getHeight() != PH) page = Bitmap.createBitmap(PW, PH, Bitmap.Config.ARGB_8888);
            new Canvas(page).drawBitmap(src, 0, 0, null);
        }
        frameDirty = true;
    }

    /** UI thread, about twice a second */
    public void poll() {
        if (!opened) return;
        try {
            JSONObject o = vb.tabsInfo();
            int n = o.getJSONArray("tabs").length(), c = o.getInt("cur");
            String t = "", u = vb.url();
            if (n > 0) t = o.getJSONArray("tabs").getJSONObject(c).getString("t");
            if (n != tabCount || c != tabCur || !t.equals(title) || !u.equals(url) || desktopMode != vb.isDesktop()) {
                tabCount = Math.max(1, n); tabCur = c; title = t; url = u; desktopMode = vb.isDesktop(); dirty = true;
            }
        } catch (Throwable ignored) { }
    }

    private void go(String text) {
        String s = text.trim();
        if (s.isEmpty()) return;
        final String target;
        if (s.contains("://")) target = s;
        else if (s.contains(".") && !s.contains(" ")) target = "https://" + s;
        else { String q; try { q = URLEncoder.encode(s, "UTF-8"); } catch (Throwable t) { q = s; } target = "https://www.google.com/search?q=" + q; }
        ui.post(() -> vb.open(target, PW, PH));
    }

    public final NvKeyboard.Sink pageSink = new NvKeyboard.Sink() {
        @Override public void onText(String s) { ui.post(() -> vb.text(s)); }
        @Override public void onBackspace() { ui.post(() -> vb.key("backspace")); }
        @Override public void onEnter(String all) { ui.post(() -> vb.key("enter")); }
    };

    public final NvKeyboard.Sink urlSink = new NvKeyboard.Sink() {
        @Override public void onText(String s) { if (urlEdit == null) urlEdit = ""; urlEdit += s; dirty = true; }
        @Override public void onBackspace() { if (urlEdit != null && !urlEdit.isEmpty()) urlEdit = urlEdit.substring(0, urlEdit.length() - 1); dirty = true; }
        @Override public void onEnter(String all) { String t = urlEdit; urlEdit = null; dirty = true; if (t != null) go(t); if (kbHost != null) kbHost.showKeyboard(pageSink); }
    };

    private void act(B b) {
        switch (b.type) {
            case BACK: ui.post(() -> vb.nav("back")); break;
            case FWD: ui.post(() -> vb.nav("forward")); break;
            case RELOAD: ui.post(() -> vb.nav("reload")); break;
            case NEWTAB: ui.post(() -> vb.newTab("https://www.google.com")); break;
            case TABS: ui.post(() -> { int n = tabCount; if (n > 1) vb.switchTab((tabCur + 1) % n); }); break;
            case DESK: ui.post(() -> vb.setDesktop(!vb.isDesktop())); break;
            case KEYB: if (kbHost != null) kbHost.showKeyboard(pageSink); break;
            case URL: urlEdit = ""; if (kbHost != null) kbHost.showKeyboard(urlSink); break;
            case CLOSE: if (onClose != null) onClose.run(); break;
            case NEWWIN: if (onNewWindow != null) onNewWindow.run(); break;
            default: break;
        }
    }

    // ---------- NvContent ----------
    @Override public Bitmap bitmap() { return bmp; }
    @Override public void markDirty() { dirty = true; }
    @Override public void setDragging(boolean d) { if (d != dragging) { dragging = d; dirty = true; } }
    @Override public boolean covers(float x, float y) { return true; }
    @Override public boolean inGrip(float x, float y) { return y > H * 0.94f && (x < W * 0.10f || x > W * 0.90f); }
    @Override public boolean handleRecent() { return hoverHandle || (lastHandle && System.currentTimeMillis() - lastHT < 300); }

    @Override public boolean takeDirty() {
        long now = System.currentTimeMillis();
        if (pressed != null && now - pressT > 150) { pressed = null; dirty = true; }
        if (!dirty && !frameDirty) return false;
        boolean onlyPage = !dirty && page != null;           // only a new page picture: no need to redraw the toolbar
        dirty = false; frameDirty = false;
        if (onlyPage) {
            synchronized (lock) {
                if (page != null) { cv.save(); cv.clipRect(0, TB, PW, TB + PH); cv.drawBitmap(page, 0, TB, null); cv.restore(); }
            }
            if (touching) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setColor(0x9922B8CF); cv.drawCircle(tlx, tly, 16, p); p.setStyle(Paint.Style.FILL); }
        } else draw();
        return true;
    }

    @Override public void update(boolean valid, float x, float y, boolean pinch) {
        long now = System.currentTimeMillis();
        boolean inPage = valid && y >= TB && y < TB + PH && x >= 0 && x < PW;
        // ---- page touch ----
        boolean edge = pinch && !wasPinch;
        // recent pointer positions: closing the fingers (or pushing the finger) moves the pointer a little, so a tap lands where it was just before
        if (valid && !pinch) { hx[hi] = x; hy[hi] = y; ht[hi] = now; hi = (hi + 1) % HN; }
        if (!touching && edge && inPage) {
            float sx = x, sy = y;
            for (int i = 0; i < HN; i++) {
                int k = (hi + i) % HN;                       // oldest first
                if (ht[k] != 0 && now - ht[k] <= 300 && now - ht[k] >= 70 && Math.hypot(hx[k] - x, hy[k] - y) < 110) { sx = hx[k]; sy = hy[k]; break; }
            }
            touching = true; moved = false; tx0 = tlx = sx; ty0 = tly = sy;
            send(0, sx, sy - TB);
        } else if (touching && pinch) {
            // a click is the default: only a clear, deliberate move (about 1/12 of the page) becomes a scroll; small shakes are ignored
            if (valid && !moved && Math.hypot(x - tx0, y - ty0) > 105) { moved = true; sx0 = x; sy0 = y; }
            if (valid && moved) {                                  // scroll relative to where the move began, so the page does not jump
                tlx = tx0 + (x - sx0); tly = ty0 + (y - sy0);
                send(2, tlx, tly - TB);
            }
        } else if (touching && !pinch) {
            touching = false;
            send(1, moved ? tlx : tx0, (moved ? tly : ty0) - TB);
        }
        // ---- toolbar ----
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

    private void send(final int a, float px, float py) {
        final float fx = Math.max(0f, Math.min(1f, px / PW)), fy = Math.max(0f, Math.min(1f, py / PH));
        ui.post(() -> vb.touch(a, fx, fy));
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

    private static String shortUrl(String u) {
        String s = u == null ? "" : u;
        int i = s.indexOf("://");
        if (i >= 0) s = s.substring(i + 3);
        if (s.startsWith("www.")) s = s.substring(4);
        return s;
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
            if (b.type == URL) {
                round(b.r, 0xFF15171D, 16);
                stroke(b.r, urlEdit != null ? 0xFF22B8CF : (hover == b ? 0xFF9ED8FF : 0x33FFFFFF), urlEdit != null ? 3f : 2f, 16);
                if (urlEdit != null) {
                    String s = urlEdit;
                    p.setTextSize(26);
                    while (p.measureText(s + "|") > b.r.width() - 28 && s.length() > 1) s = s.substring(1);
                    text(s + "|", b.r.left + 14, b.r.centerY(), 26, 0xFFFFFFFF, Paint.Align.LEFT, 0);
                } else {
                    String t = !title.isEmpty() ? title : shortUrl(url);
                    if (!opened && !err.isEmpty()) t = "ผิดพลาด: " + err;
                    else if (t.isEmpty()) t = "จิ้มเพื่อพิมพ์ที่อยู่เว็บหรือคำค้นหา";
                    text(t, b.r.left + 14, b.r.centerY(), 24, title.isEmpty() ? 0x99FFFFFF : 0xFFFFFFFF, Paint.Align.LEFT, b.r.width() - 28);
                }
                continue;
            }
            int c1 = 0xFF454A58, c2 = 0xFF2B2E38;
            if (b.type == CLOSE) { c1 = 0xFF6A3036; c2 = 0xFF4A2024; }
            if (b.type == DESK && desktopMode) { c1 = 0xFF4D8DFF; c2 = 0xFF2E6BFF; }
            if (pressed == b) { c1 = 0xFF6FA8FF; c2 = 0xFF4D8DFF; }
            else if (hover == b) { c1 = 0xFF5F6675; c2 = 0xFF454B59; }
            p.setShader(new LinearGradient(0, b.r.top, 0, b.r.bottom, c1, c2, Shader.TileMode.CLAMP));
            p.setStyle(Paint.Style.FILL);
            cv.drawRoundRect(b.r, 16, 16, p);
            p.setShader(null);
            if (hover == b) stroke(b.r, 0xFF22B8CF, 3f, 16);
            String lab = b.type == TABS ? String.valueOf(tabCount) : b.label;
            text(lab, b.r.centerX(), b.r.centerY(), 28, 0xFFFFFFFF, Paint.Align.CENTER, b.r.width() - 8);
        }

        // the page
        RectF pg = new RectF(0, TB, PW, TB + PH);
        synchronized (lock) {
            if (page != null) cv.drawBitmap(page, 0, TB, null);
            else {
                round(pg, 0xFF101218, 0);
                text(err.isEmpty() ? (opened ? "กำลังโหลด…" : "ยังไม่ได้เปิดเบราว์เซอร์") : "เปิดเบราว์เซอร์ไม่ได้: " + err, PW / 2f, TB + PH / 2f, 30, 0xCCFFFFFF, Paint.Align.CENTER, PW - 60);
            }
        }
        if (touching) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setColor(0x9922B8CF); cv.drawCircle(tlx, tly, 16, p); p.setStyle(Paint.Style.FILL); }

        float hp = 6;
        RectF hb = new RectF(handle.left + hp, handle.centerY() - 5, handle.right - hp, handle.centerY() + 5);
        round(hb, dragging ? 0xFFFFA24D : (hoverHandle ? 0xFF22B8CF : 0xE6FFFFFF), 5);
    }
}
