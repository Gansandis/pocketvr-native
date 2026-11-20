package com.pocketvr.app;

/**
 * Finds, by itself, how much older the camera picture really is than its time stamp says (compared with the turn sensor), so the picture and the windows stay together
 * while the head turns. No Android classes: it is tested on any computer.
 *
 * Why it can be measured: the picture of the room is matched against what the turn sensor says between two pictures. If the time used for the sensor is wrong by e ms,
 * the sensor's turn between the two pictures is the turn of a window shifted by e ms, and that differs from the real one by e * (how much the turning speed changed
 * between the two pictures). So the left-over shift of the room (res) is a straight line of that change (g, worked out by NvMotion for the same pictures):  res = g * e.
 * e is the slope of the line, found by least squares over many pictures taken while the head speeds up and slows down (a still head, or a steady turn, says nothing:
 * g is zero then, and nothing is learned).
 *
 * Safety: the correction is limited to +-MAX_OFF ms, it goes in half steps, and if two corrections in a row do not make the left-over smaller (wrong direction)
 * the direction is turned round; after two turn-rounds in a minute it switches itself off and goes back to 0.
 */
final class NvLagTune {
    static final double MAX_OFF = 60.0;              // ms: more than this is not a timing error, it is something else
    private static final double SGG_MIN = 2.5e-6;    // how much "turning speed change" has to be seen before a result is trusted (picture units^2 per ms^2)
    private static final int N_MIN = 6;
    private static final double GAIN = 0.5;          // part of the found error that is taken out at once
    private static final double MAX_RES = 0.05;      // a left-over bigger than this (3 px of the small picture) is a bad match, not a timing error
    private static final double DEAD = 1.0;          // ms: a smaller error than this is left alone

    volatile boolean on = false;                     // off to begin with: on the phone it ran into its limit and did not settle
    volatile boolean good = false;                   // a result has been found and taken in recently
    volatile double offMs = 0.0;                     // learned: the picture is this much older than the time stamp says (ms)
    volatile double lastE = 0.0;                     // the last error found (ms)
    volatile int batches = 0;
    volatile String note = "";
    volatile double sign = 1.0;

    private double srg = 0, sgg = 0;
    private int n = 0;
    private double prevE = 0;
    private boolean havePrev = false;
    private long lastGoodMs = 0, flipT0 = 0;
    private int flips = 0, worse = 0;
    private boolean dead = false;

    void reset() { offMs = 0; srg = sgg = 0; n = 0; havePrev = false; good = false; dead = false; flips = 0; worse = 0; note = ""; }

    /** one pair of pictures: res = left-over shift of the room's middle (picture units), g = how it changes per ms of extra lag (picture units / ms) */
    void add(double rx, double ry, double gx, double gy, int used, long nowMs) {
        if (!on || dead) { return; }
        if (used < 14) return;
        if (Math.abs(rx) > MAX_RES || Math.abs(ry) > MAX_RES) return;
        double gg = gx * gx + gy * gy;
        if (!(gg > 0) || Double.isNaN(gg) || Double.isNaN(rx + ry)) return;
        srg += rx * gx + ry * gy; sgg += gg; n++;
        if (sgg < SGG_MIN || n < N_MIN) return;
        double e = sign * srg / sgg;                // ms of extra lag still missing
        srg = 0; sgg = 0; n = 0;
        batches++; lastE = e;
        // wrong direction: a right correction takes away about half of the error (the next error is about half of the last one). If the error GROWS twice in a row
        // (same side, bigger each time, and not small to begin with), the correction went the wrong way.
        if (havePrev && Math.abs(prevE) > 12.0 && Math.signum(e) == Math.signum(prevE) && Math.abs(e) > 1.15 * Math.abs(prevE)) worse++; else worse = 0;
        if (worse >= 2) {
            sign = -sign; havePrev = false; worse = 0;
            if (nowMs - flipT0 > 60000) { flipT0 = nowMs; flips = 0; }
            flips++;
            note = "กลับทิศแก้ " + flips;
            if (flips >= 2) { dead = true; good = false; offMs = 0.0; note = "ปิดตัวเอง (แก้แล้วไม่ดีขึ้น)"; }
            return;
        }
        prevE = e; havePrev = true;
        if (Math.abs(e) > DEAD) offMs = Math.max(-MAX_OFF, Math.min(MAX_OFF, offMs + GAIN * e));
        lastGoodMs = nowMs; good = true;
    }

    boolean fresh(long nowMs) { return good && nowMs - lastGoodMs < 60000; }
    boolean isDead() { return dead; }
}
