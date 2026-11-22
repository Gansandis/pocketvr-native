package com.pocketvr.app;

/**
 * Finds where the index finger REALLY ends in the cut-out of the hand (NvSeg's picture: S x S bytes, 128 = even odds, above = hand), so the pointer can sit on the real fingertip
 * and not where the hand model thinks it is. No Android classes: tested on any computer.
 * It starts inside the finger (at the last joint but one) and walks along the finger's direction until the cut stops being hand.
 */
final class NvTipPix {
    private static float at(byte[] p, int S, float x, float y) {              // the cut's value at a place (bilinear), 0..1
        if (x < 0f || y < 0f || x > S - 1.001f || y > S - 1.001f) return 0f;
        int x0 = (int) x, y0 = (int) y; float fx = x - x0, fy = y - y0;
        float a = (p[y0 * S + x0] & 255), b = (p[y0 * S + x0 + 1] & 255), c = (p[(y0 + 1) * S + x0] & 255), d = (p[(y0 + 1) * S + x0 + 1] & 255);
        return ((a + (b - a) * fx) * (1f - fy) + (c + (d - c) * fx) * fy) / 255f;
    }

    /**
     * p = the cut, q = the 21 points in the picture of that cut (x, y pairs), size = wrist to the middle finger's root in the same units.
     * Returns the fingertip {x, y} in the cut's pixels, or null when the cut does not say (no hand there, the finger is not clearly stretched, the end is far from the model's tip).
     */
    static float[] tip(byte[] p, int S, float[] q, float size) {
        if (p == null || q == null || q.length < 42 || !(size > 8f)) return null;
        float sx = q[14], sy = q[15], dx = q[16] - sx, dy = q[17] - sy, l = (float) Math.hypot(dx, dy);
        if (l < 3f) return null;                                              // the last bone is too short to give a direction (finger folded or pointing at the camera)
        dx /= l; dy /= l;
        if (at(p, S, sx, sy) < 0.5f) return null;                             // the joint is not hand in the cut: the cut is not good here
        float max = l * 1.8f + 0.12f * size, end = -1f, prev = at(p, S, sx, sy);
        for (float t = 0.25f; t <= max; t += 0.25f) {
            float v = at(p, S, sx + dx * t, sy + dy * t);
            if (v < 0.5f) { end = t - 0.25f + 0.25f * ((prev - 0.5f) / Math.max(1e-4f, prev - v)); break; }
            prev = v;
        }
        if (end < 0f) return null;                                            // never ended: the cut spills over into something else
        if (end < 0.4f * l || end > l * 1.7f) return null;                    // far away from what the model says: not trusted
        end -= 0.025f * size;                                                 // a little inside the nail's edge
        return new float[]{sx + dx * end, sy + dy * end};
    }
}
