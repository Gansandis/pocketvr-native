package com.pocketvr.app;

import android.opengl.GLES20;
import android.opengl.Matrix;

import java.util.ArrayList;
import java.util.Random;

/**
 * The shooter as a 3D world: draws the enemies, the weapon, the crosshair, the effects and a little scenery, and runs NvGunCore every frame.
 *
 * Two ways to play:
 *   - head mode (handMode = false, the default): the gun is a view model low on the right, you aim with your head, the trigger is a button / a tap.
 *   - hand mode (handMode = true, needs the host to call hands() every frame): the machine gun hangs at your body. Grab it with a fist, aim with
 *     that hand, fire with the index finger of the other hand, put it down by opening the hand, and walk by reaching out an open hand. See NvGunHands.
 *
 * Inputs (from any thread): setTrigger(), requestStart(), requestReload(), requestSwitch(), requestDraw(); hands().
 */
public final class NvGunWorld implements NvGameWorld {
    public final NvGunCore core = new NvGunCore();
    public final NvGunHud hud = new NvGunHud(core);
    public final NvGunHands hands = new NvGunHands();
    public NvGunCore.Listener hostListener;          // the host's sounds / vibration (called from the game thread)
    public boolean envOn = true;
    public boolean handMode = false;                 // true = the gun lives in your hands (the host must call hands())
    public boolean sceneryOn = true;                 // draw a floor, a grid and some pillars (they are what makes walking visible)
    public float arenaR = 12f;                       // how far from where the game began you may walk (metres)

    private final NvGunGfx g = new NvGunGfx();
    private final float[] vp = new float[16], mon = new float[16], t1 = new float[16], t2 = new float[16], tmp = new float[16];
    private float[] curVP = vp;
    private float al = 1f;                           // alpha of the part being drawn
    private float partFlash = 0f;                    // lights up the gun's parts (when a hand is at it)
    private final Random rnd = new Random();

    // ---- input ----
    private volatile boolean trigKey = false, startReq = false, reloadReq = false, switchReq = false, drawReq = false;

    public void setTrigger(boolean down) { trigKey = down; }
    public void requestStart() { startReq = true; }
    public void requestReload() { reloadReq = true; }
    public void requestSwitch() { switchReq = true; }
    /** take the gun out / put it away without the hands (hand mode) */
    public void requestDraw() { drawReq = true; }

    @Override public void pointer(boolean valid, float wx, float wy, float wz, long nowMs) { /* the finger pointer is not used by this game */ }
    @Override public void hands(float[] d, float aspect, float tanH, float tanV, boolean rot180, long nowMs) {
        if (handMode) hands.setFrame(d, aspect, tanH, tanV, rot180, nowMs);
    }
    @Override public void glLost() { g.lost(); hud.lost(); }
    @Override public boolean envActive() { return envOn && core.state != NvGunCore.TITLE; }

    // ---- what update() works out and draw() uses ----
    private float ex, ey, ez, fx, fy, fz, rx, ry, rz, ux, uy, uz, yaw;       // (ex,ey) = the eyes in the game: the host's eyes + how far you have walked
    private float walkX = 0f, walkY = 0f;
    private float sceneX = 0f, sceneY = 0f;
    private boolean sceneInit = false;
    private float aimX, aimY, aimZ;                  // where the bullets go
    private float chX = 0f, chY = 0f, chZ = -3f;     // where the crosshair is, in view space
    private float mzX, mzY, mzZ;                     // the muzzle of the gun in a hand, in the world
    private final float[] rnKeep = new float[16];
    private long clockMs = 0;
    private int aimKind = 0;                         // what the crosshair is on: 0 nothing, 1 body, 2 head
    private long lastT = 0;

    // ---- effects ----
    private static final class Chunk { float x, y, z, vx, vy, vz, rot, vrot, size, life; int color; }
    private final ArrayList<Chunk> chunks = new ArrayList<>();
    private float tracerT = 0f, flashT = 0f, kick = 0f, hitT = 0f, hurtFlash = 0f;
    private boolean hitRed = false;
    private float tx0, ty0, tz0, tx1, ty1, tz1;
    private final float[] hurtAz = new float[4], hurtT = new float[4];
    private int hurtN = 0;

