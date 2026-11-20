package com.pocketvr.app;

/**
 * What the hands do in the shooter. Pure Java (no Android), so it is tested on any computer with made-up hands (test/NvGunHandsTest).
 *
 *   - The gun hangs at your body (low, to the right, follows where you stand and slowly follows where you turn).
 *     Make a FIST on it (the fist must be where the gun is, seen from your eyes) and it jumps into that hand.
 *   - The gun points the way that hand is seen from your eyes (like a laser pointer): the hand is the crosshair.
 *   - Open the gun hand for half a second and the gun flies back to your body (put down).
 *   - The OTHER hand fires: hold the index finger out = ready, curl it in = fire (the machine gun keeps firing while it is curled),
 *     stretch it out again = stop.
 *   - Only while the gun is NOT in a hand: an open hand reached out in front of you walks you through the scene.
 *     You walk toward where the hand is seen; the further the arm is stretched, the faster.
 *
 * Input: setFrame() with the hand points as PocketVR's hand model gives them (up to two hands, 21 points each, picture units).
 * Everything is measured from the eyes: the hand's place is a ray through the point in the picture, with a length worked out from
 * the size of the palm (the same idea as Nv3DHands).
 */
public final class NvGunHands {
    public static final int HOLSTERED = 0, HELD = 1;

    private static final float PALM_L = 0.095f, PALM_W = 0.075f;   // a real palm: wrist -> middle knuckle, and across the knuckles (metres)
    private static final float EXT = 1.18f, CURL = 1.00f;          // a finger: tip-to-wrist / middle-joint-to-wrist; above EXT = out, below CURL = curled

    // ---- settings (change them freely) ----
    public float anchorF = 0.38f, anchorR = 0.18f, anchorD = 0.26f;  // where the gun hangs: forward, right, down from the eyes (body frame, metres)
    public float grabAngleDeg = 10f, grabDepth = 0.25f;              // a fist takes the gun when it is this close to it: angle seen from the eyes, difference in distance
    public float grabHoldMs = 120f, releaseHoldMs = 450f;            // how long the fist / the open hand must last
    public float walkMinDist = 0.42f, walkFullDist = 0.70f, walkMaxSpeed = 1.6f;   // arm reach (eyes -> hand) that starts walking / walks at full speed
    public float bodyYawTau = 0.8f;                                  // seconds the body takes to follow the head's turn
    public float tiltGain = 0f;                                      // 0 = the gun points exactly along eyes->hand. >0 lets the hand's own tilt (seen in the picture) bend it a bit
    public float handTimeoutMs = 250f;                               // a hand not seen for this long is gone

    // ---- results (read them after update) ----
    public int gunState = HOLSTERED;
    public boolean aimFromHand = false;                  // the gun hand is seen now: aim with it (otherwise the host aims with the head)
    public float aimX, aimY, aimZ;                       // the aim direction from the eyes (unit)
    public float gunX, gunY, gunZ, gunDX, gunDY, gunDZ;  // where to draw the gun and which way it points (unit); between the body and the hand while it flies
    public float gunBlend = 0f;                          // 0 = at the body, 1 = in the hand
    public boolean grabReady = false;                    // a hand is at the gun: show it lit up
    public boolean trigger = false;                      // the trigger hand has its index finger curled
    public boolean armed = false;
    public float walkSpeed = 0f, walkYaw = 0f;           // metres/second and the direction (radians, 0 = +X); read walkSpeed01 for a 0..1 amount
    public float bodyYaw = 0f;

    public static final class Hand {
        public boolean valid;
        public float px, py, pz;                         // the palm's centre in the world
        public float vx, vy, vz;                         // unit vector from the eyes to the palm
        public float dist;                               // eyes -> palm (metres), smoothed
        public float sx, sy;                             // the palm in the picture, -1..1 (x right, y up)
        public final float[] ratio = new float[4];       // index, middle, ring, pinky
        public final boolean[] ext = new boolean[4], curl = new boolean[4];
        public boolean fist, open;
        public float fistMs, openMs;
        public long seenMs = -1000000;
        float tx, ty;                                    // the hand's own direction in the picture (wrist -> middle knuckle), unit
    }
    public final Hand[] hand = {new Hand(), new Hand()};
    private int heldSlot = -1;

    // ---- the latest picture's hands ----
    private final float[] raw = new float[127];
    private int rawN = 0;
    private float rawAr = 1.333f, rawTH = 0.65f, rawTV = 0.49f;
    private boolean rawRot = false, rawNew = false;
    private long rawMs = 0;

