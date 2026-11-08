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
 * The settings window: categories on the left, the settings of the chosen category on the right.
 * Each setting is a row: a switch, a button that cycles through choices, a  - / +  stepper, or a plain action button.
 */
public class NvSettings implements NvContent {
    public Runnable onClose;

    private static final int W = 1100, H = 660;
    private static final int TOGGLE = 0, CYCLE = 1, STEP = 2, ACTION = 3;
    private static final int ROW_H = 68, ROW_GAP = 8, ROWS_TOP = 84;

    private static class Row {
        int kind; String emoji, label, button;
        NvUi.Txt value; NvUi.Flag on; Runnable tap, minus, plus;
    }
    private static class Cat { String emoji, name; final List<Row> rows = new ArrayList<>(); final RectF r = new RectF(); }
    private static class B { final RectF r = new RectF(); Runnable act; Cat cat; boolean small; float ah = 0f, ap = 0f, kp = -1f; }

    private final Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Canvas cv = new Canvas(bmp);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Cat> cats = new ArrayList<>();
    private final List<B> btns = new ArrayList<>();
    private final RectF handle = new RectF(W / 2f - 120, H - 34, W / 2f + 120, H - 4);
    private final RectF closeR = new RectF(W - 14 - 70, 10, W - 14, 66);
    private int sel = 0;
    private float scroll = 0f, maxScroll = 0f, pressScroll = 0f, pressY = 0f;
    private boolean scrolling = false, pressInContent = false;
    private B pressTgt = null;
    private static final float BAND_BOTTOM = H - 46;

    private boolean dirty = true, dragging = false, wasPinch = false;
    private B hover = null, lastH = null, pressed = null;
    private boolean hoverHandle = false, hoverClose = false, lastHandle = false;
    private long lastHT = 0, pressT = 0, lastAct = 0, lastLive = 0;

    // ---------- building the list of settings ----------
    public void cat(String emoji, String name) { Cat c = new Cat(); c.emoji = emoji; c.name = name; cats.add(c); }
    private Row row(int kind, String emoji, String label) {
        Row r = new Row(); r.kind = kind; r.emoji = emoji; r.label = label;
        cats.get(cats.size() - 1).rows.add(r);
        return r;
    }
    public void toggle(String emoji, String label, NvUi.Flag on, Runnable tap) { Row r = row(TOGGLE, emoji, label); r.on = on; r.tap = tap; }
    public void cycle(String emoji, String label, NvUi.Txt value, Runnable next) { Row r = row(CYCLE, emoji, label); r.value = value; r.tap = next; }
    public void step(String emoji, String label, NvUi.Txt value, Runnable minus, Runnable plus) { Row r = row(STEP, emoji, label); r.value = value; r.minus = minus; r.plus = plus; }
    public void action(String emoji, String label, String button, Runnable tap) { Row r = row(ACTION, emoji, label); r.button = button; r.tap = tap; }
    public void build() { layout(); }

    // ---------- layout ----------
    private B mk(float x, float y, float w, float h, Runnable act) {
        B b = new B(); b.r.set(x, y, x + w, y + h); b.act = act; btns.add(b); return b;
    }

    private void layout() {
        btns.clear();
        float y = ROWS_TOP;
        for (int i = 0; i < cats.size(); i++) {
            final Cat c = cats.get(i);
            c.r.set(14, y, 252, y + 64);
            B b = mk(c.r.left, c.r.top, c.r.width(), c.r.height(), () -> { sel = cats.indexOf(c); scroll = 0f; layout(); });
            b.cat = c;
            y += 72;
        }
        if (sel >= cats.size()) sel = 0;
        if (cats.isEmpty()) { dirty = true; return; }
        Cat cur = cats.get(sel);
        maxScroll = Math.max(0f, cur.rows.size() * (ROW_H + ROW_GAP) - (BAND_BOTTOM - ROWS_TOP));
        scroll = Math.max(0f, Math.min(maxScroll, scroll));
        float ry = ROWS_TOP - scroll, rx = 270, rw = W - 14 - rx;
        for (Row r : cur.rows) {
            float rr = rx + rw - 16;
            switch (r.kind) {
                case TOGGLE: mk(rr - 116, ry + 11, 116, 46, r.tap); break;
                case CYCLE: mk(rr - 330, ry + 9, 330, 50, r.tap); break;
                case STEP: mk(rr - 70, ry + 9, 70, 50, r.plus); mk(rr - 70 - 190 - 8 - 70, ry + 9, 70, 50, r.minus); break;
                default: mk(rr - 250, ry + 9, 250, 50, r.tap); break;
            }
            ry += ROW_H + ROW_GAP;
        }
        dirty = true;
    }

