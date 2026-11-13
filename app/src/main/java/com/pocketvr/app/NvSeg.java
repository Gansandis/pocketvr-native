package com.pocketvr.app;

/**
 * Cuts the hand out of the camera picture from its REAL pixels (not from a drawn shape).
 * The 21 points of the hand model are only the seeds: a thin skeleton = surely hand, far away = surely room.
 * Everything in between is decided by the colours of this very picture: a colour histogram of the hand and one of the room
 * are learnt from the seeds, and a mean-field MRF with edge-aware smoothing (strong edge = the cut may pass there, flat colour = it may not) gives the
 * probability of "hand" for every pixel. This is the idea of GrabCut (Rother et al.) in a light form that runs in a few ms on a phone.
 * The result is a small S x S probability picture of a square area around the hand.
 */
final class NvSeg {
    static final int S = 96;
    private static final int[][] BONES = {{0, 1}, {1, 2}, {2, 3}, {3, 4}, {0, 5}, {5, 6}, {6, 7}, {7, 8}, {9, 10}, {10, 11}, {11, 12}, {13, 14}, {14, 15}, {15, 16}, {0, 17}, {17, 18}, {18, 19}, {19, 20}, {5, 9}, {9, 13}, {13, 17}, {0, 9}, {1, 5}, {2, 5}};
    private static final int[] POLY = {0, 1, 5, 9, 13, 17};
    private static final float[] SIG = new float[1025];
    static { for (int i = 0; i <= 1024; i++) { float x = (i - 512) / 512f * 12f; SIG[i] = (float) (1.0 / (1.0 + Math.exp(-x))); } }
    private static float sig(float x) { int i = (int) (x * (512f / 12f) + 512.5f); return SIG[i < 0 ? 0 : (i > 1024 ? 1024 : i)]; }

    // scratch (the worker is a single thread, so these are reused)
    private final float[] dB = new float[S * S], dA = new float[S * S], pF = new float[S * S], p = new float[S * S], q = new float[S * S], L0 = new float[S * S], U = new float[S * S], tmp = new float[S * S];
    private final byte[] kind = new byte[S * S];             // 0 = hard room, 1 = near (unsure), 2 = probably hand, 3 = surely hand
    private final int[] idx = new int[S * S];
    private final float[][] E = new float[4][S * S];
    private final float[] hf = new float[4096], hb = new float[4096];
    private static final int[] DY = {0, 1, 1, 1}, DX = {1, 0, 1, -1};
    private static final float[] DL = {1f, 1f, 0.70710678f, 0.70710678f};

