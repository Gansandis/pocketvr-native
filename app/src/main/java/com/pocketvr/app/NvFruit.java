package com.pocketvr.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Random;

/**
 * Fruit slicing game in a floating window. Fruits are thrown up; swipe your fingertip across them to cut them in two.
 * Do not cut the bombs, and do not let fruits fall (3 lives). The fingertip does not have to pinch: it is the speed of the swipe that cuts.
 */
public class NvFruit implements NvContent {
    public Runnable onClose;

    private static final int W = 720, H = 450;
    private static final float GRAV = 950f;
    private static int best = 0;                                  // best score in this run of the app

    private final Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Canvas cv = new Canvas(bmp);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();
    private final RectF closeR = new RectF(W - 52 * 1.5f, 10 * 1.5f, W - 14 * 1.5f, 48 * 1.5f);
    private final RectF handleR = new RectF(W / 2f - 180f, H - 81f, W / 2f + 180f, H - 6f);
    private final RectF startR = new RectF(W / 2f - 150f, H / 2f + 20f, W / 2f + 150f, H / 2f + 92f);

    private static final int[] BODY = {0xFF2E9B4A, 0xFFF28C1E, 0xFFD6302B, 0xFFF2D21E, 0xFF7A3AA8};
    private static final int[] FLESH = {0xFFE8444F, 0xFFFFC35A, 0xFFFFF1C9, 0xFFFFF7A8, 0xFFC890E8};

    private static class Fruit { float x, y, vx, vy, r, ang, vang; int type; boolean bomb; }
    private static class Piece { float x, y, vx, vy, r, ang, vang; int type; int side; float life; }
    private static class Drop { float x, y, vx, vy, r, life; int color; }
    private static class Pt { float x, y; long t; }

    private final ArrayList<Fruit> fruits = new ArrayList<>();
    private final ArrayList<Piece> pieces = new ArrayList<>();
    private final ArrayList<Drop> drops = new ArrayList<>();
    private final ArrayList<Pt> trail = new ArrayList<>();

    private int state = 0;                                        // 0 = title, 1 = playing, 2 = game over
    private int score = 0, lives = 3, combo = 0;
    private float spawnT = 0f, elapsed = 0f, flash = 0f, comboShow = 0f;
    private String comboText = "";
    private long lastT = 0, lastAct = 0, lastHT = 0, lastPT = 0;
    private float lastX = -1, lastY = -1;
    private boolean dirty = true, dragging = false, wasPinch = false, hadPtr = false;
    private Object hover = null, lastH = null;
    private final Object CLOSE = new Object(), HANDLE = new Object(), START = new Object();

    @Override public Bitmap bitmap() { return bmp; }
    @Override public void markDirty() { dirty = true; }
    @Override public void setDragging(boolean d) { if (d != dragging) { dragging = d; dirty = true; } }
    @Override public boolean covers(float x, float y) { return true; }
    @Override public boolean inGrip(float x, float y) { return y > H * 0.78f && (x < W * 0.12f || x > W * 0.88f); }
    @Override public boolean handleRecent() { return hover == HANDLE || (lastH == HANDLE && System.currentTimeMillis() - lastHT < 300); }

    private void start() {
        fruits.clear(); pieces.clear(); drops.clear(); trail.clear();
        score = 0; lives = 3; combo = 0; spawnT = 0.4f; elapsed = 0f; state = 1;
    }

    private void spawn() {
        int n = 1 + (rnd.nextFloat() < Math.min(0.6f, 0.15f + elapsed / 120f) ? 1 : 0) + (rnd.nextFloat() < Math.min(0.35f, elapsed / 200f) ? 1 : 0);
        for (int i = 0; i < n; i++) {
            Fruit f = new Fruit();
            f.bomb = elapsed > 8f && rnd.nextFloat() < Math.min(0.22f, 0.07f + elapsed / 400f);
            f.type = rnd.nextInt(BODY.length);
            f.r = f.bomb ? 30f : 36f + rnd.nextInt(14);
            f.x = W * (0.18f + 0.64f * rnd.nextFloat());
            f.y = H + f.r;
            f.vx = (W / 2f - f.x) * (0.25f + 0.35f * rnd.nextFloat()) + (rnd.nextFloat() - 0.5f) * 90f;
            f.vy = -(720f + 130f * rnd.nextFloat());
            f.ang = rnd.nextFloat() * 6.28f; f.vang = (rnd.nextFloat() - 0.5f) * 6f;
            fruits.add(f);
        }
    }

