package com.pocketvr.app;

/**
 * Follows the hand between two answers of the hand model, at the camera's own rate.
 * The hand model needs ~70-100 ms per picture on this kind of phone, so its answers are old when they arrive and come only ~10 times a second.
 * This class looks at how the little grey picture inside the hand's box moved from one camera frame to the next (block matching, to a fraction of a pixel),
 * and adds the moves up (cumX, cumY, as a fraction of the picture size). The renderer then moves the model's points on by "moves since the picture
 * that answer belongs to": the hand shows up where it is NOW, not where it was 70+ ms ago. Only a shift of the whole hand is followed
 * (finger bending still waits for the model).
 * No Android classes here, so it can be tested anywhere.
 */
final class NvFlow {
    static final int GW = 192, GH = 108;
    private static final int R = 10;               // largest move per frame that is looked for, in grey pixels (about 5 % of the picture width)
    private static final int HN = 96;

    private byte[] prev = new byte[GW * GH];
    private boolean havePrev = false;
    private float cumX = 0f, cumY = 0f;
    private final long[] ht = new long[HN];
    private final float[] hx = new float[HN], hy = new float[HN];
    private int hn = 0, hi = 0;
    private int fails = 0;
    private boolean valid = false;
    private long lastT = 0;

    synchronized boolean valid() { return valid; }
    synchronized long lastTime() { return lastT; }
    synchronized float cumX() { return cumX; }
    synchronized float cumY() { return cumY; }

    synchronized void reset() { havePrev = false; hn = 0; hi = 0; fails = 0; valid = false; for (int k = 0; k < NT; k++) { tok[k] = false; tcx[k] = 0f; tcy[k] = 0f; tfl[k] = 0; } }

