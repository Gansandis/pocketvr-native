package com.pocketvr.app;

/**
 * Follows the hands between two answers of the hand model, at the camera's own rate.
 * The hand model needs ~70-100 ms per picture on this kind of phone, so its answers are old when they arrive and come only ~10 times a second.
 * This class looks at how the little grey picture moved from one camera frame to the next (block matching, to a fraction of a pixel),
 * and adds the moves up as a fraction of the picture size. The renderer then moves the model's points on by "moves since the picture
 * that answer belongs to": the hand shows up where it is NOW, not where it was 70+ ms ago.
 *
 * Every hand has two followers of its own: one on the palm (wrist and knuckles) and one on the fingers. Two hands that move in different
 * ways do not pull on each other, and when a hand turns or rolls, the fingers move more than the palm: the difference between the two
 * followers tells how much each point has to be moved (see kFactors / shiftOf).
 * No Android classes here, so it can be tested anywhere.
 */
final class NvFlow {
    static final int GW = 192, GH = 108;
    private static final int R = 10;               // largest move per frame that is looked for, in grey pixels (about 5 % of the picture width)
    private static final int HN = 96;
    static final int NTRK = 4;                     // follower k: hand k / 2, part k % 2 (0 = palm, 1 = fingers)

    private byte[] prev = new byte[GW * GH];
    private boolean havePrev = false;
    private final float[] cx = new float[NTRK], cy = new float[NTRK];
    private final float[][] ghx = new float[NTRK][HN], ghy = new float[NTRK][HN];
    private final long[] ht = new long[HN];
    private int hn = 0, hi = 0;
    private final int[] fails = new int[NTRK];
    private final boolean[] valid = new boolean[NTRK];
    private long lastT = 0;
    private final int[] sadBuf = new int[(2 * R + 1) * (2 * R + 1)];
    private final float[] mv = new float[2];

    synchronized boolean valid(int k) { return valid[k]; }
    synchronized int validMask() { int m = 0; for (int k = 0; k < NTRK; k++) if (valid[k]) m |= 1 << k; return m; }
    synchronized long lastTime() { return lastT; }
    synchronized float cumX(int k) { return cx[k]; }
    synchronized float cumY(int k) { return cy[k]; }

    synchronized void reset() {
        havePrev = false; hn = 0; hi = 0;
        for (int k = 0; k < NTRK; k++) { cx[k] = 0f; cy[k] = 0f; fails[k] = 0; valid[k] = false; }
        for (int k = 0; k < NT; k++) { tok[k] = false; tcx[k] = 0f; tcy[k] = 0f; tfl[k] = 0; }
    }

    /** the moves of follower k added up as they were at time t (between two frames: in between). Out of range: the nearest one. */
    synchronized void cumAt(int k, long t, float[] out) { interp(t, ghx[k], ghy[k], cx[k], cy[k], out); }

    private void interp(long t, float[] ax, float[] ay, float nowX, float nowY, float[] out) {
        if (hn == 0) { out[0] = nowX; out[1] = nowY; return; }
        int newest = (hi - 1 + HN) % HN, oldest = hn < HN ? 0 : hi;
        if (t >= ht[newest]) { out[0] = ax[newest]; out[1] = ay[newest]; return; }
        if (t <= ht[oldest]) { out[0] = ax[oldest]; out[1] = ay[oldest]; return; }
        int a = oldest;
        for (int k = 0; k < hn - 1; k++) {
            int i0 = (oldest + k) % HN, i1 = (oldest + k + 1) % HN;
            if (ht[i1] >= t) { a = i0; break; }
        }
        int b = (a + 1) % HN;
        float f = ht[b] == ht[a] ? 0f : (float) (t - ht[a]) / (ht[b] - ht[a]);
        out[0] = ax[a] + (ax[b] - ax[a]) * f;
        out[1] = ay[a] + (ay[b] - ay[a]) * f;
    }

    // ---- which landmarks belong to the palm (the others are the fingers) ----
    private static final boolean[] PALM = new boolean[21];
    static { for (int i : new int[]{0, 1, 2, 5, 9, 13, 17}) PALM[i] = true; }

