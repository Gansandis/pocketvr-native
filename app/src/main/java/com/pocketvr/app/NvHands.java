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
    private HandLandmarker lm;
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
            lastTs = Math.max(lastTs + 1, tsMs);
            MPImage img = new BitmapImageBuilder(bmp).build();
            long t0 = System.nanoTime();
            HandLandmarkerResult r = lm.detectForVideo(img, lastTs);
            inferMs += ((System.nanoTime() - t0) / 1e6f - inferMs) * 0.2f;
            try { img.close(); } catch (Throwable ignored) { }
            List<List<NormalizedLandmark>> all = r.landmarks();
            int n = Math.min(all.size(), 2);
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
        lm = null;
    }
}
