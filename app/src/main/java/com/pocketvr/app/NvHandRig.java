package com.pocketvr.app;

/**
 * A steady hand skeleton. No Android classes: it is tested on any computer.
 *
 * The hand model gives 21 points for every picture and every point jumps about on its own, so the fingers get longer and shorter from picture to picture
 * and shiver even when the hand is still. A real hand has bones of fixed length that only turn at the joints. This class
 *  1. learns the length of every bone of YOUR hand (as a part of the palm length) from pictures where the hand is open and faces the camera,
 *  2. rebuilds every picture's points from the wrist outwards with those lengths, so a finger can only bend, never stretch,
 *  3. smooths the DIRECTION of each bone as seen from the palm (not the point positions in the picture): when the whole hand moves fast the fingers
 *     are still for the palm, so they stay calm, and when a finger really moves (a trigger finger curling) its speed opens the filter and it follows.
 *
 * Points are in the hand model's picture units: x, y = 0..1 of the picture's width and height, z = depth in the units of x. ar = picture width / height.
 */
final class NvHandRig {
    /** parent -> child of every bone, from the wrist outwards (landmark numbers of the hand model); the middle finger's first bone is the palm length itself */
    static final int[] LINK = {
            0, 1, 1, 2, 2, 3, 3, 4,             // thumb
            0, 5, 5, 6, 6, 7, 7, 8,             // index
            0, 9, 9, 10, 10, 11, 11, 12,        // middle
            0, 13, 13, 14, 14, 15, 15, 16,      // ring
            0, 17, 17, 18, 18, 19, 19, 20};     // little
    static final int NL = LINK.length / 2;      // 20 bones
    private static final int PALM_LINK = 8;     // the link 0 -> 9 (index in the list of links): the unit of length
    private static final int[] PALM_SET = {0, 4, 8, 12, 16};   // links that start at the wrist: they also tell how big the hand is (they hardly bend)

    volatile boolean on = true;
    volatile boolean frozen = false;            // true = keep what was learned (no more learning)

    // ---- what is learned: every bone's length as a part of the palm length ----
    private final float[] ratio = new float[NL];
    private final float[][] ring = new float[NL][SAMPLES];
    private int ringN = 0, ringPos = 0;
    static final int SAMPLES = 40, NEED = 24;
    private volatile boolean learned = false;

    boolean isLearned() { return learned; }
    /** 0..1: how far the learning is */
    float progress() { return learned ? 1f : Math.min(1f, ringN / (float) NEED); }

    void resetLearning() {
        learned = false; ringN = 0; ringPos = 0;
        java.util.Arrays.fill(ratio, 0f);
        for (int i = 0; i < slots.length; i++) slots[i].have = false;
    }