    /**
     * How far each of the 21 points of one hand is moved by the difference "fingers moved minus palm moved":
     * 0 = like the palm, 1 = like the middle of the fingers, more = further out along the hand (a turn of the hand moves the tips most),
     * negative = on the other side of the palm's middle (the wrist moves the other way). r/base: the model's points as in `result`.
     */
    static void kFactors(float[] r, int base, float[] k) {
        float ps = 0f, pt = 0f, fs = 0f, ft = 0f; int pn = 0, fn = 0;
        for (int lm = 0; lm < 21; lm++) {
            float x = r[base + lm * 3] * GW, y = r[base + lm * 3 + 1] * GH;
            if (PALM[lm]) { ps += x; pt += y; pn++; } else { fs += x; ft += y; fn++; }
        }
        float pcx = ps / pn, pcy = pt / pn, fcx = fs / fn, fcy = ft / fn;
        float ax = fcx - pcx, ay = fcy - pcy, l2 = ax * ax + ay * ay;
        for (int lm = 0; lm < 21; lm++) {
            if (l2 < 4f) { k[lm] = PALM[lm] ? 0f : 1f; continue; }
            float x = r[base + lm * 3] * GW, y = r[base + lm * 3 + 1] * GH;
            float v = ((x - pcx) * ax + (y - pcy) * ay) / l2;
            k[lm] = Math.max(-1f, Math.min(1.8f, v));
        }
    }

    /**
     * The move of one point, from what the palm follower (pdx, pdy) and the fingers follower (fdx, fdy) have seen since the model's picture.
     * Both there: palm + k * (fingers - palm); one there: that one for every point; none: false (nothing to go by).
     */
    static boolean shiftOf(float k, boolean pok, float pdx, float pdy, boolean fok, float fdx, float fdy, float[] out) {
        float x, y;
        if (pok && fok) {
            float dx = Math.max(-0.05f, Math.min(0.05f, fdx - pdx)), dy = Math.max(-0.05f, Math.min(0.05f, fdy - pdy));   // the two cannot differ by more than this: a wild difference is a wrong match
            x = pdx + k * dx; y = pdy + k * dy;
        } else if (pok) { x = pdx; y = pdy; }
        else if (fok) { x = fdx; y = fdy; }
        else return false;
        out[0] = Math.max(-0.35f, Math.min(0.35f, x));
        out[1] = Math.max(-0.35f, Math.min(0.35f, y));
        return true;
    }

    // ---- fingertips (thumb, index, middle of up to two hands): each one is followed on its own, so a pinch closing is seen at the camera rate too ----
    static final int NT = 6;
    private final float[] tcx = new float[NT], tcy = new float[NT];
    private final float[][] thx = new float[NT][HN], thy = new float[NT][HN];
    private final boolean[] tok = new boolean[NT];
    private final int[] tfl = new int[NT];

    synchronized void tipNow(float[] out) { for (int k = 0; k < NT; k++) { out[k * 3] = tcx[k]; out[k * 3 + 1] = tcy[k]; out[k * 3 + 2] = tok[k] ? 1f : 0f; } }
    synchronized void tipAt(int k, long t, float[] out) { interp(t, thx[k], thy[k], tcx[k], tcy[k], out); }
    synchronized boolean tipValid(int k) { return tok[k]; }

    /** tips: NT pairs of (x, y) as fractions of the picture = where each fingertip was in the previous frame; NaN = no such finger */
    private static final int[] DISC_DX, DISC_DY;
    static {
        int n = 0;
        int[] tx = new int[400], ty = new int[400];
        for (int j = -5; j <= 5; j++) for (int i = -5; i <= 5; i++) if (i * i + j * j <= 16) { tx[n] = i; ty[n] = j; n++; }     // a round patch, radius 4: mostly fingertip, little background
        DISC_DX = java.util.Arrays.copyOf(tx, n); DISC_DY = java.util.Arrays.copyOf(ty, n);
    }

