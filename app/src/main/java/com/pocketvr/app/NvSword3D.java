package com.pocketvr.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.opengl.Matrix;

import java.util.ArrayList;
import java.util.Random;

/**
 * Sword game with 3D monsters standing in the room. They walk towards you in waves; swipe your fingertip across one to slash it
 * (the pointer's ray is swept through the room, so a monster is hit when the ray passes over it).
 * This class is both the little score window (a NvContent) and the 3D world drawn by the renderer.
 * Monsters: slime (1 hit), skeleton (2 hits), ghost (1 hit, drifts side to side), a big boss every 5th wave. One that reaches you takes a heart.
 */
public class NvSword3D implements NvContent {
    public Runnable onClose;

    // ================= the score window =================
    private static final int W = 640, H = 240;
    private static int best = 0;
    private final Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Canvas cv = new Canvas(bmp);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF closeR = new RectF(W - 70f, 12f, W - 16f, 66f);
    private final RectF handleR = new RectF(W / 2f - 180f, H - 52f, W / 2f + 180f, H - 6f);
    private final RectF startR = new RectF(W / 2f - 130f, 100f, W / 2f + 130f, 168f);
    private boolean dirty = true, dragging = false, wasPinch = false;
    private long lastDraw = 0, lastAct = 0, lastHT = 0;
    private Object hover = null, lastH = null;
    private final Object CLOSE = new Object(), HANDLE = new Object(), START = new Object();

    @Override public Bitmap bitmap() { return bmp; }
    @Override public void markDirty() { dirty = true; }
    @Override public void setDragging(boolean d) { if (d != dragging) { dragging = d; dirty = true; } }
    @Override public boolean covers(float x, float y) { return true; }
    @Override public boolean inGrip(float x, float y) { return y > H * 0.7f && (x < W * 0.12f || x > W * 0.88f); }
    @Override public boolean handleRecent() { return hover == HANDLE || (lastH == HANDLE && System.currentTimeMillis() - lastHT < 300); }

    @Override public boolean takeDirty() {
        long now = System.currentTimeMillis();
        if (!dirty && !(state == 1 && now - lastDraw > 120)) return false;
        dirty = false; lastDraw = now;
        draw();
        return true;
    }

    private void text(String s, float cx, float cy, float size, int color, Paint.Align al) {
        s = Tr.t(s);
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(color); p.setTextSize(size); p.setTextAlign(al);
        cv.drawText(s, cx, cy + size * 0.35f, p);
    }