    private boolean bodyInit = false;
    private float grabMs = 0f, curlMs = 0f;
    private float lastGripX, lastGripY, lastGripZ, lastDX = 1f, lastDY = 0f, lastDZ = 0f;
    private float ancX, ancY, ancZ, ancDX = 1f, ancDY = 0f;
    private boolean toggleReq = false;

    /** the hands of one picture: d[0] = how many, then 63 numbers (21 x,y,z) each, as the hand model gives them. aspect = picture width / height. */
    public synchronized void setFrame(float[] d, float aspect, float tanH, float tanV, boolean rot180, long nowMs) {
        int n = d == null ? 0 : Math.min(2, (int) d[0]);
        if (d != null && d.length < 1 + n * 63) n = 0;
        rawN = n;
        if (n > 0) System.arraycopy(d, 0, raw, 0, 1 + n * 63);
        rawAr = aspect; rawTH = tanH; rawTV = tanV; rawRot = rot180; rawMs = nowMs; rawNew = true;
    }

    /** draw / put the gun away without the hands (a key); with no hand seen the host aims with the head */
    public void requestToggle() { toggleReq = true; }

    public synchronized void reset() {
        gunState = HOLSTERED; heldSlot = -1; aimFromHand = false; trigger = false; armed = false; gunBlend = 0f; walkSpeed = 0f; grabReady = false; grabMs = 0f;
        for (Hand h : hand) { h.valid = false; h.seenMs = -1000000; h.fistMs = h.openMs = 0f; }
    }

    public float walkSpeed01() { return walkMaxSpeed <= 0f ? 0f : Math.min(1f, walkSpeed / walkMaxSpeed); }