    private void splash(float x, float y, int color, int n) {
        for (int i = 0; i < n; i++) {
            Drop d = new Drop();
            d.x = x; d.y = y; d.color = color;
            float a = rnd.nextFloat() * 6.28f, s = 80f + rnd.nextFloat() * 260f;
            d.vx = (float) Math.cos(a) * s; d.vy = (float) Math.sin(a) * s - 80f;
            d.r = 2.5f + rnd.nextFloat() * 4f; d.life = 0.5f + rnd.nextFloat() * 0.4f;
            drops.add(d);
        }
    }

    private void cut(Fruit f, float dx, float dy) {
        float a = (float) Math.toDegrees(Math.atan2(dy, dx));
        if (f.bomb) {
            splash(f.x, f.y, 0xFFFFC04D, 26);
            flash = 1f; lives = 0; end();
            return;
        }
        for (int s = -1; s <= 1; s += 2) {
            Piece pc = new Piece();
            pc.x = f.x; pc.y = f.y; pc.r = f.r; pc.type = f.type; pc.side = s;
            pc.ang = a;                                                // the cut line: the halves fly apart across it
            float nx = -dy, ny = dx, nl = (float) Math.hypot(nx, ny); if (nl < 1e-3f) { nx = 0f; ny = 1f; nl = 1f; }
            pc.vx = f.vx * 0.6f + s * nx / nl * 150f; pc.vy = f.vy * 0.4f + s * ny / nl * 150f - 60f;
            pc.vang = s * (60f + rnd.nextFloat() * 90f); pc.life = 1.6f;
            pieces.add(pc);
        }
        splash(f.x, f.y, FLESH[f.type], 14);
        combo++;
        score += 1 + (combo >= 3 ? combo - 2 : 0);
        fruits.remove(f);
    }

    private void end() {
        state = 2;
        if (score > best) best = score;
    }

    private void step(float dt) {
        if (flash > 0f) flash = Math.max(0f, flash - dt * 2.2f);
        if (comboShow > 0f) comboShow = Math.max(0f, comboShow - dt);
        for (int i = fruits.size() - 1; i >= 0; i--) {
            Fruit f = fruits.get(i);
            f.vy += GRAV * dt; f.x += f.vx * dt; f.y += f.vy * dt; f.ang += f.vang * dt;
            if (f.y - f.r > H && f.vy > 0f) {
                fruits.remove(i);
                if (state == 1 && !f.bomb) { lives--; if (lives <= 0) { lives = 0; end(); } }
            }
        }
        for (int i = pieces.size() - 1; i >= 0; i--) {
            Piece q = pieces.get(i);
            q.vy += GRAV * dt; q.x += q.vx * dt; q.y += q.vy * dt; q.ang += q.vang * dt; q.life -= dt;
            if (q.life <= 0f || q.y - q.r > H + 40) pieces.remove(i);
        }
        for (int i = drops.size() - 1; i >= 0; i--) {
            Drop d = drops.get(i);
            d.vy += GRAV * 0.6f * dt; d.x += d.vx * dt; d.y += d.vy * dt; d.life -= dt;
            if (d.life <= 0f) drops.remove(i);
        }
        if (state == 1) {
            elapsed += dt;
            spawnT -= dt;
            if (spawnT <= 0f) { spawn(); spawnT = Math.max(0.55f, 1.35f - elapsed / 70f) + rnd.nextFloat() * 0.35f; }
        } else if (state == 0 && fruits.isEmpty()) {
            spawnT -= dt;
            if (spawnT <= 0f) { spawn(); spawnT = 1.2f; }                    // fruits fly in the background on the title page
        }
    }

    @Override public boolean takeDirty() {
        long now = System.currentTimeMillis();
        boolean active = state != 1 ? (state == 0 || !pieces.isEmpty() || !drops.isEmpty() || !fruits.isEmpty() || flash > 0f) : true;
        if (lastT == 0) lastT = now;
        if (!active && !dirty) { lastT = now; return false; }
        long gap = now - lastT;
        if (gap < 30 && !dirty) return false;
        float dt = Math.min(0.05f, gap / 1000f);
        lastT = now;
        if (!dragging) step(dt);
        dirty = false;
        draw(now);
        return true;
    }