    // ---------- NvContent ----------
    @Override public Bitmap bitmap() { return bmp; }
    @Override public void markDirty() { dirty = true; }
    @Override public void setDragging(boolean d) { if (d != dragging) { dragging = d; dirty = true; } }
    @Override public boolean covers(float x, float y) { return true; }
    @Override public boolean inGrip(float x, float y) { return y > H * 0.93f && (x < W * 0.10f || x > W * 0.90f); }
    @Override public boolean handleRecent() { return hoverHandle || (lastHandle && System.currentTimeMillis() - lastHT < 300); }

    @Override public boolean takeDirty() {
        long now = System.currentTimeMillis();
        if (pressed != null && now - pressT > 150) { pressed = null; dirty = true; }
        if (now - lastLive > 600) { lastLive = now; dirty = true; }          // numbers that change by themselves (walk-around test) stay fresh
        if (!dirty) return false;
        dirty = false;
        draw();
        return true;
    }

    @Override public void update(boolean valid, float x, float y, boolean pinch) {
        B h = null; boolean hh = false, hc = false;
        if (valid) {
            for (B b : btns) {
                if (!b.r.contains(x, y)) continue;
                if (b.cat == null && (y < ROWS_TOP - 6 || y > BAND_BOTTOM)) continue;        // rows scrolled out of the list are not there
                h = b; break;
            }
            if (h == null && closeR.contains(x, y)) hc = true;
            if (h == null && !hc && handle.contains(x, y)) hh = true;
        }
        long now = System.currentTimeMillis();
        if (h != null) { lastH = h; lastHT = now; lastHandle = false; }
        else if (hh) { lastHandle = true; lastHT = now; }
        if (h != hover || hh != hoverHandle || hc != hoverClose) { hover = h; hoverHandle = hh; hoverClose = hc; dirty = true; }
        boolean edge = pinch && !wasPinch;
        boolean released = !pinch && wasPinch;
        wasPinch = pinch;
        if (edge && hc && now - lastAct > 300) { lastAct = now; if (onClose != null) onClose.run(); return; }
        B tgt = h != null ? h : ((lastH != null && !lastHandle && now - lastHT < 250 && btns.contains(lastH)) ? lastH : null);
        if (edge) {
            pressTgt = tgt; pressY = y; pressScroll = scroll; scrolling = false;
            pressInContent = valid && x >= 262 && y >= ROWS_TOP - 6 && y <= BAND_BOTTOM;
        }
        // drag up / down with the fingers closed: scroll the list (the list follows the hand)
        if (pinch && valid && pressInContent && maxScroll > 0f) {
            if (!scrolling && Math.abs(y - pressY) > 30) { scrolling = true; pressTgt = null; }
            if (scrolling) {
                float ns = Math.max(0f, Math.min(maxScroll, pressScroll + (pressY - y)));
                if (Math.abs(ns - scroll) > 0.5f) { scroll = ns; layout(); }
            }
        }
        if (released) {
            B t = pressTgt; pressTgt = null;
            if (!scrolling && t != null && (valid || now - lastHT < 300) && now - lastAct > 250) {
                lastAct = now; pressed = t; pressT = now; dirty = true;
                try { if (t.act != null) t.act.run(); } catch (Throwable ignored) { }
            }
            scrolling = false; pressInContent = false; dirty = true;
        }
        if (!valid && !pinch) pressTgt = null;
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
        s = Tr.t(s);
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(color); p.setTextSize(size); p.setTextAlign(al);
        while (maxW > 0 && p.measureText(s) > maxW && size > 10) { size -= 1; p.setTextSize(size); }
        cv.drawText(s, cx, cy + size * 0.35f, p);
    }

