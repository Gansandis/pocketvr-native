package com.pocketvr.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Separate native VR screen: own camera, own hand model, own drawing. Tap top-left = split/full, top-right = lens strength, back = return. */
public class NativeVRActivity extends Activity implements VRRenderer.Host, NvCam.Listener {
    private GLSurfaceView gl;
    private VRRenderer rend;
    private NvCam cam;
    private NvHands hands;
    private TextView hud;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile String err = "";
    private boolean hudOn = true;
    private android.hardware.SensorManager sm;
    private android.hardware.Sensor rotSensor;
    private final android.hardware.SensorEventListener sl = new android.hardware.SensorEventListener() {
        private final float[] r = new float[16], o = new float[16];
        @Override public void onSensorChanged(android.hardware.SensorEvent e) {
            try {
                android.hardware.SensorManager.getRotationMatrixFromVector(r, e.values);
                int rot = getWindowManager().getDefaultDisplay().getRotation();
                if (rot == android.view.Surface.ROTATION_90) {
                    android.hardware.SensorManager.remapCoordinateSystem(r, android.hardware.SensorManager.AXIS_Y, android.hardware.SensorManager.AXIS_MINUS_X, o);
                    rend.setRotation(o);
                } else if (rot == android.view.Surface.ROTATION_270) {
                    android.hardware.SensorManager.remapCoordinateSystem(r, android.hardware.SensorManager.AXIS_MINUS_Y, android.hardware.SensorManager.AXIS_X, o);
                    rend.setRotation(o);
                } else rend.setRotation(r);
            } catch (Throwable ignored) { }
        }
        @Override public void onAccuracyChanged(android.hardware.Sensor s, int acc) { }
    };
    private final NvUi menu = new NvUi();
    private VRRenderer.Win wMenu, wClock, wStats;
    private final long t0 = System.currentTimeMillis();
    private boolean swRunning = false;
    private long swStart = 0, swAcc = 0;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (cam != null && rend != null) {
                float f = rend.fps;
                long p = cam.detPaceMs;
                if (f >= 42) p = Math.max(0, p - 20); else if (f > 0 && f < 34) p = Math.min(160, p + 20);
                cam.detPaceMs = p;
                if (hudOn) {
                    hud.setText((System.currentTimeMillis() - t0 < 15000 ? "จีบนิ้วโป้ง+นิ้วกลางค้างไว้ = เปิด/ปิดเมนู\n" : "") + String.format("native v3.79 | วาด %.0f fps | กล้อง %.0f | ตรวจ %.0f Hz | โมเดล %.0f ms | แปลง %.0f ms | เว้น %d ms | นำหน้า %.0f ms%s",
                            f, cam.camFps, cam.detHz, hands.inferMs, cam.convMs, p, rend.predMs, err.isEmpty() ? "" : "\n! " + err));
                } else hud.setText("");
            }
            ui.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hands = new NvHands();
        cam = new NvCam(this, hands, this);
        cam.detPaceMs = 0;
        rend = new VRRenderer(this, cam);
        rend.ui = menu;
        wMenu = rend.addWindow(menu, 0.95f);      // starts hidden: pinch thumb + middle finger and hold to bring it up
        buildWindows();
        buildMenu();
        gl = new GLSurfaceView(this);
        gl.setEGLContextClientVersion(2);
        gl.setPreserveEGLContextOnPause(false);
        gl.setRenderer(rend);
        gl.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        FrameLayout root = new FrameLayout(this);
        root.addView(gl);
        hud = new TextView(this);
        hud.setTextColor(Color.WHITE);
        hud.setTextSize(11);
        hud.setBackgroundColor(0x66000000);
        hud.setPadding(12, 4, 12, 4);
        root.addView(hud, new FrameLayout.LayoutParams(-2, -2));
        setContentView(root);
        root.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                float x = e.getX() / v.getWidth(), y = e.getY() / v.getHeight();
                if (y < 0.25f) {
                    if (x < 0.33f) rend.stereo = !rend.stereo;
                    else if (x > 0.67f) rend.lens = (rend.lens + 1) % 4;
                    else hudOn = !hudOn;
                }
            }
            return true;
        });
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 7);
    }

    // small floating windows (start hidden; opened from the menu)
    private void buildWindows() {
        final java.text.SimpleDateFormat tf = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
        final java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("EEE d MMM", new java.util.Locale("th"));
        final NvPanel clock = new NvPanel(600, 300, "นาฬิกา / จับเวลา");
        clock.refreshMs = 200;
        clock.body = (cv, p, w, h) -> {
            float u = h / 300f;
            p.setShader(null);
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setTextAlign(android.graphics.Paint.Align.CENTER);
            java.util.Date now = new java.util.Date();
            p.setColor(0xFFFFFFFF);
            p.setTextSize(66 * u);
            cv.drawText(tf.format(now), w / 2f, 112 * u, p);
            p.setColor(0xFFB8BEC8);
            p.setTextSize(20 * u);
            cv.drawText(df.format(now), w / 2f, 142 * u, p);
            long e = swAcc + (swRunning ? System.currentTimeMillis() - swStart : 0);
            p.setColor(0xFF9EE7F2);
            p.setTextSize(32 * u);
            cv.drawText(String.format(java.util.Locale.US, "%02d:%02d.%d", e / 60000, (e / 1000) % 60, (e / 100) % 10), w / 2f, 182 * u, p);
        };
        clock.add(new NvUi.Btn("", () -> swRunning ? "⏸ หยุด" : "▶ เริ่ม", null, () -> swRunning, 0, 0, () -> {
            long n = System.currentTimeMillis();
            if (swRunning) { swAcc += n - swStart; swRunning = false; } else { swStart = n; swRunning = true; }
        }), 40, 198, 250, 52);
        clock.add(new NvUi.Btn("", () -> "↺ รีเซ็ต", null, null, 0, 0, () -> { swRunning = false; swAcc = 0; }), 310, 198, 250, 52);
        wClock = rend.addWindow(clock, 0.55f);
        clock.onClose = () -> rend.setVisible(wClock, false, 0f, 0f);

        final NvPanel stats = new NvPanel(560, 300, "สถานะระบบ");
        stats.refreshMs = 500;
        stats.body = (cv, p, w, h) -> {
            float u = h / 300f;
            p.setShader(null);
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setTextAlign(android.graphics.Paint.Align.LEFT);
            p.setColor(0xFFFFFFFF);
            p.setTextSize(21 * u);
            String[] ln = {
                String.format("วาด %.0f fps   กล้อง %.0f fps", rend.fps, cam.camFps),
                String.format("ตรวจมือ %.0f ครั้ง/วิ   โมเดล %.0f ms", cam.detHz, hands.inferMs),
                String.format("แปลงภาพ %.0f ms   เว้น %d ms", cam.convMs, cam.detPaceMs),
                String.format("คาดการณ์นำหน้า %.0f ms", rend.predMs),
                err.isEmpty() ? "ไม่มีข้อผิดพลาด" : "! " + err};
            for (int i = 0; i < ln.length; i++) cv.drawText(ln[i], 30 * u, (86 + i * 36) * u, p);
        };
        wStats = rend.addWindow(stats, 0.55f);
        stats.onClose = () -> rend.setVisible(wStats, false, 0f, 0f);
    }

    private void buildMenu() {
        final int GA = 0xFF5A5A63, GB = 0xFF38383E;
        menu.home.add(new NvUi.Btn("🥽", () -> "แยกจอ VR", () -> rend.stereo ? "เปิดอยู่" : "ปิดอยู่", () -> rend.stereo, GA, GB, () -> rend.stereo = !rend.stereo));
        menu.home.add(new NvUi.Btn("👓", () -> "เลนส์", () -> rend.lens == 0 ? "ปิด" : rend.lens == 1 ? "เบา" : rend.lens == 2 ? "กลาง" : "แรง", () -> rend.lens > 0, GA, GB, () -> rend.lens = (rend.lens + 1) % 4));
        menu.home.add(new NvUi.Btn("✋", () -> "คาดการณ์มือ", () -> rend.predict ? "เปิดอยู่" : "ปิดอยู่", () -> rend.predict, GA, GB, () -> rend.predict = !rend.predict));
        menu.home.add(new NvUi.Btn("🦴", () -> "โครงมือ", () -> rend.skel == 0 ? "ปิด" : rend.skel == 1 ? "เส้นโครง" : "เส้นขอบเรืองแสง", () -> rend.skel > 0, GA, GB, () -> rend.skel = (rend.skel + 1) % 3));
        menu.home.add(new NvUi.Btn("⏱", () -> "นาฬิกา/จับเวลา", () -> wClock.visible ? "เปิดอยู่" : "ปิดอยู่", () -> wClock.visible, GA, GB, () -> rend.toggle(wClock, 18f, 0f)));
        menu.home.add(new NvUi.Btn("📈", () -> "สถานะระบบ", () -> wStats.visible ? "เปิดอยู่" : "ปิดอยู่", () -> wStats.visible, GA, GB, () -> rend.toggle(wStats, -18f, 0f)));
        menu.home.add(new NvUi.Btn("📊", () -> "ตัวเลขบนจอ", () -> hudOn ? "เปิดอยู่" : "ปิดอยู่", () -> hudOn, GA, GB, () -> hudOn = !hudOn));
        menu.home.add(new NvUi.Btn("📍", () -> "จัดกลางใหม่", () -> "วางเมนูตรงหน้า", null, GA, GB, () -> rend.recenter = true));
        menu.home.add(new NvUi.Btn("🎈", () -> "ลอยเบาๆ", () -> rend.floaty ? "เปิดอยู่" : "ปิดอยู่", () -> rend.floaty, GA, GB, () -> rend.floaty = !rend.floaty));
        menu.home.add(new NvUi.Btn("🏠", () -> "กลับแอปหลัก", () -> "ออกจากหน้าจอนี้", null, 0xFF8D6BFF, 0xFF4A2FB8, () -> runOnUiThread(() -> finish())));
        menu.dock.add(new NvUi.Btn("🏠", () -> "เมนู", null, () -> menu.open, 0, 0, () -> menu.toggleOpen()));
        menu.dock.add(new NvUi.Btn("🥽", () -> "แยกจอ", null, () -> rend.stereo, 0, 0, () -> rend.stereo = !rend.stereo));
        menu.dock.add(new NvUi.Btn("👓", () -> "เลนส์", null, () -> rend.lens > 0, 0, 0, () -> rend.lens = (rend.lens + 1) % 4));
        menu.dock.add(new NvUi.Btn("✖", () -> "ซ่อนเมนู", null, null, 0, 0, () -> rend.hideMenu()));
        menu.dock.add(new NvUi.Btn("✋", () -> "คาดการณ์", null, () -> rend.predict, 0, 0, () -> rend.predict = !rend.predict));
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.Intent q = new android.content.Intent(android.content.Intent.ACTION_MAIN);
            q.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            java.util.List<android.content.pm.ResolveInfo> infos = pm.queryIntentActivities(q, 0);
            java.util.List<android.content.pm.ResolveInfo> keep = new java.util.ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (android.content.pm.ResolveInfo ri : infos) {
                String p = ri.activityInfo.packageName;
                if (p.equals(getPackageName()) || !seen.add(p)) continue;
                keep.add(ri);
            }
            java.util.Collections.sort(keep, (a, b) -> String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm))));
            for (int i = 0; i < keep.size() && i < 60; i++) {
                final android.content.pm.ResolveInfo ri = keep.get(i);
                final String label = String.valueOf(ri.loadLabel(pm));
                final String pkg = ri.activityInfo.packageName;
                NvUi.Btn b = new NvUi.Btn("", () -> label, null, null, 0xFF3A3F4B, 0xFF262932, () -> runOnUiThread(() -> {
                    try {
                        android.content.Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
                        if (li != null) startActivity(li);
                    } catch (Throwable ignored) { }
                }));
                b.icon = iconOf(ri.loadIcon(pm));
                menu.apps.add(b);
            }
        } catch (Throwable t) { err = "apps: " + t; }
        menu.relayout();
    }

    private static android.graphics.Bitmap iconOf(android.graphics.drawable.Drawable d) {
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bm);
        d.setBounds(0, 0, 96, 96);
        d.draw(c);
        return bm;
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (r.length == 0 || r[0] != PackageManager.PERMISSION_GRANTED) finish();
        else { gl.onPause(); gl.onResume(); }
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override protected void onResume() {
        super.onResume();
        immersive();
        gl.onResume();
        try {
            sm = (android.hardware.SensorManager) getSystemService(SENSOR_SERVICE);
            rotSensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (rotSensor == null) rotSensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR);
            if (rotSensor != null) sm.registerListener(sl, rotSensor, android.hardware.SensorManager.SENSOR_DELAY_GAME);
            else err = "no turn sensor";
        } catch (Throwable t) { err = "sensor: " + t; }
        rend.recenter = true;
        ui.post(tick);
    }

    @Override protected void onPause() {
        ui.removeCallbacks(tick);
        try { if (sm != null) sm.unregisterListener(sl); } catch (Throwable ignored) { }
        cam.stop();
        gl.onPause();
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

    // called on the drawing thread when a new camera texture exists
    @Override public void onCamSurface(SurfaceTexture st) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) cam.start(st);
    }

    @Override public void onImageSize(int w, int h, boolean rot180) { rend.setImage(w, h, rot180); }

    @Override public void onError(String msg) { err = msg; }
}
