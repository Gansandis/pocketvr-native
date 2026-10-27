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
    private final NvUi ui = new NvUi();
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (cam != null && rend != null) {
                float f = rend.fps;
                long p = cam.detPaceMs;
                if (f >= 42) p = Math.max(0, p - 20); else if (f > 0 && f < 34) p = Math.min(160, p + 20);
                cam.detPaceMs = p;
                if (hudOn) {
                    hud.setText(String.format("native v3.78 | วาด %.0f fps | กล้อง %.0f | ตรวจ %.0f Hz | โมเดล %.0f ms | แปลง %.0f ms | เว้น %d ms | นำหน้า %.0f ms%s",
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
        buildMenu();
        rend.ui = ui;
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

    private void buildMenu() {
        ui.toggles.add(new NvUi.Btn(() -> "แยกจอ VR: " + (rend.stereo ? "เปิด" : "ปิด"), () -> rend.stereo, () -> rend.stereo = !rend.stereo));
        ui.toggles.add(new NvUi.Btn(() -> "เลนส์: " + (rend.lens == 0 ? "ปิด" : rend.lens == 1 ? "เบา" : rend.lens == 2 ? "กลาง" : "แรง"), () -> rend.lens > 0, () -> rend.lens = (rend.lens + 1) % 4));
        ui.toggles.add(new NvUi.Btn(() -> "คาดการณ์มือ: " + (rend.predict ? "เปิด" : "ปิด"), () -> rend.predict, () -> rend.predict = !rend.predict));
        ui.toggles.add(new NvUi.Btn(() -> "โครงมือ: " + (rend.showSkel ? "เปิด" : "ปิด"), () -> rend.showSkel, () -> rend.showSkel = !rend.showSkel));
        ui.toggles.add(new NvUi.Btn(() -> "ตัวเลขสถานะ: " + (hudOn ? "เปิด" : "ปิด"), () -> hudOn, () -> hudOn = !hudOn));
        ui.toggles.add(new NvUi.Btn(() -> "กลับแอปหลัก", null, () -> runOnUiThread(() -> finish())));
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
                NvUi.Btn b = new NvUi.Btn(() -> label, null, () -> runOnUiThread(() -> {
                    try {
                        android.content.Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
                        if (li != null) startActivity(li);
                    } catch (Throwable ignored) { }
                }));
                b.icon = iconOf(ri.loadIcon(pm));
                ui.apps.add(b);
            }
            if (keep.size() > 6) ui.toggles.add(new NvUi.Btn(() -> "แอปหน้าถัดไป", null, () -> ui.nextPage()));
        } catch (Throwable t) { err = "apps: " + t; }
        ui.relayout();
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
        ui.post(tick);
    }

    @Override protected void onPause() {
        ui.removeCallbacks(tick);
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