    // ---- drawing ----
    private void fruitBody(Canvas c, int type, float r, boolean half) {
        p.setShader(null); p.setStyle(Paint.Style.FILL);
        p.setColor(BODY[type]);
        c.drawCircle(0, 0, r, p);
        p.setColor(0x33000000);
        c.drawCircle(r * 0.18f, r * 0.2f, r * 0.82f, p);
        p.setColor(BODY[type]);
        c.drawCircle(-r * 0.06f, -r * 0.06f, r * 0.86f, p);
        if (type == 0) {                                                   // melon: darker stripes
            p.setColor(0x55134D24); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(r * 0.14f);
            for (int k = -1; k <= 1; k++) c.drawArc(new RectF(-r * (0.55f + 0.1f * Math.abs(k)) + k * r * 0.35f, -r * 0.95f, r * (0.55f + 0.1f * Math.abs(k)) + k * r * 0.35f, r * 0.95f), 90f, 180f, false, p);
            p.setStyle(Paint.Style.FILL);
        } else if (type == 1) {                                            // orange: little leaf
            p.setColor(0xFF3C9D3A); c.drawOval(new RectF(-r * 0.05f, -r * 1.02f, r * 0.45f, -r * 0.72f), p);
        } else if (type == 2) {                                            // apple: stem
            p.setColor(0xFF5A3A1E); c.drawRect(-r * 0.05f, -r * 1.12f, r * 0.08f, -r * 0.7f, p);
            p.setColor(0xFF3C9D3A); c.drawOval(new RectF(r * 0.08f, -r * 1.1f, r * 0.5f, -r * 0.85f), p);
        } else if (type == 3) {                                            // lemon: pointed ends
            p.setColor(BODY[3]); Path t = new Path(); t.moveTo(r * 0.85f, 0); t.lineTo(r * 1.2f, -r * 0.06f); t.lineTo(r * 0.85f, r * 0.2f); t.close(); c.drawPath(t, p);
        } else {                                                           // grapes: a few small circles
            p.setColor(0x66FFFFFF);
            for (int k = 0; k < 5; k++) c.drawCircle((float) Math.cos(k * 1.2566) * r * 0.5f, (float) Math.sin(k * 1.2566) * r * 0.5f, r * 0.2f, p);
            p.setColor(0xFF3C9D3A); c.drawRect(-r * 0.04f, -r * 1.1f, r * 0.06f, -r * 0.8f, p);
        }
        p.setColor(0x55FFFFFF); c.drawCircle(-r * 0.35f, -r * 0.4f, r * 0.16f, p);      // shine
    }

    private void drawFruit(Canvas c, Fruit f) {
        c.save(); c.translate(f.x, f.y); c.rotate((float) Math.toDegrees(f.ang));
        if (f.bomb) {
            p.setStyle(Paint.Style.FILL); p.setShader(null);
            p.setColor(0xFF1B1B1F); c.drawCircle(0, 0, f.r, p);
            p.setColor(0xFF3A3A42); c.drawCircle(-f.r * 0.3f, -f.r * 0.3f, f.r * 0.3f, p);
            p.setColor(0xFFE5484D); c.drawRect(-f.r * 0.1f, -f.r * 1.2f, f.r * 0.1f, -f.r * 0.85f, p);
            p.setColor(0xFFFFC04D); c.drawCircle(0, -f.r * 1.25f, f.r * 0.14f, p);
            p.setColor(0xFFE5484D); p.setTextAlign(Paint.Align.CENTER); p.setTextSize(f.r * 0.9f);
            NvGlass.font(p, true); c.drawText("!", 0, f.r * 0.3f, p);
        } else fruitBody(c, f.type, f.r, false);
        c.restore();
    }

