package com.pocketvr.app;

/**
 * Remembers HOW THICK this person's fingers are, from the real pixels, so the outline can afterwards be drawn from the (steady) skeleton and these widths alone,
 * without cutting the hand out of the camera picture again every frame. No Android classes: it is tested on any computer.
 *
 * It learns from the hand cut that NvSeg makes (a small S x S picture, 0..255 = how likely a pixel is hand): at the middle of every finger bone it walks to both
 * sides, across the finger, until the cut ends, and so measures the finger's width (as a part of the palm length). Only pictures of an open hand that
 * faces the camera are used (the caller says so), the middle value of many pictures is kept, and a finger that touches its neighbour (the walk does not end) is not counted.
 */
final class NvHandShape {
    /** the landmark at the far end of the bones whose width is learnt (the number is the index in the renderer's list of widths) */
    static final int[] LEARN = {2, 3, 4, 6, 7, 8, 10, 11, 12, 14, 15, 16, 18, 19, 20, 17};
    private static final int[] PARENT = {1, 2, 3, 5, 6, 7, 9, 10, 11, 13, 14, 15, 17, 18, 19, 0};   // the near end of the same bones (the last one, 0 -> 17, is the little-finger edge of the palm)
    private static final int RING = 31, NEED = 12, MIN_BONES = 8;     // learned when 8 of the 16 bones have been measured well (the first bone of every finger is hidden by its neighbours, it follows the others)
    static final float LO = 0.035f, HI = 0.16f;

    private final float[][] ring = new float[LEARN.length][RING];
    private final int[] cnt = new int[LEARN.length], pos = new int[LEARN.length];
    private volatile boolean learned = false;
    private final float[] w = new float[21];
    /** half width of the bone ending at each landmark, in palm lengths (wrist to the root of the middle finger), used until the real ones are learnt: fingers get thinner towards the tip, palm lines are wide */
    static final float[] DEFAULT = {0f, 0.10f, 0.11f, 0.105f, 0.09f, 0.10f, 0.088f, 0.078f, 0.068f, 0.10f, 0.092f, 0.082f, 0.07f, 0.10f, 0.088f, 0.078f, 0.066f, 0.10f, 0.072f, 0.064f, 0.056f};

    NvHandShape() { System.arraycopy(DEFAULT, 0, w, 0, 21); }

    boolean isLearned() { return learned; }
    float progress() {
        if (learned) return 1f;
        float sum = 0f; for (int c : cnt) sum += Math.min(c, NEED);
        return Math.min(1f, sum / (NEED * (float) MIN_BONES));
    }
    /** the half widths now (a part of the palm length) for every landmark: the learnt ones where known, the given defaults elsewhere */
    float[] widths() { return w; }

    void reset() {
        learned = false;
        java.util.Arrays.fill(cnt, 0); java.util.Arrays.fill(pos, 0);
        System.arraycopy(DEFAULT, 0, w, 0, 21);
    }

