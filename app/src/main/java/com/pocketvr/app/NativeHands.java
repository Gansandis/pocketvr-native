package com.pocketvr.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hand model that runs inside the app itself (MediaPipe's own Android library) instead of inside the web page.
 * The page keeps the camera and sends small pictures; this class answers with the 21 points of each hand.
 * Everything runs on ONE thread: the graphics delegate must be used from the thread that created it.
 */
public class NativeHands {
    public interface Cb { void done(boolean ok, Object data); }

    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private HandLandmarker one, two;
    private long lastTs1 = 0, lastTs2 = 0;

    public void init(final Context ctx, final String delegate, final int hands, final Cb cb) {
        ex.execute(() -> {
            try {
                close();
                one = make(ctx, delegate, 1);
                two = null;
                if (hands == 2) { try { two = make(ctx, delegate, 2); } catch (Throwable ignored) { two = null; } }
                JSONObject o = new JSONObject();
                o.put("two", two != null);
                cb.done(true, o);
            } catch (Throwable t) {
                close();
                cb.done(false, String.valueOf(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
            }
        });
    }

    private HandLandmarker make(Context ctx, String delegate, int n) {
        BaseOptions.Builder bo = BaseOptions.builder().setModelAssetPath("mp/hand_landmarker.task");
        bo.setDelegate("CPU".equals(delegate) ? Delegate.CPU : Delegate.GPU);
        HandLandmarker.HandLandmarkerOptions opt = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(bo.build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(n)
                .setMinHandDetectionConfidence(0.3f)
                .setMinHandPresenceConfidence(0.3f)
                .setMinTrackingConfidence(0.3f)
                .build();
        return HandLandmarker.createFromOptions(ctx, opt);
    }

    public void detect(final String jpgBase64, final long ts, final String mode, final Cb cb) {
        ex.execute(() -> {
            try {
                long t0 = System.nanoTime();
                byte[] raw = Base64.decode(jpgBase64, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                if (bmp == null) { cb.done(false, "decode"); return; }
                boolean useTwo = "B".equals(mode) && two != null;
                HandLandmarker m = useTwo ? two : one;
                if (m == null) { cb.done(false, "not ready"); return; }
                long t;
                if (useTwo) { lastTs2 = Math.max(lastTs2 + 1, ts); t = lastTs2; } else { lastTs1 = Math.max(lastTs1 + 1, ts); t = lastTs1; }
                MPImage img = new BitmapImageBuilder(bmp).build();
                long t1 = System.nanoTime();
                HandLandmarkerResult r = m.detectForVideo(img, t);
                long t2 = System.nanoTime();
                try { img.close(); } catch (Throwable ignored) { }
                bmp.recycle();
                JSONArray lms = new JSONArray();
                List<List<NormalizedLandmark>> all = r.landmarks();
                for (List<NormalizedLandmark> hand : all) {
                    JSONArray h = new JSONArray();
                    for (NormalizedLandmark p : hand) {
                        JSONObject q = new JSONObject();
                        q.put("x", p.x()); q.put("y", p.y()); q.put("z", p.z());
                        h.put(q);
                    }
                    lms.put(h);
                }
                JSONObject o = new JSONObject();
                o.put("lms", lms);
                o.put("mode", useTwo ? "B" : "A");
                o.put("inf", (t2 - t1) / 1e6);          // model time only
                o.put("dec", (t1 - t0) / 1e6);          // picture decoding time
                cb.done(true, o);
            } catch (Throwable t) {
                cb.done(false, String.valueOf(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
            }
        });
    }

    /** Phone heat: battery temperature (deg C) and, on Android 10+, the system's own heat level (0 none ... 6 shutdown). */
    public JSONObject thermal(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            android.content.Intent i = ctx.registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (i != null) o.put("bat", i.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                if (pm != null) o.put("st", pm.getCurrentThermalStatus());
            }
        } catch (Throwable ignored) { }
        return o;
    }

    public void close() {
        try { if (one != null) one.close(); } catch (Throwable ignored) { }
        try { if (two != null) two.close(); } catch (Throwable ignored) { }
        one = null; two = null; lastTs1 = lastTs2 = 0;
    }
}
