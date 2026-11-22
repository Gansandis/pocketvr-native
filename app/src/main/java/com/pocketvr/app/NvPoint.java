package com.pocketvr.app;

/**
 * The "point to select" gesture: the middle, ring and little fingers are folded in (a fist, or a pointing hand with the index out). It is the look of poking a button, but it is a pose, not a real touch.
 * No Android classes: tested on any computer.
 *
 * What counts as a click depends on level:
 *   0 = "just stretch": the click is given AT ONCE, on the very first picture the pose is seen (no waiting),
 *   1 = "light poke":   the pose, and then a small push forward (the hand gets a little smaller in the picture; a quick pull back is counted the same way),
 *   2 = "firm poke":    the pose, and a bigger push forward.
 * grip = how tightly the other three fingers must be folded: 0 loose, 1 medium, 2 tight.
 * A hand that only drifts nearer / further slowly is not a push (the base follows slow changes), and one click is given per push / per pose.
 */
final class NvPoint {
    volatile int level = 1;
    volatile int grip = 1;
    /** the finger folded: the tip's distance from the wrist, compared with the distance of its first knuckle, must be below this */
    private static final float[] FOLD = {1.55f, 1.30f, 1.12f};
    private static final float[] PUSH = {0.0f, 0.03f, 0.05f};        // push forward needed (change of ln hand size)
    static final long PULSE_MS = 60, SETTLE_MS = 100, SETTLE0_MS = 0, REARM_MS = 250, REARM0_MS = 200;

    // results of the last update
    boolean pose, press, fired;
    /** why the last look said "not pointing" (shown on the debug lines so a wrong reading can be seen) */
    String why = "";
    private float fMid, fRing, fPink, lastRatio, lastRatioM, pushNow;
    float prog;                                  // 0..1: how near the click is (the pointer ring tightens with it)

    private long poseSince = 0, firedT = 0, lastT = 0, aboveSince = 0, relT = 0;
    private boolean locked = false, init = false, wait = false;
    private float depthF = 0f, base = 0f;

    void reset() { pose = false; press = false; fired = false; prog = 0f; poseSince = 0; locked = false; init = false; wait = false; aboveSince = 0; lastT = 0; }

    /** the pointer should stay where it is (the click is near / the finger is being pushed) */
    boolean freeze() { return pose && prog > 0.5f && !(level == 0 && locked); }

    /** how far a finger's tip is from the wrist, compared with its first knuckle: about 0.8-1.1 folded in, 1.6-2 stretched out */
    static float fold(float[] r, int tip, int mcp, float ar) {
        float b = d(r, 0, mcp, ar);
        return b < 1e-5f ? 0f : d(r, 0, tip, ar) / b;
    }

    private static float d(float[] r, int i, int j, float ar) {
        return (float) Math.hypot((r[1 + i * 3] - r[1 + j * 3]) * ar, r[1 + i * 3 + 1] - r[1 + j * 3 + 1]);
    }

    /** is the hand in the pointing pose? (r = [n, then 21 x 3 numbers] of the one hand, size = wrist to the middle finger's root, psize = the hand size with the palm width counted) */
    boolean poseOf(float[] r, float ar, float psize, float ratio, float ratioM) {
        lastRatio = ratio; lastRatioM = ratioM;
        if (psize < 1e-4f) { why = "ไม่เห็นมือ"; return false; }
        fMid = fold(r, 12, 9, ar); fRing = fold(r, 16, 13, ar); fPink = fold(r, 20, 17, ar);
        // (the index may be out or folded: a finger pointing straight forward looks short in the picture, so it is not checked. A fist or a pointing hand both count.)
        if (ratio < 0.30f) { why = "โป้งชิดปลายนิ้วชี้ (จีบ)"; return false; }
        if (ratioM < 0.20f && fMid > 1.2f) { why = "โป้งแตะนิ้วกลางที่ยื่นอยู่ (ท่าเปิดเมนู)"; return false; }   // thumb on a folded middle finger is a normal way to point; on a stretched one it is the menu sign
        // "click at once" has no push to tell a poke from a fist, so the index finger must be OUT (a fist stays free for the menu gesture: it is the same limit isFist uses)
        if (level == 0 && fold(r, 8, 5, ar) < 1.25f) { why = "นิ้วชี้ยังไม่ยื่น (คลิกทันทีต้องยื่นนิ้วชี้ ส่วนกำมือ = เมนู)"; return false; }
        float fo = FOLD[Math.max(0, Math.min(2, grip))];
        if (fMid > fo) { why = "นิ้วกลางยังไม่หุบ"; return false; }
        if (fRing > fo) { why = "นิ้วนางยังไม่หุบ"; return false; }
        if (fPink > fo) { why = "นิ้วก้อยยังไม่หุบ"; return false; }
        why = "";
        return true;
    }