    String save() {
        if (!learned) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < LEARN.length; i++) { if (i > 0) b.append(','); b.append(Math.round(w[LEARN[i]] * 10000f) / 10000f); }
        return b.toString();
    }
    boolean load(String s) {
        try {
            if (s == null || s.isEmpty()) return false;
            String[] p = s.split(",");
            if (p.length != LEARN.length) return false;
            float[] v = new float[LEARN.length];
            for (int i = 0; i < v.length; i++) { v[i] = Float.parseFloat(p[i]); if (!(v[i] >= LO && v[i] <= HI)) return false; }
            for (int i = 0; i < v.length; i++) { w[LEARN[i]] = v[i]; java.util.Arrays.fill(ring[i], v[i]); cnt[i] = RING; pos[i] = 0; }
            learned = true;
            return true;
        } catch (Throwable t) { return false; }
    }

    private static float at(byte[] p, int S, float x, float y) {      // the cut's value at a place (bilinear), 0..1
        if (x < 0f || y < 0f || x > S - 1.001f || y > S - 1.001f) return 0f;
        int x0 = (int) x, y0 = (int) y; float fx = x - x0, fy = y - y0;
        float a = (p[y0 * S + x0] & 255), b = (p[y0 * S + x0 + 1] & 255), c = (p[(y0 + 1) * S + x0] & 255), d = (p[(y0 + 1) * S + x0 + 1] & 255);
        return ((a + (b - a) * fx) * (1f - fy) + (c + (d - c) * fx) * fy) / 255f;
    }

    /** walks from (cx, cy) along (dx, dy) until the cut falls below one half; returns the distance, or -1 if it does not end within max */
    private static float run(byte[] p, int S, float cx, float cy, float dx, float dy, float max) {
        float prev = at(p, S, cx, cy);
        for (float t = 0.25f; t <= max; t += 0.25f) {
            float v = at(p, S, cx + dx * t, cy + dy * t);
            if (v < 0.5f) { float f = (prev - 0.5f) / Math.max(1e-4f, prev - v); return t - 0.25f + 0.25f * f; }
            prev = v;
        }
        return -1f;
    }

    /**
     * One cut of an open hand that faces the camera. p = S x S cut, q = the 21 points in that picture's pixels (x, y pairs), size = wrist to the middle finger's root in the same pixels.
     */
    void add(byte[] p, int S, float[] q, float size) {
        if (p == null || q == null || q.length < 42 || !(size > 8f)) return;
        float cx0 = (q[0] + q[10] + q[18] + q[26] + q[34]) / 5f, cy0 = (q[1] + q[11] + q[19] + q[27] + q[35]) / 5f;     // the palm's middle
        float max = 0.30f * size;
        for (int i = 0; i < LEARN.length; i++) {
            int a = PARENT[i], b = LEARN[i];
            float ax = q[a * 2], ay = q[a * 2 + 1], bx = q[b * 2], by = q[b * 2 + 1];
            float dx = bx - ax, dy = by - ay, l = (float) Math.sqrt(dx * dx + dy * dy);
            if (l < 2f) continue;
            dx /= l; dy /= l;
            float nx = -dy, ny = dx;
            float cx = (ax + bx) / 2f, cy = (ay + by) / 2f;
            if (at(p, S, cx, cy) < 0.5f) continue;                    // the middle of the bone is not hand in the cut: the cut is not good here
            float r1 = run(p, S, cx, cy, nx, ny, max), r2 = run(p, S, cx, cy, -nx, -ny, max);
            float v;
            if (b == 17) {                                             // the palm's edge on the little finger's side: only the way out (away from the palm's middle) counts
                float sgn = ((cx - cx0) * nx + (cy - cy0) * ny) >= 0f ? 1f : -1f;
                float ro = sgn > 0f ? r1 : r2;
                if (ro < 0f) continue;
                v = ro / size;
            } else {
                if (r1 < 0f || r2 < 0f) continue;                      // the finger touches another one (or the cut spills): not a clean measurement
                if (Math.abs(r1 - r2) > 0.6f * Math.max(r1, r2)) continue;   // the bone is not in the middle of the finger: the skeleton is off
                v = 0.5f * (r1 + r2) / size;
            }
            if (!(v >= LO && v <= HI)) continue;
            ring[i][pos[i]] = v; pos[i] = (pos[i] + 1) % RING; if (cnt[i] < RING) cnt[i]++;
        }
        int good = 0;
        for (int i = 0; i < LEARN.length; i++) if (cnt[i] >= NEED) good++;
        if (good < MIN_BONES) return;
        float[] tmp = new float[RING], med = new float[LEARN.length], gs = new float[6], gn = new float[6];
        float all = 0f; int alln = 0;
        for (int i = 0; i < LEARN.length; i++) {
            if (cnt[i] < NEED) { med[i] = -1f; continue; }
            int n = cnt[i];
            System.arraycopy(ring[i], 0, tmp, 0, n);
            java.util.Arrays.sort(tmp, 0, n);
            med[i] = tmp[n / 2];
            float sc = med[i] / DEFAULT[LEARN[i]];
            gs[i / 3] += sc; gn[i / 3]++; all += sc; alln++;
        }
        all /= alln;
        for (int i = 0; i < LEARN.length; i++) {
            float v = med[i];
            if (v < 0f) { int g = i / 3; float sc = gn[g] > 0 ? gs[g] / gn[g] : all; v = DEFAULT[LEARN[i]] * sc; }      // a bone that could not be measured: as thick as the others of its finger, compared with the default
            w[LEARN[i]] = Math.max(LO, Math.min(HI, v));
        }
        learned = true;
    }
}
