package com.pocketvr.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.ArrayList;
import java.util.List;

/** A floating keyboard window: English, Thai (Kedmanee) and symbols. Pinch on a key to type. */
public class NvKeyboard implements NvContent {
    public interface Sink { void onText(String s); void onBackspace(); void onEnter(String all); }
    public interface Copier { void copy(String s); }

    public Sink sink;               // where typed text goes; null = only the text line of this window
    public Copier copier;
    public Runnable onClose;

    private static final int W = 1000, H = 424;
    private static final int CHAR = 0, SHIFT = 1, BACK = 2, SPACE = 3, ENTER = 4, LANG = 5, SYM = 6, COPY = 7, CLOSE = 8, CLEAR = 9;

    private static class Key {
        int type; String value; String label; float weight = 1f; final RectF r = new RectF();
    }

    private final Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Canvas cv = new Canvas(bmp);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Key> keys = new ArrayList<>();
    private final RectF field = new RectF(14, 8, W - 14 - 2 * 74 - 2 * 8 - 74 - 8, 64);
    private final RectF handle = new RectF(W / 2f - 110, H - 34, W / 2f + 110, H - 4);

    private final StringBuilder text = new StringBuilder();
    private int lang = 0;               // 0 English, 1 Thai, 2 symbols
    private boolean shift = false;
    private boolean dirty = true, dragging = false, wasPinch = false;
    private Key hover = null, lastH = null, pressed = null;
    private boolean hoverHandle = false, lastHandle = false;
    private long lastHT = 0, pressT = 0, holdT0 = 0, lastRep = 0, lastAct = 0, toastUntil = 0;
    private String toast = "";

    public NvKeyboard() { layout(); }

    public String text() { return text.toString(); }
    public void clear() { text.setLength(0); dirty = true; }

    // ---------- layout ----------
    private static final String[][] EN = {
            {"1234567890", "!@#$%^&*()"}, {"qwertyuiop", "QWERTYUIOP"}, {"asdfghjkl", "ASDFGHJKL"}, {"zxcvbnm", "ZXCVBNM"}};
    private static final String[][] TH = {
            {"ๅ/-ภถุึคตจขช", "+๑๒๓๔ู฿๕๖๗๘๙"}, {"ๆไำพะัีรนยบลฃ", "๐\"ฎฑธํ๊ณฯญฐ,ฅ"}, {"ฟหกดเ้่าสวง", "ฤฆฏโฌ็๋ษศซ."}, {"ผปแอิืทมใฝ", "()ฉฮฺ์?ฒฬฦ"}};
    private static final String[][] SY = {
            {"1234567890", "1234567890"}, {"@#$%&-+()/", "@#$%&-+()/"}, {"*\"':;!?~,.", "*\"':;!?~,."}, {"_=[]{}<>", "_=[]{}<>"}};

    private Key mk(int type, String value, String label, float w) {
        Key k = new Key(); k.type = type; k.value = value; k.label = label; k.weight = w; return k;
    }

