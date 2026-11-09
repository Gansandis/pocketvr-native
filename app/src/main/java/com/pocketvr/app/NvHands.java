package com.pocketvr.app;

import android.content.Context;
import android.graphics.Bitmap;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.util.List;

/**
 * Hand model for the native VR screen. Must be opened and used from ONE thread (the graphics delegate belongs to the thread that made it).
 * Result layout: [numberOfHands, then 63 numbers (x, y, z of 21 points) per hand], x and y in 0..1 of the picture (y downwards).
 */
public class NvHands {
    private HandLandmarker lm;                 // the model asked for (one or two hands)
    private HandLandmarker lm1;                // when two hands are asked for: a one-hand copy, used while only one hand is in view
    private int lastN = 0;
    public volatile float maxDistM = 0.85f;                 // hands further from the camera than this (estimated from the size of the palm) are other people's
    public volatile boolean lockMine = false;               // follow only the hands that have been in view for a second (a new hand from elsewhere is ignored)
    private boolean locked = false;
    private long nearSince = 0, lastNear = 0, lastKept = 0;
    private final float[] lkX = new float[2], lkY = new float[2], lkD = new float[2];
    private int lkN = 0;
    public static final float HFOV_DEG = 65f;                // the back camera's picture width angle (a typical phone), used to turn the size of a palm into a distance
    public static final float NEAR_SHARE = 0.4f;          // a hand has to be at least this big compared with the biggest one in the picture
    public volatile boolean onlyMine = true;               // keep only the hands of the user: a hand that is small in the picture (far away = someone else) is dropped
        /** per hand (same order as the result): how likely it is the user's LEFT hand, 0..1; -1 = not known. The model judges from how the hand looks as if the picture were a mirror, and the back camera does not mirror, so its "Right" is the user's left. */
    public volatile float[] leftness = new float[]{-1f, -1f};
    private long lastProbe = 0, lastTwoSeen = 0;
    private static final long HOLD_TWO_MS = 1500;
    private static final long PROBE_MS = 300;  // how often the two-hand model looks for a second hand while only one is in view
    private long lastTs = 0;
    public volatile String error = "";
    public volatile float inferMs = 0;

    public boolean open(Context ctx, boolean gpu, int hands) {
        try {
            BaseOptions.Builder bo = BaseOptions.builder().setModelAssetPath("mp/hand_landmarker.task");
            bo.setDelegate(gpu ? Delegate.GPU : Delegate.CPU);
            HandLandmarker.HandLandmarkerOptions opt = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(bo.build())
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumHands(hands)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.3f)
                    .setMinTrackingConfidence(0.3f)
                    .build();
            lm = HandLandmarker.createFromOptions(ctx, opt);
            lm1 = null; lastN = 0; lastProbe = 0;
            if (hands >= 2) {                                          // a model that looks for two hands runs its slow hand-finder on every picture while only one hand is in view,
                try {                                                  // which is what made the points late and jerky. So a one-hand copy does the everyday work and the
                    HandLandmarker.HandLandmarkerOptions o1 = HandLandmarker.HandLandmarkerOptions.builder()      // two-hand model only looks for a second hand now and then.
                            .setBaseOptions(bo.build()).setRunningMode(RunningMode.VIDEO).setNumHands(1)
                            .setMinHandDetectionConfidence(0.5f).setMinHandPresenceConfidence(0.3f).setMinTrackingConfidence(0.3f).build();
                    lm1 = HandLandmarker.createFromOptions(ctx, o1);
                } catch (Throwable t) { lm1 = null; }
            }
            return true;
        } catch (Throwable t) {
            error = String.valueOf(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            lm = null;
            return false;
        }
    }