    public NvGunWorld() {
        core.startWeapon = NvGunCore.RIFLE;                                        // the gun is a long machine gun
        core.lockWeapon = true;
        core.listener = new NvGunCore.Listener() {
            @Override public void onShot(NvGunCore.Shot s) {
                flashT = 0.06f; kick = 1f; tracerT = 0.07f;
                if (handMode && hands.gunState == NvGunHands.HELD && hands.aimFromHand) { tx0 = mzX; ty0 = mzY; tz0 = mzZ; }
                else {
                    float vx = 0.10f, vy = -0.11f, vz = core.weapon == NvGunCore.RIFLE ? -0.90f : -0.56f;          // the muzzle, in view space
                    tx0 = ex + rx * vx + ux * vy - fx * vz; ty0 = ey + ry * vx + uy * vy - fy * vz; tz0 = ez + rz * vx + uz * vy - fz * vz;
                }
                tx1 = s.hx; ty1 = s.hy; tz1 = s.hz;
                NvGunCore.Listener h = hostListener; if (h != null) h.onShot(s);
            }
            @Override public void onHit(NvGunCore.Enemy e, boolean head, boolean kill) {
                hitT = 0.14f; hitRed = head || kill;
                NvGunCore.Shot s = core.shot;
                debris(s.hx, s.hy, s.hz, colorOf(e), head ? 9 : 5, 2.2f, 0.05f);
                if (kill) debris(core.xOf(e), core.yOf(e), core.zOf(e) + 0.9f * e.scale, colorOf(e), e.boss ? 40 : 16, e.boss ? 3.5f : 2.5f, e.boss ? 0.16f : 0.09f);
                NvGunCore.Listener h = hostListener; if (h != null) h.onHit(e, head, kill);
            }
            @Override public void onHurt(float fromAz, int dmg) {
                hurtFlash = Math.min(1f, 0.45f + dmg / 30f);
                int i = hurtN < hurtAz.length ? hurtN++ : 0;
                hurtAz[i] = fromAz; hurtT[i] = 1.0f;
                NvGunCore.Listener h = hostListener; if (h != null) h.onHurt(fromAz, dmg);
            }
            @Override public void onDry() { NvGunCore.Listener h = hostListener; if (h != null) h.onDry(); }
            @Override public void onReload() { NvGunCore.Listener h = hostListener; if (h != null) h.onReload(); }
            @Override public void onReloaded() { NvGunCore.Listener h = hostListener; if (h != null) h.onReloaded(); }
            @Override public void onWave(int wave, boolean boss) { NvGunCore.Listener h = hostListener; if (h != null) h.onWave(wave, boss); }
            @Override public void onGameOver(int score) { NvGunCore.Listener h = hostListener; if (h != null) h.onGameOver(score); }
        };
    }

    private static int colorOf(NvGunCore.Enemy e) {
        switch (e.type) {
            case NvGunCore.RUNNER: return 0xFFD05050;
            case NvGunCore.TANK: return 0xFF8A8F99;
            case NvGunCore.DRONE: return 0xFFFF6A5A;
            case NvGunCore.BOSS: return 0xFFB04CD8;
            default: return 0xFF8DAA6A;
        }
    }

    private void debris(float x, float y, float z, int color, int n, float sp, float size) {
        for (int i = 0; i < n && chunks.size() < 120; i++) {
            Chunk c = new Chunk();
            c.x = x; c.y = y; c.z = z; c.color = color; c.size = size * (0.5f + rnd.nextFloat());
            float a = rnd.nextFloat() * 6.28f, v = sp * (0.4f + rnd.nextFloat());
            c.vx = (float) Math.cos(a) * v; c.vy = (float) Math.sin(a) * v; c.vz = 1.0f + rnd.nextFloat() * 2f;
            c.rot = rnd.nextFloat() * 360f; c.vrot = (rnd.nextFloat() - 0.5f) * 600f; c.life = 0.7f + rnd.nextFloat() * 0.5f;
            chunks.add(c);
        }
    }

