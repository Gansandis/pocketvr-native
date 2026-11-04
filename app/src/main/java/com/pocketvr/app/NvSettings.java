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
    private static class B { final RectF r = new RectF(); Runnable act; Cat cat; boolean small; }

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
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(color); p.setTextSize(size); p.setTextAlign(al);
        while (maxW > 0 && p.measureText(s) > maxW && size > 10) { size -= 1; p.setTextSize(size); }
        cv.drawText(s, cx, cy + size * 0.35f, p);
    }

    private void button(RectF r, String label, boolean active, boolean isHover, boolean isPressed, float size) {
        int c1 = 0xFF454A58, c2 = 0xFF2B2E38;
        if (active) { c1 = 0xFF4D8DFF; c2 = 0xFF2E6BFF; }
        if (isPressed) { c1 = 0xFF6FA8FF; c2 = 0xFF4D8DFF; }
        else if (isHover) { c1 = 0xFF5F6675; c2 = 0xFF454B59; }
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, c1, c2, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        cv.drawRoundRect(r, 16, 16, p);
        p.setShader(null);
        if (isHover) stroke(r, 0xFF22B8CF, 3f, 16);
        text(label, r.centerX(), r.centerY(), size, 0xFFFFFFFF, Paint.Align.CENTER, r.width() - 14);
    }

    private B btnAt(float x, float y) { for (B b : btns) if (b.r.contains(x, y)) return b; return null; }

    private void draw() {
        cv.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
        RectF pr = new RectF(2, 2, W - 2, H - 2);
        p.setShader(new LinearGradient(0, 0, 0, H, (NvUi.glass << 24) | 0x2A2C36, (NvUi.glass << 24) | 0x121319, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        cv.drawRoundRect(pr, 30, 30, p);
        p.setShader(null);
        stroke(pr, 0x40FFFFFF, 2f, 30);
        NvPanel.grips(cv, p, pr, 30, 1.4f);

        text("⚙  ตั้งค่า", 30, 38, 34, 0xFFFFFFFF, Paint.Align.LEFT, 400);
        Cat cur = cats.isEmpty() ? null : cats.get(Math.min(sel, cats.size() - 1));
        if (cur != null) text(cur.emoji + "  " + cur.name, 270, 38, 28, 0xCCFFFFFF, Paint.Align.LEFT, 500);
        round(closeR, hoverClose ? 0xFF8A3E46 : 0xFF6A3036, 16);
        if (hoverClose) stroke(closeR, 0xFF22B8CF, 3f, 16);
        text("✕", closeR.centerX(), closeR.centerY(), 30, 0xFFFFFFFF, Paint.Align.CENTER, 0);

        // categories
        for (Cat c : cats) {
            boolean active = cur == c;
            boolean hv = hover != null && hover.cat == c;
            int c1 = active ? 0xFF4D8DFF : 0x55454A58, c2 = active ? 0xFF2E6BFF : 0x552B2E38;
            if (!active && hv) { c1 = 0xFF5F6675; c2 = 0xFF454B59; }
            p.setShader(new LinearGradient(0, c.r.top, 0, c.r.bottom, c1, c2, Shader.TileMode.CLAMP));
            p.setStyle(Paint.Style.FILL);
            cv.drawRoundRect(c.r, 18, 18, p);
            p.setShader(null);
            if (hv) stroke(c.r, 0xFF22B8CF, 3f, 18);
            text(c.emoji, c.r.left + 34, c.r.centerY(), 32, 0xFFFFFFFF, Paint.Align.CENTER, 0);
            text(c.name, c.r.left + 70, c.r.centerY(), 26, 0xFFFFFFFF, Paint.Align.LEFT, c.r.width() - 80);
        }

        // rows
        if (cur != null) {
            float ry = ROWS_TOP - scroll, rx = 270, rw = W - 14 - rx;
            int bi = cats.size();
            cv.save();
            cv.clipRect(rx - 6, ROWS_TOP - 6, W - 6, BAND_BOTTOM);                                         // the row buttons come after the category buttons in btns
            for (Row r : cur.rows) {
                RectF rowR = new RectF(rx, ry, rx + rw, ry + ROW_H);
                round(rowR, 0x66101218, 18);
                text(r.emoji, rowR.left + 36, rowR.centerY(), 32, 0xFFFFFFFF, Paint.Align.CENTER, 0);
                float labelMax = rw - 36 - 70 - (r.kind == TOGGLE ? 150 : r.kind == CYCLE ? 360 : r.kind == STEP ? 360 : 280);
                text(r.label, rowR.left + 72, rowR.centerY(), 26, 0xFFFFFFFF, Paint.Align.LEFT, Math.max(120, labelMax));
                try {
                    switch (r.kind) {
                        case TOGGLE: {
                            B b = btns.get(bi++);
                            boolean on = r.on != null && r.on.get();
                            boolean hv = hover == b, pr2 = pressed == b;
                            round(b.r, on ? (pr2 ? 0xFF6FA8FF : 0xFF2E6BFF) : (hv ? 0xFF5F6675 : 0xFF3A3E4A), 23);
                            float kx = on ? b.r.right - 25 : b.r.left + 25;
                            p.setColor(0xFFFFFFFF); cv.drawCircle(kx, b.r.centerY(), 18, p);
                            if (hv) stroke(b.r, 0xFF22B8CF, 3f, 23);
                            break;
                        }
                        case CYCLE: {
                            B b = btns.get(bi++);
                            button(b.r, r.value != null ? r.value.get() : "", false, hover == b, pressed == b, 25);
                            break;
                        }
                        case STEP: {
                            B plus = btns.get(bi++), minus = btns.get(bi++);
                            button(minus.r, "−", false, hover == minus, pressed == minus, 34);
                            button(plus.r, "+", false, hover == plus, pressed == plus, 34);
                            RectF vr = new RectF(minus.r.right + 4, minus.r.top, plus.r.left - 4, minus.r.bottom);
                            text(r.value != null ? r.value.get() : "", vr.centerX(), vr.centerY(), 25, 0xFFFFFFFF, Paint.Align.CENTER, vr.width());
                            break;
                        }
                        default: {
                            B b = btns.get(bi++);
                            button(b.r, r.button, false, hover == b, pressed == b, 25);
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
                round(new RectF(W - 12, ty, W - 7, ty + th), scrolling ? 0xFF22B8CF : 0x88FFFFFF, 3);
            }
        }
        float hp = 6;
        RectF hb = new RectF(handle.left + hp, handle.centerY() - 5, handle.right - hp, handle.centerY() + 5);
        round(hb, dragging ? 0xFFFFA24D : (hoverHandle ? 0xFF22B8CF : 0xE6FFFFFF), 5);
    }
}
