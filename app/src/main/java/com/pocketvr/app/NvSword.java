package com.pocketvr.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Random;

/**
 * Sword game in a floating window: monsters walk towards you in waves, swipe your fingertip across them to slash them.
 * Slime (1 hit), skeleton (2 hits), ghost (1 hit, drifts from side to side), and a big boss every 5th wave. A monster that reaches you takes a heart.
 */
public class NvSword implements NvContent {
    public Runnable onClose;

    private static final int W = 720, H = 450;
    private static final float HORIZON = 150f, FOOT = H - 100f;      // monsters come from the far line (small) to the front (big)
    private static int best = 0;

    private final Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Canvas cv = new Canvas(bmp);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();
    private final RectF closeR = new RectF(W - 78f, 15f, W - 21f, 72f);
    private final RectF handleR = new RectF(W / 2f - 180f, H - 81f, W / 2f + 180f, H - 6f);
    private final RectF startR = new RectF(W / 2f - 150f, H / 2f + 20f, W / 2f + 150f, H / 2f + 92f);

    private static class Mob { int type; float x0, x, y, t, speed, r, hp, maxHp, flash, cool, wob, ph; boolean boss; }
    private static class Spark { float x, y, vx, vy, r, life; int color; }
    private static class Slash { float x1, y1, x2, y2, life; }
    private static class Pt { float x, y; long t; }

    private final ArrayList<Mob> mobs = new ArrayList<>();
    private final ArrayList<Spark> sparks = new ArrayList<>();
    private final ArrayList<Slash> slashes = new ArrayList<>();
    private final ArrayList<Pt> trail = new ArrayList<>();