    // ================= one step =================
    @Override public void update(float[] rn, float eyeX, float eyeY, float eyeZ, long nowNs) {
        float dt = lastT == 0 ? 0f : Math.min(0.05f, (nowNs - lastT) / 1e9f);
        lastT = nowNs;
        long nowMs = nowNs / 1000000L;
        clockMs = nowMs;
        System.arraycopy(rn, 0, rnKeep, 0, 16);
        ex = eyeX + walkX; ey = eyeY + walkY; ez = eyeZ;
        fx = -rn[2]; fy = -rn[6]; fz = -rn[10];          // where the head looks
        rx = rn[0]; ry = rn[4]; rz = rn[8];              // right
        ux = rn[1]; uy = rn[5]; uz = rn[9];              // up
        yaw = (float) Math.atan2(fy, fx);
        if (!sceneInit) { sceneX = ex; sceneY = ey; sceneInit = true; }

        if (startReq) {
            startReq = false;
            float hx = fx, hy = fy;
            if (Math.hypot(hx, hy) < 0.3) { hx = -rn[1]; hy = -rn[5]; }      // looking straight up or down: use the top edge of the phone
            core.start(ex, ey, ez, (float) Math.atan2(hy, hx));
            sceneX = ex; sceneY = ey;
            hands.reset();
            chunks.clear(); hurtN = 0;
        }
        if (reloadReq) { reloadReq = false; core.reload(); }
        if (switchReq) { switchReq = false; core.switchWeapon(); }
        if (drawReq) { drawReq = false; hands.requestToggle(); }

        // ---- who aims and who pulls the trigger ----
        float ax = fx, ay = fy, az = fz;
        boolean trig = trigKey;
        if (handMode) {
            hands.update(dt, nowMs, rn, ex, ey, ez);
            if (hands.gunState == NvGunHands.HELD) {
                trig = trig || hands.trigger;
                if (hands.aimFromHand) { ax = hands.aimX; ay = hands.aimY; az = hands.aimZ; }
            } else trig = false;                                              // the gun is hanging at your body: nothing to fire
            // ---- walking (only with the gun put down) ----
            if (core.state == NvGunCore.PLAYING && hands.walkSpeed > 0f) {
                walkX += (float) Math.cos(hands.walkYaw) * hands.walkSpeed * dt;
                walkY += (float) Math.sin(hands.walkYaw) * hands.walkSpeed * dt;
                float qx = eyeX + walkX - core.ox, qy = eyeY + walkY - core.oy, qd = (float) Math.sqrt(qx * qx + qy * qy);
                if (qd > arenaR) { float k = 1f - arenaR / qd; walkX -= qx * k; walkY -= qy * k; }       // the arena has an edge
                ex = eyeX + walkX; ey = eyeY + walkY;
            }
            // the muzzle of the gun in the hand: ahead of the grip and a little up (the model is drawn 1.25 times its size)
            gunBasis(hands.gunDX, hands.gunDY, hands.gunDZ);
            mzX = hands.gunX + hands.gunDX * 0.66f + gbu[0] * 0.07f; mzY = hands.gunY + hands.gunDY * 0.66f + gbu[1] * 0.07f; mzZ = hands.gunZ + hands.gunDZ * 0.66f + gbu[2] * 0.07f;
            hud.hint = hintText();
        } else hud.hint = "";
        aimX = ax; aimY = ay; aimZ = az;
        core.assistDeg = handMode && hands.aimFromHand ? 3.5f : 1.6f;        // a hand shakes more than a head: be more forgiving

        core.tick(dt, ex, ey, ez, ax, ay, az, trig);
        aimKind = core.peek(ex, ey, ez, ax, ay, az);

        // the crosshair is where the bullet goes: the middle of the screen, or wherever the gun hand points
        float cxw = ax * 3f, cyw = ay * 3f, czw = az * 3f;
        chX = rn[0] * cxw + rn[4] * cyw + rn[8] * czw; chY = rn[1] * cxw + rn[5] * cyw + rn[9] * czw; chZ = rn[2] * cxw + rn[6] * cyw + rn[10] * czw;
        if (chZ > -0.5f) { chX = 0f; chY = 0f; chZ = -3f; }                  // the aim is behind you: keep the cross in front

        // effects
        tracerT = Math.max(0f, tracerT - dt); flashT = Math.max(0f, flashT - dt); hitT = Math.max(0f, hitT - dt);
        kick = Math.max(0f, kick - dt * 9f); hurtFlash = Math.max(0f, hurtFlash - dt * 1.6f);
        for (int i = 0; i < hurtAz.length; i++) if (hurtT[i] > 0f) hurtT[i] -= dt;
        for (int i = chunks.size() - 1; i >= 0; i--) {
            Chunk c = chunks.get(i);
            c.vz -= 7f * dt; c.x += c.vx * dt; c.y += c.vy * dt; c.z += c.vz * dt; c.rot += c.vrot * dt; c.life -= dt;
            if (c.life <= 0f || c.z < core.floorZ) chunks.remove(i);
        }
    }

    private final float[] gbr = new float[3], gbu = new float[3];
    /** the right and up directions of a gun that points along f (unit), kept upright (no roll) */
    private void gunBasis(float fx, float fy, float fz) {
        float rx = fy, ry = -fx, l = (float) Math.sqrt(rx * rx + ry * ry);
        if (l < 0.05f) { rx = 1f; ry = 0f; l = 1f; }
        rx /= l; ry /= l;
        gbr[0] = rx; gbr[1] = ry; gbr[2] = 0f;
        gbu[0] = ry * fz; gbu[1] = -rx * fz; gbu[2] = rx * fy - ry * fx;
    }