    private void layout() {
        keys.clear();
        String[][] set = lang == 0 ? EN : lang == 1 ? TH : SY;
        int si = shift ? 1 : 0;
        float top = 78, rowH = 56, gap = 6;
        for (int r = 0; r < 4; r++) {
            String s = set[r][si];
            List<Key> row = new ArrayList<>();
            if (r == 3) row.add(mk(SHIFT, "", "⇧", 1.5f));
            for (int i = 0; i < s.length(); i++) {
                String ch = s.substring(i, i + 1);
                boolean comb = Character.getType(ch.charAt(0)) == Character.NON_SPACING_MARK;
                row.add(mk(CHAR, ch, comb ? "◌" + ch : ch, 1f));
            }
            if (r == 3) row.add(mk(BACK, "", "⌫", 1.5f));
            place(row, top + r * (rowH + gap), rowH);
        }
        List<Key> bot = new ArrayList<>();
        bot.add(mk(SYM, "", lang == 2 ? "ABC" : "123", 1.5f));
        bot.add(mk(LANG, "", lang == 1 ? "ไทย" : "EN", 1.5f));
        bot.add(mk(SPACE, " ", "", 5f));
        bot.add(mk(CHAR, ".", ".", 1f));
        bot.add(mk(ENTER, "", "⏎", 1.7f));
        place(bot, top + 4 * (rowH + gap), rowH);
        // top row: copy / clear / close next to the text line
        float x = W - 14 - 74;
        Key c = mk(CLOSE, "", "✕", 1f); c.r.set(x, 8, x + 74, 64); keys.add(c);
        x -= 82; Key cl = mk(CLEAR, "", "🗑", 1f); cl.r.set(x, 8, x + 74, 64); keys.add(cl);
        x -= 82; Key cp = mk(COPY, "", "📋", 1f); cp.r.set(x, 8, x + 74, 64); keys.add(cp);
        dirty = true;
    }

    private void place(List<Key> row, float y, float h) {
        float total = 0;
        for (Key k : row) total += k.weight;
        float gap = 6, avail = W - 28 - gap * (row.size() - 1);
        float x = 14;
        for (Key k : row) {
            float w = avail * k.weight / total;
            k.r.set(x, y, x + w, y + h);
            x += w + gap;
            keys.add(k);
        }
    }

    // ---------- typing ----------
    private void press(Key k) {
        long now = System.currentTimeMillis();
        pressed = k; pressT = now; dirty = true;
        switch (k.type) {
            case CHAR: case SPACE:
                if (sink != null) sink.onText(k.value); else text.append(k.value);
                if (shift && k.type == CHAR) { shift = false; layout(); }
                break;
            case SHIFT: shift = !shift; layout(); break;
            case SYM: lang = lang == 2 ? 0 : 2; shift = false; layout(); break;
            case LANG: lang = lang == 1 ? 0 : 1; shift = false; layout(); break;
            case BACK:
                if (sink != null) sink.onBackspace();
                else if (text.length() > 0) text.setLength(text.offsetByCodePoints(text.length(), -1));
                break;
            case ENTER:
                if (sink != null) sink.onEnter(text.toString()); else text.append('\n');
                break;
            case COPY:
                if (copier != null) { copier.copy(text.toString()); toast = "คัดลอกแล้ว"; toastUntil = now + 1500; }
                break;
            case CLEAR: text.setLength(0); break;
            case CLOSE: if (onClose != null) onClose.run(); break;
            default: break;
        }
    }

    // ---------- NvContent ----------
    @Override public Bitmap bitmap() { return bmp; }
    @Override public void markDirty() { dirty = true; }
    @Override public void setDragging(boolean d) { if (d != dragging) { dragging = d; dirty = true; } }
    @Override public boolean covers(float x, float y) { return true; }
    @Override public boolean inGrip(float x, float y) { return y > H * 0.93f && (x < W * 0.12f || x > W * 0.88f); }
    @Override public boolean handleRecent() { return hoverHandle || (lastHandle && System.currentTimeMillis() - lastHT < 300); }

    @Override public boolean takeDirty() {
        long now = System.currentTimeMillis();
        if (pressed != null && now - pressT > 140) { pressed = null; dirty = true; }
        if (toastUntil != 0 && now > toastUntil) { toastUntil = 0; dirty = true; }
        if (!dirty) return false;
        dirty = false;
        draw();
        return true;
    }