    // ================= one step =================
    public synchronized void update(float dt, long nowMs, float[] rn, float ex, float ey, float ez) {
        float dtMs = dt * 1000f;
        // the head
        float fx = -rn[2], fy = -rn[6];
        if (Math.hypot(fx, fy) < 0.3) { fx = -rn[1]; fy = -rn[5]; }
        float headYaw = (float) Math.atan2(fy, fx);
        if (!bodyInit) { bodyYaw = headYaw; bodyInit = true; }
        else bodyYaw += wrap(headYaw - bodyYaw) * (1f - (float) Math.exp(-dt / Math.max(0.05f, bodyYawTau)));
        float bfx = (float) Math.cos(bodyYaw), bfy = (float) Math.sin(bodyYaw);
        ancX = ex + bfx * anchorF + bfy * anchorR; ancY = ey + bfy * anchorF - bfx * anchorR; ancZ = ez - anchorD;
        ancDX = bfx; ancDY = bfy;
        float ax = ancX - ex, ay = ancY - ey, az = ancZ - ez;
        float aDist = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        float anx = ax / aDist, any = ay / aDist, anz = az / aDist;

        if (rawNew) { rawNew = false; processFrame(nowMs, rn, ex, ey, ez); }

        // hands gone / poses lasting
        for (Hand h : hand) {
            h.valid = nowMs - h.seenMs < handTimeoutMs;
            if (!h.valid) { h.fistMs = h.openMs = 0f; h.fist = h.open = false; continue; }
            if (h.fist) h.fistMs += dtMs; else h.fistMs = 0f;
            if (h.open) h.openMs += dtMs; else h.openMs = 0f;
        }

        boolean wantWalk = false; float walkTarget = 0f, walkDir = walkYaw;
        grabReady = false;

        if (toggleReq) {
            toggleReq = false;
            if (gunState == HOLSTERED) { gunState = HELD; heldSlot = -1; grabMs = 0f; }
            else { gunState = HOLSTERED; heldSlot = -1; }
        }

        if (gunState == HOLSTERED) {
            trigger = false; armed = false; aimFromHand = false;
            for (int s = 0; s < 2; s++) {
                Hand h = hand[s];
                if (!h.valid) continue;
                float cos = h.vx * anx + h.vy * any + h.vz * anz;
                float ang = (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, cos))));
                boolean near = ang < grabAngleDeg && Math.abs(h.dist - aDist) < grabDepth;
                if (near) {
                    grabReady = true;
                    if (h.fist && h.fistMs >= grabHoldMs) { gunState = HELD; heldSlot = s; break; }
                } else if (h.open && h.openMs >= 150f && h.dist >= walkMinDist) {       // an open hand reached out: walk
                    float amount = Math.max(0f, Math.min(1f, (h.dist - walkMinDist) / Math.max(0.05f, walkFullDist - walkMinDist)));
                    if (amount * walkMaxSpeed > walkTarget) {
                        wantWalk = true; walkTarget = amount * walkMaxSpeed;
                        walkDir = (float) Math.atan2(h.vy, h.vx);
                    }
                }
            }
            gunBlend = Math.max(0f, gunBlend - dt / 0.25f);
        } else {
            // a gun taken with a key waits for a fist to adopt it
            if (heldSlot < 0) {
                for (int s = 0; s < 2; s++) if (hand[s].valid && hand[s].fist && hand[s].fistMs >= 300f) { heldSlot = s; break; }
            }
            Hand g = heldSlot >= 0 ? hand[heldSlot] : null;
            if (g != null && g.valid) {
                aimFromHand = true;
                float vx = g.vx, vy = g.vy, vz = g.vz;
                if (tiltGain != 0f) {                                                    // the hand's own tilt in the picture bends the barrel a little
                    float tvx = g.tx * rawTH, tvy = g.ty * rawTV;
                    float wx = rn[0] * tvx + rn[1] * tvy, wy = rn[4] * tvx + rn[5] * tvy, wz = rn[8] * tvx + rn[9] * tvy;
                    vx += wx * tiltGain; vy += wy * tiltGain; vz += wz * tiltGain;
                    float l = (float) Math.sqrt(vx * vx + vy * vy + vz * vz); vx /= l; vy /= l; vz /= l;
                }
                aimX = vx; aimY = vy; aimZ = vz;
                lastGripX = g.px; lastGripY = g.py; lastGripZ = g.pz; lastDX = vx; lastDY = vy; lastDZ = vz;
                if (g.open && g.openMs >= releaseHoldMs) { gunState = HOLSTERED; heldSlot = -1; aimFromHand = false; trigger = false; armed = false; }
                gunBlend = Math.min(1f, gunBlend + dt / 0.15f);
            } else {
                aimFromHand = false;
                gunBlend = Math.max(0f, gunBlend - dt / 0.25f);
            }
            // the other hand: finger out = ready, finger curled = fire
            trigger = false;
            if (gunState == HELD && heldSlot >= 0) {
                Hand t = hand[1 - heldSlot];
                if (t.valid) {
                    if (t.ext[0]) { armed = true; curlMs = 0f; }
                    trigger = armed && t.curl[0];
                    if (trigger) {
                        curlMs += dtMs;
                        if (curlMs > 10000f) { armed = false; trigger = false; }        // a finger curled for ten seconds is a hand at rest, not a burst: stretch the finger to arm again
                    }
                } else { armed = false; curlMs = 0f; }
            } else { armed = false; curlMs = 0f; }
        }

        // walking: smooth in and out
        float target = wantWalk ? walkTarget : 0f;
        walkSpeed += (target - walkSpeed) * (1f - (float) Math.exp(-dt / 0.2f));
        if (walkSpeed < 0.02f) walkSpeed = 0f;
        if (wantWalk) walkYaw = walkDir;

        // where to draw the gun: between the body and the hand
        float b = gunBlend;
        float gx0 = ancX, gy0 = ancY, gz0 = ancZ, dx0 = ancDX, dy0 = ancDY, dz0 = 0f;
        gunX = gx0 + (lastGripX - gx0) * b; gunY = gy0 + (lastGripY - gy0) * b; gunZ = gz0 + (lastGripZ - gz0) * b;
        float dx = dx0 + (lastDX - dx0) * b, dy = dy0 + (lastDY - dy0) * b, dz = dz0 + (lastDZ - dz0) * b;
        float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dl < 1e-4f) { dx = ancDX; dy = ancDY; dz = 0f; dl = 1f; }
        gunDX = dx / dl; gunDY = dy / dl; gunDZ = dz / dl;
    }

    // ================= reading the picture =================
    private static float wrap(float a) {
        while (a > Math.PI) a -= 2f * (float) Math.PI;
        while (a < -Math.PI) a += 2f * (float) Math.PI;
        return a;
    }

    private final float[] lx = new float[21], ly = new float[21], lz = new float[21];
    private final float[] cand = new float[2 * 9];
    private final Hand tmpA = new Hand(), tmpB = new Hand();

    private float d3(int a, int b) {
        float dx = (lx[a] - lx[b]) * rawAr, dy = ly[a] - ly[b], dz = (lz[a] - lz[b]) * rawAr;     // picture units, in picture heights
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void processFrame(long nowMs, float[] rn, float ex, float ey, float ez) {
        int n = rawN;
        if (n == 0) return;
        Hand[] fresh = {tmpA, tmpB};
        for (int i = 0; i < n; i++) {
            int base = 1 + i * 63;
            for (int k = 0; k < 21; k++) {
                float x = raw[base + k * 3], y = raw[base + k * 3 + 1];
                if (rawRot) { x = 1f - x; y = 1f - y; }
                lx[k] = x; ly[k] = y; lz[k] = raw[base + k * 3 + 2];
            }
            Hand h = fresh[i];
            // palm centre in the picture
            float cx = (lx[0] + lx[5] + lx[9] + lx[13] + lx[17]) / 5f, cy = (ly[0] + ly[5] + ly[9] + ly[13] + ly[17]) / 5f;
            h.sx = cx * 2f - 1f; h.sy = 1f - cy * 2f;
            // how far: from the size of the palm
            float est = Math.max(d3(0, 9) / PALM_L, d3(5, 17) / PALM_W);
            float rawDist = Math.max(0.12f, Math.min(1.4f, 1f / (2f * rawTV * Math.max(est, 1e-4f))));
            h.dist = rawDist;                                  // smoothed against the slot's old value below
            // the ray from the eyes through the palm
            float vx = h.sx * rawTH, vy = h.sy * rawTV, vz = -1f;
            float l = (float) Math.sqrt(vx * vx + vy * vy + 1f);
            vx /= l; vy /= l; vz /= l;
            h.vx = rn[0] * vx + rn[1] * vy + rn[2] * vz;
            h.vy = rn[4] * vx + rn[5] * vy + rn[6] * vz;
            h.vz = rn[8] * vx + rn[9] * vy + rn[10] * vz;
            // fingers
            for (int f = 0; f < 4; f++) {
                int mcp = 5 + f * 4;
                float pip = d3(0, mcp + 1);
                h.ratio[f] = pip < 1e-5f ? 1f : d3(0, mcp + 3) / pip;
            }
            // the hand's own direction in the picture (for tiltGain)
            float tx = lx[9] - lx[0], ty = -(ly[9] - ly[0]);
            float tl = (float) Math.sqrt(tx * tx + ty * ty);
            h.tx = tl > 1e-5f ? tx / tl : 0f; h.ty = tl > 1e-5f ? ty / tl : 0f;
        }
        // which of the two remembered hands is which: keep each hand in its slot by where it was
        int[] slot = {0, 1};
        if (n == 1) {
            slot[0] = nearestSlot(fresh[0], rn, ex, ey, ez, nowMs);
        } else {
            float c0 = cost(fresh[0], hand[0], nowMs, ex, ey, ez, rn) + cost(fresh[1], hand[1], nowMs, ex, ey, ez, rn);
            float c1 = cost(fresh[0], hand[1], nowMs, ex, ey, ez, rn) + cost(fresh[1], hand[0], nowMs, ex, ey, ez, rn);
            if (c1 < c0) { slot[0] = 1; slot[1] = 0; }
        }
        for (int i = 0; i < n; i++) store(fresh[i], hand[slot[i]], nowMs, ex, ey, ez);
    }

    private float cost(Hand f, Hand old, long nowMs, float ex, float ey, float ez, float[] rn) {
        if (nowMs - old.seenMs > 700) return 0.25f;                          // an unknown slot: a mild cost, so a known one that is close wins
        float dist = f.dist;
        float qx = ex + f.vx * dist - old.px, qy = ey + f.vy * dist - old.py, qz = ez + f.vz * dist - old.pz;
        return (float) Math.sqrt(qx * qx + qy * qy + qz * qz);
    }

    private int nearestSlot(Hand f, float[] rn, float ex, float ey, float ez, long nowMs) {
        float c0 = cost(f, hand[0], nowMs, ex, ey, ez, rn), c1 = cost(f, hand[1], nowMs, ex, ey, ez, rn);
        return c1 < c0 ? 1 : 0;
    }

    private void store(Hand f, Hand h, long nowMs, float ex, float ey, float ez) {
        boolean wasLive = nowMs - h.seenMs < 700;
        h.sx = f.sx; h.sy = f.sy; h.vx = f.vx; h.vy = f.vy; h.vz = f.vz; h.tx = f.tx; h.ty = f.ty;
        h.dist = wasLive ? h.dist + (f.dist - h.dist) * 0.35f : f.dist;
        h.px = ex + h.vx * h.dist; h.py = ey + h.vy * h.dist; h.pz = ez + h.vz * h.dist;
        int nExt = 0, nCurl = 0;
        for (int k = 0; k < 4; k++) {
            h.ratio[k] = f.ratio[k];
            if (f.ratio[k] > EXT) { h.ext[k] = true; h.curl[k] = false; }
            else if (f.ratio[k] < CURL) { h.curl[k] = true; h.ext[k] = false; }
            if (h.ext[k]) nExt++;
            if (h.curl[k]) nCurl++;
        }
        h.fist = nCurl >= 3;
        h.open = nExt >= 3;
        h.seenMs = nowMs;
        h.valid = true;
    }
}