    private int state = 0;                                        // 0 = title, 1 = playing, 2 = game over
    private int score = 0, hp = 5, wave = 0, toSpawn = 0, combo = 0;
    private float spawnT = 0f, banner = 0f, hurt = 0f, comboShow = 0f;
    private String bannerText = "", comboText = "";
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
        mobs.clear(); sparks.clear(); slashes.clear(); trail.clear();
        score = 0; hp = 5; wave = 0; combo = 0; state = 1;
        nextWave();
    }

    private void nextWave() {
        wave++;
        boolean boss = wave % 5 == 0;
        toSpawn = boss ? 1 + wave / 5 : 3 + wave * 2;
        spawnT = 1.4f; banner = 1.8f;
        bannerText = boss ? "ด่านบอส! รอบที่ " + wave : "รอบที่ " + wave;
        if (wave > 1 && hp < 5) hp++;                                  // a heart back for each wave cleared
    }

    private void spawn() {
        boolean boss = wave % 5 == 0;
        Mob m = new Mob();
        m.boss = boss;
        float roll = rnd.nextFloat();
        m.type = boss ? 3 : (roll < 0.45f ? 0 : (roll < 0.75f ? 1 : 2));
        if (wave < 2 && m.type == 2) m.type = 0;
        m.x0 = W * (0.2f + 0.6f * rnd.nextFloat());
        m.x = m.x0; m.y = HORIZON; m.t = 0f;
        float k = 1f + 0.06f * wave;
        switch (m.type) {
            case 0: m.maxHp = 1; m.speed = 0.15f * k; m.r = 46f; break;
            case 1: m.maxHp = 2; m.speed = 0.13f * k; m.r = 50f; break;
            case 2: m.maxHp = 1; m.speed = 0.17f * k; m.r = 44f; break;
            default: m.maxHp = 10 + wave * 2; m.speed = 0.07f; m.r = 80f; break;
        }
        m.speed = Math.min(m.speed, 0.4f);
        m.hp = m.maxHp; m.ph = rnd.nextFloat() * 6.28f;
        mobs.add(m);
    }

    private float scaleOf(Mob m) { return 0.35f + 0.95f * m.t; }
    private float radius(Mob m) { return m.r * scaleOf(m); }

    private void burst(float x, float y, int color, int n, float sp) {
        for (int i = 0; i < n; i++) {
            Spark s = new Spark();
            s.x = x; s.y = y; s.color = color;
            float a = rnd.nextFloat() * 6.28f, v = sp * (0.3f + rnd.nextFloat());
            s.vx = (float) Math.cos(a) * v; s.vy = (float) Math.sin(a) * v - 50f;
            s.r = 2.5f + rnd.nextFloat() * 4.5f; s.life = 0.4f + rnd.nextFloat() * 0.5f;
            sparks.add(s);
        }
    }

    private int colorOf(Mob m) { return m.type == 0 ? 0xFF4CD16B : (m.type == 1 ? 0xFFEDE7D6 : (m.type == 2 ? 0xFFBFD8FF : 0xFFB04CD8)); }

    private void hit(Mob m, float dx, float dy, long now) {
        m.hp -= 1; m.flash = 0.25f; m.cool = 0.22f;
        m.t = Math.max(0f, m.t - (m.boss ? 0.015f : 0.07f));          // pushed back a little
        Slash sl = new Slash(); float l = (float) Math.hypot(dx, dy); if (l < 1f) { dx = 1f; l = 1f; }
        sl.x1 = m.x - dx / l * radius(m) * 1.3f; sl.y1 = m.y - dy / l * radius(m) * 1.3f; sl.x2 = m.x + dx / l * radius(m) * 1.3f; sl.y2 = m.y + dy / l * radius(m) * 1.3f; sl.life = 0.25f;
        slashes.add(sl);
        burst(m.x, m.y, colorOf(m), 8, 220f);
        if (m.hp <= 0) {
            burst(m.x, m.y, colorOf(m), m.boss ? 50 : 22, m.boss ? 420f : 300f);
            mobs.remove(m);
            combo++;
            int pts = m.boss ? 25 : (m.type == 1 ? 3 : 2);
            if (combo >= 3) { pts += combo - 2; comboText = "คอมโบ x" + combo; comboShow = 1f; }
            score += pts;
        }
    }

    private void step(float dt) {
        if (hurt > 0f) hurt = Math.max(0f, hurt - dt * 2.5f);
        if (comboShow > 0f) comboShow = Math.max(0f, comboShow - dt);
        if (banner > 0f) banner = Math.max(0f, banner - dt);
        for (int i = sparks.size() - 1; i >= 0; i--) { Spark s = sparks.get(i); s.vy += 700f * dt; s.x += s.vx * dt; s.y += s.vy * dt; s.life -= dt; if (s.life <= 0f) sparks.remove(i); }
        for (int i = slashes.size() - 1; i >= 0; i--) { Slash s = slashes.get(i); s.life -= dt; if (s.life <= 0f) slashes.remove(i); }
        if (state != 1) {
            if (state == 0) { spawnT -= dt; if (mobs.size() < 3 && spawnT <= 0f) { wave = 1; spawn(); spawnT = 1.2f; } }
            moveMobs(dt, false);
            return;
        }
        if (banner <= 0f && toSpawn > 0) { spawnT -= dt; if (spawnT <= 0f) { spawn(); toSpawn--; spawnT = Math.max(0.5f, 1.5f - wave * 0.06f) + rnd.nextFloat() * 0.5f; } }
        moveMobs(dt, true);
        if (state == 1 && toSpawn == 0 && mobs.isEmpty() && banner <= 0f) nextWave();
    }

    private void moveMobs(float dt, boolean playing) {
        for (int i = mobs.size() - 1; i >= 0; i--) {
            Mob m = mobs.get(i);
            m.t += m.speed * dt; m.wob += dt; if (m.flash > 0f) m.flash -= dt; if (m.cool > 0f) m.cool -= dt;
            float ft = Math.min(1f, m.t);
            float sway = m.type == 2 ? (float) Math.sin(m.wob * 2.4f + m.ph) * 110f * (0.3f + ft) : (float) Math.sin(m.wob * 1.3f + m.ph) * 16f * ft;
            m.x = m.x0 + (W / 2f - m.x0) * ft * 0.55f + sway;
            m.y = HORIZON + (FOOT - HORIZON) * ft;
            if (m.t >= 1f) {
                mobs.remove(i);
                if (playing) {
                    hp -= m.boss ? 2 : 1; hurt = 1f; combo = 0;
                    if (hp <= 0) { hp = 0; state = 2; if (score > best) best = score; }
                }
            }
        }
        if (!playing && state == 0) { /* title: the monsters just walk past */ }
    }

    @Override public boolean takeDirty() {
        long now = System.currentTimeMillis();
        if (lastT == 0) lastT = now;
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
    private void eye(Canvas c, float x, float y, float r, int pupil) {
        p.setColor(0xFFFFFFFF); c.drawCircle(x, y, r, p);
        p.setColor(pupil); c.drawCircle(x, y + r * 0.1f, r * 0.5f, p);
    }

    private void drawMob(Canvas c, Mob m) {
        float s = scaleOf(m), r = m.r * s;
        float wob = (float) Math.sin(m.wob * 6f + m.ph);
        c.save(); c.translate(m.x, m.y);
        p.setShader(null); p.setStyle(Paint.Style.FILL);
        p.setColor(0x33000000); c.drawOval(new RectF(-r * 0.9f, r * 0.75f, r * 0.9f, r * 1.05f), p);     // shadow
        int body = m.flash > 0f ? 0xFFFFFFFF : colorOf(m);
        if (m.type == 0) {                                                  // slime
            float sq = 1f + 0.08f * wob;
            RectF b = new RectF(-r * sq, -r * 0.8f / sq, r * sq, r * 0.85f);
            p.setColor(body); c.drawRoundRect(b, r * 0.9f, r * 0.8f, p);
            p.setColor(0x55FFFFFF); c.drawOval(new RectF(-r * 0.55f, -r * 0.6f, -r * 0.1f, -r * 0.25f), p);
            eye(c, -r * 0.32f, -r * 0.05f, r * 0.2f, 0xFF1B1B1F); eye(c, r * 0.32f, -r * 0.05f, r * 0.2f, 0xFF1B1B1F);
            p.setColor(0xFF1B6A33); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(r * 0.07f); c.drawArc(new RectF(-r * 0.25f, r * 0.15f, r * 0.25f, r * 0.5f), 0f, 180f, false, p); p.setStyle(Paint.Style.FILL);
        } else if (m.type == 1) {                                           // skeleton
            p.setColor(body); c.drawCircle(0, -r * 0.3f, r * 0.62f, p);
            c.drawRoundRect(new RectF(-r * 0.4f, r * 0.1f, r * 0.4f, r * 0.45f), r * 0.1f, r * 0.1f, p);
            p.setColor(0xFF2A2A30); c.drawCircle(-r * 0.24f, -r * 0.32f, r * 0.17f, p); c.drawCircle(r * 0.24f, -r * 0.32f, r * 0.17f, p);
            Path nose = new Path(); nose.moveTo(0, -r * 0.18f); nose.lineTo(-r * 0.07f, 0); nose.lineTo(r * 0.07f, 0); nose.close(); c.drawPath(nose, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(r * 0.06f); c.drawLine(-r * 0.3f, r * 0.15f, r * 0.3f, r * 0.15f, p);
            for (int k = -1; k <= 1; k++) c.drawLine(k * r * 0.14f, r * 0.08f, k * r * 0.14f, r * 0.22f, p);
            p.setColor(body); c.drawLine(-r * 0.6f, r * 0.2f, -r * 0.95f, r * 0.7f + r * 0.1f * wob, p); c.drawLine(r * 0.6f, r * 0.2f, r * 0.95f, r * 0.7f - r * 0.1f * wob, p); p.setStyle(Paint.Style.FILL);
            if (m.hp < m.maxHp) { p.setColor(0xFF2A2A30); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(r * 0.05f); c.drawLine(-r * 0.05f, -r * 0.9f, r * 0.1f, -r * 0.55f, p); p.setStyle(Paint.Style.FILL); }
        } else if (m.type == 2) {                                           // ghost
            Path g = new Path();
            g.moveTo(-r * 0.7f, r * 0.8f); g.lineTo(-r * 0.7f, -r * 0.2f); g.quadTo(-r * 0.7f, -r * 0.95f, 0, -r * 0.95f); g.quadTo(r * 0.7f, -r * 0.95f, r * 0.7f, -r * 0.2f); g.lineTo(r * 0.7f, r * 0.8f);
            for (int k = 0; k < 4; k++) g.quadTo(r * (0.7f - 0.35f * (k + 0.5f)), r * (0.55f + 0.12f * wob), r * (0.7f - 0.35f * (k + 1)), r * 0.8f);
            g.close();
            p.setColor((body & 0x00FFFFFF) | 0xCC000000); c.drawPath(g, p);
            p.setColor(0xFF1B1B1F); c.drawOval(new RectF(-r * 0.4f, -r * 0.4f, -r * 0.14f, -r * 0.02f), p); c.drawOval(new RectF(r * 0.14f, -r * 0.4f, r * 0.4f, -r * 0.02f), p);
            c.drawOval(new RectF(-r * 0.12f, r * 0.08f, r * 0.12f, r * 0.38f), p);
        } else {                                                            // boss: a big horned blob
            p.setColor(m.flash > 0f ? 0xFFFFFFFF : 0xFF6B2A9E); c.drawRoundRect(new RectF(-r, -r * 0.8f, r, r * 0.9f), r * 0.8f, r * 0.7f, p);
            p.setColor(0xFFE8E0C8);
            Path h1 = new Path(); h1.moveTo(-r * 0.7f, -r * 0.5f); h1.lineTo(-r * 0.95f, -r * 1.2f); h1.lineTo(-r * 0.3f, -r * 0.75f); h1.close(); c.drawPath(h1, p);
            Path h2 = new Path(); h2.moveTo(r * 0.7f, -r * 0.5f); h2.lineTo(r * 0.95f, -r * 1.2f); h2.lineTo(r * 0.3f, -r * 0.75f); h2.close(); c.drawPath(h2, p);
            p.setColor(0xFFFFD84D); c.drawOval(new RectF(-r * 0.55f, -r * 0.35f, -r * 0.15f, -r * 0.05f), p); c.drawOval(new RectF(r * 0.15f, -r * 0.35f, r * 0.55f, -r * 0.05f), p);
            p.setColor(0xFF1B1B1F); c.drawRect(-r * 0.4f, r * 0.15f, r * 0.4f, r * 0.45f, p);
            p.setColor(0xFFFFFFFF); for (int k = 0; k < 5; k++) c.drawRect(-r * 0.4f + k * r * 0.16f, r * 0.15f, -r * 0.4f + k * r * 0.16f + r * 0.08f, r * 0.3f, p);
        }
        c.restore();
        if (m.maxHp > 1) {                                                  // health bar
            float bw = r * 1.6f;
            p.setColor(0x88000000); cv.drawRect(m.x - bw / 2, m.y - r * 1.25f, m.x + bw / 2, m.y - r * 1.25f + 6f * s + 2f, p);
            p.setColor(m.boss ? 0xFFE5484D : 0xFFFFB84D); cv.drawRect(m.x - bw / 2, m.y - r * 1.25f, m.x - bw / 2 + bw * Math.max(0f, m.hp / m.maxHp), m.y - r * 1.25f + 6f * s + 2f, p);
        }
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
        NvPanel.grips(cv, p, pr, 45f, 1.5f);
        NvGlass.font(p, true);
        cv.save();
        Path clip = new Path(); clip.addRoundRect(pr, 45f, 45f, Path.Direction.CW); cv.clipPath(clip);
        // ground
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(0x33000000); cv.drawRect(0, HORIZON - 10f, W, H, p);
        p.setColor(0x22FFFFFF); cv.drawRect(0, HORIZON - 10f, W, HORIZON - 7f, p);
        ArrayList<Mob> order = new ArrayList<>(mobs);
        order.sort((a, b) -> Float.compare(a.t, b.t));
        for (Mob m : order) drawMob(cv, m);
        for (Slash s : slashes) {                                           // the cut mark across a monster
            p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND);
            float a = Math.min(1f, s.life / 0.25f);
            p.setColor(((int) (255 * a) << 24) | 0x00FFFFFF); p.setStrokeWidth(3f + 7f * a); cv.drawLine(s.x1, s.y1, s.x2, s.y2, p);
        }
        for (Spark s : sparks) { p.setStyle(Paint.Style.FILL); p.setColor((s.color & 0x00FFFFFF) | ((int) (255 * Math.min(1f, s.life * 2.5f)) << 24)); cv.drawCircle(s.x, s.y, s.r, p); }
        if (trail.size() > 1) {                                             // the blade: a bright trail behind the fingertip
            p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 1; i < trail.size(); i++) {
                Pt a = trail.get(i - 1), b = trail.get(i);
                float age = Math.max(0f, 1f - (now - b.t) / 220f);
                if (age <= 0f) continue;
                p.setColor(((int) (230 * age) << 24) | 0x00BFE8FF); p.setStrokeWidth(3f + 9f * age);
                cv.drawLine(a.x, a.y, b.x, b.y, p);
            }
            p.setStyle(Paint.Style.FILL);
        }
        if (hurt > 0f) { p.setStyle(Paint.Style.FILL); p.setColor(((int) (120 * hurt) << 24) | 0x00E5484D); cv.drawRect(0, 0, W, H, p); }
        cv.restore();
        text("⚔ ฟันดาบ", 32, 36, 28f, NvGlass.TEXT, Paint.Align.LEFT);
        if (state != 0) {
            text("คะแนน " + score, W / 2f, 36, 28f, 0xFFFFFFFF, Paint.Align.CENTER);
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < 5; i++) h.append(i < hp ? "♥" : "♡");
            text(h.toString(), W - 100, 36, 26f, 0xFFFF6B7A, Paint.Align.RIGHT);
            text("รอบ " + wave, 32, 76, 22f, 0xCCFFFFFF, Paint.Align.LEFT);
        }
        NvGlass.fill(cv, p, closeR, hover == CLOSE ? 0xFFE5484D : 0x33FFFFFF, closeR.width() / 2);
        text("✕", closeR.centerX(), closeR.centerY(), 25f, NvGlass.TEXT, Paint.Align.CENTER);
        if (state == 1 && banner > 0f) text(bannerText, W / 2f, H / 2f - 30f, 40f, ((int) (255 * Math.min(1f, banner)) << 24) | 0x00FFE27A, Paint.Align.CENTER);
        if (comboShow > 0f) text(comboText, W / 2f, 100, 30f, ((int) (255 * Math.min(1f, comboShow * 2f)) << 24) | 0x00FFE27A, Paint.Align.CENTER);
        if (state == 0 || state == 2) {
            p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(0x77000000);
            cv.drawRoundRect(new RectF(W / 2f - 230, H / 2f - 130, W / 2f + 230, H / 2f + 110), 36f, 36f, p);
            if (state == 0) {
                text("ปัดนิ้วฟันมอนสเตอร์", W / 2f, H / 2f - 82, 30f, 0xFFFFFFFF, Paint.Align.CENTER);
                text("อย่าให้ถึงตัว · เลือด 5 หัวใจ · บอสทุก 5 รอบ", W / 2f, H / 2f - 40, 20f, 0xCCFFFFFF, Paint.Align.CENTER);
                if (best > 0) text("คะแนนสูงสุด " + best, W / 2f, H / 2f - 4, 24f, 0xFFFFE27A, Paint.Align.CENTER);
            } else {
                text("พ่ายแพ้", W / 2f, H / 2f - 90, 36f, 0xFFFF8A8A, Paint.Align.CENTER);
                text("คะแนน " + score + "  (ถึงรอบที่ " + wave + ")", W / 2f, H / 2f - 46, 28f, 0xFFFFFFFF, Paint.Align.CENTER);
                text("คะแนนสูงสุด " + best, W / 2f, H / 2f - 8, 24f, 0xFFFFE27A, Paint.Align.CENTER);
            }
            NvGlass.fill(cv, p, startR, hover == START ? 0xFF4C7CE3 : 0xFF3558B8, 34f);
            text(state == 0 ? "เริ่มต่อสู้" : "สู้อีกครั้ง", startR.centerX(), startR.centerY(), 30f, 0xFFFFFFFF, Paint.Align.CENTER);
        }
        float hp0 = 6f;
        RectF hb = new RectF(handleR.left + hp0 + 100f, handleR.centerY() - 7f, handleR.right - hp0 - 100f, handleR.centerY() + 7f);
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(dragging ? 0xFFFFA24D : (hover == HANDLE ? 0xFFFFFFFF : 0xA6FFFFFF));
        cv.drawRoundRect(hb, 7f, 7f, p);
    }

    // ---- input ----
    @Override public void update(boolean valid, float x, float y, boolean pinch) {
        long now = System.currentTimeMillis();
        Object h = null;
        if (valid) {
            if (closeR.contains(x, y)) h = CLOSE;
            else if (state != 1 && startR.contains(x, y)) h = START;
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
        if (valid && state == 1 && h != HANDLE && h != CLOSE) {
            if (hadPtr && now - lastPT < 120 && lastX >= 0f) {
                float dx = x - lastX, dy = y - lastY, dist = (float) Math.hypot(dx, dy);
                float sp = dist / Math.max(0.008f, (now - lastPT) / 1000f);
                if (sp > 240f && dist > 5f) {
                    for (int i = mobs.size() - 1; i >= 0; i--) {
                        if (i >= mobs.size()) continue;
                        Mob m = mobs.get(i);
                        if (m.cool > 0f) continue;
                        if (segDist(m.x, m.y, lastX, lastY, x, y) < radius(m) + 8f) hit(m, dx, dy, now);
                    }
                }
            }
            Pt t = new Pt(); t.x = x; t.y = y; t.t = now; trail.add(t);
            while (trail.size() > 14 || (trail.size() > 1 && now - trail.get(0).t > 260)) trail.remove(0);
            lastX = x; lastY = y; lastPT = now; hadPtr = true;
            dirty = true;
        } else if (hadPtr && now - lastPT > 150) { hadPtr = false; lastX = -1f; trail.clear(); }
    }

    private static float segDist(float px, float py, float ax, float ay, float bx, float by) {
        float vx = bx - ax, vy = by - ay, wx = px - ax, wy = py - ay;
        float c = vx * vx + vy * vy;
        float t = c < 1e-6f ? 0f : Math.max(0f, Math.min(1f, (wx * vx + wy * vy) / c));
        return (float) Math.hypot(px - (ax + t * vx), py - (ay + t * vy));
    }
}