    private void trackTips(byte[] gray, float[] tips) {
        final int P = 5, R2 = 5, S = 2 * R2 + 1, N = DISC_DX.length;
        for (int k = 0; k < NT; k++) {
            if (tips == null || Float.isNaN(tips[k * 2]) || Float.isNaN(tips[k * 2 + 1])) { tok[k] = false; tfl[k] = 0; continue; }
            int cx0 = Math.round(tips[k * 2] * GW), cy0 = Math.round(tips[k * 2 + 1] * GH);
            if (cx0 < P + R2 + 1 || cx0 > GW - P - R2 - 2 || cy0 < P + R2 + 1 || cy0 > GH - P - R2 - 2) { tok[k] = false; continue; }
            int sum = 0;
            for (int q = 0; q < N; q++) sum += prev[(cy0 + DISC_DY[q]) * GW + cx0 + DISC_DX[q]] & 0xFF;
            int mean = sum / N, dev = 0;
            for (int q = 0; q < N; q++) dev += Math.abs((prev[(cy0 + DISC_DY[q]) * GW + cx0 + DISC_DX[q]] & 0xFF) - mean);
            boolean ok = false;
            if ((float) dev / N > 3.5f) {
                int[] sad = new int[S * S];
                int best = Integer.MAX_VALUE, bi = 0;
                long tot = 0;
                for (int dy = -R2; dy <= R2; dy++) for (int dx = -R2; dx <= R2; dx++) {
                    int sd = 0;
                    int off = dy * GW + dx;
                    for (int q = 0; q < N; q++) {
                        int p0 = (cy0 + DISC_DY[q]) * GW + cx0 + DISC_DX[q];
                        int d = (prev[p0] & 0xFF) - (gray[p0 + off] & 0xFF);
                        sd += d < 0 ? -d : d;
                    }
                    int idx = (dy + R2) * S + (dx + R2);
                    sad[idx] = sd; tot += sd;
                    if (sd < best) { best = sd; bi = idx; }
                }
                int bx = bi % S, by = bi / S;
                if (bx > 0 && bx < S - 1 && by > 0 && by < S - 1 && best < 0.75f * ((float) tot / (S * S))) {
                    tcx[k] += ((bx - R2) + sub(sad[by * S + bx - 1], sad[bi], sad[by * S + bx + 1])) / GW;
                    tcy[k] += ((by - R2) + sub(sad[(by - 1) * S + bx], sad[bi], sad[(by + 1) * S + bx])) / GH;
                    ok = true;
                }
            }
            if (ok) { tfl[k] = 0; tok[k] = true; }
            else if (++tfl[k] > 3) tok[k] = false;
        }
    }

    private void push(long t) {
        ht[hi] = t;
        for (int k = 0; k < NTRK; k++) { ghx[k][hi] = cx[k]; ghy[k][hi] = cy[k]; }
        for (int k = 0; k < NT; k++) { thx[k][hi] = tcx[k]; thy[k][hi] = tcy[k]; }
        hi = (hi + 1) % HN;
        if (hn < HN) hn++;
    }

    private static final int[][] BONES = {{0,1},{1,2},{2,3},{3,4},{0,5},{5,6},{6,7},{7,8},{9,10},{10,11},{11,12},{13,14},{14,15},{15,16},{0,17},{17,18},{18,19},{19,20},{5,9},{9,13},{13,17}};
    private final byte[] mask = new byte[GW * GH];
    private int[] spx = new int[4096], spy = new int[4096];

    private void disc(float cx0, float cy0, float r, int x0, int y0, int x1, int y1) {
        int ya = Math.max(y0, (int) (cy0 - r)), yb = Math.min(y1 - 1, (int) (cy0 + r) + 1);
        int xa = Math.max(x0, (int) (cx0 - r)), xb = Math.min(x1 - 1, (int) (cx0 + r) + 1);
        float r2 = r * r;
        for (int y = ya; y <= yb; y++) for (int x = xa; x <= xb; x++) { float dx = x - cx0, dy = y - cy0; if (dx * dx + dy * dy <= r2) mask[y * GW + x] = 1; }
    }