    /** the moves added up as they were at time t (between two frames: in between). Out of range: the nearest one. */
    synchronized void cumAt(long t, float[] out) { interp(t, hx, hy, cumX, cumY, out); }

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
            int cx = Math.round(tips[k * 2] * GW), cy = Math.round(tips[k * 2 + 1] * GH);
            if (cx < P + R2 + 1 || cx > GW - P - R2 - 2 || cy < P + R2 + 1 || cy > GH - P - R2 - 2) { tok[k] = false; continue; }
            int sum = 0;
            for (int q = 0; q < N; q++) sum += prev[(cy + DISC_DY[q]) * GW + cx + DISC_DX[q]] & 0xFF;
            int mean = sum / N, dev = 0;
            for (int q = 0; q < N; q++) dev += Math.abs((prev[(cy + DISC_DY[q]) * GW + cx + DISC_DX[q]] & 0xFF) - mean);
            boolean ok = false;
            if ((float) dev / N > 3.5f) {
                int[] sad = new int[S * S];
                int best = Integer.MAX_VALUE, bi = 0;
                long tot = 0;
                for (int dy = -R2; dy <= R2; dy++) for (int dx = -R2; dx <= R2; dx++) {
                    int sd = 0;
                    int off = dy * GW + dx;
                    for (int q = 0; q < N; q++) {
                        int p0 = (cy + DISC_DY[q]) * GW + cx + DISC_DX[q];
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
        ht[hi] = t; hx[hi] = cumX; hy[hi] = cumY;
        for (int k = 0; k < NT; k++) { thx[k][hi] = tcx[k]; thy[k][hi] = tcy[k]; }
        hi = (hi + 1) % HN;
        if (hn < HN) hn++;
    }

    private static final int[][] BONES = {{0,1},{1,2},{2,3},{3,4},{0,5},{5,6},{6,7},{7,8},{9,10},{10,11},{11,12},{13,14},{14,15},{15,16},{0,17},{17,18},{18,19},{19,20},{5,9},{9,13},{13,17}};
    private final byte[] mask = new byte[GW * GH];
    private int[] spx = new int[4096], spy = new int[4096];

    /**
     * Only the pixels that belong to the hand are compared (round patches along the finger bones and palm), so a background that does not move
     * does not pull the answer towards "nothing moved".
     * pts: 21 points per hand (x, y as fractions of the picture) for up to two hands, or null (then the whole box is used).
     */
    private int collect(int x0, int y0, int x1, int y1, float[] pts) {
        int n = 0;
        if (pts == null || pts.length < 42) {
            for (int y = y0; y < y1; y += 2) for (int x = x0; x < x1; x += 2) { if (n >= spx.length) return n; spx[n] = x; spy[n] = y; n++; }
            return n;
        }
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) mask[y * GW + x] = 0;
        int hands = pts.length / 42;
        for (int h = 0; h < hands; h++) {
            int o = h * 42;
            float palm = (float) Math.hypot((pts[o + 18] - pts[o]) * GW, (pts[o + 19] - pts[o + 1]) * GH);       // wrist to middle-finger knuckle
            float rad = Math.max(3f, palm * 0.28f);
            for (int k = 0; k < 21; k++) disc(pts[o + k * 2] * GW, pts[o + k * 2 + 1] * GH, rad, x0, y0, x1, y1);
            for (int[] bn : BONES) disc((pts[o + bn[0] * 2] + pts[o + bn[1] * 2]) * 0.5f * GW, (pts[o + bn[0] * 2 + 1] + pts[o + bn[1] * 2 + 1]) * 0.5f * GH, rad, x0, y0, x1, y1);
        }
        for (int y = y0; y < y1; y += 2) for (int x = x0; x < x1; x += 2) {
            if (mask[y * GW + x] != 0) { if (n >= spx.length) return n; spx[n] = x; spy[n] = y; n++; }
        }
        return n;
    }

    private void disc(float cx, float cy, float r, int x0, int y0, int x1, int y1) {
        int ya = Math.max(y0, (int) (cy - r)), yb = Math.min(y1 - 1, (int) (cy + r) + 1);
        int xa = Math.max(x0, (int) (cx - r)), xb = Math.min(x1 - 1, (int) (cx + r) + 1);
        float r2 = r * r;
        for (int y = ya; y <= yb; y++) for (int x = xa; x <= xb; x++) { float dx = x - cx, dy = y - cy; if (dx * dx + dy * dy <= r2) mask[y * GW + x] = 1; }
    }

    /**
     * gray: GW*GH picture of this frame; t: when the frame was taken (ms);
     * box: the hand's box {x0, y0, x1, y1} as fractions of the picture, or null when there is no hand (then nothing is measured);
     * pts: the hand's points (see collect), or null.
     */
    synchronized void feed(byte[] gray, long t, float[] box, float[] pts, float[] tips) {
        lastT = t;
        boolean ok = false;
        if (havePrev && box != null) {
            int x0 = Math.max(R + 1, (int) (box[0] * GW)), x1 = Math.min(GW - R - 2, (int) (box[2] * GW));
            int y0 = Math.max(R + 1, (int) (box[1] * GH)), y1 = Math.min(GH - R - 2, (int) (box[3] * GH));
            if (x1 - x0 >= 18 && y1 - y0 >= 18) {
                int n = collect(x0, y0, x1, y1, pts);
                if (n >= 60) {
                    // is there enough to look at? (a flat patch cannot be followed)
                    int sum = 0;
                    for (int i = 0; i < n; i++) sum += prev[spy[i] * GW + spx[i]] & 0xFF;
                    int mean = sum / n, dev = 0;
                    for (int i = 0; i < n; i++) dev += Math.abs((prev[spy[i] * GW + spx[i]] & 0xFF) - mean);
                    float texture = (float) dev / n;
                    if (texture > 3.0f) {
                        final int S = 2 * R + 1;
                        int[] sad = new int[S * S];
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
                            cumX += ((bx - R) + sx) / GW;
                            cumY += ((by - R) + sy) / GH;
                            ok = true;
                        }
                    }
                }
            }
        }
        if (havePrev) trackTips(gray, tips);
        if (box == null) { valid = false; fails = 0; }
        else if (ok) { fails = 0; valid = true; }
        else if (++fails > 6) valid = false;                  // lost for a while: say so, the renderer goes back to guessing from speed
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