    /** the shape of a hand: the palm is about as wide as it is long (a foot is much longer than wide) and the fingers are not tiny next to the palm */
    private static boolean plausible(List<NormalizedLandmark> h, float ar) {
        if (h.size() < 21) return false;
        float pl = (float) Math.hypot((h.get(9).x() - h.get(0).x()) * ar, h.get(9).y() - h.get(0).y());
        float pw = (float) Math.hypot((h.get(5).x() - h.get(17).x()) * ar, h.get(5).y() - h.get(17).y());
        if (pl < 1e-4f) return false;
        float r = pw / pl;
        return r > 0.25f && r < 2.6f;                             // wide limits: a hand turned, pointing at the camera or foreshortened still passes
    }

    public float[] detect(Bitmap bmp, long tsMs) {
        if (lm == null) return null;
        try {
            long wall = System.nanoTime() / 1000000L;
            HandLandmarker use = lm;
            boolean twoLately = lastN >= 2 || wall - lastTwoSeen < HOLD_TWO_MS;           // two hands were in view a moment ago: stay on the two-hand model (a one-frame miss must not drop the second hand)
            if (lm1 != null && !twoLately && wall - lastProbe < PROBE_MS) use = lm1;      // one hand (or none) in view: the cheap model, unless it is time to look for a second hand
            else if (lm1 != null && !twoLately) lastProbe = wall;
            lastTs = Math.max(lastTs + 1, tsMs);
            MPImage img = new BitmapImageBuilder(bmp).build();
            long t0 = System.nanoTime();
            HandLandmarkerResult r = use.detectForVideo(img, lastTs);
            inferMs += ((System.nanoTime() - t0) / 1e6f - inferMs) * 0.2f;
            try { img.close(); } catch (Throwable ignored) { }
            List<List<NormalizedLandmark>> all = new java.util.ArrayList<>(r.landmarks());
            java.util.List<java.util.List<com.google.mediapipe.tasks.components.containers.Category>> hd = r.handednesses();
            final float[] lf = new float[all.size()];
            final float[] sc = new float[all.size()];
            for (int i = 0; i < lf.length; i++) {
                lf[i] = -1f; sc[i] = 1f;
                try {
                    com.google.mediapipe.tasks.components.containers.Category c = hd.get(i).get(0);
                    sc[i] = c.score();
                    boolean modelRight = "Right".equalsIgnoreCase(c.categoryName());
                    lf[i] = modelRight ? c.score() : 1f - c.score();
                } catch (Throwable ignored) { }
            }
            if (onlyMine && !all.isEmpty()) {
                float arr = (float) bmp.getWidth() / Math.max(1, bmp.getHeight());
                float[] sz = new float[all.size()];
                float mx = 0f;
                for (int i = 0; i < sz.length; i++) {
                    List<NormalizedLandmark> hh = all.get(i);
                    sz[i] = hh.size() > 9 ? (float) Math.hypot((hh.get(9).x() - hh.get(0).x()) * arr, hh.get(9).y() - hh.get(0).y()) : 0f;
                    mx = Math.max(mx, sz[i]);
                }
                List<List<NormalizedLandmark>> kept = new java.util.ArrayList<>();
                float[] lk = new float[all.size()];
                int nk = 0;
                final float fw = 0.5f / (float) Math.tan(Math.toRadians(HFOV_DEG / 2.0));
                float[] dist = new float[sz.length];
                boolean[] okc = new boolean[sz.length];
                for (int i = 0; i < sz.length; i++) {              // how far is each hand: a palm is about 8-9 cm wide and 10 cm long, so size in the picture gives the distance
                    List<NormalizedLandmark> hh = all.get(i);
                    float pwN = hh.size() > 17 ? (float) Math.hypot(hh.get(5).x() - hh.get(17).x(), (hh.get(5).y() - hh.get(17).y()) / arr) : 0f;
                    float plN = hh.size() > 9 ? (float) Math.hypot(hh.get(9).x() - hh.get(0).x(), (hh.get(9).y() - hh.get(0).y()) / arr) : 0f;
                    float sc2 = Math.max(pwN / 0.085f, plN / 0.10f);
                    dist[i] = sc2 > 1e-4f ? fw / sc2 : 99f;
                    okc[i] = dist[i] <= maxDistM && sz[i] >= NEAR_SHARE * mx && plausible(hh, arr);
                }
                long nowT = System.nanoTime() / 1000000L;
                boolean[] take = new boolean[sz.length];
                if (lockMine && locked) {
                    int keep = 0; float refD = 0f;
                    for (int i = 0; i < sz.length; i++) {          // a hand that carries on from where a followed hand was
                        if (!okc[i]) continue;
                        List<NormalizedLandmark> hh = all.get(i);
                        for (int k = 0; k < lkN; k++) if (Math.hypot(hh.get(0).x() - lkX[k], (hh.get(0).y() - lkY[k]) / arr) < 0.25f) { take[i] = true; refD = dist[i]; keep++; break; }
                    }
                    if (keep > 0 && keep < 2) {                      // a second hand: only one at about the same distance as the first (your two hands are at the same arm's length)
                        for (int i = 0; i < sz.length && keep < 2; i++) if (okc[i] && !take[i] && Math.abs(dist[i] - refD) < 0.3f * refD) { take[i] = true; keep++; }
                    }
                } else {
                    for (int i = 0; i < sz.length; i++) take[i] = okc[i];
                }
                for (int i = 0; i < sz.length; i++) if (take[i]) { kept.add(all.get(i)); lk[nk++] = lf[i]; }
                if (nk > 0) {                                        // remember where they are; a hand that is followed for a second becomes "mine"
                    lkN = 0;
                    for (List<NormalizedLandmark> hh : kept) if (lkN < 2) { lkX[lkN] = hh.get(0).x(); lkY[lkN] = hh.get(0).y(); lkD[lkN] = 0f; lkN++; }
                    lastKept = nowT;
                    if (nowT - lastNear > 350) nearSince = nowT;
                    lastNear = nowT;
                    if (!locked && nowT - nearSince >= 1000) locked = true;
                } else if (locked && nowT - lastKept > 1500) { locked = false; nearSince = 0; lkN = 0; }
                if (!lockMine) locked = false;
                all = kept;
                System.arraycopy(lk, 0, lf, 0, nk);
            }
            Integer[] order = new Integer[all.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            if (all.size() > 1) {                                      // keep the two hands in a steady order (left to right in the picture)
                final List<List<NormalizedLandmark>> src = all;
                java.util.Arrays.sort(order, (a, b) -> Float.compare(src.get(a).isEmpty() ? 0f : src.get(a).get(0).x(), src.get(b).isEmpty() ? 0f : src.get(b).get(0).x()));
            }
            float[] ln = new float[]{-1f, -1f};
            for (int i = 0; i < order.length && i < 2; i++) ln[i] = lf[order[i]];
            leftness = ln;
            List<List<NormalizedLandmark>> sorted = new java.util.ArrayList<>();
            for (int i = 0; i < order.length; i++) sorted.add(all.get(order[i]));
            all = sorted;
            int n = Math.min(all.size(), 2);
            lastN = n;
            if (n >= 2) lastTwoSeen = System.nanoTime() / 1000000L;
            float[] out = new float[1 + n * 63];
            out[0] = n;
            for (int i = 0; i < n; i++) {
                List<NormalizedLandmark> hand = all.get(i);
                for (int k = 0; k < 21 && k < hand.size(); k++) {
                    NormalizedLandmark p = hand.get(k);
                    int o = 1 + i * 63 + k * 3;
                    out[o] = p.x();
                    out[o + 1] = p.y();
                    out[o + 2] = p.z();
                }
            }
            return out;
        } catch (Throwable t) {
            error = String.valueOf(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            return null;
        }
    }

    public void close() {
        try { if (lm != null) lm.close(); } catch (Throwable ignored) { }
        try { if (lm1 != null) lm1.close(); } catch (Throwable ignored) { }
        lm = null; lm1 = null;
    }
}
