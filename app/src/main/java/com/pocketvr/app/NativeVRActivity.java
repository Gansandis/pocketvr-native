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
    private android.hardware.Sensor magSensor;
    public volatile boolean compass = true;                  // the compass sensor slowly corrects the turn sensor's sideways drift
    private final android.hardware.SensorEventListener sl = new android.hardware.SensorEventListener() {
        private final float[] r = new float[16], o = new float[16], gm = new float[16], out = new float[16];
        private boolean haveG = false, haveD = false;
        private double delta = 0;                            // radians: how far the turn sensor has drifted from the compass
        private long lastMagT = 0;
        private float[] mapped(float[] values) {
            android.hardware.SensorManager.getRotationMatrixFromVector(r, values);
            int rot = getWindowManager().getDefaultDisplay().getRotation();
            if (rot == android.view.Surface.ROTATION_90) { android.hardware.SensorManager.remapCoordinateSystem(r, android.hardware.SensorManager.AXIS_Y, android.hardware.SensorManager.AXIS_MINUS_X, o); return o; }
            if (rot == android.view.Surface.ROTATION_270) { android.hardware.SensorManager.remapCoordinateSystem(r, android.hardware.SensorManager.AXIS_MINUS_Y, android.hardware.SensorManager.AXIS_X, o); return o; }
            return r;
        }
        private double heading(float[] m) { return Math.atan2(-m[6], -m[2]); }   // where the back camera points, turned about "up"
        @Override public void onSensorChanged(android.hardware.SensorEvent e) {
            try {
                boolean isMag = magSensor != null && e.sensor.getType() == android.hardware.Sensor.TYPE_ROTATION_VECTOR && rotSensor != null && rotSensor.getType() == android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR;
                float[] m = mapped(e.values);
                if (isMag) {
                    if (!haveG || e.accuracy < 2) return;
                    if (Math.abs(m[10]) > 0.8f || Math.abs(gm[10]) > 0.8f) return;       // looking straight up/down: the turn angle is undefined
                    double tgt = heading(m) - heading(gm);
                    while (tgt > Math.PI) tgt -= 2 * Math.PI;
                    while (tgt < -Math.PI) tgt += 2 * Math.PI;
                    double err = tgt - delta;
                    while (err > Math.PI) err -= 2 * Math.PI;
                    while (err < -Math.PI) err += 2 * Math.PI;
                    long now = e.timestamp;
                    double dt = lastMagT == 0 ? 0 : Math.min(0.5, (now - lastMagT) / 1e9);
                    lastMagT = now;
                    if (!haveD) { delta = tgt; haveD = true; }
                    else if (compass) {
                        double step = err * dt / 20.0;                                  // slow: about 20 s, so compass noise never shows
                        double mx = Math.toRadians(0.5) * dt * 3;                       // and never faster than ~1.5 degrees a second
                        delta += Math.max(-mx, Math.min(mx, step));
                    }
                    return;
                }
                System.arraycopy(m, 0, gm, 0, 16);
                haveG = true;
                if (compass && haveD) {
                    float c = (float) Math.cos(delta), s = (float) Math.sin(delta);
                    System.arraycopy(m, 0, out, 0, 16);
                    for (int k = 0; k < 4; k++) { out[k] = c * m[k] - s * m[4 + k]; out[4 + k] = s * m[k] + c * m[4 + k]; }
                    rend.setRotation(out);
                } else rend.setRotation(m);
                if (motion != null && motion.enabled) motion.pushRot(e.timestamp, rend.rotCopy());
            } catch (Throwable ignored) { }
        }
        @Override public void onAccuracyChanged(android.hardware.Sensor s, int acc) { }
    };
    private final NvUi menu = new NvUi();
    private VRRenderer.Win wMenu, wClock, wStats, wKeys;
    private NvKeyboard kbd;
    private NvMedia media;
    private NvVideo video;
    private VRRenderer.Win wMedia, wVideo;
    private final java.util.concurrent.ExecutorService bgIo = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final NvBrowser[] brs = new NvBrowser[3];             // up to three browser windows at once
    private final VRRenderer.Win[] bws = new VRRenderer.Win[3];
    private static final float[] BYAW = {0f, 52f, -52f};
    private NvMotion motion;
    private final long t0 = System.currentTimeMillis();
    private boolean swRunning = false;
    private long swStart = 0, swAcc = 0;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (cam != null && rend != null) {
                float f = rend.fps;
                long p = cam.detPaceMs;
                if (f >= 40) p = Math.max(0, p - 20); else if (f > 0 && f < 28) p = Math.min(100, p + 15);
                cam.detPaceMs = p;
                for (NvBrowser b : brs) if (b != null) b.poll();
                if (motion != null && motion.enabled) menu.markDirty();      // so the numbers on the walk-around button stay fresh
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
        motion = new NvMotion();
        motion.rot = () -> rend.rotCopy();
        cam.motion = motion;
        rend.motion = motion;
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

        final NvKeyboard kb = new NvKeyboard();
        kb.copier = s -> runOnUiThread(() -> {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("pocketvr", s));
            } catch (Throwable ignored) { }
        });
        wKeys = rend.addWindow(kb, 0.80f);
        kbd = kb;
        kb.onClose = () -> { kb.sink = null; rend.setVisible(wKeys, false, 0f, 0f); };

        // gallery, 360 viewer and cinema
        video = new NvVideo(this);
        wVideo = rend.addWindow(video, 1.7f);
        wVideo.showInCinema = true; wKeys.showInCinema = true;
        video.onClose = () -> { video.release(); rend.setVisible(wVideo, false, 0f, 0f); rend.cinema = false; };
        media = new NvMedia(this);
        wMedia = rend.addWindow(media, 1.3f);
        wMedia.showInCinema = true;
        media.onClose = () -> { rend.setPano(null); media.panoOn = false; rend.setVisible(wMedia, false, 0f, 0f); };
        media.host = new NvMedia.Host() {
            @Override public void onPano(final android.net.Uri u) {
                if (rend.panoActive()) { rend.setPano(null); media.panoOn = false; return; }
                media.panoOn = true;                                       // button shows "exit" while it loads
                bgIo.execute(() -> {
                    android.graphics.Bitmap b = media.decode(u, 4096);
                    if (b == null) { media.panoOn = false; return; }
                    rend.setPano(b);
                });
            }
            @Override public void onVideo(android.net.Uri u) {
                rend.setPano(null); media.panoOn = false;
                video.open(u);
                rend.cinema = true;                                       // the room goes dark: only the screen (and the menu) stay
                rend.setVisible(wVideo, true, 0f, 4f);
                rend.recenter = true;
                rend.setVisible(wMedia, false, 0f, 0f);                   // the cinema takes the place of the gallery
            }
        };
    }

    // ---- permission to read the phone's pictures and videos (asked once, with the phone in the hand) ----
    private String[] mediaPerms() {
        return android.os.Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO}
                : new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private boolean hasMediaPerm() {
        for (String s : mediaPerms()) if (checkSelfPermission(s) == PackageManager.PERMISSION_GRANTED) return true;   // "some photos only" on newer Android still counts
        return false;
    }

    private void openGallery() {
        if (!hasMediaPerm()) {
            menu.toast("ถอดแว่นแล้วกดอนุญาตบนหน้าจอ");
            runOnUiThread(() -> requestPermissions(mediaPerms(), 77));
            return;
        }
        if (!media.isLoaded()) media.reload();
        rend.setVisible(wMedia, true, 0f, 0f);
    }

    private NvBrowser browserSlot(final int i) {
        if (brs[i] != null) return brs[i];
        final NvBrowser b = new NvBrowser(this, ui);
        b.kbHost = s -> { kbd.sink = s; kbd.clear(); if (!wKeys.visible) rend.setVisible(wKeys, true, 0f, -28f); };
        final VRRenderer.Win w = rend.addWindow(b, 1.25f);
        w.showInCinema = true;
        b.onClose = () -> { b.close(); rend.setVisible(w, false, 0f, 0f); if (kbd != null) kbd.sink = null; };
        b.onNewWindow = () -> openBrowser(-1);
        brs[i] = b; bws[i] = w;
        return b;
    }

    /** show browser window i (or the first free one when i < 0) */
    private void openBrowser(int want) {
        int i = want;
        if (i < 0) { for (int k = 0; k < brs.length; k++) if (bws[k] == null || !bws[k].visible) { i = k; break; } }
        if (i < 0) { menu.toast("เปิดเบราว์เซอร์ได้สูงสุด 3 หน้าต่าง"); return; }
        NvBrowser b = browserSlot(i);
        rend.setVisible(bws[i], true, BYAW[i], 4f);
        if (!b.isOpen()) b.open("https://www.google.com");
        b.setActive(true);
    }

    private int browsersShown() { int n = 0; for (VRRenderer.Win w : bws) if (w != null && w.visible) n++; return n; }

    private void buildMenu() {
        final int GA = 0xFF5A5A63, GB = 0xFF38383E;
        final java.util.List<NvUi.Btn> cCtl = menu.addTab("🎛", "ควบคุม", "การควบคุมมือ");
        final java.util.List<NvUi.Btn> cDisp = menu.addTab("🖥", "แสดงผล", "การแสดงผล");
        final java.util.List<NvUi.Btn> cLab = menu.addTab("🧪", "ทดลอง", "ฟีเจอร์ทดลอง");
        menu.home.add(new NvUi.Btn("🌐", () -> "เบราว์เซอร์", () -> browsersShown() > 0 ? browsersShown() + " หน้าต่าง" : "ปิดอยู่", () -> browsersShown() > 0, GA, GB, () -> {
            if (browsersShown() > 0) {                                     // hide them all (they keep their pages and rest quietly)
                for (int k = 0; k < brs.length; k++) if (brs[k] != null && bws[k].visible) { brs[k].setActive(false); rend.setVisible(bws[k], false, 0f, 0f); }
            } else {
                boolean any = false;                                         // bring back the ones that were open
                for (int k = 0; k < brs.length; k++) if (brs[k] != null && brs[k].isOpen()) { rend.setVisible(bws[k], true, BYAW[k], 4f); brs[k].setActive(true); any = true; }
                if (!any) openBrowser(0);
            }
        }));
        menu.home.add(new NvUi.Btn("🖼", () -> "แกลเลอรี/โรงหนัง", () -> rend.panoActive() ? "กำลังดูภาพ 360°" : (wVideo.visible ? "กำลังเล่นวิดีโอ" : (wMedia.visible ? "เปิดอยู่" : "รูป วิดีโอ 360°")), () -> wMedia.visible || wVideo.visible || rend.panoActive(), GA, GB, () -> {
            if (wMedia.visible) { media.onClose.run(); }
            else if (wVideo.visible) { video.onClose.run(); }
            else openGallery();
        }));
        menu.home.add(new NvUi.Btn("🎬", () -> "โหมดโรงหนัง", () -> rend.cinema ? "ห้องมืด เห็นแค่จอ" : "ปิดอยู่ (เห็นห้องจริง)", () -> rend.cinema, GA, GB, () -> { rend.cinema = !rend.cinema; rend.recenter = true; }));
        menu.home.add(new NvUi.Btn("⌨", () -> "คีย์บอร์ด", () -> wKeys.visible ? "เปิดอยู่" : "ปิดอยู่", () -> wKeys.visible, GA, GB, () -> rend.toggle(wKeys, 0f, -24f)));
        menu.home.add(new NvUi.Btn("⏱", () -> "นาฬิกา/จับเวลา", () -> wClock.visible ? "เปิดอยู่" : "ปิดอยู่", () -> wClock.visible, GA, GB, () -> rend.toggle(wClock, 18f, 0f)));
        menu.home.add(new NvUi.Btn("📈", () -> "สถานะระบบ", () -> wStats.visible ? "เปิดอยู่" : "ปิดอยู่", () -> wStats.visible, GA, GB, () -> rend.toggle(wStats, -18f, 0f)));
        menu.home.add(new NvUi.Btn("📍", () -> "จัดกลางใหม่", () -> "วางเมนูตรงหน้า", null, GA, GB, () -> rend.recenter = true));
        menu.home.add(new NvUi.Btn("🕸", () -> "เวอร์ชันเว็บ (สำรอง)", () -> "เปิดหน้าเว็บเดิม", null, 0xFF8D6BFF, 0xFF4A2FB8, () -> runOnUiThread(() -> {
            try { startActivity(new android.content.Intent(this, MainActivity.class)); } catch (Throwable t) { err = "web: " + t; }
            finish();
        })));
        menu.home.add(new NvUi.Btn("⏻", () -> "ออกจากแอป", () -> "ปิด PocketVR", null, 0xFF6A3036, 0xFF4A2024, () -> runOnUiThread(() -> finishAffinity())));

        cCtl.add(new NvUi.Btn("👆", () -> "เลือกด้วย", () -> rend.sel == 0 ? "จีบนิ้ว" : rend.sel == 1 ? "สัมผัสอากาศ" : "ทั้งสองแบบ", () -> rend.sel != 0, GA, GB, () -> rend.sel = (rend.sel + 1) % 3));
        cCtl.add(new NvUi.Btn("🎚", () -> "ความไวสัมผัสอากาศ", () -> rend.pokeS == 0 ? "ไว (เบา)" : rend.pokeS == 1 ? "กลาง" : "หนัก (ต้องดัน)", () -> rend.sel != 0, GA, GB, () -> rend.pokeS = (rend.pokeS + 1) % 3));
        cCtl.add(new NvUi.Btn("✋", () -> "คาดการณ์มือ", () -> rend.predict ? "เปิดอยู่" : "ปิดอยู่", () -> rend.predict, GA, GB, () -> rend.predict = !rend.predict));
        cCtl.add(new NvUi.Btn("🔍", () -> "ความละเอียดตรวจมือ", () -> cam.detW + " px" + (cam.detW <= 320 ? " (เร็ว)" : cam.detW >= 640 ? " (แม่นสุด)" : ""), null, GA, GB, () -> cam.detW = cam.detW <= 320 ? 480 : (cam.detW <= 480 ? 640 : 320)));
        cCtl.add(new NvUi.Btn("🧭", () -> "เข็มทิศช่วยแก้ทิศ", () -> compass ? "เปิดอยู่ (กันจอไหล)" : "ปิดอยู่", () -> compass, GA, GB, () -> compass = !compass));
        cCtl.add(new NvUi.Btn("🎈", () -> "ลอยเบาๆ", () -> rend.floaty ? "เปิดอยู่" : "ปิดอยู่", () -> rend.floaty, GA, GB, () -> rend.floaty = !rend.floaty));
        cDisp.add(new NvUi.Btn("🥽", () -> "แยกจอ VR", () -> rend.stereo ? "เปิดอยู่" : "ปิดอยู่", () -> rend.stereo, GA, GB, () -> rend.stereo = !rend.stereo));
        cDisp.add(new NvUi.Btn("👓", () -> "เลนส์", () -> rend.lens == 0 ? "ปิด" : rend.lens == 1 ? "เบา" : rend.lens == 2 ? "กลาง" : "แรง", () -> rend.lens > 0, GA, GB, () -> rend.lens = (rend.lens + 1) % 4));
        cDisp.add(new NvUi.Btn("🦴", () -> "โครงมือ", () -> rend.skel == 0 ? "ปิด" : rend.skel == 1 ? "เส้นโครง" : "เส้นขอบเรืองแสง", () -> rend.skel > 0, GA, GB, () -> rend.skel = (rend.skel + 1) % 3));
        cDisp.add(new NvUi.Btn("🪟", () -> "ความโปร่งของแผง", () -> NvUi.glass >= 0xE0 ? "ทึบ" : NvUi.glass >= 0xA0 ? "กึ่งโปร่ง" : NvUi.glass >= 0x70 ? "โปร่ง" : "โปร่งมาก", () -> NvUi.glass < 0xE0, GA, GB, () -> {
            NvUi.glass = NvUi.glass >= 0xE0 ? 0xB0 : NvUi.glass >= 0xA0 ? 0x80 : NvUi.glass >= 0x70 ? 0x55 : 0xF0;
            menu.markDirty(); if (wClock != null) wClock.c.markDirty(); if (wStats != null) wStats.c.markDirty();
        }));
        cDisp.add(new NvUi.Btn("📊", () -> "ตัวเลขบนจอ", () -> hudOn ? "เปิดอยู่" : "ปิดอยู่", () -> hudOn, GA, GB, () -> hudOn = !hudOn));
        cLab.add(new NvUi.Btn("🚶", () -> "เดินรอบได้ (ทดลอง)", () -> motion.enabled ? (motion.err.isEmpty() ? String.format("เปิด %.2f,%.2f ม. จุด %d/%d %s", motion.px, motion.py, motion.usedN, motion.tracked, motion.how + " " + motion.size) : "ผิดพลาด " + (motion.err.length() > 26 ? motion.err.substring(motion.err.length() - 26) : motion.err)) : "ปิดอยู่", () -> motion.enabled, GA, GB, () -> {
            motion.enabled = !motion.enabled;
            motion.reset();
            for (VRRenderer.Win w : rend.wins) w.placeReq = true;       // put the windows up again in front of you, at the new starting point
        }));
        cLab.add(new NvUi.Btn("📏", () -> "ขนาดห้อง (ความไวเดิน)", () -> String.format("ประมาณ %.1f ม.", motion.depthM), () -> motion.depthM > 3.5f, GA, GB, () -> motion.depthM = motion.depthM < 2.5f ? 3f : motion.depthM < 3.5f ? 4f : motion.depthM < 5f ? 6f : 2f));
        cLab.add(new NvUi.Btn("📐", () -> "ปรับเทียบการเดิน", () -> motion.calibrating ? String.format("วัดได้ %.2f ม. เดินครบ 1 ม. แล้วกด", motion.calWalked()) : "กด แล้วเดินข้าง 1 ม. แล้วกดอีกที", () -> motion.calibrating, GA, GB, () -> {
            if (!motion.enabled) return;
            if (!motion.calibrating) motion.calStart(); else motion.calEnd(1.0f);
        }));
        cLab.add(new NvUi.Btn("⏱", () -> "หน่วงภาพกล้อง", () -> motion.tsReal ? "ใช้เวลาจากภาพ" : motion.lagMs + " ms (กดเปลี่ยน)", null, GA, GB, () -> motion.lagMs = motion.lagMs < 50 ? 70 : motion.lagMs < 90 ? 110 : motion.lagMs < 130 ? 150 : 30));
        cDisp.add(new NvUi.Btn("👁", () -> "ภาพซ้อน: ขยับเข้าหาจมูก", () -> String.format("%.2f", rend.eyeShift) + "  (กดเพิ่ม)", () -> rend.stereo, GA, GB, () -> rend.eyeShift = Math.min(0.40f, Math.round((rend.eyeShift + 0.03f) * 100f) / 100f)));
        cDisp.add(new NvUi.Btn("👁", () -> "ภาพซ้อน: ขยับออกจากจมูก", () -> String.format("%.2f", rend.eyeShift) + "  (กดลด)", () -> rend.stereo, GA, GB, () -> rend.eyeShift = Math.max(-0.20f, Math.round((rend.eyeShift - 0.03f) * 100f) / 100f)));
        cDisp.add(new NvUi.Btn("✨", () -> "ความคมภาพกล้อง", () -> rend.sharpen <= 0.01f ? "ปิด (เร็วสุด)" : rend.sharpen < 0.45f ? "น้อย" : rend.sharpen < 0.75f ? "กลาง" : "มาก", () -> rend.sharpen > 0.01f, GA, GB, () -> rend.sharpen = rend.sharpen <= 0.01f ? 0.3f : rend.sharpen < 0.45f ? 0.55f : rend.sharpen < 0.75f ? 0.9f : 0f));
        cDisp.add(new NvUi.Btn("🖱", () -> "เคอร์เซอร์", () -> rend.cursorSz == 0 ? "เล็กจาง" : rend.cursorSz == 1 ? "กลาง" : "ใหญ่", null, GA, GB, () -> rend.cursorSz = (rend.cursorSz + 1) % 3));
        menu.dock.add(new NvUi.Btn("🏠", () -> "เมนู", null, () -> menu.open, 0, 0, () -> menu.toggleOpen()));
        menu.dock.add(new NvUi.Btn("🥽", () -> "แยกจอ", null, () -> rend.stereo, 0, 0, () -> rend.stereo = !rend.stereo));
        menu.dock.add(new NvUi.Btn("👓", () -> "เลนส์", null, () -> rend.lens > 0, 0, 0, () -> rend.lens = (rend.lens + 1) % 4));
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
        if (rc == 77) {                                                  // pictures / videos permission (the app keeps running if it is refused)
            if (hasMediaPerm()) { media.reload(); rend.setVisible(wMedia, true, 0f, 0f); }
            else menu.toast("ยังไม่ได้รับอนุญาตให้อ่านรูป");
            return;
        }
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
            magSensor = rotSensor != null && rotSensor.getType() == android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR ? sm.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR) : null;
            if (rotSensor != null) sm.registerListener(sl, rotSensor, android.hardware.SensorManager.SENSOR_DELAY_GAME);
            if (magSensor != null) sm.registerListener(sl, magSensor, android.hardware.SensorManager.SENSOR_DELAY_UI);
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

    @Override protected void onDestroy() {
        try { if (video != null) video.release(); } catch (Throwable ignored) { }
        bgIo.shutdown();
        for (NvBrowser b : brs) { try { if (b != null) b.close(); } catch (Throwable ignored) { } }
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

    // called on the drawing thread when a new camera texture exists
    @Override public void onCamSurface(SurfaceTexture st) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) cam.start(st);
    }

    @Override public void onImageSize(int w, int h, boolean rot180) { rend.setImage(w, h, rot180); }

    @Override public void onError(String msg) { err = msg; }
}