    private void draw() {
        cv.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
        RectF pr = new RectF(2, 2, W - 2, H - 2);
        NvGlass.panel(cv, p, pr, 40f, 1.4f);
        NvPanel.grips(cv, p, pr, 40f, 1.4f);
        NvGlass.font(p, true);
        text("⚔ ฟันมอนสเตอร์ 3D", 30, 38, 28f, NvGlass.TEXT, Paint.Align.LEFT);
        NvGlass.fill(cv, p, closeR, hover == CLOSE ? 0xFFE5484D : 0x33FFFFFF, closeR.width() / 2);
        text("✕", closeR.centerX(), closeR.centerY(), 26f, NvGlass.TEXT, Paint.Align.CENTER);
        if (state == 1) {
            text("คะแนน " + score, 30, 104, 34f, 0xFFFFFFFF, Paint.Align.LEFT);
            text("รอบที่ " + wave, 30, 150, 26f, 0xCCFFFFFF, Paint.Align.LEFT);
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < 5; i++) h.append(i < hp ? "♥" : "♡");
            text(h.toString(), W - 40, 104, 40f, 0xFFFF6B7A, Paint.Align.RIGHT);
            if (banner > 0f) text(bannerText, W / 2f + 40, 150, 34f, 0xFFFFE27A, Paint.Align.CENTER);
            else if (comboShow > 0f) text(comboText, W / 2f + 40, 150, 30f, 0xFFFFE27A, Paint.Align.CENTER);
        } else {
            if (state == 0) {
                text("ปัดนิ้วฟันมอนสเตอร์ที่เดินเข้าหาคุณ", W / 2f, 78, 24f, 0xFFFFFFFF, Paint.Align.CENTER);
                if (best > 0) text("คะแนนสูงสุด " + best, W / 2f, 190, 22f, 0xFFFFE27A, Paint.Align.CENTER);
            } else {
                text("พ่ายแพ้  คะแนน " + score + "  (รอบที่ " + wave + ")", W / 2f, 78, 26f, 0xFFFF8A8A, Paint.Align.CENTER);
                text("คะแนนสูงสุด " + best, W / 2f, 190, 22f, 0xFFFFE27A, Paint.Align.CENTER);
            }
            NvGlass.fill(cv, p, startR, hover == START ? 0xFF4C7CE3 : 0xFF3558B8, 32f);
            text(state == 0 ? "เริ่มต่อสู้" : "สู้อีกครั้ง", startR.centerX(), startR.centerY(), 28f, 0xFFFFFFFF, Paint.Align.CENTER);
        }
        NvGlass.fill(cv, p, envR, hover == ENV ? 0x66FFFFFF : 0x33FFFFFF, envR.height() / 2);
        text(envOn ? "ฉาก: เปิด" : "ฉาก: ปิด", envR.centerX(), envR.centerY(), 22f, NvGlass.TEXT, Paint.Align.CENTER);
        RectF hb = new RectF(handleR.left + 100f, handleR.centerY() - 7f, handleR.right - 100f, handleR.centerY() + 7f);
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(dragging ? 0xFFFFA24D : (hover == HANDLE ? 0xFFFFFFFF : 0xA6FFFFFF));
        cv.drawRoundRect(hb, 7f, 7f, p);
    }

    @Override public void update(boolean valid, float x, float y, boolean pinch) {
        long now = System.currentTimeMillis();
        Object h = null;
        if (valid) {
            if (closeR.contains(x, y)) h = CLOSE;
            else if (state != 1 && startR.contains(x, y)) h = START;
            else if (envR.contains(x, y)) h = ENV;
            else if (handleR.contains(x, y)) h = HANDLE;
        }
        if (h != null) { lastH = h; lastHT = now; }
        if (h != hover) { hover = h; dirty = true; }
        boolean edge = pinch && !wasPinch;
        wasPinch = pinch;
        Object tgt = h != null ? h : ((lastH != null && now - lastHT < 250) ? lastH : null);
        if (edge && tgt != null && now - lastAct > 400) {
            if (tgt == CLOSE) { lastAct = now; state = 0; clearAll(); if (onClose != null) onClose.run(); }
            else if (tgt == START && state != 1) { lastAct = now; startReq = true; dirty = true; }
            else if (tgt == ENV) { lastAct = now; envOn = !envOn; dirty = true; }
        }
    }

    // ================= the game =================
    private static class Mob { int type; float az, dist, speed, hp, maxHp, flash, cool, wob, ph, size, rad; boolean boss; }
    private static class Chunk { float x, y, z, vx, vy, vz, rot, vrot, size, life; int color; }

    private final Random rnd = new Random();
    private final ArrayList<Mob> mobs = new ArrayList<>();
    private final ArrayList<Chunk> chunks = new ArrayList<>();
    private volatile int state = 0;                                 // 0 = title, 1 = playing, 2 = game over
    private int score = 0, hp = 5, wave = 0, toSpawn = 0, combo = 0;
    private float spawnT = 0f, banner = 0f, comboShow = 0f;
    private String bannerText = "", comboText = "";
    private boolean startReq = false, envOn = true;
    private int bossLeft = 0;
    private final RectF envR = new RectF(24f, H - 58f, 220f, H - 10f);
    private final Object ENV = new Object();
    /** the room is swapped for a night scene while a game is going (the button in the score window turns it off to see the real room) */
    public boolean envActive() { return envOn && state != 0; }
    private float ox, oy, oz, heading = 0f;                         // where the game is centred (where you stood) and which way was ahead
    private float ex, ey, ez;                                       // where your eyes are now
    private long lastT = 0;

    private void clearAll() { mobs.clear(); chunks.clear(); }

    private void nextWave() {
        wave++;
        boolean boss = wave % 5 == 0;
        bossLeft = boss ? 1 + wave / 5 : 0;
        toSpawn = boss ? bossLeft + 4 + wave / 2 : 4 + wave * 3;
        spawnT = 1.6f; banner = 2.0f;
        bannerText = boss ? "ด่านบอส! รอบที่ " + wave : "รอบที่ " + wave;
        if (wave > 1 && hp < 5) hp++;                               // a heart back for each wave cleared
    }

    private void spawn() {
        boolean boss = bossLeft > 0;
        if (boss) bossLeft--;
        Mob m = new Mob();
        m.boss = boss;
        if (boss) m.type = 3;
        else {
            int[] pool = {0, 0, 0, 1, 1, 2, 2, 4, 4, 4, 5, 5, 6};
            int n = wave < 2 ? 5 : (wave < 3 ? 7 : (wave < 4 ? 10 : (wave < 6 ? 12 : 13)));
            m.type = pool[rnd.nextInt(n)];
        }
        m.az = heading + (float) Math.toRadians(-62 + 124 * rnd.nextFloat());
        m.dist = 6.5f;
        float k = Math.min(2f, 1f + 0.05f * wave);
        switch (m.type) {
            case 0: m.maxHp = 1; m.speed = 0.55f * k; m.size = 1f; m.rad = 0.5f; break;
            case 1: m.maxHp = 2; m.speed = 0.50f * k; m.size = 1f; m.rad = 0.7f; break;
            case 2: m.maxHp = 1; m.speed = 0.62f * k; m.size = 1f; m.rad = 0.55f; break;
            case 4: m.maxHp = 1; m.speed = 1.0f * k; m.size = 0.9f; m.rad = 0.42f; break;
            case 5: m.maxHp = 2; m.speed = 0.55f * k; m.size = 1f; m.rad = 0.55f; break;
            case 6: m.maxHp = 4; m.speed = 0.34f * k; m.size = 1.5f; m.rad = 0.95f; break;
            default: m.maxHp = 10 + wave * 2; m.speed = 0.28f; m.size = 2.1f; m.rad = 1.3f; break;
        }
        m.hp = m.maxHp; m.ph = rnd.nextFloat() * 6.28f;
        mobs.add(m);
    }

    private float azOf(Mob m) { return m.az + (m.type == 2 ? (float) Math.sin(m.wob * 2.2f + m.ph) * 0.35f * Math.min(1f, m.dist / 4f) : (m.type == 4 ? (float) Math.sin(m.wob * 3.1f + m.ph) * 0.45f * Math.min(1f, m.dist / 3f) : 0f)); }
    private float zOf(Mob m) { return oz - 1.15f + (m.type == 2 ? 0.85f + 0.12f * (float) Math.sin(m.wob * 2f + m.ph) : (m.type == 4 ? 1.1f + 0.35f * (float) Math.sin(m.wob * 3f + m.ph) : (m.type == 5 ? 0.35f * Math.abs((float) Math.sin(m.wob * 4f + m.ph)) : 0f))); }
    private float xOf(Mob m) { return ox + m.dist * (float) Math.cos(azOf(m)); }
    private float yOf(Mob m) { return oy + m.dist * (float) Math.sin(azOf(m)); }

    private int colorOf(Mob m) { return m.type == 0 ? 0xFF4CD16B : (m.type == 1 ? 0xFFEDE7D6 : (m.type == 2 ? 0xFFBFD8FF : (m.type == 4 ? 0xFF6A4C93 : (m.type == 5 ? 0xFFF28C1E : (m.type == 6 ? 0xFF8A8F99 : 0xFFB04CD8))))); }

    private void debris(float x, float y, float z, int color, int n, float sp, float size) {
        for (int i = 0; i < n; i++) {
            Chunk c = new Chunk();
            c.x = x; c.y = y; c.z = z; c.color = color; c.size = size * (0.5f + rnd.nextFloat());
            float a = rnd.nextFloat() * 6.28f, v = sp * (0.4f + rnd.nextFloat());
            c.vx = (float) Math.cos(a) * v; c.vy = (float) Math.sin(a) * v; c.vz = 1.2f + rnd.nextFloat() * 2f;
            c.rot = rnd.nextFloat() * 360f; c.vrot = (rnd.nextFloat() - 0.5f) * 600f; c.life = 0.9f + rnd.nextFloat() * 0.6f;
            chunks.add(c);
        }
    }

    private void hit(Mob m) {
        m.hp -= 1; m.flash = 0.25f; m.cool = 0.25f;
        m.dist += m.boss ? 0.15f : 0.5f;                            // knocked back a little
        float x = xOf(m), y = yOf(m), z = zOf(m) + 0.7f * m.size;
        if (m.hp <= 0) {
            debris(x, y, z, colorOf(m), m.boss ? 40 : 16, m.boss ? 3.5f : 2.5f, m.boss ? 0.16f : 0.1f);
            mobs.remove(m);
            combo++;
            int pts = m.boss ? 25 : (m.type == 1 ? 3 : 2);
            if (combo >= 3) { pts += combo - 2; comboText = "คอมโบ x" + combo; comboShow = 1f; dirty = true; }
            score += pts; dirty = true;
        } else debris(x, y, z, 0xFFFFFFFF, 5, 2f, 0.05f);
    }

    private void step(float dt) {
        if (comboShow > 0f) comboShow = Math.max(0f, comboShow - dt);
        if (banner > 0f) { banner = Math.max(0f, banner - dt); }
        for (int i = chunks.size() - 1; i >= 0; i--) {
            Chunk c = chunks.get(i);
            c.vz -= 7f * dt; c.x += c.vx * dt; c.y += c.vy * dt; c.z += c.vz * dt; c.rot += c.vrot * dt; c.life -= dt;
            if (c.life <= 0f) chunks.remove(i);
        }
        if (state != 1) return;
        if (banner <= 0f && toSpawn > 0) {
            spawnT -= dt;
            if (spawnT <= 0f) { spawn(); toSpawn--; spawnT = Math.max(0.7f, 1.8f - wave * 0.07f) + rnd.nextFloat() * 0.6f; }
        }
        for (int i = mobs.size() - 1; i >= 0; i--) {
            Mob m = mobs.get(i);
            m.dist -= m.speed * dt; m.wob += dt;
            if (m.flash > 0f) m.flash -= dt;
            if (m.cool > 0f) m.cool -= dt;
            if (m.dist < 1.0f) {                                    // it reached you
                mobs.remove(i);
                hp -= m.boss ? 2 : 1; combo = 0; dirty = true;
                if (hp <= 0) { hp = 0; state = 2; if (score > best) best = score; mobs.clear(); }
            }
        }
        if (state == 1 && toSpawn == 0 && mobs.isEmpty() && banner <= 0f) nextWave();
    }

    // ---- the ray of the fingertip, swept through the room ----
    private final float[] prevD = new float[3];
    private boolean hasPrev = false;
    private long prevT = 0;

    /** the pointer's direction in the room (unit vector, Z up); valid = the hand is there */
    public void pointer(boolean valid, float wx, float wy, float wz, long nowMs) {
        if (!valid) { hasPrev = false; return; }
        float l = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (l < 1e-4f) return;
        wx /= l; wy /= l; wz /= l;
        if (hasPrev && state == 1) {
            float dt = (nowMs - prevT) / 1000f;
            float dot = Math.max(-1f, Math.min(1f, prevD[0] * wx + prevD[1] * wy + prevD[2] * wz));
            float ang = (float) Math.toDegrees(Math.acos(dot));
            if (dt > 0.001f && dt < 0.15f && ang > 0.3f && ang / dt > 35f) {
                float bx = wx - prevD[0], by = wy - prevD[1], bz = wz - prevD[2], bb = bx * bx + by * by + bz * bz;
                for (int i = mobs.size() - 1; i >= 0; i--) {
                    if (i >= mobs.size()) continue;
                    Mob m = mobs.get(i);
                    if (m.cool > 0f) continue;
                    float cx = xOf(m) - ex, cy = yOf(m) - ey, cz = zOf(m) + 0.7f * m.size - ez;
                    float D = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
                    if (D < 0.2f) continue;
                    cx /= D; cy /= D; cz /= D;
                    float t = bb < 1e-9f ? 0f : Math.max(0f, Math.min(1f, ((cx - prevD[0]) * bx + (cy - prevD[1]) * by + (cz - prevD[2]) * bz) / bb));
                    float qx = prevD[0] + bx * t, qy = prevD[1] + by * t, qz = prevD[2] + bz * t, ql = (float) Math.sqrt(qx * qx + qy * qy + qz * qz);
                    float a = (float) Math.acos(Math.max(-1f, Math.min(1f, (cx * qx + cy * qy + cz * qz) / Math.max(1e-4f, ql))));
                    float rho = (float) Math.asin(Math.min(1f, m.rad / D));
                    if (a < rho + 0.03f) hit(m);
                }
            }
        }
        prevD[0] = wx; prevD[1] = wy; prevD[2] = wz; prevT = nowMs; hasPrev = true;
    }

    // ================= drawing the monsters =================
    private final Nv3D g3 = new Nv3D();
    private final float[] vp = new float[16], mon = new float[16], t1 = new float[16], t2 = new float[16];

    public void glLost() { g3.lost(); }

    private void part(Nv3D.Mesh mesh, float tx, float ty, float tz, float rx, float sx, float sy, float sz, int color, float flash) {
        partR(mesh, tx, ty, tz, rx, 0f, sx, sy, sz, color, flash);
    }

    private void partR(Nv3D.Mesh mesh, float tx, float ty, float tz, float rx, float ry, float sx, float sy, float sz, int color, float flash) {
        System.arraycopy(mon, 0, t1, 0, 16);
        Matrix.translateM(t1, 0, tx, ty, tz);
        if (rx != 0f) Matrix.rotateM(t1, 0, rx, 1f, 0f, 0f);
        if (ry != 0f) Matrix.rotateM(t1, 0, ry, 0f, 1f, 0f);
        Matrix.scaleM(t1, 0, sx, sy, sz);
        Matrix.multiplyMM(t2, 0, vp, 0, t1, 0);
        g3.draw(mesh, t2, t1, color, flash, 1f);
    }

    private void drawMob(Mob m) {
        float x = xOf(m) - ex, y = yOf(m) - ey, z = zOf(m) - ez;
        float dx = ox - xOf(m), dy = oy - yOf(m);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dy));         // its front (+Y) turns to face you
        Matrix.setIdentityM(mon, 0);
        Matrix.translateM(mon, 0, x, y, z);
        Matrix.rotateM(mon, 0, yaw, 0f, 0f, 1f);
        Matrix.scaleM(mon, 0, m.size, m.size, m.size);
        float fl = m.flash > 0f ? Math.min(1f, m.flash * 4f) : 0f;
        float sw = (float) Math.sin(m.wob * 7f + m.ph);
        int body = colorOf(m);
        final int DARK = 0xFF1B1B1F, WHITE = 0xFFFFFFFF;
        switch (m.type) {
            case 0: {                                                   // slime
                float sq = 1f + 0.07f * sw;
                part(g3.sphere, 0, 0, 0.36f, 0, 0.46f * sq, 0.46f * sq, 0.36f / sq, body, fl);
                part(g3.sphere, -0.17f, 0.36f, 0.50f, 0, 0.10f, 0.10f, 0.10f, WHITE, fl);
                part(g3.sphere, 0.17f, 0.36f, 0.50f, 0, 0.10f, 0.10f, 0.10f, WHITE, fl);
                part(g3.sphere, -0.17f, 0.44f, 0.50f, 0, 0.05f, 0.05f, 0.05f, DARK, 0f);
                part(g3.sphere, 0.17f, 0.44f, 0.50f, 0, 0.05f, 0.05f, 0.05f, DARK, 0f);
                part(g3.box, 0, 0.45f, 0.30f, 0, 0.10f, 0.02f, 0.025f, 0xFF1B6A33, 0f);
                break;
            }
            case 1: {                                                   // skeleton
                part(g3.cyl, -0.10f, 0f, 0.30f, 0, 0.05f, 0.05f, 0.30f, body, fl);
                part(g3.cyl, 0.10f, 0f, 0.30f, 0, 0.05f, 0.05f, 0.30f, body, fl);
                part(g3.box, 0, 0, 0.80f, 0, 0.20f, 0.09f, 0.22f, body, fl);
                for (int k = 0; k < 3; k++) part(g3.box, 0, 0.09f, 0.68f + k * 0.12f, 0, 0.17f, 0.02f, 0.025f, 0xFF4A4A52, 0f);
                part(g3.sphere, 0, 0, 1.22f, 0, 0.20f, 0.19f, 0.20f, body, fl);
                part(g3.sphere, -0.08f, 0.17f, 1.25f, 0, 0.05f, 0.04f, 0.06f, DARK, 0f);
                part(g3.sphere, 0.08f, 0.17f, 1.25f, 0, 0.05f, 0.04f, 0.06f, DARK, 0f);
                part(g3.box, 0, 0.18f, 1.12f, 0, 0.09f, 0.02f, 0.03f, DARK, 0f);
                part(g3.cyl, -0.30f, 0f, 0.82f, 25f * sw, 0.04f, 0.04f, 0.26f, body, fl);
                part(g3.cyl, 0.30f, 0f, 0.82f, -25f * sw, 0.04f, 0.04f, 0.26f, body, fl);
                if (m.hp < m.maxHp) part(g3.box, 0.05f, 0.19f, 1.38f, 0, 0.01f, 0.01f, 0.1f, DARK, 0f);
                break;
            }
            case 2: {                                                   // ghost
                part(g3.sphere, 0, 0, 0.62f, 0, 0.32f, 0.30f, 0.36f, body, fl);
                part(g3.cone, 0, 0, 0.28f, 180f, 0.32f, 0.30f, 0.30f, body, fl);
                part(g3.sphere, -0.12f, 0.27f, 0.70f, 0, 0.06f, 0.05f, 0.09f, DARK, 0f);
                part(g3.sphere, 0.12f, 0.27f, 0.70f, 0, 0.06f, 0.05f, 0.09f, DARK, 0f);
                part(g3.sphere, 0, 0.29f, 0.50f, 0, 0.07f, 0.05f, 0.09f, DARK, 0f);
                part(g3.sphere, -0.34f, 0.04f, 0.50f + 0.05f * sw, 0, 0.10f, 0.08f, 0.16f, body, fl);
                part(g3.sphere, 0.34f, 0.04f, 0.50f - 0.05f * sw, 0, 0.10f, 0.08f, 0.16f, body, fl);
                break;
            }
            case 4: {                                                   // bat
                float fl2 = 28f + 32f * sw;
                part(g3.sphere, 0, 0, 0.5f, 0, 0.15f, 0.13f, 0.17f, body, fl);
                part(g3.sphere, 0, 0.11f, 0.64f, 0, 0.11f, 0.10f, 0.11f, body, fl);
                part(g3.cone, -0.06f, 0.10f, 0.78f, 0, 0.03f, 0.03f, 0.08f, body, fl);
                part(g3.cone, 0.06f, 0.10f, 0.78f, 0, 0.03f, 0.03f, 0.08f, body, fl);
                part(g3.sphere, -0.04f, 0.2f, 0.66f, 0, 0.025f, 0.02f, 0.03f, 0xFFFF4D4D, 0f);
                part(g3.sphere, 0.04f, 0.2f, 0.66f, 0, 0.025f, 0.02f, 0.03f, 0xFFFF4D4D, 0f);
                partR(g3.box, -0.36f, 0f, 0.54f, 0, fl2, 0.30f, 0.02f, 0.17f, 0xFF4A3470, fl);
                partR(g3.box, 0.36f, 0f, 0.54f, 0, -fl2, 0.30f, 0.02f, 0.17f, 0xFF4A3470, fl);
                break;
            }
            case 5: {                                                   // pumpkin head
                part(g3.sphere, 0, 0, 0.42f, 0, 0.50f, 0.48f, 0.40f, body, fl);
                part(g3.sphere, -0.26f, 0.02f, 0.42f, 0, 0.26f, 0.44f, 0.38f, 0xFFD97712, fl);
                part(g3.sphere, 0.26f, 0.02f, 0.42f, 0, 0.26f, 0.44f, 0.38f, 0xFFD97712, fl);
                part(g3.cyl, 0, 0, 0.84f, 0, 0.06f, 0.06f, 0.10f, 0xFF4F7A2A, fl);
                part(g3.box, -0.17f, 0.45f, 0.52f, 0, 0.09f, 0.03f, 0.07f, 0xFFFFE27A, 0f);
                part(g3.box, 0.17f, 0.45f, 0.52f, 0, 0.09f, 0.03f, 0.07f, 0xFFFFE27A, 0f);
                part(g3.box, 0, 0.47f, 0.30f, 0, 0.22f, 0.03f, 0.05f, 0xFFFFE27A, 0f);
                for (int k = 0; k < 3; k++) part(g3.box, -0.12f + k * 0.12f, 0.48f, 0.33f, 0, 0.03f, 0.02f, 0.04f, DARK, 0f);
                part(g3.sphere, -0.2f, 0.1f, 0.04f, 0, 0.12f, 0.14f, 0.06f, 0xFF4F7A2A, 0f);
                part(g3.sphere, 0.2f, 0.1f, 0.04f, 0, 0.12f, 0.14f, 0.06f, 0xFF4F7A2A, 0f);
                break;
            }
            case 6: {                                                   // stone golem
                part(g3.box, -0.25f, 0f, 0.30f, 0, 0.18f, 0.18f, 0.30f, 0xFF6E737C, fl);
                part(g3.box, 0.25f, 0f, 0.30f, 0, 0.18f, 0.18f, 0.30f, 0xFF6E737C, fl);
                part(g3.box, 0, 0, 0.95f, 0, 0.50f, 0.30f, 0.40f, body, fl);
                part(g3.box, 0, 0.05f, 1.55f, 0, 0.25f, 0.22f, 0.22f, body, fl);
                part(g3.sphere, -0.1f, 0.26f, 1.58f, 0, 0.05f, 0.03f, 0.05f, 0xFF4DD2FF, 0f);
                part(g3.sphere, 0.1f, 0.26f, 1.58f, 0, 0.05f, 0.03f, 0.05f, 0xFF4DD2FF, 0f);
                part(g3.box, -0.72f, 0f, 0.90f, 22f * sw, 0.16f, 0.16f, 0.40f, 0xFF7B808A, fl);
                part(g3.box, 0.72f, 0f, 0.90f, -22f * sw, 0.16f, 0.16f, 0.40f, 0xFF7B808A, fl);
                part(g3.box, 0, 0.3f, 1.05f, 0, 0.14f, 0.02f, 0.16f, 0xFF4DD2FF, 0f);
                break;
            }
            default: {                                                  // boss: a big horned beast
                int purple = 0xFF6B2A9E, horn = 0xFFE8E0C8;
                part(g3.cyl, -0.28f, 0f, 0.25f, 0, 0.16f, 0.16f, 0.25f, 0xFF4A1C70, fl);
                part(g3.cyl, 0.28f, 0f, 0.25f, 0, 0.16f, 0.16f, 0.25f, 0xFF4A1C70, fl);
                part(g3.sphere, 0, 0, 0.82f, 0, 0.62f, 0.56f, 0.58f, purple, fl);
                part(g3.cone, -0.34f, 0.04f, 1.50f, 20f, 0.10f, 0.10f, 0.30f, horn, fl);
                part(g3.cone, 0.34f, 0.04f, 1.50f, 20f, 0.10f, 0.10f, 0.30f, horn, fl);
                part(g3.sphere, -0.22f, 0.50f, 1.00f, 0, 0.12f, 0.08f, 0.12f, 0xFFFFD84D, 0f);
                part(g3.sphere, 0.22f, 0.50f, 1.00f, 0, 0.12f, 0.08f, 0.12f, 0xFFFFD84D, 0f);
                part(g3.sphere, -0.22f, 0.57f, 1.00f, 0, 0.04f, 0.03f, 0.06f, DARK, 0f);
                part(g3.sphere, 0.22f, 0.57f, 1.00f, 0, 0.04f, 0.03f, 0.06f, DARK, 0f);
                part(g3.box, 0, 0.55f, 0.62f, 0, 0.30f, 0.04f, 0.09f, DARK, 0f);
                for (int k = 0; k < 4; k++) part(g3.box, -0.21f + k * 0.14f, 0.58f, 0.68f, 0, 0.03f, 0.02f, 0.05f, WHITE, 0f);
                part(g3.sphere, -0.66f, 0f, 0.80f + 0.06f * sw, 0, 0.18f, 0.18f, 0.26f, purple, fl);
                part(g3.sphere, 0.66f, 0f, 0.80f - 0.06f * sw, 0, 0.18f, 0.18f, 0.26f, purple, fl);
                break;
            }
        }
    }

    /** called by the renderer once per picture while the game window is up: runs the game and draws the monsters in the room */
    public void drawWorld(float[] proj, float[] rn, float eyeX, float eyeY, float eyeZ) {
        if (!g3.ready()) g3.init();
        long nowN = System.nanoTime();
        float dt = lastT == 0 ? 0f : Math.min(0.05f, (nowN - lastT) / 1e9f);
        lastT = nowN;
        ex = eyeX; ey = eyeY; ez = eyeZ;
        if (startReq) {                                              // start: centred where you stand, ahead = where you look now
            startReq = false;
            float fx = -rn[2], fy = -rn[6];
            if (Math.hypot(fx, fy) < 0.3) { fx = -rn[1]; fy = -rn[5]; }   // looking straight up or down: use the phone's top edge
            heading = (float) Math.atan2(fy, fx);
            ox = eyeX; oy = eyeY; oz = eyeZ;
            mobs.clear(); chunks.clear();
            score = 0; hp = 5; wave = 0; combo = 0; state = 1; dirty = true;
            nextWave();
        }
        step(dt);
        if (mobs.isEmpty() && chunks.isEmpty()) return;
        Matrix.multiplyMM(vp, 0, proj, 0, rn, 0);
        g3.begin();
        for (Mob m : mobs) drawMob(m);
        for (Chunk c : chunks) {
            Matrix.setIdentityM(mon, 0);
            Matrix.translateM(mon, 0, c.x - ex, c.y - ey, c.z - ez);
            Matrix.rotateM(mon, 0, c.rot, 1f, 0.6f, 0.3f);
            Matrix.scaleM(mon, 0, c.size, c.size, c.size);
            Matrix.multiplyMM(t2, 0, vp, 0, mon, 0);
            g3.draw(g3.box, t2, mon, c.color, 0f, Math.min(1f, c.life * 2.5f));
        }
    }
}
