package com.pocketvr.app;

/**
 * Takes the slow sideways drift out of the phone's turn sensor with the help of the camera picture.
 * A turn sensor drifts (a few degrees a minute) and nothing inside it can tell a drift from a very slow real turn; the picture of the room can: if the room does not move
 * in the picture, the phone did not turn. NvMotion measures how far the room has moved compared with what the gyro said (resX); this class turns that into a slowly
 * learned "the gyro is off by so many degrees a second" and returns the angle to add to the facing. No Android classes: it is tested on any computer.
 * Safety: if the corrections make the left-over bigger (wrong direction, bad picture) the direction is flipped, and after two flips in a minute it switches itself off.
 */
final class NvVisYaw {
    static double SIGN = 1.0;                       // which way the left-over shift has to be turned into the correction (settled by the test)
    private static final double K_I = 0.02;         // how fast the learned drift follows what is left over (per result)
    private static final double MAX_BIAS = Math.toRadians(4.0);        // a turn sensor never drifts more than this (rad/s); more = not trusted
    private static final double MAX_RATE = Math.toRadians(12.0);       // a left-over faster than this is a bad measurement, not a drift

    volatile boolean on = false;                    // off to begin with (see NvMotion.visOn)
    volatile boolean good = false;                  // the picture has recently given a trustworthy result
    volatile double biasRate = 0.0;                 // learned: how far the gyro is off (rad/s)
    volatile double lastRate = 0.0;                 // the last left-over (rad/s)
    volatile String note = "";
    private double visAdj = 0.0;                    // the angle added to the facing (rad)
    private int lastSeq = -1;
    private long lastGoodMs = 0, lastMs = 0, winStart = 0, flipT0 = 0, satT0 = 0;
    private double winSum = 0, prevWin = -1;
    private int winN = 0, flips = 0;
    private boolean dead = false;

    /** called with every sensor reading: returns the angle (rad) to add to the facing */
    double step(NvMotion mo, long nowMs) {
        if (!on || dead || mo == null || !mo.visOn) {
            good = false; biasRate = 0.0;
            // off or given up: whatever turn it had added is taken back slowly (a few seconds), so the windows do not stay turned away from where they were
            if (visAdj != 0.0) {
                double dt = lastMs == 0 ? 0.0 : Math.min(0.2, (nowMs - lastMs) / 1000.0);
                visAdj *= Math.exp(-dt / 2.0);
                if (Math.abs(visAdj) < 1e-5) visAdj = 0.0;
            }
            lastMs = nowMs;
            return visAdj;
        }
        // the learned drift goes on being taken out in between results
        if (lastMs != 0 && good) visAdj += biasRate * Math.min(0.05, (nowMs - lastMs) / 1000.0);
        lastMs = nowMs;
        int sq = mo.resSeq;
        if (sq != lastSeq) {
            lastSeq = sq;
            double span = mo.resSpan;
            if (span > 0.02 && nowMs - mo.resMs < 400) {
                double rate = SIGN * (mo.resX * mo.resTanH) / span;      // how fast the gyro is off right now (rad/s), left over after the correction so far
                if (Math.abs(rate) < MAX_RATE) {
                    lastRate = rate;
                    biasRate = Math.max(-MAX_BIAS, Math.min(MAX_BIAS, biasRate + K_I * rate));
                    // stuck at the limit = the correction runs away (wrong direction or a bad picture): turn round at once
                    if (Math.abs(biasRate) >= MAX_BIAS * 0.95) { if (satT0 == 0) satT0 = nowMs; } else satT0 = 0;
                    if (satT0 != 0 && nowMs - satT0 > 2000) {
                        SIGN = -SIGN; biasRate = 0.0; visAdj = 0.0; satT0 = 0; winStart = 0; winSum = 0; winN = 0; prevWin = -1;
                        if (nowMs - flipT0 > 60000) { flipT0 = nowMs; flips = 0; }
                        flips++;
                        note = "กลับทิศแก้ " + flips;
                        if (flips >= 2) { dead = true; good = false; note = "ปิดตัวเอง (แก้แล้วแย่ลง)"; }
                    }
                    lastGoodMs = nowMs;
                    // is it getting better? (mean left-over of 6 s, against the 6 s before)
                    if (winStart == 0) winStart = nowMs;
                    winSum += Math.abs(rate); winN++;
                    if (nowMs - winStart >= 6000 && winN > 10) {
                        double w = winSum / winN;
                        if (prevWin >= 0 && w > 1.3 * prevWin && w > Math.toRadians(0.25)) {
                            SIGN = -SIGN; biasRate = 0.0;                         // worse: the other direction
                            if (nowMs - flipT0 > 60000) { flipT0 = nowMs; flips = 0; }
                            flips++;
                            note = "กลับทิศแก้ " + flips;
                            if (flips >= 2) { dead = true; good = false; note = "ปิดตัวเอง (แก้แล้วแย่ลง)"; }
                            prevWin = -1;
                        } else prevWin = w;
                        winStart = nowMs; winSum = 0; winN = 0;
                    }
                }
            }
        }
        good = nowMs - lastGoodMs < 1000;
        return visAdj;
    }
    double adj() { return visAdj; }
}