    private boolean animating = false;

    /** glide the pointed-at / pressed amounts of a button; keeps the picture redrawing while something is still moving */
    private void ease(B b) {
        float th = hover == b ? 1f : 0f;
        float tp = (pressTgt == b && wasPinch && !scrolling) ? 1f : (pressed == b ? 1f : 0f);
        b.ah += (th - b.ah) * 0.4f;
        b.ap += (tp - b.ap) * 0.5f;
        if (Math.abs(th - b.ah) > 0.02f || Math.abs(tp - b.ap) > 0.02f) animating = true; else { b.ah = th; b.ap = tp; }
    }

    private void button(B b, String label, boolean active, float size) {
        ease(b);
        RectF r = b.r;
        float rad = r.height() / 2f;
        float sc = 1f + 0.03f * b.ah - 0.06f * b.ap;
        cv.save();
        cv.scale(sc, sc, r.centerX(), r.centerY());
        if (active) NvGlass.fill(cv, p, r, 0xF2FFFFFF, rad);
        else NvGlass.controlA(cv, p, r, rad, 1.3f, false, b.ah, b.ap);
        NvGlass.font(p, true);
        text(label, r.centerX(), r.centerY(), size, active ? 0xFF1C1E26 : NvGlass.TEXT, Paint.Align.CENTER, r.width() - 22);
        cv.restore();
    }

    private B btnAt(float x, float y) { for (B b : btns) if (b.r.contains(x, y)) return b; return null; }