    private void drawPiece(Canvas c, Piece q) {
        c.save(); c.translate(q.x, q.y); c.rotate(q.ang);
        float a = q.life < 0.5f ? q.life / 0.5f : 1f;
        RectF o = new RectF(-q.r, -q.r, q.r, q.r), in = new RectF(-q.r * 0.82f, -q.r * 0.82f, q.r * 0.82f, q.r * 0.82f);
        float st = q.side > 0 ? 0f : 180f;
        p.setShader(null); p.setStyle(Paint.Style.FILL);
        p.setAlpha((int) (255 * a)); p.setColor((BODY[q.type] & 0x00FFFFFF) | ((int) (255 * a) << 24)); c.drawArc(o, st, 180f, true, p);
        p.setColor((FLESH[q.type] & 0x00FFFFFF) | ((int) (255 * a) << 24)); c.drawArc(in, st, 180f, true, p);
        if (q.type == 0) { p.setColor(((int) (255 * a) << 24) | 0x00222222); for (int k = -2; k <= 2; k++) c.drawCircle(k * q.r * 0.3f, (q.side > 0 ? 1 : -1) * q.r * 0.3f, q.r * 0.05f, p); }
        c.restore();
        p.setAlpha(255);
    }

    private void text(String s, float cx, float cy, float size, int color, Paint.Align al) {
        s = Tr.t(s);
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(color); p.setTextSize(size); p.setTextAlign(al);
        cv.drawText(s, cx, cy + size * 0.35f, p);
    }