    private String hintText() {
        if (core.state != NvGunCore.PLAYING) return "";
        if (hands.gunState == NvGunHands.HOLSTERED)
            return hands.grabReady ? "กำมือเพื่อหยิบปืน" : "มองลงหาปืน กำมือที่ปืนเพื่อหยิบ  |  แบมือยื่นไปข้างหน้า = เดิน";
        if (!hands.aimFromHand) return "ยื่นมือกำเข้ากล้องเพื่อเล็งด้วยมือ";
        return hands.armed ? "หุบนิ้วชี้มืออีกข้าง = ยิง  |  แบมือค้าง = วางปืน" : "ชูนิ้วชี้มืออีกข้างให้เห็น แล้วหุบเพื่อยิง";
    }

    // ================= drawing =================
    /** one part of a model: moved by (tx,ty,tz), turned (degrees) about X, Y, Z, scaled by (sx,sy,sz) (half sizes of the unit solids) */
    private void part(NvGunGfx.Mesh mesh, float tx, float ty, float tz, float rotX, float rotY, float rotZ, float sx, float sy, float sz, int color, float flash) {
        System.arraycopy(mon, 0, t1, 0, 16);
        Matrix.translateM(t1, 0, tx, ty, tz);
        if (rotZ != 0f) Matrix.rotateM(t1, 0, rotZ, 0f, 0f, 1f);
        if (rotY != 0f) Matrix.rotateM(t1, 0, rotY, 0f, 1f, 0f);
        if (rotX != 0f) Matrix.rotateM(t1, 0, rotX, 1f, 0f, 0f);
        Matrix.scaleM(t1, 0, sx, sy, sz);
        Matrix.multiplyMM(t2, 0, curVP, 0, t1, 0);
        g.draw(mesh, t2, t1, color, flash, al);
    }

