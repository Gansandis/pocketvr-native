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
    /** per hand (same order as the result): how likely it is the user's LEFT hand, 0..1; -1 = not known. The model judges from how the hand looks as if the picture were a mirror, and the back camera does not mirror, so its "Right" is the user's left. */
    public volatile float[] leftness = new float[]{-1f, -1f};
    private long lastProbe = 0;
    private static final long PROBE_MS = 450;  // how often the two-hand model looks for a second hand while only one is in view
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

    public float[] detect(Bitmap bmp, long tsMs) {
        if (lm == null) return null;
        try {
            long wall = System.nanoTime() / 1000000L;
            HandLandmarker use = lm;
            if (lm1 != null && lastN < 2 && wall - lastProbe < PROBE_MS) use = lm1;      // one hand (or none) in view: the cheap model, unless it is time to look for a second hand
            else if (lm1 != null && lastN < 2) lastProbe = wall;
            lastTs = Math.max(lastTs + 1, tsMs);
            MPImage img = new BitmapImageBuilder(bmp).build();
            long t0 = System.nanoTime();
            HandLandmarkerResult r = use.detectForVideo(img, lastTs);
            inferMs += ((System.nanoTime() - t0) / 1e6f - inferMs) * 0.2f;
            try { img.close(); } catch (Throwable ignored) { }
            List<List<NormalizedLandmark>> all = new java.util.ArrayList<>(r.landmarks());
            java.util.List<java.util.List<com.google.mediapipe.tasks.components.containers.Category>> hd = r.handednesses();
            final float[] lf = new float[all.size()];
            for (int i = 0; i < lf.length; i++) {
                lf[i] = -1f;
                try {
                    com.google.mediapipe.tasks.components.containers.Category c = hd.get(i).get(0);
                    boolean modelRight = "Right".equalsIgnoreCase(c.categoryName());
                    lf[i] = modelRight ? c.score() : 1f - c.score();
                } catch (Throwable ignored) { }
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