    @Override public void update(boolean valid, float x, float y, boolean pinch) {
        Key h = null; boolean hh = false;
        if (valid) {
            for (Key k : keys) if (k.r.contains(x, y)) { h = k; break; }
            if (h == null && handle.contains(x, y)) hh = true;
        }
        long now = System.currentTimeMillis();
        if (h != null) { lastH = h; lastHT = now; lastHandle = false; }
        else if (hh) { lastHandle = true; lastHT = now; }
        if (h != hover || hh != hoverHandle) { hover = h; hoverHandle = hh; dirty = true; }
        boolean edge = pinch && !wasPinch;
        wasPinch = pinch;
        Key tgt = h != null ? h : ((lastH != null && !lastHandle && now - lastHT < 250) ? lastH : null);
        if (edge && tgt != null && now - lastAct > 120) {
            lastAct = now; holdT0 = now; lastRep = now;
            press(tgt);
        } else if (pinch && h != null && h.type == BACK && now - holdT0 > 450 && now - lastRep > 90) {
            lastRep = now; press(h);                                       // hold backspace to keep deleting
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

    private void draw() {
        cv.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
        RectF pr = new RectF(2, 2, W - 2, H - 2);
        p.setShader(new LinearGradient(0, 0, 0, H, (NvUi.glass << 24) | 0x2A2C36, (NvUi.glass << 24) | 0x121319, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        cv.drawRoundRect(pr, 30, 30, p);
        p.setShader(null);
        stroke(pr, 0x40FFFFFF, 2f, 30);
        NvPanel.grips(cv, p, pr, 30, 1.4f);

        // text line
        round(field, 0xFF15171D, 14);
        stroke(field, 0x33FFFFFF, 1.5f, 14);
        String shown = text.toString().replace('\n', '↵');
        boolean tip = toastUntil != 0;
        if (tip) text(toast, field.centerX(), field.centerY(), 26, 0xFF7FE3A0, Paint.Align.CENTER, field.width() - 20);
        else if (shown.isEmpty()) text(sink != null ? "พิมพ์ที่ช่องของหน้าต่างที่เลือก" : "จีบที่ปุ่มเพื่อพิมพ์", field.left + 16, field.centerY(), 24, 0x77FFFFFF, Paint.Align.LEFT, field.width() - 30);
        else {
            p.setTextSize(28);
            String s = shown;
            while (p.measureText(s + "|") > field.width() - 28 && s.length() > 1) s = s.substring(s.offsetByCodePoints(0, 1));
            text(s + "|", field.left + 16, field.centerY(), 28, 0xFFFFFFFF, Paint.Align.LEFT, 0);
        }

        for (Key k : keys) {
            boolean special = k.type != CHAR && k.type != SPACE;
            int c1 = special ? 0xFF4A4E5A : 0xFF3F4452, c2 = special ? 0xFF2B2E38 : 0xFF2B2E38;
            if (k.type == SHIFT && shift) { c1 = 0xFF4D8DFF; c2 = 0xFF2E6BFF; }
            if (k.type == CLOSE) { c1 = 0xFF6A3036; c2 = 0xFF4A2024; }
            if (pressed == k) { c1 = 0xFF6FA8FF; c2 = 0xFF4D8DFF; }
            else if (hover == k) { c1 = 0xFF5F6675; c2 = 0xFF454B59; }
            p.setShader(new LinearGradient(0, k.r.top, 0, k.r.bottom, c1, c2, Shader.TileMode.CLAMP));
            p.setStyle(Paint.Style.FILL);
            cv.drawRoundRect(k.r, 14, 14, p);
            p.setShader(null);
            if (hover == k) stroke(k.r, 0xFF22B8CF, 3f, 14);
            if (k.type == SPACE) text(lang == 1 ? "เว้นวรรค" : "space", k.r.centerX(), k.r.centerY(), 20, 0x99FFFFFF, Paint.Align.CENTER, 0);
            else text(k.label, k.r.centerX(), k.r.centerY(), special ? 26 : 30, 0xFFFFFFFF, Paint.Align.CENTER, k.r.width() - 8);
        }
        float hp = 6;
        RectF hb = new RectF(handle.left + hp, handle.centerY() - 5, handle.right - hp, handle.centerY() + 5);
        round(hb, dragging ? 0xFFFFA24D : (hoverHandle ? 0xFF22B8CF : 0xE6FFFFFF), 5);
    }
}