    private void drawEnemy(NvGunCore.Enemy e, float dx, float dy, float dz) {
        float x = core.xOf(e), y = core.yOf(e), z = core.zOf(e);
        float s = e.scale;
        // a soft shadow on the floor: the only depth cue the picture has besides size
        Matrix.setIdentityM(mon, 0);
        Matrix.translateM(mon, 0, x - dx, y - dy, core.floorZ + 0.02f - dz);
        al = 0.35f;
        part(g.cyl, 0, 0, 0, 0, 0, 0, 0.55f * s, 0.55f * s, 0.005f, 0xFF000000, 0f);
        al = 1f;

        float fdx = core.px - x, fdy = core.py - y;
        float face = (float) Math.toDegrees(Math.atan2(-fdx, fdy));               // its front (+Y) turns to face you
        Matrix.setIdentityM(mon, 0);
        Matrix.translateM(mon, 0, x - dx, y - dy, z - dz);
        Matrix.rotateM(mon, 0, face, 0f, 0f, 1f);
        Matrix.scaleM(mon, 0, s, s, s);
        float fl = e.flash > 0f ? Math.min(1f, e.flash * 5f) : 0f;
        float sw = (float) Math.sin(e.wob * (3f + e.speed * 2.5f));
        final int DARK = 0xFF1B1B1F;
        switch (e.type) {
            case NvGunCore.DRONE: {
                part(g.sphere, 0, 0, 0.40f, 0, 0, 0, 0.32f, 0.32f, 0.22f, 0xFF5A5F6B, fl);
                part(g.sphere, 0, 0.28f, 0.42f, 0, 0, 0, 0.09f, 0.06f, 0.09f, 0xFFFF3B3B, 0f);
                part(g.box, 0, 0, 0.18f, 0, 0, 0, 0.06f, 0.20f, 0.03f, DARK, fl);
                float spin = e.wob * 900f;
                part(g.box, -0.40f, 0, 0.66f, 0, 0, spin, 0.26f, 0.02f, 0.008f, 0xFFB0B5C0, 0f);
                part(g.box, 0.40f, 0, 0.66f, 0, 0, -spin, 0.26f, 0.02f, 0.008f, 0xFFB0B5C0, 0f);
                part(g.cyl, -0.40f, 0, 0.55f, 0, 0, 0, 0.02f, 0.02f, 0.10f, DARK, fl);
                part(g.cyl, 0.40f, 0, 0.55f, 0, 0, 0, 0.02f, 0.02f, 0.10f, DARK, fl);
                break;
            }
            default: {
                int body, head, limb;
                float w = 1f;
                switch (e.type) {
                    case NvGunCore.RUNNER: body = 0xFF9B3535; head = 0xFFE0B0A0; limb = 0xFF6E2222; w = 0.85f; break;
                    case NvGunCore.TANK: body = 0xFF4A5068; head = 0xFF8A8F99; limb = 0xFF383C4E; w = 1.25f; break;
                    case NvGunCore.BOSS: body = 0xFF6B2A9E; head = 0xFFB07AD0; limb = 0xFF4A1C70; w = 1.15f; break;
                    default: body = 0xFF55705A; head = 0xFFB5C99A; limb = 0xFF3D5242; break;
                }
                float run = e.type == NvGunCore.RUNNER ? 1.4f : 1f;
                part(g.cyl, -0.12f * w, 0, 0.42f, 30f * sw * run, 0, 0, 0.075f * w, 0.075f * w, 0.42f, limb, fl);
                part(g.cyl, 0.12f * w, 0, 0.42f, -30f * sw * run, 0, 0, 0.075f * w, 0.075f * w, 0.42f, limb, fl);
                part(g.box, 0, 0, 1.02f, 0, 0, 0, 0.25f * w, 0.14f * w, 0.32f, body, fl);
                part(g.box, 0, 0, 0.72f, 0, 0, 0, 0.26f * w, 0.15f * w, 0.05f, DARK, 0f);
                part(g.sphere, 0, 0, 1.62f, 0, 0, 0, 0.20f, 0.20f, 0.21f, head, fl);
                int eye = e.type == NvGunCore.BOSS ? 0xFFFFD84D : 0xFFFF4040;
                part(g.sphere, -0.07f, 0.17f, 1.66f, 0, 0, 0, 0.035f, 0.03f, 0.035f, eye, 0f);
                part(g.sphere, 0.07f, 0.17f, 1.66f, 0, 0, 0, 0.035f, 0.03f, 0.035f, eye, 0f);
                float armA = e.attacking ? -75f + 25f * sw : -55f + 12f * sw;          // arms stretched out in front of it
                part(g.box, -0.34f * w, 0.12f, 1.05f, armA, 0, 0, 0.06f * w, 0.06f * w, 0.27f, limb, fl);
                part(g.box, 0.34f * w, 0.12f, 1.05f, armA, 0, 0, 0.06f * w, 0.06f * w, 0.27f, limb, fl);
                if (e.type == NvGunCore.TANK) {
                    part(g.box, -0.40f * w, 0, 1.30f, 0, 0, 0, 0.14f, 0.16f, 0.09f, 0xFF7A8094, fl);
                    part(g.box, 0.40f * w, 0, 1.30f, 0, 0, 0, 0.14f, 0.16f, 0.09f, 0xFF7A8094, fl);
                    part(g.box, 0, 0.15f * w, 1.05f, 0, 0, 0, 0.18f, 0.03f, 0.22f, 0xFF7A8094, fl);
                }
                if (e.type == NvGunCore.BOSS) {
                    part(g.cone, -0.15f, 0, 1.90f, 18f, 0, 0, 0.06f, 0.06f, 0.20f, 0xFFE8E0C8, fl);
                    part(g.cone, 0.15f, 0, 1.90f, 18f, 0, 0, 0.06f, 0.06f, 0.20f, 0xFFE8E0C8, fl);
                    part(g.sphere, -0.42f * w, 0, 1.28f, 0, 0, 0, 0.16f, 0.16f, 0.14f, body, fl);
                    part(g.sphere, 0.42f * w, 0, 1.28f, 0, 0, 0, 0.16f, 0.16f, 0.14f, body, fl);
                }
                break;
            }
        }
    }