    /** all four fingers are folded in (index, middle, ring, little): a fist. The thumb is not looked at. limit = fold ratio below which a finger counts as folded */
    static boolean isFist(float[] r, float ar, float limit) {
        return fold(r, 8, 5, ar) < limit && fold(r, 12, 9, ar) < limit && fold(r, 16, 13, ar) < limit && fold(r, 20, 17, ar) < limit;
    }

    /** the middle finger is clearly stretched out (the thumb + middle finger menu sign needs this; a fist or a pointing hand has it folded in) */
    static boolean middleOut(float[] r, float ar, float limit) { return fold(r, 12, 9, ar) > limit; }

    /** one line for the debug screen */
    String dbg() {
        return "ท่าชี้: " + (pose ? "ใช่" : "ไม่ (" + why + ")") + " | พับ กลาง " + String.format(java.util.Locale.US, "%.2f นาง %.2f ก้อย %.2f", fMid, fRing, fPink)
            + " (ต้อง<" + String.format(java.util.Locale.US, "%.2f", FOLD[Math.max(0, Math.min(2, grip))]) + ") | โป้ง-ชี้ " + String.format(java.util.Locale.US, "%.2f", lastRatio)
            + " โป้ง-กลาง " + String.format(java.util.Locale.US, "%.2f", lastRatioM) + " | ดัน " + String.format(java.util.Locale.US, "%.1f", pushNow * 100f) + "% (ต้อง " + String.format(java.util.Locale.US, "%.0f", PUSH[Math.max(0, Math.min(2, level))] * 100f) + "%)" + " | คืบหน้า " + Math.round(prog * 100f) + "%";
    }

    /** call every picture the hand is seen. Returns true on the picture the click is given. handSpd = how fast the fingertip crosses the picture (picture widths per second) */
    boolean update(float[] r, float ar, long now, float size, float psize, float ratio, float ratioM, float handSpd, boolean pinching) {
        fired = false;
        if (r == null || r.length < 64) { reset(); return false; }
        float dt = lastT == 0 ? 0.02f : Math.min(0.1f, (now - lastT) / 1000f);
        lastT = now;
        // the pulse that ends by itself
        if (press && now - firedT > PULSE_MS) press = false;
        boolean p = !pinching && poseOf(r, ar, psize, ratio, ratioM);
        if (pinching) why = "จีบอยู่";
        if (!p) {
            pose = false; poseSince = 0; prog = 0f; locked = false; init = false; wait = false; aboveSince = 0;
            return false;
        }
        if (!pose) { pose = true; poseSince = now; }
        // the size of the hand as "depth": it gets smaller as the hand goes forward (away from the camera)
        float sz = Math.max(1e-3f, size);
        float z8 = r[1 + 8 * 3 + 2], z0 = r[1 + 2];
        float depthRaw = -((float) Math.log(sz) - 0.6f * (z8 - z0) / sz);
        depthF = !init ? depthRaw : depthF + (depthRaw - depthF) * (1f - (float) Math.exp(-dt / 0.07f));
        long held = now - poseSince;
        boolean ready = level == 0 ? (held >= SETTLE0_MS && now - firedT > REARM0_MS && handSpd < 1.0f)      // (only a really fast swing is kept from clicking)
                                   : (held > SETTLE_MS && now - relT > REARM_MS);
        if (!init || !ready) { base = depthF; init = true; if (!ready) { prog = 0f; return false; } }
        if (locked) {                                                           // one click per push / pose: wait until it is over
            prog = 1f;
            if (level == 0) return false;
            if (Math.abs(depthF - base) < PUSH[Math.max(1, Math.min(2, level))] * 0.45f) { locked = false; relT = now; base = depthF; }
            return false;
        }
        int lv = Math.max(0, Math.min(2, level));
        if (lv == 0) {
            prog = Math.max(0f, Math.min(1f, held / (float) SETTLE0_MS));
            fire(now); return true;                                               // the pose is there: click now
        }
        float th = PUSH[lv];
        float up = Math.abs(depthF - base);                                   // a poke is a quick move out and back: whichever way the hand size changes first counts
        pushNow = up;
        prog = Math.max(0f, Math.min(1f, up / th));
        if (up > th) {
            if (aboveSince == 0) aboveSince = now;
            if (now - aboveSince > 30) { fire(now); return true; }
        } else {
            aboveSince = 0;
            base += (depthF - base) * Math.min(1f, dt / 0.45f);                    // slow changes (the whole arm moving) are not a push
        }
        return false;
    }

    private void fire(long now) { fired = true; press = true; firedT = now; locked = true; aboveSince = 0; }
}