    /**
     * Follows one part (reg 0 = palm, 1 = fingers) of one hand: only the pixels that belong to that part are compared (round patches along the
     * bones), so a background that does not move does not pull the answer towards "nothing moved".
     * pts: 21 points per hand (x, y as fractions of the picture), up to two hands.
     */
    private boolean trackRegion(byte[] gray, float[] pts, int h, int reg, int k) {
        int o = h * 42;
        boolean wantPalm = reg == 0;
        float palm = (float) Math.hypot((pts[o + 18] - pts[o]) * GW, (pts[o + 19] - pts[o + 1]) * GH);       // wrist to middle-finger knuckle
        float rad = Math.max(3f, palm * 0.28f);
        float bx0 = 1e9f, by0 = 1e9f, bx1 = -1e9f, by1 = -1e9f;
        for (int lm = 0; lm < 21; lm++) {
            if (PALM[lm] != wantPalm) continue;
            float x = pts[o + lm * 2] * GW, y = pts[o + lm * 2 + 1] * GH;
            if (x < bx0) bx0 = x; if (x > bx1) bx1 = x;
            if (y < by0) by0 = y; if (y > by1) by1 = y;
        }
        int x0 = Math.max(R + 1, (int) (bx0 - rad - 1f)), x1 = Math.min(GW - R - 2, (int) (bx1 + rad + 2f));
        int y0 = Math.max(R + 1, (int) (by0 - rad - 1f)), y1 = Math.min(GH - R - 2, (int) (by1 + rad + 2f));
        if (x1 - x0 < 8 || y1 - y0 < 8) return false;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) mask[y * GW + x] = 0;
        for (int lm = 0; lm < 21; lm++) if (PALM[lm] == wantPalm) disc(pts[o + lm * 2] * GW, pts[o + lm * 2 + 1] * GH, rad, x0, y0, x1, y1);
        for (int[] bn : BONES) {
            if (PALM[bn[1]] != wantPalm) continue;       // a bone belongs to the part its far end is in
            disc((pts[o + bn[0] * 2] + pts[o + bn[1] * 2]) * 0.5f * GW, (pts[o + bn[0] * 2 + 1] + pts[o + bn[1] * 2 + 1]) * 0.5f * GH, rad, x0, y0, x1, y1);
        }
        if (wantPalm) {                                  // fill the palm: between the wrist and the knuckles
            disc((pts[o] * 0.5f + pts[o + 18] * 0.5f) * GW, (pts[o + 1] * 0.5f + pts[o + 19] * 0.5f) * GH, rad * 1.1f, x0, y0, x1, y1);
            disc((pts[o] * 0.5f + pts[o + 26] * 0.5f) * GW, (pts[o + 1] * 0.5f + pts[o + 27] * 0.5f) * GH, rad, x0, y0, x1, y1);
        }
        int n = 0;
        for (int y = y0; y < y1; y += 2) for (int x = x0; x < x1; x += 2) {
            if (mask[y * GW + x] != 0) { if (n >= spx.length) break; spx[n] = x; spy[n] = y; n++; }
        }
        if (n < 40) return false;
        // is there enough to look at? (a flat patch cannot be followed)
        int sum = 0;
        for (int i = 0; i < n; i++) sum += prev[spy[i] * GW + spx[i]] & 0xFF;
        int mean = sum / n, dev = 0;
        for (int i = 0; i < n; i++) dev += Math.abs((prev[spy[i] * GW + spx[i]] & 0xFF) - mean);
        if ((float) dev / n <= 3.0f) return false;
        final int S = 2 * R + 1;
        int[] sad = sadBuf;
        int best = Integer.MAX_VALUE, bi = 0;
        long tot = 0;
        for (int dy = -R; dy <= R; dy++) {
            for (int dx = -R; dx <= R; dx++) {
                int s = 0;
                int off = dy * GW + dx;
                for (int i = 0; i < n; i++) {
                    int p0 = spy[i] * GW + spx[i];
                    int d = (prev[p0] & 0xFF) - (gray[p0 + off] & 0xFF);
                    s += d < 0 ? -d : d;
                }
                int idx = (dy + R) * S + (dx + R);
                sad[idx] = s; tot += s;
                if (s < best) { best = s; bi = idx; }
            }
        }
        int bx = bi % S, by = bi / S;
        float mean2 = (float) tot / (S * S);
        // clear single minimum, and not on the edge of what is searched (a bigger move than that cannot be told)
        if (bx > 0 && bx < S - 1 && by > 0 && by < S - 1 && best < 0.8f * mean2) {
            float sx = sub(sad[by * S + bx - 1], sad[bi], sad[by * S + bx + 1]);
            float sy = sub(sad[(by - 1) * S + bx], sad[bi], sad[(by + 1) * S + bx]);
            cx[k] += ((bx - R) + sx) / GW;
            cy[k] += ((by - R) + sy) / GH;
            return true;
        }
        return false;
    }

    /**
     * gray: GW*GH picture of this frame; t: when the frame was taken (ms);
     * pts: the hands' points (21 per hand: x, y as fractions of the picture, where the hand was in the previous frame), up to two hands; null = no hand;
     * tips: see trackTips.
     */
    synchronized void feed(byte[] gray, long t, float[] pts, float[] tips) {
        lastT = t;
        int nh = pts == null ? 0 : Math.min(2, pts.length / 42);
        for (int k = 0; k < NTRK; k++) {
            int h = k >> 1;
            if (h >= nh) { valid[k] = false; fails[k] = 0; continue; }
            if (!havePrev) continue;
            boolean ok = false;
            try { ok = trackRegion(gray, pts, h, k & 1, k); } catch (RuntimeException e) { ok = false; }
            if (ok) { fails[k] = 0; valid[k] = true; }
            else if (++fails[k] > 6) valid[k] = false;        // lost for a while: say so, the renderer goes back to guessing from speed
        }
        if (havePrev) trackTips(gray, tips);
        System.arraycopy(gray, 0, prev, 0, GW * GH);
        havePrev = true;
        push(t);
    }

    /** where the lowest point of a parabola through three values lies (-0.5 .. 0.5) */
    private static float sub(int a, int b, int c) {
        float d = a - 2f * b + c;
        if (d <= 1e-3f) return 0f;
        float o = 0.5f * (a - c) / d;
        return Math.max(-0.5f, Math.min(0.5f, o));
    }
}