    private void drawTracer(float dx, float dy, float dz) {
        if (tracerT <= 0f) return;
        float ax = tx0 - dx, ay = ty0 - dy, az = tz0 - dz, bx = tx1 - dx, by = ty1 - dy, bz = tz1 - dz;
        float vx = bx - ax, vy = by - ay, vz = bz - az, len = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (len < 0.05f) return;
        Matrix.setIdentityM(mon, 0);
        Matrix.translateM(mon, 0, (ax + bx) / 2f, (ay + by) / 2f, (az + bz) / 2f);
        Matrix.rotateM(mon, 0, (float) Math.toDegrees(Math.atan2(-vx, vy)), 0f, 0f, 1f);
        Matrix.rotateM(mon, 0, (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, vz / len)))), 1f, 0f, 0f);
        al = Math.min(1f, tracerT / 0.07f);
        part(g.box, 0, 0, 0, 0, 0, 0, 0.006f, len / 2f, 0.006f, 0xFFFFE9A0, 1f);
        al = 1f;
    }

    private static final int METAL = 0xFF3A3F4B, DARKM = 0xFF22252C, GRIP = 0xFF2B2E36, ACC = 0xFFFFB347;

    /** the long machine gun, built around (0,0,0) = the middle of the body, barrel toward -Z; `mon` is where it sits */
    private void machineGunParts() {
        float f = partFlash;
        part(g.box, 0, 0, -0.14f, 0, 0, 0, 0.020f, 0.028f, 0.190f, METAL, f);
        part(g.box, 0, -0.004f, -0.30f, 0, 0, 0, 0.024f, 0.030f, 0.070f, GRIP, f);
        part(g.cyl, 0, 0.008f, -0.42f, 0, 0, 0, 0.009f, 0.009f, 0.120f, 0xFF1B1D22, f);
        part(g.box, 0, -0.070f, -0.12f, 10f, 0, 0, 0.014f, 0.040f, 0.020f, DARKM, f);
        part(g.box, 0, -0.055f, -0.02f, -12f, 0, 0, 0.014f, 0.040f, 0.018f, GRIP, f);       // the pistol grip, where the hand is
        part(g.box, 0, -0.010f, 0.08f, 0, 0, 0, 0.016f, 0.030f, 0.090f, GRIP, f);
        part(g.sphere, 0, 0.040f, -0.28f, 0, 0, 0, 0.005f, 0.005f, 0.005f, ACC, 0f);
    }

    private void pistolParts() {
        part(g.box, 0, 0.02f, -0.06f, 0, 0, 0, 0.017f, 0.020f, 0.085f, METAL, 0f);
        part(g.box, 0, -0.005f, -0.045f, 0, 0, 0, 0.015f, 0.015f, 0.070f, DARKM, 0f);
        part(g.cyl, 0, 0.02f, -0.15f, 0, 0, 0, 0.009f, 0.009f, 0.020f, 0xFF15171C, 0f);
        part(g.box, 0, -0.06f, -0.01f, -12f, 0, 0, 0.015f, 0.045f, 0.020f, GRIP, 0f);
        part(g.box, 0, 0.043f, -0.12f, 0, 0, 0, 0.003f, 0.005f, 0.004f, ACC, 0f);
    }

    /** the gun out in the room: at the body, or flying to / sitting in the hand */
    private void drawWorldGun(float dx, float dy, float dz) {
        if (!handMode || core.state == NvGunCore.TITLE) return;
        boolean inHand = hands.gunState == NvGunHands.HELD && hands.aimFromHand;
        if (!inHand && hands.gunState == NvGunHands.HELD) return;               // held, but the hand is out of sight: the view model is shown instead
        gunBasis(hands.gunDX, hands.gunDY, hands.gunDZ);
        Matrix.setIdentityM(mon, 0);
        // columns: right, up, back (the model's barrel is -Z), then the position (relative to the eye)
        mon[0] = gbr[0]; mon[1] = gbr[1]; mon[2] = gbr[2];
        mon[4] = gbu[0]; mon[5] = gbu[1]; mon[6] = gbu[2];
        mon[8] = -hands.gunDX; mon[9] = -hands.gunDY; mon[10] = -hands.gunDZ;
        mon[12] = hands.gunX - dx; mon[13] = hands.gunY - dy; mon[14] = hands.gunZ - dz;
        Matrix.translateM(mon, 0, 0f, 0.055f, 0.02f);                           // the grip sits in the hand
        Matrix.scaleM(mon, 0, 1.25f, 1.25f, 1.25f);
        partFlash = hands.grabReady ? 0.30f + 0.25f * (float) Math.sin(clockMs * 0.012) : 0f;
        machineGunParts();
        partFlash = 0f;
        if (flashT > 0f && inHand) {
            al = Math.min(1f, flashT / 0.04f);
            part(g.sphere, 0, 0.008f, -0.58f, 0, 0, 0, 0.035f, 0.035f, 0.05f, 0xFFFFD84D, 1f);
            al = 1f;
        }
    }

    // ---- scenery: a floor with a grid, pillars with lamps and a moon. It is what shows that you walk. ----
    private static final int NP = 24;
    private float[] pa, pr, ph; private int[] pc;
    private void initScenery() {
        pa = new float[NP]; pr = new float[NP]; ph = new float[NP]; pc = new int[NP];
        Random r = new Random(11);
        int[] cols = {0xFF2D3A5C, 0xFF3A2D5C, 0xFF2D5C54, 0xFF5C2D4A, 0xFF4A4F66};
        for (int i = 0; i < NP; i++) {
            pa[i] = (float) (i * 2 * Math.PI / NP + r.nextFloat() * 0.15f);
            pr[i] = 17f + r.nextFloat() * 6f;
            ph[i] = 3f + r.nextFloat() * 8f;
            pc[i] = cols[r.nextInt(cols.length)];
        }
    }

    private void solid(NvGunGfx.Mesh m, float x, float y, float z, float sx, float sy, float sz, int color, float dx, float dy, float dz) {
        Matrix.setIdentityM(mon, 0);
        Matrix.translateM(mon, 0, x - dx, y - dy, z - dz);
        part(m, 0, 0, 0, 0, 0, 0, sx, sy, sz, color, 0f);
    }

    private void drawScenery(float dx, float dy, float dz) {
        if (pa == null) initScenery();
        float fl = (core.state == NvGunCore.TITLE ? dz - NvGunCore.EYE_H : core.floorZ);
        float cx = (float) Math.floor(dx / 4f) * 4f, cy = (float) Math.floor(dy / 4f) * 4f;     // the grid is fixed in the world, the floor slides under you
        solid(g.box, dx, dy, fl - 0.02f, 60f, 60f, 0.02f, 0xFF1D2433, dx, dy, dz);
        for (int i = -10; i <= 10; i++) {
            solid(g.box, cx + i * 4f, dy, fl + 0.005f, 0.04f, 40f, 0.004f, 0xFF3C4A66, dx, dy, dz);
            solid(g.box, dx, cy + i * 4f, fl + 0.005f, 40f, 0.04f, 0.004f, 0xFF3C4A66, dx, dy, dz);
        }
        for (int i = 0; i < NP; i++) {
            float x = sceneX + (float) Math.cos(pa[i]) * pr[i], y = sceneY + (float) Math.sin(pa[i]) * pr[i];
            solid(g.box, x, y, fl + ph[i], 0.8f, 0.8f, ph[i], pc[i], dx, dy, dz);
            solid(g.sphere, x, y, fl + ph[i] * 2f + 0.3f, 0.35f, 0.35f, 0.35f, 0xFFFFC24D, dx, dy, dz);
        }
        solid(g.sphere, dx + 55f, dy + 40f, dz + 38f, 4f, 4f, 4f, 0xFFE8EEFF, dx, dy, dz);        // the moon stays with you
    }

    /** everything that sits in front of your face and turns with the head: the weapon, the crosshair, the hit mark, the damage arrows */
    private void drawViewLocked(float[] proj) {
        curVP = proj;
        final float D = 3f;                                      // the crosshair floats at this distance (metres)
        int cc = aimKind == 2 ? 0xFFFF4D4D : (aimKind == 1 ? 0xFFFFB347 : 0xFFFFFFFF);
        Matrix.setIdentityM(mon, 0);
        if (core.state == NvGunCore.PLAYING && (!handMode || hands.gunState == NvGunHands.HELD)) {
            Matrix.translateM(mon, 0, chX, chY, chZ + D);                       // the cross sits where the bullet goes (the middle of the screen in head mode)
            part(g.box, -0.14f, 0, -D, 0, 0, 0, 0.05f, 0.004f, 0.002f, cc, 1f);
            part(g.box, 0.14f, 0, -D, 0, 0, 0, 0.05f, 0.004f, 0.002f, cc, 1f);
            part(g.box, 0, 0.14f, -D, 0, 0, 0, 0.004f, 0.05f, 0.002f, cc, 1f);
            part(g.box, 0, -0.14f, -D, 0, 0, 0, 0.004f, 0.05f, 0.002f, cc, 1f);
            part(g.sphere, 0, 0, -D, 0, 0, 0, 0.007f, 0.007f, 0.007f, cc, 1f);
            if (hitT > 0f) {
                al = Math.min(1f, hitT / 0.14f);
                int hc = hitRed ? 0xFFFF4D4D : 0xFFFFFFFF;
                for (int i = 0; i < 4; i++) {
                    float sx = (i & 1) == 0 ? -1f : 1f, sy = (i & 2) == 0 ? -1f : 1f;
                    part(g.box, sx * 0.09f, sy * 0.09f, -D, 0, 0, sx * sy * 45f, 0.03f, 0.004f, 0.002f, hc, 1f);
                }
                al = 1f;
            }
        }
        if (core.state == NvGunCore.PLAYING) {
            Matrix.setIdentityM(mon, 0);
            // arrows around the middle of the screen that point at whoever hit you
            for (int i = 0; i < hurtAz.length; i++) {
                if (hurtT[i] <= 0f) continue;
                float rel = hurtAz[i] - yaw;
                al = Math.min(1f, hurtT[i] * 2f);
                part(g.box, (float) -Math.sin(rel) * 0.36f, (float) Math.cos(rel) * 0.24f, -1.2f, 0, 0, (float) Math.toDegrees(-rel), 0.05f, 0.012f, 0.004f, 0xFFFF3B3B, 1f);
                al = 1f;
            }
        }
        // ---- the weapon, low on the right (head mode, or hand mode when the gun hand is out of the camera's sight) ----
        if (!handMode || (hands.gunState == NvGunHands.HELD && !hands.aimFromHand)) {
            float pr = core.reloadT > 0f ? core.reloadProgress() : 0f;
            float tilt = core.reloadT > 0f ? (float) Math.sin(Math.PI * pr) : 0f;
            Matrix.setIdentityM(mon, 0);
            Matrix.translateM(mon, 0, 0.10f, -0.12f - 0.07f * tilt, -0.38f + 0.045f * kick);
            Matrix.rotateM(mon, 0, 7f * kick - 55f * tilt, 1f, 0f, 0f);
            Matrix.rotateM(mon, 0, 20f * tilt, 0f, 0f, 1f);
            if (core.weapon == NvGunCore.RIFLE) machineGunParts();
            else pistolParts();
            if (flashT > 0f) {
                al = Math.min(1f, flashT / 0.04f);
                boolean rifle = core.weapon == NvGunCore.RIFLE;
                part(g.sphere, 0, rifle ? 0.008f : 0.02f, rifle ? -0.55f : -0.18f, 0, 0, 0, 0.035f, 0.035f, 0.05f, 0xFFFFD84D, 1f);
                al = 1f;
            }
        }
        curVP = vp;
    }

    /** when you walk the edges of the picture go dark: the picture moves without your body moving, and the dark frame keeps that from making you ill */
    private void drawVignette(float[] proj) {
        float amount = handMode ? hands.walkSpeed01() : 0f;
        if (amount < 0.03f) return;
        float tanH = 1f / proj[0], tanV = 1f / proj[5], zz = 0.3f;
        float W = zz * tanH, H = zz * tanV, tw = W * 0.30f * amount, th = H * 0.30f * amount;
        curVP = proj; al = 0.85f * Math.min(1f, amount * 1.4f);
        Matrix.setIdentityM(mon, 0);
        part(g.box, -W + tw / 2f, 0, -zz, 0, 0, 0, tw / 2f, H * 1.2f, 0.001f, 0xFF000000, 0f);
        part(g.box, W - tw / 2f, 0, -zz, 0, 0, 0, tw / 2f, H * 1.2f, 0.001f, 0xFF000000, 0f);
        part(g.box, 0, H - th / 2f, -zz, 0, 0, 0, W * 1.2f, th / 2f, 0.001f, 0xFF000000, 0f);
        part(g.box, 0, -H + th / 2f, -zz, 0, 0, 0, W * 1.2f, th / 2f, 0.001f, 0xFF000000, 0f);
        al = 1f; curVP = vp;
    }

    @Override public void draw(float[] proj, float[] rn, float eyeX, float eyeY, float eyeZ) {
        if (!g.ready()) g.init();
        float dx = eyeX + walkX, dy = eyeY + walkY, dz = eyeZ;          // the eye that is drawn from: the host's eye (left or right) + how far you have walked
        Matrix.multiplyMM(vp, 0, proj, 0, rn, 0);
        curVP = vp; al = 1f;
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        g.begin();
        if (sceneryOn) drawScenery(dx, dy, dz);
        for (NvGunCore.Enemy e : core.enemies) drawEnemy(e, dx, dy, dz);
        for (Chunk c : chunks) {
            Matrix.setIdentityM(mon, 0);
            Matrix.translateM(mon, 0, c.x - dx, c.y - dy, c.z - dz);
            Matrix.rotateM(mon, 0, c.rot, 1f, 0.6f, 0.3f);
            al = Math.min(1f, c.life * 2.5f);
            part(g.box, 0, 0, 0, 0, 0, 0, c.size, c.size, c.size, c.color, 0f);
        }
        al = 1f;
        drawWorldGun(dx, dy, dz);
        drawTracer(dx, dy, dz);

        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT);                      // what is on your face must never sink into a monster
        drawViewLocked(proj);

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        drawVignette(proj);
        if (hurtFlash > 0f) {                                           // the whole picture flushes red for a moment
            Matrix.setIdentityM(mon, 0);
            curVP = proj; al = hurtFlash * 0.35f;
            part(g.box, 0, 0, -0.3f, 0, 0, 0, 2f, 2f, 0.001f, 0xFFFF1010, 0f);
            al = 1f; curVP = vp;
        }
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    /** the numbers panel, floating low in front of you (the standalone host calls this after draw(); PocketVR shows the HUD as a normal window instead) */
    public void drawHud(float[] proj) {
        Matrix.setIdentityM(tmp, 0);
        Matrix.translateM(tmp, 0, 0f, -0.42f, -1.3f);
        Matrix.multiplyMM(t2, 0, proj, 0, tmp, 0);
        hud.drawQuad(t2, 0.55f, 0.55f * NvGunHud.H / NvGunHud.W);
    }
}
