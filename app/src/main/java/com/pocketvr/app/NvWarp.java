package com.pocketvr.app;

/**
 * Carries the cut of the hand (made ~100 ms ago) to where the hand is NOW, finger by finger.
 * Before, the whole picture of the cut was moved and turned as one piece by the palm; a finger that had moved on its own (a wave, a pinch, a spread)
 * then left a second outline beside the real one. Here the picture of the cut is a small grid; every corner of the grid follows the palm and
 * the five fingers, each with its own turn, in proportion to how close the corner is to that bone chain (linear blend, as in a skinned hand).
 * No Android classes: it can be tested on any computer.
 */
final class NvWarp {
    static final int G = 14;                                    // cells per side of the grid
    static final int NV = (G + 1) * (G + 1);
    /** the six bone chains: the palm (spokes to the knuckles) and the five fingers (the thumb from its joint 1) */
    private static final int[][] CH = {{0, 5, 9, 13, 17}, {1, 2, 3, 4}, {5, 6, 7, 8}, {9, 10, 11, 12}, {13, 14, 15, 16}, {17, 18, 19, 20}};
    private static final float MAX_TURN = 1.25f;                // a finger turned more than this (rad) against the palm: not trusted, it follows the palm
    private final float[] cx = new float[6], cy = new float[6], bx = new float[6], by = new float[6], mr = new float[6], mi = new float[6];
    private final float[] wt = new float[6];

    /** result of the last warp(): how far the chains' stiff move is from the real points (mean, in hand sizes) */
    float resid = 0f;

    /**
     * a: the 21 points (x,y interleaved, pixels) when the cut was made. b: the 21 points now (same pixels).
     * The square area of the cut starts at (x0,y0) and has the side `side` (pixels, same system as a and b).
     * out gets (G+1)*(G+1) pairs, row by row (v outer, u inner): where each grid corner is now.
     * returns false when the hands cannot be compared (a point is missing or the palm collapsed).
     */
    boolean warp(float[] a, float[] b, float x0, float y0, float side, float[] out) {
        float size = (float) Math.hypot(a[18] - a[0], a[19] - a[1]);
        float sizeB = (float) Math.hypot(b[18] - b[0], b[19] - b[1]);
        if (size < 2f || sizeB < 2f) return false;
        float scPalm = 1f;
        // ---- one stiff move for every chain
        for (int j = 0; j < 6; j++) {
            int[] c = CH[j];
            float ax = 0, ay = 0, qx = 0, qy = 0;
            for (int k : c) { ax += a[k * 2]; ay += a[k * 2 + 1]; qx += b[k * 2]; qy += b[k * 2 + 1]; }
            ax /= c.length; ay /= c.length; qx /= c.length; qy /= c.length;
            float sr = 0, si = 0, den = 0;
            for (int k : c) {
                float p = a[k * 2] - ax, q = a[k * 2 + 1] - ay, p2 = b[k * 2] - qx, q2 = b[k * 2 + 1] - qy;
                sr += p2 * p + q2 * q; si += q2 * p - p2 * q; den += p * p + q * q;
            }
            cx[j] = ax; cy[j] = ay; bx[j] = qx; by[j] = qy;
            if (den < 1e-3f) { mr[j] = Float.NaN; continue; }
            sr /= den; si /= den;
            if (j == 0) {                                       // the palm: turn, size and move
                float s = (float) Math.hypot(sr, si);
                if (s < 0.5f || s > 2f) return false;
                scPalm = s; mr[0] = sr; mi[0] = si;
            } else {                                            // a finger: only the turn is taken from its own points, the size is the palm's
                float s = (float) Math.hypot(sr, si);
                if (s < 1e-4f) { mr[j] = Float.NaN; continue; }
                mr[j] = sr / s; mi[j] = si / s;                 // unit turn; the size is put in below once the palm is known
            }
        }
        for (int j = 1; j < 6; j++) {
            if (Float.isNaN(mr[j])) { mr[j] = mr[0]; mi[j] = mi[0]; continue; }
            // how far this finger's turn is from the palm's turn
            float pr = mr[0] / scPalm, pi = mi[0] / scPalm;
            float dr = mr[j] * pr + mi[j] * pi, di = mi[j] * pr - mr[j] * pi;
            float ang = (float) Math.atan2(di, dr);
            if (Math.abs(ang) > MAX_TURN) { mr[j] = mr[0]; mi[j] = mi[0]; continue; }
            mr[j] *= scPalm; mi[j] *= scPalm;
        }
        // how well the stiff moves explain the points (a finger that bent since the cut shows up here)
        float rs = 0; int rn = 0;
        for (int j = 0; j < 6; j++) {
            for (int k : CH[j]) {
                float p = a[k * 2] - cx[j], q = a[k * 2 + 1] - cy[j];
                float tx = bx[j] + mr[j] * p - mi[j] * q, ty = by[j] + mi[j] * p + mr[j] * q;
                rs += (float) Math.hypot(tx - b[k * 2], ty - b[k * 2 + 1]); rn++;
            }
        }
        resid = rs / rn / Math.max(4f, sizeB);
        // ---- the grid: every corner gets the blend of the six moves
        float sig = 0.06f * size, sig2 = sig * sig;
        float palmR = 0.09f * size, fingR = 0.06f * size;
        float pcx = cx[0], pcy = cy[0];
        int idx = 0;
        for (int v = 0; v <= G; v++) {
            for (int u = 0; u <= G; u++) {
                float px = x0 + side * u / G, py = y0 + side * v / G;
                float ws = 0;
                // palm: the spokes from the middle of the palm to the knuckles and the thumb joint
                float dp = 1e9f;
                for (int k : CH[0]) dp = Math.min(dp, segDist(px, py, pcx, pcy, a[k * 2], a[k * 2 + 1]));
                dp = Math.min(dp, segDist(px, py, pcx, pcy, a[2], a[3]));
                dp = Math.min(dp, segDist(px, py, a[0], a[1], pcx, pcy));
                wt[0] = wOf(Math.max(0f, dp - palmR), sig2) + 1e-3f;
                ws = wt[0];
                for (int j = 1; j < 6; j++) {
                    int[] c = CH[j];
                    float d = 1e9f;
                    for (int s = 0; s + 1 < c.length; s++) d = Math.min(d, segDist(px, py, a[c[s] * 2], a[c[s] * 2 + 1], a[c[s + 1] * 2], a[c[s + 1] * 2 + 1]));
                    wt[j] = wOf(Math.max(0f, d - fingR), sig2);
                    ws += wt[j];
                }
                float ox = 0, oy = 0;
                for (int j = 0; j < 6; j++) {
                    float w = wt[j] / ws;
                    if (w < 1e-4f) continue;
                    float p = px - cx[j], q = py - cy[j];
                    ox += w * (bx[j] + mr[j] * p - mi[j] * q);
                    oy += w * (by[j] + mi[j] * p + mr[j] * q);
                }
                out[idx++] = ox; out[idx++] = oy;
            }
        }
        return true;
    }

    private static float wOf(float d, float sig2) { return (float) Math.exp(-(d * d) / sig2); }

    private static float segDist(float px, float py, float x0, float y0, float x1, float y1) {
        float dx = x1 - x0, dy = y1 - y0, l2 = dx * dx + dy * dy;
        float t = l2 < 1e-6f ? 0f : ((px - x0) * dx + (py - y0) * dy) / l2;
        t = t < 0f ? 0f : (t > 1f ? 1f : t);
        float ex = x0 + dx * t - px, ey = y0 + dy * t - py;
        return (float) Math.sqrt(ex * ex + ey * ey);
    }
}