    private void draw(long now) {
        cv.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
        RectF pr = new RectF(2, 2, W - 2, H - 2);
        NvGlass.panel(cv, p, pr, 45f, 1.5f);
        PanelGrips.draw(cv, p, pr, 45f, 1.5f);
        NvGlass.font(p, true);
        cv.save();
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(pr, 45f, 45f, android.graphics.Path.Direction.CW);
        cv.clipPath(clip);
        for (Drop d : drops) { p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor((d.color & 0x00FFFFFF) | ((int) (255 * Math.min(1f, d.life * 2f)) << 24)); cv.drawCircle(d.x, d.y, d.r, p); }
        for (Piece q : pieces) drawPiece(cv, q);
        for (Fruit f : fruits) drawFruit(cv, f);
        // the blade: a short bright trail behind the fingertip
        if (trail.size() > 1) {
            p.setShader(null); p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 1; i < trail.size(); i++) {
                Pt a = trail.get(i - 1), b = trail.get(i);
                float age = Math.max(0f, 1f - (now - b.t) / 220f);
                if (age <= 0f) continue;
                p.setColor(((int) (230 * age) << 24) | 0x00FFFFFF); p.setStrokeWidth(3f + 9f * age);
                cv.drawLine(a.x, a.y, b.x, b.y, p);
            }
            p.setStyle(Paint.Style.FILL);
        }
        if (flash > 0f) { p.setColor(((int) (150 * flash) << 24) | 0x00FFFFFF); cv.drawRect(0, 0, W, H, p); }
        cv.restore();
        // top bar
        text("🍉 ฟันผลไม้", 32, 36, 28f, NvGlass.TEXT, Paint.Align.LEFT);
        if (state != 0) {
            text("คะแนน " + score, W / 2f, 36, 30f, 0xFFFFFFFF, Paint.Align.CENTER);
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < 3; i++) h.append(i < lives ? "♥ " : "♡ ");
            text(h.toString(), W - 140, 36, 28f, 0xFFFF6B7A, Paint.Align.RIGHT);
        }
        NvGlass.fill(cv, p, closeR, hover == CLOSE ? 0xFFE5484D : 0x33FFFFFF, closeR.width() / 2);
        text("✕", closeR.centerX(), closeR.centerY(), 25f, NvGlass.TEXT, Paint.Align.CENTER);
        if (comboShow > 0f) text(comboText, W / 2f, 92, 34f, ((int) (255 * Math.min(1f, comboShow * 2f)) << 24) | 0x00FFE27A, Paint.Align.CENTER);
        if (state == 0 || state == 2) {
            p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(0x66000000);
            cv.drawRoundRect(new RectF(W / 2f - 230, H / 2f - 130, W / 2f + 230, H / 2f + 110), 36f, 36f, p);
            if (state == 0) {
                text("ปัดนิ้วฟันผลไม้ให้ขาด", W / 2f, H / 2f - 82, 30f, 0xFFFFFFFF, Paint.Align.CENTER);
                text("อย่าฟันระเบิด · ผลไม้ตก 3 ลูก = จบเกม", W / 2f, H / 2f - 40, 22f, 0xCCFFFFFF, Paint.Align.CENTER);
                if (best > 0) text("คะแนนสูงสุด " + best, W / 2f, H / 2f - 4, 24f, 0xFFFFE27A, Paint.Align.CENTER);
            } else {
                text("จบเกม", W / 2f, H / 2f - 90, 36f, 0xFFFF8A8A, Paint.Align.CENTER);
                text("คะแนน " + score, W / 2f, H / 2f - 46, 32f, 0xFFFFFFFF, Paint.Align.CENTER);
                text("คะแนนสูงสุด " + best, W / 2f, H / 2f - 8, 24f, 0xFFFFE27A, Paint.Align.CENTER);
            }
            NvGlass.fill(cv, p, startR, hover == START ? 0xFF4CC36B : 0xFF2E9B4A, 34f);
            text(state == 0 ? "เริ่มเล่น" : "เล่นอีกครั้ง", startR.centerX(), startR.centerY(), 30f, 0xFFFFFFFF, Paint.Align.CENTER);
        }
        float hp = 6f;
        RectF hb = new RectF(handleR.left + hp + 100f, handleR.centerY() - 7f, handleR.right - hp - 100f, handleR.centerY() + 7f);
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(dragging ? 0xFFFFA24D : (hover == HANDLE ? 0xFFFFFFFF : 0xA6FFFFFF));
        cv.drawRoundRect(hb, 7f, 7f, p);
    }

    /** the two glowing corner arcs (same look as the other windows) */
    private static final class PanelGrips {
        static void draw(Canvas cv, Paint p, RectF pr, float r, float u) { NvPanel.grips(cv, p, pr, r, u); }
    }

    // ---- input ----
    @Override public void update(boolean valid, float x, float y, boolean pinch) {
        long now = System.currentTimeMillis();
        Object h = null;
        if (valid) {
            if (closeR.contains(x, y)) h = CLOSE;
            else if ((state != 1) && startR.contains(x, y)) h = START;
            else if (handleR.contains(x, y)) h = HANDLE;
        }
        if (h != null) { lastH = h; lastHT = now; }
        if (h != hover) { hover = h; dirty = true; }
        boolean edge = pinch && !wasPinch;
        wasPinch = pinch;
        Object tgt = h != null ? h : ((lastH != null && now - lastHT < 250) ? lastH : null);
        if (edge && tgt != null && now - lastAct > 400) {
            if (tgt == CLOSE) { lastAct = now; if (onClose != null) onClose.run(); }
            else if (tgt == START && state != 1) { lastAct = now; start(); }
        }
        // slicing: the fingertip moves fast across a fruit
        if (valid && state == 1 && h != HANDLE && h != CLOSE) {
            if (hadPtr && now - lastPT < 120 && lastX >= 0f) {
                float dx = x - lastX, dy = y - lastY, dist = (float) Math.hypot(dx, dy);
                float sp = dist / Math.max(0.008f, (now - lastPT) / 1000f);
                if (sp > 260f && dist > 5f) {
                    int hits = 0;
                    for (int i = fruits.size() - 1; i >= 0; i--) {
                        Fruit f = fruits.get(i);
                        if (segDist(f.x, f.y, lastX, lastY, x, y) < f.r + 6f) { cut(f, dx, dy); hits++; if (state != 1) break; }
                    }
                    if (hits >= 2) { comboText = hits + " ลูกพร้อมกัน! +" + hits; score += hits; comboShow = 1.1f; }
                    else if (hits == 0 && sp > 260f) combo = 0;
                }
            }
            Pt t = new Pt(); t.x = x; t.y = y; t.t = now; trail.add(t);
            while (trail.size() > 14 || (trail.size() > 1 && now - trail.get(0).t > 260)) trail.remove(0);
            lastX = x; lastY = y; lastPT = now; hadPtr = true;
            dirty = true;
        } else {
            if (hadPtr && now - lastPT > 150) { hadPtr = false; lastX = -1f; trail.clear(); combo = 0; }
        }
    }

    private static float segDist(float px, float py, float ax, float ay, float bx, float by) {
        float vx = bx - ax, vy = by - ay, wx = px - ax, wy = py - ay;
        float c = vx * vx + vy * vy;
        float t = c < 1e-6f ? 0f : Math.max(0f, Math.min(1f, (wx * vx + wy * vy) / c));
        return (float) Math.hypot(px - (ax + t * vx), py - (ay + t * vy));
    }
}