    /**
     * @param rgb  S*S pixels 0xRRGGBB of the square area, row by row from the top
     * @param lm   21 points (x,y pairs) in the pixels of that area
     * @param size distance wrist - root of the middle finger in the same pixels
     * @param arm  also take the forearm as hand
     * @return S*S bytes, 0..255 = how likely it is hand
     */
    byte[] run(int[] rgb, float[] lm, float size, boolean arm) {
        final int n = S * S;
        // 1) distance of every pixel to the skeleton (palm polygon counts as 0)
        float[] px = new float[POLY.length * 2];
        for (int i = 0; i < POLY.length; i++) { px[i * 2] = lm[POLY[i] * 2]; px[i * 2 + 1] = lm[POLY[i] * 2 + 1]; }
        float ax = lm[0] - lm[18], ay = lm[1] - lm[19];
        float al = (float) Math.hypot(ax, ay); if (al < 1e-3f) al = 1f; ax /= al; ay /= al;
        float fx0 = lm[0] + ax * size * 0.10f, fy0 = lm[1] + ay * size * 0.10f, fx1 = lm[0] + ax * size * 2.2f, fy1 = lm[1] + ay * size * 2.2f;
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            float cx = x + 0.5f, cy = y + 0.5f, best = 1e9f;
            for (int[] b : BONES) {
                float x0 = lm[b[0] * 2], y0 = lm[b[0] * 2 + 1], bx = lm[b[1] * 2] - x0, by = lm[b[1] * 2 + 1] - y0;
                float l2 = bx * bx + by * by, t = l2 < 1e-4f ? 0f : ((cx - x0) * bx + (cy - y0) * by) / l2;
                t = t < 0f ? 0f : (t > 1f ? 1f : t);
                float dx = cx - (x0 + bx * t), dy = cy - (y0 + by * t), d2 = dx * dx + dy * dy;
                if (d2 < best) best = d2;
            }
            float d = (float) Math.sqrt(best);
            if (d > 0f && inPoly(px, cx, cy)) d = 0f;
            dB[y * S + x] = d;
            if (arm) {
                float bx = fx1 - fx0, by = fy1 - fy0, l2 = bx * bx + by * by, t = ((cx - fx0) * bx + (cy - fy0) * by) / l2;
                t = t < 0f ? 0f : (t > 1f ? 1f : t);
                float dx = cx - (fx0 + bx * t), dy = cy - (fy0 + by * t);
                dA[y * S + x] = (float) Math.sqrt(dx * dx + dy * dy);
            } else dA[y * S + x] = 1e9f;
        }
        // 2) seeds and the prior
        float sureH = Math.max(1.2f, size * 0.02f), probH = size * 0.075f, nearH = size * 0.18f, hardH = size * HARD;
        float sureA = size * 0.12f, probA = size * 0.28f, nearA = size * 0.40f, hardA = size * 0.50f;
        for (int i = 0; i < n; i++) {
            float d = dB[i], da = dA[i];
            byte k;
            if (d <= sureH || da <= sureA) k = 3;
            else if (d <= probH || da <= probA) k = 2;
            else if (d <= nearH || da <= nearA) k = 1;
            else if (d <= hardH || da <= hardA) k = 1;
            else k = 0;
            kind[i] = k;
            {   // how likely "hand" is from the distance to the skeleton alone: a finger is about 0.09 of the hand size from its bone to its edge, so beyond that it gets quickly unlikely
                float ph = sigf((size * 0.100f - d) / (size * 0.035f)), pa = arm ? sigf((size * 0.34f - da) / (size * 0.08f)) : 0f;
                float pp = Math.max(ph, pa);
                pF[i] = k == 3 ? 0.97f : (pp < 0.04f ? 0.04f : (pp > 0.95f ? 0.95f : pp));
            }
            p[i] = pF[i];
            int c = rgb[i];
            int r = (c >> 20) & 15, g = (c >> 12) & 15, b = (c >> 4) & 15;
            idx[i] = r * 256 + g * 16 + b;
        }
        // 3) edge weights between neighbours (contrast sensitive)
        double var = 0; int cnt = 0;
        for (int d = 0; d < 4; d++) {
            float[] e = E[d];
            for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
                int y2 = y + DY[d], x2 = x + DX[d];
                if (y2 >= S || x2 < 0 || x2 >= S) { e[y * S + x] = 0f; continue; }
                e[y * S + x] = cdist(rgb[y * S + x], rgb[y2 * S + x2]);
                if (dB[y * S + x] < size * 0.30f) { var += e[y * S + x]; cnt++; }
            }
        }
        float inv = (float) (1.0 / (2.0 * (var / Math.max(1, cnt) + 1e-3) * VF));
        for (int d = 0; d < 4; d++) {
            float[] e = E[d];
            for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
                int y2 = y + DY[d], x2 = x + DX[d];
                if (y2 >= S || x2 < 0 || x2 >= S) continue;
                e[y * S + x] = (float) Math.exp(-e[y * S + x] * inv) / DL[d];
            }
        }
        // 4) a few rounds: learn the two colour histograms from the current guess, then smooth with the MRF
        for (int round = 0; round < 2; round++) {
            java.util.Arrays.fill(hf, 0.15f); java.util.Arrays.fill(hb, 0.15f);
            for (int i = 0; i < n; i++) {
                // the hand colours come from where the hand surely is (and, in later rounds, from what the last round called hand); the room colours from far away and from what was called room.
                // The unsure ring round the hand teaches nothing at first: it holds both.
                float wf = kind[i] >= 2 ? (round == 0 ? (kind[i] == 3 ? 1f : 0.35f) : p[i]) : (round == 0 ? 0f : (kind[i] == 1 ? p[i] * 0.6f : 0f));
                float wb = kind[i] == 0 ? 1f : (kind[i] == 1 ? (round == 0 ? 0.25f : 1f - p[i]) : (round == 0 ? 0f : 1f - p[i]));
                hf[idx[i]] += wf; hb[idx[i]] += wb;
            }
            float sf = 0, sb = 0;
            for (int i = 0; i < 4096; i++) { sf += hf[i]; sb += hb[i]; }
            for (int i = 0; i < n; i++) U[i] = (float) Math.log((hf[idx[i]] / sf) / (hb[idx[i]] / sb));
            blur(U);
            for (int i = 0; i < n; i++) {
                float pr = (float) Math.log(pF[i] / (1f - pF[i]));
                float v = U[i] + 1.2f * pr;
                if (kind[i] == 3 && (dB[i] <= sureH || dA[i] <= sureA)) v = 6f;
                if (kind[i] == 0) v = -6f;
                L0[i] = v; q[i] = sig(v);
            }
            for (int it = 0; it < 10; it++) {
                for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
                    int i = y * S + x;
                    float msg = 0f;
                    for (int d = 0; d < 4; d++) {
                        int y2 = y + DY[d], x2 = x + DX[d];
                        if (y2 < S && x2 >= 0 && x2 < S) msg += E[d][i] * (2f * q[y2 * S + x2] - 1f);
                        int y3 = y - DY[d], x3 = x - DX[d];
                        if (y3 >= 0 && x3 >= 0 && x3 < S) msg += E[d][y3 * S + x3] * (2f * q[y3 * S + x3] - 1f);
                    }
                    tmp[i] = sig(L0[i] + LAM * msg);
                }
                System.arraycopy(tmp, 0, q, 0, n);
            }
            for (int i = 0; i < n; i++) {
                float v = q[i]; v = v < 0.02f ? 0.02f : (v > 0.98f ? 0.98f : v);
                if (kind[i] == 3 && (dB[i] <= sureH || dA[i] <= sureA)) v = 0.98f;
                if (kind[i] == 0) v = 0.02f;
                p[i] = v;
            }
        }
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = (byte) Math.round(p[i] * 255f);
        return out;
    }

    static final float HARD = 0.22f, VF = 0.5f, LAM = 2.2f;
    private static float sigf(float x) { return 1f / (1f + (float) Math.exp(-x)); }
    private static float cdist(int a, int b) {
        int dr = ((a >> 16) & 255) - ((b >> 16) & 255), dg = ((a >> 8) & 255) - ((b >> 8) & 255), db = (a & 255) - (b & 255);
        return dr * dr + dg * dg + db * db;
    }
    private static boolean inPoly(float[] P, float x, float y) {
        boolean in = false; int m = P.length / 2;
        for (int i = 0, j = m - 1; i < m; j = i++) {
            float xi = P[i * 2], yi = P[i * 2 + 1], xj = P[j * 2], yj = P[j * 2 + 1];
            if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) in = !in;
        }
        return in;
    }
    private void blur(float[] a) {      // 1 2 1 in both directions
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            int l = x > 0 ? x - 1 : x, r = x < S - 1 ? x + 1 : x;
            tmp[y * S + x] = (a[y * S + l] + 2f * a[y * S + x] + a[y * S + r]) * 0.25f;
        }
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            int u = y > 0 ? y - 1 : y, d = y < S - 1 ? y + 1 : y;
            a[y * S + x] = (tmp[u * S + x] + 2f * tmp[y * S + x] + tmp[d * S + x]) * 0.25f;
        }
    }

    /** the square area (x0, y0, side) in picture pixels that holds the hand (and the forearm) with some room round it */
    static float[] roi(float[] lm, float size, boolean arm) {
        float mnx = 1e9f, mny = 1e9f, mxx = -1e9f, mxy = -1e9f;
        for (int k = 0; k < 21; k++) { mnx = Math.min(mnx, lm[k * 2]); mxx = Math.max(mxx, lm[k * 2]); mny = Math.min(mny, lm[k * 2 + 1]); mxy = Math.max(mxy, lm[k * 2 + 1]); }
        if (arm) {
            float ax = lm[0] - lm[18], ay = lm[1] - lm[19], al = (float) Math.hypot(ax, ay); if (al < 1e-3f) al = 1f;
            float ex = lm[0] + ax / al * size * 2.2f, ey = lm[1] + ay / al * size * 2.2f;
            mnx = Math.min(mnx, ex); mxx = Math.max(mxx, ex); mny = Math.min(mny, ey); mxy = Math.max(mxy, ey);
        }
        float half = Math.max(mxx - mnx, mxy - mny) / 2f * 1.45f + size * 0.10f;
        float cx = (mnx + mxx) / 2f, cy = (mny + mxy) / 2f;
        return new float[]{cx - half, cy - half, 2f * half};
    }

    /** S x S picture of that square (each pixel = mean of 4 taps, edges repeated) */
    static void crop(int[] src, int W, int H, float x0, float y0, float side, int[] out) {
        float st = side / S;
        for (int j = 0; j < S; j++) for (int i = 0; i < S; i++) {
            int r = 0, g = 0, b = 0;
            for (int t = 0; t < 4; t++) {
                float sx = x0 + (i + 0.25f + (t & 1) * 0.5f) * st, sy = y0 + (j + 0.25f + (t >> 1) * 0.5f) * st;
                int xi = (int) Math.floor(sx), yi = (int) Math.floor(sy);
                xi = xi < 0 ? 0 : (xi >= W ? W - 1 : xi); yi = yi < 0 ? 0 : (yi >= H ? H - 1 : yi);
                int c = src[yi * W + xi]; r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255;
            }
            out[j * S + i] = ((r >> 2) << 16) | ((g >> 2) << 8) | (b >> 2);
        }
    }
}