    /** the learned lengths as text (kept in the settings), and back */
    String save() {
        if (!learned) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < NL; i++) { if (i > 0) b.append(','); b.append(Math.round(ratio[i] * 10000f) / 10000f); }
        return b.toString();
    }
    boolean load(String s) {
        try {
            if (s == null || s.isEmpty()) return false;
            String[] p = s.split(",");
            if (p.length != NL) return false;
            float[] r = new float[NL];
            for (int i = 0; i < NL; i++) { r[i] = Float.parseFloat(p[i]); if (!(r[i] > 0.05f && r[i] < 2.5f)) return false; }
            if (Math.abs(r[PALM_LINK] - 1f) > 0.01f) return false;
            System.arraycopy(r, 0, ratio, 0, NL);
            for (int i = 0; i < NL; i++) java.util.Arrays.fill(ring[i], r[i]);      // the window of samples starts from what was saved (later pictures only nudge it)
            ringN = SAMPLES; ringPos = 0; learned = true;
            return true;
        } catch (Throwable t) { return false; }
    }

    // ---- the state of each of the two hand places (the hands come ordered left to right in the picture) ----
    private static final class Slot {
        boolean have;
        long t;
        float wx, wy;                 // where the wrist was (to know that it is the same hand)
        final float[] loc = new float[NL * 3];     // filtered direction of every bone, in the palm's frame
        final float[] vel = new float[NL * 3];     // its filtered speed
        final float[] pd = new float[15], pv = new float[15];   // the five palm bones' offsets in the picture, in palm lengths (steadied), and their speed
        final float[] w = new float[3], wv = new float[3];      // the wrist, steadied, and its speed
        float sizeV;                                              // how fast the palm length changes (part per second)
        float ang, angV;                                         // the turn of the palm frame in the picture (radians), steadied, and its speed
        final float[] sign = new float[40];                      // which side of the picture each bone points to (+1 away from the camera, -1 towards it)
        float size;                   // the palm length, smoothed
    }
    private final Slot[] slots = {new Slot(), new Slot()};

    // ---- how calm / how quick ----
    static final float F_MIN = 0.8f;      // Hz: cut-off of a point that does not move
    static final float F_BETA = 2.5f;     // how much a moving bone opens the filter (Hz per unit/s)
    static final float F_D = 2.0f;        // Hz: cut-off of the speed measurement
    static final float W_MIN = 1.0f, W_BETA = 2.0f;   // the wrist: Hz, and Hz per (palm length / s)
    static final float D_MIN = 1.0f, D_BETA = 2.0f;   // the palm bones' directions: Hz, and Hz per (rad / s)
    static final float A_MIN = 0.8f, A_BETA = 2.5f;   // the turn of the palm frame: Hz, and Hz per (rad / s)
    static final float W0 = 0.45f;        // rad/s: a turn slower than this is taken as the model's shiver
    static final float S_MIN = 0.5f, S_BETA = 5.0f;   // the hand size: Hz, and Hz per (part of its size / s)
    static final float Z_SIZE = 0.35f;     // how much of the depth is used to measure the hand's size
    static final float V0 = 0.7f;         // palm lengths per second: slower than this counts as the model's own shiver (a hand drifting slowly is also calm), faster opens the filter
    static final float GAP_S = 0.30f;     // a gap longer than this starts the filters again

    private static float lpA(float dt, float fc) { float tau = 1f / (6.2831853f * fc); return 1f / (1f + tau / dt); }

    private final float[] P = new float[63], Q = new float[63];
    private final float[] e1 = new float[2], e2 = new float[2];

    private boolean fill(float[] res, int b, float ar) {
        for (int k = 0; k < 21; k++) {
            float x = res[b + k * 3], y = res[b + k * 3 + 1], z = res[b + k * 3 + 2];
            if (Float.isNaN(x + y + z) || Float.isInfinite(x + y + z)) return false;
            P[k * 3] = x * ar; P[k * 3 + 1] = y; P[k * 3 + 2] = z * ar;
        }
        return true;
    }

    private static float len(float[] a, int i, int j) {
        float dx = a[j * 3] - a[i * 3], dy = a[j * 3 + 1] - a[i * 3 + 1], dz = a[j * 3 + 2] - a[i * 3 + 2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** a hand that is open and faces the camera shows its bones at their true length: only such pictures are used for learning */
    private static boolean goodFor(float[] P, float s) {
        if (!(s > 0.03f)) return false;
        for (int f = 0; f < 4; f++) {                                           // every finger stretched: the tip is far from the wrist compared with the middle joint
            int m = 5 + f * 4;                                                    // the finger's first knuckle; the tip of a stretched finger is ~1.8 times as far from the wrist
            float a = len(P, 0, m + 3), b = len(P, 0, m);
            if (b < 1e-5f || a / b < 1.55f) return false;
        }
        float dz1 = Math.abs(P[5 * 3 + 2] - P[17 * 3 + 2]), dz2 = Math.abs(P[9 * 3 + 2] - P[2]);
        if (dz1 > 0.45f * s || dz2 > 0.45f * s) return false;                    // turned away from the camera: bones look short
        float w = len(P, 5, 17);
        return w > 0.55f * s && w < 1.2f * s;                                     // the palm is seen from the front (not edge-on)
    }

    /** is this hand (numbers of one hand in res, starting at b) open and facing the camera? (used by the shape learning, which needs the same kind of picture) */
    boolean openFront(float[] res, int b, float ar) {
        if (res == null || res.length < b + 63 || !(ar > 0.1f)) return false;
        float[] L = new float[63];
        for (int k = 0; k < 21; k++) {
            float x = res[b + k * 3], y = res[b + k * 3 + 1], z = res[b + k * 3 + 2];
            if (Float.isNaN(x + y + z) || Float.isInfinite(x + y + z)) return false;
            L[k * 3] = x * ar; L[k * 3 + 1] = y; L[k * 3 + 2] = z * ar;
        }
        return goodFor(L, len(L, 0, 9));
    }

    private static float len2(float[] a, int i, int j) {
        float dx = a[j * 3] - a[i * 3], dy = a[j * 3 + 1] - a[i * 3 + 1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private void learn() {
        float s = len2(P, 0, 9);     // (the hand faces the camera, so the picture's own lengths are the true lengths; the depth numbers are too noisy to measure with)
        for (int i = 0; i < NL; i++) ring[i][ringPos] = len2(P, LINK[i * 2], LINK[i * 2 + 1]) / s;
        ringPos = (ringPos + 1) % SAMPLES;
        if (ringN < SAMPLES) ringN++;
        if (ringN >= NEED) {
            float[] tmp = new float[ringN];
            for (int i = 0; i < NL; i++) {
                System.arraycopy(ring[i], 0, tmp, 0, ringN);
                java.util.Arrays.sort(tmp, 0, ringN);
                float med = tmp[ringN / 2];
                ratio[i] = learned ? ratio[i] + (med - ratio[i]) * 0.2f : med;     // after the first time, only a slow change
            }
            ratio[PALM_LINK] = 1f;
            learned = true;
        }
    }

    /**
     * Makes the points of one picture steady (in place). res = [number of hands, then 63 numbers (21 x,y,z) per hand], ts = the picture's time (ms).
     * Returns true if it changed something.
     */
    boolean process(float[] res, long ts, float ar) {
        if (!on || res == null || res.length < 64 || res[0] < 1 || !(ar > 0.1f)) return false;
        int n = Math.min(2, (int) res[0]);
        if (res.length < 1 + n * 63) return false;
        boolean any = false;
        int[] slotOf = new int[2];
        if (n == 1) {
            slotOf[0] = nearest(res, 1, ar);
        } else {
            float c0 = dist(slots[0], res, 1, ar) + dist(slots[1], res, 64, ar), c1 = dist(slots[1], res, 1, ar) + dist(slots[0], res, 64, ar);
            slotOf[0] = c1 < c0 ? 1 : 0; slotOf[1] = 1 - slotOf[0];
        }
        for (int h = 0; h < n; h++) {
            int b = 1 + h * 63;
            if (!fill(res, b, ar)) continue;
            Slot sl = slots[slotOf[h]];
            float s = len(P, 0, 9);
            if (!(s > 0.02f)) continue;
            if (!frozen && goodFor(P, s)) learn();
            if (!learned) { sl.have = false; sl.wx = P[0]; sl.wy = P[1]; sl.t = ts; continue; }
            if (rebuild(sl, ts, s)) {
                for (int k = 0; k < 21; k++) { res[b + k * 3] = Q[k * 3] / ar; res[b + k * 3 + 1] = Q[k * 3 + 1]; res[b + k * 3 + 2] = Q[k * 3 + 2] / ar; }
                any = true;
            }
        }
        return any;
    }

    private float dist(Slot s, float[] res, int b, float ar) {
        if (!s.have) return 0.5f;
        float dx = res[b] * ar - s.wx, dy = res[b + 1] - s.wy;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
    private int nearest(float[] res, int b, float ar) { return dist(slots[1], res, b, ar) < dist(slots[0], res, b, ar) ? 1 : 0; }

    private boolean rebuild(Slot sl, long ts, float sRaw) {
        // how big is the hand: every bone that starts at the wrist (and hardly bends) gives an estimate, which is steadier than one bone alone
        float sum = 0f; int cnt = 0;
        for (int q : PALM_SET) {                                  // (the depth counts little: it is far noisier than the picture's x and y, and the size multiplies everything)
            int a = LINK[q * 2], c = LINK[q * 2 + 1];
            float dx = P[c * 3] - P[a * 3], dy = P[c * 3 + 1] - P[a * 3 + 1], dz = (P[c * 3 + 2] - P[a * 3 + 2]) * Z_SIZE;
            sum += (float) Math.sqrt(dx * dx + dy * dy + dz * dz) / ratio[q]; cnt++;
        }
        float s = sum / cnt;
        float dt = (ts - sl.t) / 1000f;
        boolean fresh = !sl.have || dt <= 0f || dt > GAP_S || Math.abs(P[0] - sl.wx) + Math.abs(P[1] - sl.wy) > 0.45f;
        if (fresh) { sl.size = s; sl.sizeV = 0f; }
        else {                                                 // the size changes only when the hand comes nearer or goes away: calm, but it follows a real change
            float d2 = Math.max(0.004f, dt);
            sl.sizeV += ((s - sl.size) / sl.size / d2 - sl.sizeV) * lpA(d2, F_D);
            sl.size += (s - sl.size) * lpA(d2, S_MIN + S_BETA * Math.max(0f, Math.abs(sl.sizeV) - 0.25f));
        }
        float size = sl.size;
        dt = Math.max(0.004f, dt);

        // the wrist: calm when still, quick when the hand moves (speed counted in palm lengths per second)
        if (fresh) { sl.w[0] = P[0]; sl.w[1] = P[1]; sl.w[2] = P[2]; sl.wv[0] = sl.wv[1] = sl.wv[2] = 0f; }
        else {
            float aD = lpA(dt, F_D), sp = 0f;
            for (int c = 0; c < 3; c++) { float v = (P[c] - sl.w[c]) / dt / size; sl.wv[c] += (v - sl.wv[c]) * aD; sp += sl.wv[c] * sl.wv[c]; }
            float al = lpA(dt, W_MIN + W_BETA * Math.max(0f, (float) Math.sqrt(sp) - V0));
            for (int c = 0; c < 3; c++) sl.w[c] += (P[c] - sl.w[c]) * al;
        }
        Q[0] = sl.w[0]; Q[1] = sl.w[1]; Q[2] = sl.w[2];
        // the palm points (thumb root and the four knuckles): each one steadied as an offset from the wrist, in the picture's own directions (in palm lengths).
        // (Every point is steadied by itself from the wrist, not link after link: the noise of a chain of links would add up along the finger.)
        for (int pi = 0; pi < PALM_SET.length; pi++) {
            int q = PALM_SET[pi], c = LINK[q * 2 + 1];
            float ox = (P[c * 3] - Q[0]) / size, oy = (P[c * 3 + 1] - Q[1]) / size;
            int o = pi * 3;
            if (fresh) { sl.pd[o] = ox; sl.pd[o + 1] = oy; sl.pv[o] = sl.pv[o + 1] = 0f; }
            else {
                float aD = lpA(dt, F_D);
                float vx = (ox - sl.pd[o]) / dt, vy = (oy - sl.pd[o + 1]) / dt;
                sl.pv[o] += (vx - sl.pv[o]) * aD; sl.pv[o + 1] += (vy - sl.pv[o + 1]) * aD;
                float al = lpA(dt, D_MIN + D_BETA * Math.max(0f, (float) Math.sqrt(sl.pv[o] * sl.pv[o] + sl.pv[o + 1] * sl.pv[o + 1]) - V0));
                sl.pd[o] += (ox - sl.pd[o]) * al; sl.pd[o + 1] += (oy - sl.pd[o + 1]) * al;
            }
            put(q, Q[0] + sl.pd[o] * size, Q[1] + sl.pd[o + 1] * size, P[c * 3 + 2] - P[2], ratio[q] * size, sl, pi);
        }
        // the palm's frame in the picture: e1 along wrist -> the middle of the four knuckles, e2 across. Its turn is steadied by itself (a turn of the whole frame
        // by a hair moves the far fingertips most), and its scale is the (steady) palm length.
        float fx = Q[5 * 3] + Q[9 * 3] + Q[13 * 3] + Q[17 * 3] - 4f * Q[0], fy = Q[5 * 3 + 1] + Q[9 * 3 + 1] + Q[13 * 3 + 1] + Q[17 * 3 + 1] - 4f * Q[1];
        float th = (float) Math.atan2(fy, fx);
        if (fresh) { sl.ang = th; sl.angV = 0f; }
        else {
            float dth = th - sl.ang;
            while (dth > Math.PI) dth -= 6.2831853f;
            while (dth < -Math.PI) dth += 6.2831853f;
            sl.angV += (dth / dt - sl.angV) * lpA(dt, F_D);
            float al = lpA(dt, A_MIN + A_BETA * Math.max(0f, Math.abs(sl.angV) - W0));
            sl.ang += dth * al;
        }
        float sc = size;
        e1[0] = (float) Math.cos(sl.ang); e1[1] = (float) Math.sin(sl.ang); e2[0] = -e1[1]; e2[1] = e1[0];
        // the other points (the finger joints and tips): each one steadied as an offset from the wrist IN THE PALM'S FRAME. When the whole hand moves or turns, the
        // fingers do not move for the palm, so they stay calm; a finger that really moves (a trigger finger curling) has speed, and that opens the filter.
        for (int q = 0; q < NL; q++) {
            if (q == 0 || q == 4 || q == 8 || q == 12 || q == 16) continue;              // the palm points are done
            int a = LINK[q * 2], c = LINK[q * 2 + 1];
            float dx = P[c * 3] - Q[0], dy = P[c * 3 + 1] - Q[1];
            float la = (dx * e1[0] + dy * e1[1]) / sc, lb = (dx * e2[0] + dy * e2[1]) / sc;
            int o = q * 3;
            if (fresh) { sl.loc[o] = la; sl.loc[o + 1] = lb; sl.vel[o] = sl.vel[o + 1] = 0f; }
            else {
                float aD = lpA(dt, F_D);
                float va = (la - sl.loc[o]) / dt, vb = (lb - sl.loc[o + 1]) / dt;
                sl.vel[o] += (va - sl.vel[o]) * aD; sl.vel[o + 1] += (vb - sl.vel[o + 1]) * aD;
                float al = lpA(dt, F_MIN + F_BETA * Math.max(0f, (float) Math.sqrt(sl.vel[o] * sl.vel[o] + sl.vel[o + 1] * sl.vel[o + 1]) - V0));
                sl.loc[o] += (la - sl.loc[o]) * al; sl.loc[o + 1] += (lb - sl.loc[o + 1]) * al;
            }
            float tx = Q[0] + (sl.loc[o] * e1[0] + sl.loc[o + 1] * e2[0]) * sc, ty = Q[1] + (sl.loc[o] * e1[1] + sl.loc[o + 1] * e2[1]) * sc;
            put(q, tx, ty, P[c * 3 + 2] - P[a * 3 + 2], ratio[q] * size, sl, 5 + q);
        }
        sl.have = true; sl.t = ts; sl.wx = P[0]; sl.wy = P[1];
        return true;
    }

    /**
     * puts bone q's far point as near to the steadied target (tx, ty) as the bone's length allows, measured from its (already placed) near point:
     * the picture part is the target's direction pulled to the bone's length at most; the depth part follows from the length (a bone seen shorter than
     * it is lies partly along the viewing direction), and its sign is the model's (kept when the model is unsure of it)
     */
    private void put(int q, float tx, float ty, float dzModel, float length, Slot sl, int signIdx) {
        int a = LINK[q * 2], c = LINK[q * 2 + 1];
        float ox = tx - Q[a * 3], oy = ty - Q[a * 3 + 1];
        float l2 = ox * ox + oy * oy, L2 = length * length;
        float dz = 0f;
        if (l2 >= L2) {                                          // seen longer than it is: pulled back to its length
            float k = length / (float) Math.sqrt(Math.max(1e-12f, l2));
            ox *= k; oy *= k;
        } else {
            float m = (float) Math.sqrt(L2 - l2);
            float sg = sl.sign[signIdx];
            if (Math.abs(dzModel) > 0.4f * m || sg == 0f) sg = dzModel < 0f ? -1f : 1f;
            sl.sign[signIdx] = sg;
            dz = sg * m;
        }
        Q[c * 3] = Q[a * 3] + ox; Q[c * 3 + 1] = Q[a * 3 + 1] + oy; Q[c * 3 + 2] = Q[a * 3 + 2] + dz;
    }
}