    private void draw() {
        animating = false;
        cv.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
        RectF pr = new RectF(2, 2, W - 2, H - 2);
        NvGlass.panel(cv, p, pr, 36, 1.6f);
        NvPanel.grips(cv, p, pr, 36, 1.4f);
        NvGlass.font(p, true);

        text("ตั้งค่า", 34, 40, 33, NvGlass.TEXT, Paint.Align.LEFT, 400);
        Cat cur = cats.isEmpty() ? null : cats.get(Math.min(sel, cats.size() - 1));
        if (cur != null) text(cur.name, 284, 40, 29, NvGlass.TEXT, Paint.Align.LEFT, 500);
        NvGlass.fill(cv, p, closeR, hoverClose ? 0xFFE5484D : 0x33FFFFFF, closeR.height() / 2);
        if (hoverClose) NvGlass.ring(cv, p, closeR, NvGlass.RING, 2.4f, closeR.height() / 2);
        text("✕", closeR.centerX(), closeR.centerY(), 28, NvGlass.TEXT, Paint.Align.CENTER, 0);
        NvGlass.fill(cv, p, new RectF(262, 74, 263.5f, H - 40), 0x1FFFFFFF, 1);

        // categories: capsules; the chosen one is a brighter piece of glass
        for (Cat c : cats) {
            boolean active = cur == c;
            B cb = null;
            for (B bb : btns) if (bb.cat == c) { cb = bb; break; }
            if (cb != null) ease(cb);
            float ah = cb != null ? cb.ah : 0f, ap = cb != null ? cb.ap : 0f;
            float rad = c.r.height() / 2f;
            float sc = 1f + 0.03f * ah - 0.05f * ap;
            cv.save();
            cv.scale(sc, sc, c.r.centerX(), c.r.centerY());
            if (active || ah > 0.02f || ap > 0.02f) NvGlass.controlA(cv, p, c.r, rad, 1.4f, active, ah, ap);
            NvGlass.font(p, active);
            text(c.name, c.r.left + 28, c.r.centerY(), 25, active ? NvGlass.TEXT : 0xCCFFFFFF, Paint.Align.LEFT, c.r.width() - 44);
            cv.restore();
        }

        // rows
        if (cur != null) {
            float ry = ROWS_TOP - scroll, rx = 270, rw = W - 14 - rx;
            int bi = cats.size();
            cv.save();
            cv.clipRect(rx - 6, ROWS_TOP - 6, W - 6, BAND_BOTTOM);                                         // the row buttons come after the category buttons in btns
            for (Row r : cur.rows) {
                RectF rowR = new RectF(rx, ry, rx + rw, ry + ROW_H);
                NvGlass.control(cv, p, rowR, 28, 1.2f, false, false, false);
                NvGlass.font(p, true);
                float labelMax = rw - 36 - 28 - (r.kind == TOGGLE ? 150 : r.kind == CYCLE ? 360 : r.kind == STEP ? 360 : 280);
                text(r.label, rowR.left + 30, rowR.centerY(), 26, 0xFFFFFFFF, Paint.Align.LEFT, Math.max(120, labelMax));
                try {
                    switch (r.kind) {
                        case TOGGLE: {
                            B b = btns.get(bi++);
                            boolean on = r.on != null && r.on.get();
                            boolean hv = hover == b, pr2 = pressed == b;
                            // switch like Apple's: the knob slides, the track turns green; the knob stretches a little while pressed
                            ease(b);
                            float tk = on ? 1f : 0f;
                            if (b.kp < 0f) b.kp = tk;
                            b.kp += (tk - b.kp) * 0.35f;
                            if (Math.abs(tk - b.kp) > 0.02f) animating = true; else b.kp = tk;
                            int track = NvGlass.lerp(hv ? 0x66FFFFFF : 0x4DFFFFFF, NvGlass.GREEN, b.kp);
                            NvGlass.fill(cv, p, b.r, track, 23);
                            float kw = 19 + 6 * b.ap, kc = b.r.left + 24 + (b.r.width() - 48) * b.kp;
                            RectF kr = new RectF(kc - kw, b.r.centerY() - 19, kc + kw, b.r.centerY() + 19);
                            NvGlass.fill(cv, p, kr, 0xFFFFFFFF, 19);
                            if (hv) NvGlass.ring(cv, p, b.r, NvGlass.RING, 2.4f, 23);
                            break;
                        }
                        case CYCLE: {
                            B b = btns.get(bi++);
                            button(b, r.value != null ? r.value.get() : "", false, 25);
                            break;
                        }
                        case STEP: {
                            B plus = btns.get(bi++), minus = btns.get(bi++);
                            button(minus, "−", false, 34);
                            button(plus, "+", false, 34);
                            RectF vr = new RectF(minus.r.right + 4, minus.r.top, plus.r.left - 4, minus.r.bottom);
                            text(r.value != null ? r.value.get() : "", vr.centerX(), vr.centerY(), 25, 0xFFFFFFFF, Paint.Align.CENTER, vr.width());
                            break;
                        }
                        default: {
                            B b = btns.get(bi++);
                            button(b, r.button, false, 25);
                            break;
                        }
                    }
                } catch (Throwable ignored) { }
                ry += ROW_H + ROW_GAP;
            }
            cv.restore();
            if (maxScroll > 0f) {                                       // a thin bar on the right shows where you are in the list
                float tr = BAND_BOTTOM - ROWS_TOP, th = Math.max(40f, tr * tr / (tr + maxScroll));
                float ty = ROWS_TOP + (tr - th) * (scroll / maxScroll);
                round(new RectF(W - 12, ty, W - 7, ty + th), scrolling ? 0xFFFFFFFF : 0x80FFFFFF, 3);
            }
        }
        float hp = 6;
        RectF hb = new RectF(handle.left + hp, handle.centerY() - 5, handle.right - hp, handle.centerY() + 5);
        NvGlass.fill(cv, p, hb, dragging ? 0xFFFFA24D : (hoverHandle ? 0xFFFFFFFF : 0xA6FFFFFF), 5);
        if (animating) dirty = true;
    }
}
