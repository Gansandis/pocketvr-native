package com.pocketvr.app;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Draws camera -> small scene picture (+ hand skeleton) -> screen, mono or split (VR box) with optional lens warp. */
public class VRRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    public interface Host { void onCamSurface(SurfaceTexture st); }

    private static final float[] LENS_K = {0f, 0.12f, 0.22f, 0.35f};
    private static final int[] BONES = {0,1,1,2,2,3,3,4, 0,5,5,6,6,7,7,8, 5,9,9,10,10,11,11,12, 9,13,13,14,14,15,15,16, 13,17,17,18,18,19,19,20, 0,17};

    private final Host host;
    private final NvCam cam;
    public volatile boolean stereo = true;
    public volatile int lens = 1;
    public volatile float chroma = 0f;                    // lens colour fringes (red / blue edges): the three colours are drawn a hair apart at the edges, the other way round
    public volatile float fps = 0;
    public volatile int fpsCap = 0;                        // 0 = as fast as the screen allows; 30 / 45 = slow down to save heat and battery
    public volatile boolean autoRes = true;                // lower the size of the picture when the frame rate cannot be kept, and raise it again when there is room
    public volatile float sceneScale = 1f;                 // 0.6 .. 1
    private long lastScaleT = 0, lastFrameNs = 0;
    public volatile boolean predict = true;
    public volatile int skel = 1;           // 0 off, 1 skeleton, 2 glowing outline (like the web version)
    public volatile float predMs = 0;
    public NvUi ui;
    private int progUi, uiTex = 0;
    private volatile float[] rotNow = new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    public volatile boolean recenter = false;
    public volatile boolean cinema = false;                // dark room: no camera picture, only the windows marked showInCinema
    public volatile float eyeShift = 0.12f;                // VR split view: how far each eye's picture is moved towards the nose (fixes a doubled picture)
    public volatile float sharpen = 0.5f;                  // camera picture sharpening 0..1
    private boolean shown(Win w) { return w.visible && (!cinema || w.showInCinema); }
    private final float[] mP = new float[16], mM = new float[16];
    private float tanH = 0.65f, tanV = 0.37f;           // what the windows are drawn with (the field of view you see)
    private float tanHc = 0.65f, tanVc = 0.37f;         // what the camera picture covers (hands, pointer)
    private float winZoom = 1f;                          // windows are drawn this much bigger in the VR split view
    private float camScale = 1f;                        // camera picture size relative to the scene (below 1 = smaller, with a dark border)
    public volatile float vrFov = 80f;                  // VR split view: how wide each eye's view is in degrees; should match the headset lenses
    private static final float PANEL_D = 1.5f;
    private int progPanel, progVid, progPano;
    // ---- 360 picture around the user (replaces the camera picture while it is on) ----
    private volatile android.graphics.Bitmap panoBmp = null;
    private volatile int panoCmd = 0;                       // 1 = show panoBmp, 2 = switch off
    private volatile boolean panoOn = false;
    private int panoTex = 0;
    private float panoLon0 = 0f;
    private final float[] rm3 = new float[9];
    public boolean panoActive() { return panoOn || panoCmd == 1; }
    /** a whole-sky (equirectangular) picture to look around in; null = back to the camera */
    public void setPano(android.graphics.Bitmap b) { panoBmp = b; panoCmd = b == null ? 2 : 1; }
    private float cnx = 0, cny = 0;
    private volatile float[] rotPrev = null;
    private volatile long tPrev = 0, tLast = 0;
    private volatile float[] rotView = new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};   // what the picture is drawn with (the turn, carried on to this very moment)
    public void setRotation(float[] m) {
        float[] c = m.clone();
        rotPrev = rotNow; tPrev = tLast; tLast = System.nanoTime();
        rotNow = c;
        synchronized (rhLock) { rhT[rhI] = System.nanoTime(); rhM[rhI] = c; rhI = (rhI + 1) % rhT.length; }
    }
    // camera picture timewarp: the picture is ~100 ms old when it is shown, so it is turned by how far the head has turned since it was taken
    public volatile boolean reproj = true;
    public volatile int camLagMs = 80;                    // used when the picture's own time cannot be trusted
    public volatile float camLagMeasured = 0f;            // how old the picture really is when shown (ms); 0 = not known
    private final Object rhLock = new Object();
    private final long[] rhT = new long[160];
    private final float[][] rhM = new float[160][];
    private int rhI = 0;
    private float[] frameRot = null;
    private final float[] dm3 = new float[9];
    /** keep the turn a clean rotation (columns of length 1, at right angles) */
    private static void ortho(float[] m) {
        float a0 = m[0], a1 = m[1], a2 = m[2];
        float l = (float) Math.sqrt(a0 * a0 + a1 * a1 + a2 * a2); if (l < 1e-6f) return;
        a0 /= l; a1 /= l; a2 /= l;
        float b0 = m[4], b1 = m[5], b2 = m[6];
        float d = a0 * b0 + a1 * b1 + a2 * b2;
        b0 -= d * a0; b1 -= d * a1; b2 -= d * a2;
        l = (float) Math.sqrt(b0 * b0 + b1 * b1 + b2 * b2); if (l < 1e-6f) return;
        b0 /= l; b1 /= l; b2 /= l;
        m[0] = a0; m[1] = a1; m[2] = a2; m[4] = b0; m[5] = b1; m[6] = b2;
        m[8] = a1 * b2 - a2 * b1; m[9] = a2 * b0 - a0 * b2; m[10] = a0 * b1 - a1 * b0;
        m[3] = m[7] = m[11] = m[12] = m[13] = m[14] = 0f; m[15] = 1f;
    }

    /** the head's turn at a moment in the past, in between the two readings around it (smooth even when the sensor is slow) */
    private float[] rotBefore(long t) {
        synchronized (rhLock) {
            float[] a = null, b = null; long ta = Long.MIN_VALUE, tb = Long.MAX_VALUE;
            for (int i = 0; i < rhT.length; i++) {
                if (rhM[i] == null) continue;
                if (rhT[i] <= t && rhT[i] > ta) { ta = rhT[i]; a = rhM[i]; }
                if (rhT[i] > t && rhT[i] < tb) { tb = rhT[i]; b = rhM[i]; }
            }
            if (a == null || b == null || tb - ta > 100_000_000L) return a;
            float f = (float) (t - ta) / (float) (tb - ta);
            float[] o = new float[16];
            for (int i = 0; i < 16; i++) o[i] = a[i] + (b[i] - a[i]) * f;
            ortho(o);
            return o;
        }
    }

    /** once per picture: carry the last two readings on to this moment, so the view turns smoothly even if the sensor is slower than the screen */
    private void updateView() {
        float[] r1 = rotNow, r0 = rotPrev; long t1 = tLast, t0 = tPrev;
        long now = System.nanoTime();
        if (r1 == null) return;
        float span = (t1 - t0) / 1e9f, dt = (now - t1) / 1e9f;
        if (r0 == null || span < 0.002f || span > 0.1f || dt <= 0f) { rotView = r1; return; }
        float k = Math.min(dt, 0.025f) / span;                 // never look further ahead than 25 ms
        float[] e = new float[9];
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) {
            float dl = 0f;
            for (int j = 0; j < 3; j++) dl += r1[j * 4 + r] * r0[j * 4 + c];          // R1 * R0^T
            e[r * 3 + c] = (r == c ? 1f : 0f) + k * (dl - (r == c ? 1f : 0f));
        }
        float[] o = new float[16];
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) {
            float s = 0f;
            for (int j = 0; j < 3; j++) s += e[r * 3 + j] * r1[c * 4 + j];
            o[c * 4 + r] = s;
        }
        ortho(o);
        rotView = o;
    }
    public float[] rotCopy() { return rotNow; }
    public volatile NvMotion motion;
    private final float[] tE = new float[16], tF = new float[16];
    private void shiftBy(Win w, float[] m, float ux, float uy, float uz) {   // the user is at (ux,uy,uz); the window was set up at its base
        android.opengl.Matrix.setIdentityM(tE, 0);
        android.opengl.Matrix.translateM(tE, 0, w.wx - ux, w.wy - uy, w.wz - uz);
        android.opengl.Matrix.multiplyMM(tF, 0, tE, 0, m, 0);
        System.arraycopy(tF, 0, m, 0, 16);
    }
    public volatile boolean floaty = false;               // windows stay perfectly still unless this is switched on
    public volatile int cursorSz = 0;                     // 0 small and faint, 1 medium, 2 large
    public volatile int sel = 2;              // how things are pressed: 0 pinch, 1 air touch (push the index finger forward), 2 both
    public volatile int pokeS = 1;            // air touch sensitivity: 0 light, 1 medium, 2 firm
    private static final float[] POKE_TH = {0.07f, 0.10f, 0.14f};
    private float pkBase = 0, pkPeak = 0, pkProg = 0;
    private long pkT = 0;
    private boolean pkOn = false, pkInit = false;
    private long pkT2 = 0, pkAbove = 0, idxSince = 0, pkRelT = 0, lastFreshT = 0;
    private float pkDepthF = 0, handSpd = 0, lastTx = 0, lastTy = 0;
    private float[] lastRef = null;
    private final float[] tA = new float[16], tB = new float[16], tC = new float[16], tD = new float[16];

    /** A floating window in the room: it keeps its place when the phone turns, can float a little, be grabbed by its bar, and be pulled bigger or smaller. */
    public static class Win {
        public final NvContent c;
        public final float widthM;
        public volatile boolean visible = false, placeReq = false, showInCinema = false;
        public volatile boolean hasSaved = false;            // the user moved / resized it once: remember where, and put it back there next time
        public volatile float defYaw = 0, defPitch = 0;      // where it appears by default (before the user moved it)
        public volatile float svYaw = 0, svPitch = 0, svScale = 1f, svDist = 1.5f, placeDist = 0f;
        public volatile float wantScale = 0;                 // size to take when (re)placed in front of the user; 0 = normal
        public volatile boolean newOpen = false;             // just opened (not just re-centred): it comes in with a slide
        float slideDegP = 0f;                                // the same, upwards
        float slideDeg = 0f;                                 // at the start of the slide the window is this many degrees to the right of where it ends up (it starts behind its neighbour)
        float animA = 1f, animS = 1f;                        // fade and size while it comes in
        boolean animOn = false;
        long animT0 = 0;
        int texW = 0, texH = 0;
        public volatile float offYaw = 0, offPitch = 0;      // where it appears, relative to the view centre (degrees)
        float[] rot = null;
        float scale = 1f;
        int tex = 0;
        final float[] mv = new float[16], mvp = new float[16], inv = new float[16];
        float wx, wy, wz;                                  // where the user stood when this window was put up (walk-around)
        float yawA, pitchA, dragYawOff, dragPitchOff, dragS0 = 1f, dragSize0 = 1f, dist = 1.5f, dragD0 = 1.5f;
        final float phase = (float) (Math.random() * 6.28);
        Win(NvContent c, float w) { this.c = c; this.widthM = w; }
    }
    public final java.util.concurrent.CopyOnWriteArrayList<Win> wins = new java.util.concurrent.CopyOnWriteArrayList<>();
    private Win menuWin = null, dragWin = null;
    private float rayYaw = 0, rayPitch = 0;
    private boolean prevPinch = false;

    public Win addWindow(NvContent c, float widthM) {
        Win w = new Win(c, widthM);
        wins.add(w);
        if (c instanceof NvUi) { menuWin = w; w.showInCinema = true; }
        return w;
    }

    /** show / hide a window (it appears in front of you, shifted by the given angles) */
    public void setVisible(Win w, boolean v, float offYaw, float offPitch) {
        w.offYaw = offYaw; w.offPitch = offPitch; w.defYaw = offYaw; w.defPitch = offPitch;
        if (v && !w.visible && w.hasSaved) { w.offYaw = w.svYaw; w.offPitch = w.svPitch; w.wantScale = w.svScale; w.placeDist = w.svDist; }
        if (v && !w.visible) { w.placeReq = true; w.newOpen = true; }
        w.visible = v;
    }
    public void toggle(Win w, float offYaw, float offPitch) { setVisible(w, !w.visible, offYaw, offPitch); }
    public void hideMenu() { if (menuWin != null) menuWin.visible = false; }

    private void toggleMenuGL() {
        if (menuWin == null) return;
        if (menuWin.visible) { menuWin.visible = false; if (dragWin == menuWin) endDrag(); }
        else {
            menuWin.offYaw = menuWin.hasSaved ? menuWin.svYaw : 0; menuWin.offPitch = menuWin.hasSaved ? menuWin.svPitch : 0;
            if (menuWin.hasSaved) { menuWin.wantScale = menuWin.svScale; menuWin.placeDist = menuWin.svDist; }
            menuWin.placeReq = true; menuWin.newOpen = true; menuWin.visible = true; if (ui != null) ui.setOpen(true); }
    }

    private boolean anyVisible() { for (Win w : wins) if (shown(w)) return true; return false; }
    public void setWinScale(Win w, float s) { w.scale = Math.max(0.5f, Math.min(2.5f, s)); }

    private void computeProj() {
        float aspect = (float) sceneW / Math.max(1, sceneH);
        float hf = Math.max(0.8f, Math.min(1.9f, cam.hFov));
        tanH = (float) Math.tan(hf / 2f);
        tanV = tanH / aspect;
        tanHc = tanH; tanVc = tanV;
        float tanBase = tanH;
        winZoom = 1f;
        if (stereo && vw > 1 && vh > 1) {
            // in the VR box every eye looks through a lens with its own field of view. If the picture is drawn with a different one,
            // the world turns faster or slower than your head (that is what makes you dizzy). So the scene is drawn with the lens' field of view,
            // and the camera picture is shown at its true angular size inside it.
            float ea = (float) (vw / 2) / vh;
            float scx = ea < aspect ? ea / aspect : 1f;
            float fv = Math.max(40f, Math.min(120f, vrFov));
            tanH = (float) Math.tan(Math.toRadians(fv) / 2.0) / scx;
            tanV = tanH / aspect;
            winZoom = Math.max(1f, Math.min(2.2f, tanH / tanBase)) * 1.2f;   // a wider view makes windows look smaller: make them bigger by the same amount
        }
        // the camera picture always fills the whole eye (a small picture with black borders is worse than a slight zoom),
        // so hands, cursor and pointer use the same mapping as the picture
        tanHc = tanH; tanVc = tanV;
        camScale = 1f;
        float fovy = (float) Math.toDegrees(2.0 * Math.atan(tanV));
        android.opengl.Matrix.perspectiveM(mP, 0, fovy, aspect, 0.05f, 20f);
    }

    // turn a window about the phone's own view axes (yaw about the vertical view axis, pitch about the horizontal one)
    private void turnWin(Win w, float[] rn, float th, float ph) {
        android.opengl.Matrix.setRotateM(tA, 0, th, 0f, 1f, 0f);
        android.opengl.Matrix.setRotateM(tB, 0, ph, 1f, 0f, 0f);
        android.opengl.Matrix.multiplyMM(tC, 0, tA, 0, tB, 0);
        android.opengl.Matrix.multiplyMM(tD, 0, tC, 0, rn, 0);
        android.opengl.Matrix.transposeM(tA, 0, rn, 0);                    // rn is a pure turn: its transpose is its inverse
        android.opengl.Matrix.multiplyMM(tB, 0, tA, 0, tD, 0);
        android.opengl.Matrix.multiplyMM(tC, 0, tB, 0, w.rot, 0);
        w.rot = tC.clone();
    }

    private float sux = 0, suy = 0, suz = 0;          // smoothed user position, so the estimate arriving in small steps looks smooth
    // When you have walked away from the spot where a window was put up, turning/pushing it "about that old spot" goes the wrong way.
    // So at the moment of grabbing, the window is re-based on where you stand now: same place in the room, now measured from you,
    // and turned to face you (the smallest turn that does it).
    private void rebase(Win w, float ux, float uy, float uz) {
        if (w.rot == null) return;
        float[] r = w.rot;
        float ax = -r[8], ay = -r[9], az = -r[10];
        float cx = w.wx + ax * w.dist, cy = w.wy + ay * w.dist, cz = w.wz + az * w.dist;
        float vx = cx - ux, vy = cy - uy, vz = cz - uz;
        float L = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (!(L > 0.2f) || Float.isNaN(L)) return;
        float nx = vx / L, ny = vy / L, nz = vz / L;
        float kx = ay * nz - az * ny, ky = az * nx - ax * nz, kz = ax * ny - ay * nx;
        float s = (float) Math.sqrt(kx * kx + ky * ky + kz * kz), c = ax * nx + ay * ny + az * nz;
        if (c > -0.99f && s > 1e-4f) {
            android.opengl.Matrix.setRotateM(tE, 0, (float) Math.toDegrees(Math.atan2(s, c)), kx / s, ky / s, kz / s);
            android.opengl.Matrix.multiplyMM(tF, 0, tE, 0, w.rot, 0);
            w.rot = tF.clone();
        }
        w.wx = ux; w.wy = uy; w.wz = uz;
        w.dist = L; w.dragD0 = L;
    }

    public volatile boolean neck = true;                   // the eyes sit in front of the neck: turning the head also moves the eyes a little (gives depth to the windows)
    private float mux = 0, muy = 0, muz = 0;
    private void smoothUser() {
        NvMotion mo = motion;
        if (mo != null && mo.enabled) { mux += (mo.px - mux) * 0.18f; muy += (mo.py - muy) * 0.18f; muz += (mo.pz - muz) * 0.18f; }
        else { mux = muy = muz = 0f; }
        float nx = 0f, ny = 0f, nz = 0f;
        float[] rn = rotView;
        if (neck && rn != null) {
            // the eyes are 9 cm above and 10 cm in front of the point the head turns around (the neck): where they are in the room = that offset turned by the head's turn
            final float ey = 0.09f, ez = -0.10f;
            nx = rn[1] * ey + rn[2] * ez; ny = rn[5] * ey + rn[6] * ez; nz = rn[9] * ey + rn[10] * ez;
        }
        sux = mux + nx; suy = muy + ny; suz = muz + nz;
    }

    /**
     * A window that comes up on its standard place (the user has not moved it yet) sits right next to the windows that are already up:
     * flush against the side of one of them (nearest to the standard place, the right side first), so the windows form a row and never lie on top of each other.
     * It then slides out from behind that neighbour into its place (see updateWin). Angles: positive yaw = to the right, positive pitch = up.
     */
    private boolean hitsAt(Win w, float cy, float cp, float hw, float hh) {
        for (Win o : wins) {
            if (o == w || !shown(o) || o.rot == null || o.placeReq || o.showInCinema && cinema) continue;
            android.graphics.Bitmap ob = o.c.bitmap();
            if (ob == null || ob.getWidth() <= 0) continue;
            float opw = o.widthM * o.scale * winZoom, oph = opw * ob.getHeight() / ob.getWidth();
            float ohw = (float) Math.toDegrees(Math.atan(opw / 2f / Math.max(0.3f, o.dist))), ohh = (float) Math.toDegrees(Math.atan(oph / 2f / Math.max(0.3f, o.dist)));
            if (ohw > 45f) continue;                          // something huge (the cinema screen): do not dodge it
            if (Math.abs(cy - o.yawA) < hw + ohw + 1.0f && Math.abs(cp - o.pitchA) < hh + ohh + 1.5f) return true;
        }
        return false;
    }

    private static final float OPEN_MAXW = 0.85f;               // a window opens at most this wide (metres at the standard distance): not too big for the view
    private static final float MAX_FRONT_YAW = 75f;              // a new window never goes further round than this: never to the side of you or behind you
    private float openScale(Win w) { return Math.min(1f, OPEN_MAXW / Math.max(0.1f, w.widthM)); }

    /**
     * A new window sits right next to the windows that are already up (not next to your body): flush against the right or left side of one of them,
     * or just above / below it, never on top of another one. Of all the free places beside the open windows it takes the one nearest to where you look,
     * so the group grows outwards from the middle. It never goes round to the side or behind you, and it slides out from behind its neighbour (see updateWin).
     * Angles: positive yaw = to the right, positive pitch = up.
     */
    private void avoidOverlap(Win w) {
        w.slideDeg = 0f; w.slideDegP = 0f;
        if (w.hasSaved) return;
        android.graphics.Bitmap bm = w.c.bitmap();
        if (bm == null || bm.getWidth() <= 0) return;
        float sc = w.wantScale > 0 ? w.wantScale : openScale(w), d = w.placeDist > 0f ? w.placeDist : PANEL_D;
        float pw = w.widthM * sc * winZoom, ph = pw * bm.getHeight() / bm.getWidth();
        float hw = (float) Math.toDegrees(Math.atan(pw / 2f / d)), hh = (float) Math.toDegrees(Math.atan(ph / 2f / d));
        float baseY = w.offYaw, baseP = w.offPitch;
        final float gap = 2.0f;                                   // degrees between two windows
        int cap = 4 * wins.size() + 4;
        float[] cy = new float[cap], cp = new float[cap], fy = new float[cap], fp = new float[cap];
        int nc = 0;
        for (Win o : wins) {
            if (o == w || !shown(o) || o.rot == null || o.placeReq || o.showInCinema && cinema) continue;
            android.graphics.Bitmap ob = o.c.bitmap();
            if (ob == null || ob.getWidth() <= 0) continue;
            float opw = o.widthM * o.scale * winZoom, oph = opw * ob.getHeight() / ob.getWidth();
            float od = Math.max(0.3f, o.dist);
            float ohw = (float) Math.toDegrees(Math.atan(opw / 2f / od)), ohh = (float) Math.toDegrees(Math.atan(oph / 2f / od));
            if (ohw > 45f || Math.abs(o.yawA) > 100f) continue;       // the cinema screen, or a window that is far round to the side: not one to sit next to
            cy[nc] = o.yawA + ohw + hw + gap; cp[nc] = o.pitchA; fy[nc] = o.yawA; fp[nc] = o.pitchA; nc++;               // right of it
            cy[nc] = o.yawA - ohw - hw - gap; cp[nc] = o.pitchA; fy[nc] = o.yawA; fp[nc] = o.pitchA; nc++;               // left of it
            cy[nc] = o.yawA; cp[nc] = o.pitchA + ohh + hh + gap; fy[nc] = o.yawA; fp[nc] = o.pitchA; nc++;               // above it
            cy[nc] = o.yawA; cp[nc] = o.pitchA - ohh - hh - gap; fy[nc] = o.yawA; fp[nc] = o.pitchA; nc++;               // below it
        }
        if (nc == 0) return;                                      // nothing in front to sit next to: the standard place
        int best = -1; float bestC = 1e9f;
        for (int i = 0; i < nc; i++) {
            if (Math.abs(cy[i]) > MAX_FRONT_YAW || Math.abs(cp[i]) > 50f) continue;
            if (hitsAt(w, cy[i], cp[i], hw, hh)) continue;
            boolean vert = cy[i] == fy[i];                        // above / below a window: only when beside is not possible (or the window wants to be low, like the keyboard)
            float c = 0.75f * Math.abs(cy[i]) + 0.25f * Math.abs(cy[i] - baseY) + Math.abs(cp[i] - baseP) + (vert ? 30f : 0f) + (cy[i] < fy[i] ? 0.01f : 0f);     // the right side wins a tie
            if (c < bestC) { bestC = c; best = i; }
        }
        if (best < 0) {                                           // no free place beside anything: stack above / below the standard place
            for (int k = 1; k <= 4; k++) for (int sg = -1; sg <= 1; sg += 2) {
                float p = baseP + sg * k * (hh * 2f + gap);
                if (Math.abs(p) <= 50f && !hitsAt(w, baseY, p, hw, hh)) { w.offPitch = p; return; }
            }
            return;
        }
        w.offYaw = cy[best]; w.offPitch = cp[best];
        w.slideDeg = fy[best] - cy[best];                         // it starts at the middle of its neighbour and slides out to its place
        w.slideDegP = fp[best] - cp[best];
    }

    private static final float ANIM_MS = 520f;                     // how long a window takes to slide in

    private void updateWin(Win w, float[] rn, float t) {
        float ux = sux, uy = suy, uz = suz;
        if (w.placeReq || w.rot == null) {
            avoidOverlap(w);
            if (w.newOpen) { w.newOpen = false; w.animOn = true; w.animT0 = System.nanoTime(); }     // a window that is opened (not just re-centred) slides in
            else w.animOn = false;
            android.opengl.Matrix.transposeM(tA, 0, rn, 0);
            w.rot = tA.clone();
            if (w.offYaw != 0f || w.offPitch != 0f) turnWin(w, rn, -w.offYaw, w.offPitch);
            w.scale = w.wantScale > 0 ? w.wantScale : openScale(w);
            w.dist = w.placeDist > 0f ? w.placeDist : PANEL_D;
            w.wx = ux; w.wy = uy; w.wz = uz;
            w.placeReq = false;
        }
        // where the window centre is now, seen from the phone: angle to the right and angle upwards
        android.opengl.Matrix.translateM(mM, 0, w.rot, 0, 0f, 0f, -w.dist);
        shiftBy(w, mM, ux, uy, uz);
        android.opengl.Matrix.multiplyMM(w.mv, 0, rn, 0, mM, 0);
        w.yawA = (float) Math.toDegrees(Math.atan2(w.mv[12], -w.mv[14]));
        w.pitchA = (float) Math.toDegrees(Math.atan2(w.mv[13], -w.mv[14]));
        if (dragWin == w && !resizing) {
            float th = (w.yawA - (rayYaw + w.dragYawOff)) * 0.65f;
            float ph = ((rayPitch + w.dragPitchOff) - w.pitchA) * 0.65f;
            th = Math.max(-7f, Math.min(7f, th));              // at most a few degrees per frame, so nothing can snap away
            ph = Math.max(-7f, Math.min(7f, ph));
            if (th != 0f || ph != 0f) turnWin(w, rn, th, ph);
        }
        // keep it upright: undo any tilt that piled up while it was being moved (turn about its own centre, so it does not slide)
        android.opengl.Matrix.multiplyMM(tA, 0, rn, 0, w.rot, 0);
        float roll = (float) Math.toDegrees(Math.atan2(tA[1], tA[0]));
        if (Math.abs(roll) > 0.2f) {
            android.opengl.Matrix.setRotateM(tB, 0, -roll * 0.25f, 0f, 0f, 1f);
            android.opengl.Matrix.multiplyMM(tC, 0, w.rot, 0, tB, 0);
            w.rot = tC.clone();
        }
        float bx = 0f, by = 0f, bz = 0f;
        if (floaty && dragWin != w) {
            bx = 0.006f * (float) Math.sin(t * 0.6f + 1f + w.phase);
            by = 0.008f * (float) Math.sin(t * 0.9f + w.phase);
            bz = 0.010f * (float) Math.sin(t * 0.4f + 2f + w.phase);
        }
        // coming in: it starts behind its neighbour (a bit further away, a bit small, see-through, turned towards the middle) and slides out to its place
        float sx = 0f, sy = 0f, sz = 0f, tilt = 0f;
        w.animA = 1f; w.animS = 1f;
        if (w.animOn) {
            float x = (System.nanoTime() - w.animT0) / 1e6f / ANIM_MS;
            if (x >= 1f) w.animOn = false;
            else {
                float rest = 1f - x, e = 1f - rest * rest * rest, left = 1f - e;                 // ease out: quick at first, settles softly
                sx = w.dist * (float) Math.tan(Math.toRadians(w.slideDeg)) * left;               // sideways, along the window's own right
                sy = w.dist * (float) Math.tan(Math.toRadians(w.slideDegP)) * left;              // and upwards / downwards
                sz = -0.16f * left;                                                              // further away at first, comes forward
                tilt = Math.signum(w.slideDeg) * 14f * left;
                w.animA = Math.min(1f, x * 2.6f);
                w.animS = 0.9f + 0.1f * e;
            }
        }
        android.opengl.Matrix.translateM(mM, 0, w.rot, 0, bx + sx, by + sy, -w.dist + bz + sz);
        if (tilt != 0f) android.opengl.Matrix.rotateM(mM, 0, tilt, 0f, 1f, 0f);
        shiftBy(w, mM, ux, uy, uz);
        android.opengl.Matrix.multiplyMM(w.mv, 0, rn, 0, mM, 0);
        android.opengl.Matrix.multiplyMM(w.mvp, 0, mP, 0, w.mv, 0);
        android.opengl.Matrix.invertM(w.inv, 0, w.mv, 0);
    }

    private void computePanel() {
        float[] rn = rotView;
        if (recenter) {
            recenter = false;
            if (resetLayoutReq) {                                    // the OK sign: forget where the windows were left, back to the standard places and sizes
                resetLayoutReq = false;
                for (Win w : wins) { w.hasSaved = false; w.wantScale = 0f; w.placeDist = 0f; w.offYaw = w.defYaw; w.offPitch = w.defPitch; }
                if (menuWin != null) { menuWin.offYaw = 0f; menuWin.offPitch = 0f; }
                if (ui != null) ui.toast("จัดหน้าต่างและขนาดใหม่แล้ว");
            }
            // every window that is up goes back in front of you (keeping the place the user gave it, relative to where you look now)
            for (Win w : wins) if (w.visible) {
                w.placeReq = true;
                if (w.hasSaved) { w.offYaw = w.svYaw; w.offPitch = w.svPitch; w.wantScale = w.svScale; w.placeDist = w.svDist; } else w.placeDist = 0f;
            }
        }
        computeProj();
        float t = (System.nanoTime() / 1e9f) % 600f;
        smoothUser();
        for (Win w : wins) if (shown(w)) updateWin(w, rn, t);
    }

    // where the pointing finger meets the window, in that window's picture pixels
    private boolean hitWin(Win w, float nx, float ny, float[] out) {
        android.graphics.Bitmap bm = w.c.bitmap();
        if (bm == null) return false;
        float dx = nx * tanHc, dy = ny * tanVc, dz = -1f;
        float[] mInv = w.inv;
        float ox = mInv[12], oy = mInv[13], oz = mInv[14];
        float Dz = mInv[2] * dx + mInv[6] * dy + mInv[10] * dz;
        float Dx = mInv[0] * dx + mInv[4] * dy + mInv[8] * dz;
        float Dy = mInv[1] * dx + mInv[5] * dy + mInv[9] * dz;
        if (Math.abs(Dz) < 1e-5f) return false;
        float t = -oz / Dz;
        if (t <= 0) return false;
        float lx = ox + t * Dx, ly = oy + t * Dy;
        float pw = w.widthM * w.scale * winZoom;
        float ph = pw * bm.getHeight() / bm.getWidth();
        float u = lx / pw + 0.5f, v = 0.5f - ly / ph;
        if (u < -0.05f || u > 1.05f || v < -0.05f || v > 1.05f) return false;
        out[0] = u * bm.getWidth();
        out[1] = v * bm.getHeight();
        return true;
    }

    private void endDrag() {
        if (dragWin != null) {
            Win dw = dragWin;
            if (dw.rot != null && !Float.isNaN(dw.yawA) && !Float.isNaN(dw.pitchA)) {
                dw.svYaw = dw.yawA; dw.svPitch = dw.pitchA; dw.svScale = dw.scale; dw.svDist = dw.dist;      // (no longer remembered for next time: a window that is opened always slides in next to the others)
            }
            dw.c.setDragging(false);
        }
        dragWin = null;
        resizing = false;
    }

    private float[] lastRes, vel = new float[1];
    private long handT = 0;
    private float[] disp = new float[]{0};
    private float curX = 0, curY = 0;
    private boolean curOk = false;

    private volatile int imgW = 0, imgH = 0;
    private volatile boolean rot180 = false;
    private int sceneW = 0, sceneH = 0;
    private int[] fbo = new int[1], sceneTex = new int[1], camTex = new int[1];
    private SurfaceTexture st;
    private final Object lock = new Object();
    private boolean newFrame = false;
    private final float[] stm = new float[16];
    private int vw = 1, vh = 1;
    private int progCam, progCol, progFin, progRing;
    private int[] maskFbo = new int[1], maskTex = new int[1];
    private int maskW = 0, maskH = 0;
    private FloatBuffer triBuf;
    private FloatBuffer quad, lineBuf, ptBuf;
    private long fc = 0, ft0 = 0;

    public VRRenderer(Host h, NvCam c) { host = h; cam = c; }

    public void setImage(int w, int h, boolean r180) { rot180 = r180; imgH = h; imgW = w; }

    @Override public void onFrameAvailable(SurfaceTexture s) { synchronized (lock) { newFrame = true; } }

    private static int sh(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }
    private static int prog(String vs, String fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        return p;
    }
    private static FloatBuffer fb(int n) {
        return ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    // uniform places are looked up once per program (asking the driver by name every frame is wasted work)
    private final java.util.HashMap<Integer, java.util.HashMap<String, Integer>> ulc = new java.util.HashMap<>();
    private int ul(int prog, String n) {
        java.util.HashMap<String, Integer> m = ulc.get(prog);
        if (m == null) { m = new java.util.HashMap<>(); ulc.put(prog, m); }
        Integer v = m.get(n);
        if (v == null) { v = GLES20.glGetUniformLocation(prog, n); m.put(n, v); }
        return v;
    }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
        ulc.clear();
        java.util.Arrays.fill(alc, 0);
        GLES20.glDisable(GLES20.GL_DITHER);                // no dithering: a little less work for every pixel
        panoTex = 0; panoOn = false;
        for (Win w : wins) if (w.c instanceof NvVideo) ((NvVideo) w.c).glLost();
        progCam = prog(
            "attribute vec2 p; uniform float zs; varying vec2 pp; void main(){ gl_Position=vec4(p*zs,0.,1.); pp=p; }",
            "#extension GL_OES_EGL_image_external : require\n#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n" +
            "uniform samplerExternalOES tex; uniform vec2 px; uniform float amt; uniform float rf; uniform float wp; uniform mat3 dm; uniform vec2 tn; varying vec2 pp;" +
            "void main(){ vec2 q=pp; if(wp>0.5){ vec3 d=dm*vec3(pp.x*tn.x,pp.y*tn.y,-1.); if(d.z>-0.02){ gl_FragColor=vec4(0.,0.,0.,1.); return; } q=vec2(d.x/(-d.z)/tn.x, d.y/(-d.z)/tn.y); }" +
            " if(abs(q.x)>1.||abs(q.y)>1.){ gl_FragColor=vec4(0.,0.,0.,1.); return; }" +
            " vec2 t=q*0.5+0.5; t=mix(t,1.-t,rf); vec2 uv=vec2(t.x,1.-t.y); vec4 c=texture2D(tex,uv);" +
            " if(amt>0.){ vec4 b=(texture2D(tex,uv+vec2(px.x,0.))+texture2D(tex,uv-vec2(px.x,0.))+texture2D(tex,uv+vec2(0.,px.y))+texture2D(tex,uv-vec2(0.,px.y)))*0.25; c=clamp(c+(c-b)*amt,0.,1.); } gl_FragColor=c; }");
        progCol = prog(
            "attribute vec2 p; uniform float ps; void main(){ gl_Position=vec4(p,0.,1.); gl_PointSize=ps; }",
            "precision mediump float; uniform vec4 col; uniform float rnd; void main(){ if (rnd>0.5 && length(gl_PointCoord-0.5)>0.5) discard; gl_FragColor=col; }");
        progFin = prog(
            "attribute vec2 p; varying vec2 c; void main(){ gl_Position=vec4(p,0.,1.); c=p; }",
            "precision mediump float; uniform sampler2D tex; uniform vec2 sc; uniform float k; uniform float sh; uniform float ca; varying vec2 c;" +
            "void main(){ vec2 q=c; q.x+=sh; float r2=dot(q,q); q*=1.+k*r2; vec2 uv=0.5+0.5*q*sc;" +
            " if(uv.x<0.||uv.x>1.||uv.y<0.||uv.y>1.){ gl_FragColor=vec4(0.,0.,0.,1.); return; }" +
            " if(abs(ca)<0.0005){ gl_FragColor=vec4(texture2D(tex,uv).rgb,1.); return; }" +
            " vec2 ur=0.5+0.5*q*(1.+ca*r2)*sc, ub=0.5+0.5*q*(1.-ca*r2)*sc;" +
            " gl_FragColor=vec4(texture2D(tex,clamp(ur,0.,1.)).r, texture2D(tex,uv).g, texture2D(tex,clamp(ub,0.,1.)).b, 1.); }");
        progUi = prog(
            "attribute vec2 p; varying vec2 uv; void main(){ gl_Position=vec4(p,0.,1.); uv=vec2(p.x*0.5+0.5, 0.5-p.y*0.5); }",
            "precision mediump float; uniform sampler2D tex; varying vec2 uv; void main(){ gl_FragColor=texture2D(tex,uv); }");
        uiTex = 0;
        for (Win w : wins) { w.tex = 0; w.texW = 0; w.texH = 0; }      // a new graphics context: every window picture must be uploaded again in full
        progRing = prog(
            "attribute vec2 p; varying vec2 uv; void main(){ gl_Position=vec4(p,0.,1.); uv=p*0.5+0.5; }",
            "precision mediump float; uniform sampler2D tex; uniform vec2 px; uniform float rad; varying vec2 uv;" +
            "float s(float x, float y){ return texture2D(tex,uv+vec2(x,y)*px*rad).r; }" +
            "void main(){" +
            " float m = s(0.,0.)*0.25 + (s(1.,0.)+s(-1.,0.)+s(0.,1.)+s(0.,-1.))*0.125 + (s(1.,1.)+s(-1.,1.)+s(1.,-1.)+s(-1.,-1.))*0.0625;" +
            " float w = (s(3.,0.)+s(-3.,0.)+s(0.,3.)+s(0.,-3.))*0.25;" +
            " float line = 1.0 - smoothstep(0.10, 0.30, abs(m-0.5));" +
            " float glow = 0.45*smoothstep(0.0,0.6,w)*(1.0-smoothstep(0.30,0.50,m));" +
            " float e = max(line, glow);" +
            " float dk = 0.40*(1.0 - smoothstep(0.0, 0.16, abs(m-0.28)));" +
            " float a = max(e, dk);" +
            " gl_FragColor = vec4(vec3(0.92,0.97,1.0)*e, a); }");
        progAA = prog(
            "attribute vec2 p; attribute vec4 q; varying vec4 vq; void main(){ gl_Position=vec4(p,0.,1.); vq=q; }",
            "precision mediump float; uniform vec3 col; uniform float sf; uniform float fade; varying vec4 vq;" +
            "void main(){ float t=clamp(vq.x,0.,vq.z); float d=length(vec2(vq.x-t,vq.y)); float al=clamp((1.-d)*vq.w/sf,0.,1.); gl_FragColor=vec4(col*al,al)*fade; }");
        aaBuf = fb(6 * 6 * 60);
        triBuf = fb(4096);
        maskW = 0; maskFbo[0] = 0; maskTex[0] = 0;
        progPanel = prog(
            "attribute vec2 p; uniform mat4 mvp; uniform vec2 hs; varying vec2 uv; void main(){ gl_Position=mvp*vec4(p*hs,0.,1.); uv=vec2(p.x*0.5+0.5, 0.5-p.y*0.5); }",
            "precision mediump float; uniform sampler2D tex; uniform float al; varying vec2 uv; void main(){ gl_FragColor=texture2D(tex,uv)*al; }");
        progVid = prog(
            "attribute vec2 p; uniform mat4 mvp; uniform vec2 hs; uniform vec2 off; uniform vec2 fit; uniform mat4 stm; varying vec2 uv; varying vec2 c;" +
            "void main(){ gl_Position=mvp*vec4(p*hs+off,0.,1.); c=p*0.5*fit+0.5; uv=(stm*vec4(c,0.,1.)).xy; }",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES tex; varying vec2 uv; varying vec2 c;" +
            "void main(){ if(c.x<0.||c.x>1.||c.y<0.||c.y>1.) gl_FragColor=vec4(0.,0.,0.,1.); else gl_FragColor=texture2D(tex,uv); }");
        progPano = prog(
            "attribute vec2 p; uniform vec2 tn; varying vec2 ray; void main(){ gl_Position=vec4(p,0.,1.); ray=p*tn; }",
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n" +
            "uniform sampler2D tex; uniform mat3 rm; uniform float lon0; varying vec2 ray;" +
            "void main(){ vec3 d=normalize(vec3(ray,-1.)); vec3 w=d*rm; float lon=atan(w.x,w.y)+lon0; float lat=asin(clamp(w.z,-1.,1.));" +
            " gl_FragColor=texture2D(tex,vec2(fract(lon/6.2831853+0.5),0.5-lat/3.1415927)); }");
        quad = fb(8);
        quad.put(new float[]{-1,-1, 1,-1, -1,1, 1,1}).position(0);
        lineBuf = fb(BONES.length * 2 * 2 * 2);
        ptBuf = fb(21 * 2 * 2);

        GLES20.glGenTextures(1, camTex, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        sceneW = 0; sceneH = 0; fbo[0] = 0; sceneTex[0] = 0;
        android.opengl.Matrix.setIdentityM(stm, 0);
        st = new SurfaceTexture(camTex[0]);
        st.setOnFrameAvailableListener(this);
        host.onCamSurface(st);
    }

    @Override public void onSurfaceChanged(GL10 gl, int w, int h) { vw = w; vh = h; }

    private void makeScene(int iw, int ih) {
        int w = Math.max(320, Math.round(Math.min(iw, 1280) * Math.max(0.6f, Math.min(1f, sceneScale))) & ~1);
        int h = Math.max(2, Math.round(w * (float) ih / iw));
        if (sceneTex[0] != 0) { GLES20.glDeleteTextures(1, sceneTex, 0); GLES20.glDeleteFramebuffers(1, fbo, 0); }
        GLES20.glGenTextures(1, sceneTex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneTex[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glGenFramebuffers(1, fbo, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, sceneTex[0], 0);
        sceneW = w; sceneH = h;
        maskW = 0;
        if (ui != null) { ui.resize(1024, 576); ui.markDirty(); }
        if (uiTex != 0) { GLES20.glDeleteTextures(1, new int[]{uiTex}, 0); uiTex = 0; }
    }

    // the hand model answers 20-30 times a second but the screen is drawn 60 times a second: between answers the points are moved on
    // by their measured speed. Times are the moments the pictures were TAKEN (from the camera thread), not the moments this loop noticed
    // them, so the speed is not shaken by the 16 ms steps of the draw loop and the lead covers the real delay of the picture.
    private float[] rfl = new float[NvFlow.NTRK * 2];          // the followers' moves at the time of the model's picture
    private final float[] hsx = new float[42], hsy = new float[42], kfR = new float[21], shO = new float[2];     // per hand, per point: how far it has moved since that picture
    private final boolean[] foh = new boolean[2];              // per hand: is it followed at the camera rate right now
    private float[] rTip = null;
    private final float[] tsx = new float[6], tsy = new float[6];
    private final boolean[] tuse = new boolean[6];
    private float[] dispDraw = new float[]{0}, corr = new float[1];
    private float[] drawRes = null;
    private long lastPredT = 0;
    private void predictNow(long now) {
        float[] r = cam.result;
        if (r != lastRes) {
            float[] prev = lastRes;
            long tPrev = handT;
            long tc = cam.resultCapT;
            if (tc <= 0 || tc > now) tc = now;
            lastRes = r; handT = tc;
            rfl = cam.resFl; rTip = cam.resTip;
            if (vel.length != r.length) vel = new float[r.length];
            if (prev != null && prev.length == r.length && r[0] >= 1 && tc - tPrev >= 8 && tc - tPrev < 350) {
                float dt = (tc - tPrev) / 1000f;
                for (int i = 1; i < r.length; i++) {
                    if ((i - 1) % 3 == 2) continue;
                    float v = (r[i] - prev[i]) / dt;
                    v = Math.max(-3f, Math.min(3f, v));
                    vel[i] = vel[i] * 0.4f + v * 0.6f;
                }
            } else if (prev == null || prev.length != r.length || r[0] < 1 || tc - tPrev >= 350) {
                java.util.Arrays.fill(vel, 0f);
            }
        }
        if (disp.length != r.length) disp = new float[r.length];
        // how far the whole hand has moved since the picture the model answered about (seen by the camera-rate follower): the points are moved on by that,
        // so the hand is drawn where it is now, not where it was ~100 ms ago
        // every hand has its own followers (palm and fingers): two hands moving in different ways do not pull on each other, and a hand that
        // turns or rolls has its fingers moved more than its palm (NvFlow.kFactors / shiftOf give the move of every single point)
        int nh = r[0] >= 1 ? Math.min(2, Math.min((int) r[0], (r.length - 1) / 63)) : 0;
        foh[0] = foh[1] = false;
        boolean fo = false;
        float[] fnow = cam.flowNow; int fmask = cam.flowMask;
        if (cam.flowOn && nh > 0 && now - cam.flowT < 150 && fnow != null && rfl != null && fnow.length >= 8 && rfl.length >= 8) {
            for (int hnd = 0; hnd < nh; hnd++) {
                int t0 = hnd * 2;
                boolean pok = (fmask & (1 << t0)) != 0, fok = (fmask & (2 << t0)) != 0;
                if (!pok && !fok) continue;
                float pdx = 0f, pdy = 0f, fdx = 0f, fdy = 0f;
                if (pok) { pdx = fnow[t0 * 2] - rfl[t0 * 2]; pdy = fnow[t0 * 2 + 1] - rfl[t0 * 2 + 1]; }
                if (fok) { fdx = fnow[(t0 + 1) * 2] - rfl[(t0 + 1) * 2]; fdy = fnow[(t0 + 1) * 2 + 1] - rfl[(t0 + 1) * 2 + 1]; }
                NvFlow.kFactors(r, 1 + hnd * 63, kfR);
                for (int lm = 0; lm < 21; lm++) {
                    boolean has = NvFlow.shiftOf(kfR[lm], pok, pdx, pdy, fok, fdx, fdy, shO);
                    hsx[hnd * 21 + lm] = has ? shO[0] : 0f; hsy[hnd * 21 + lm] = has ? shO[1] : 0f;
                }
                foh[hnd] = true; fo = true;
            }
        }
        boolean newAns = r != drawRes;                       // a new answer of the model arrived since the last frame
        float[] prevDraw = null;
        // each thumb / index / middle fingertip that is followed on its own is moved by its own amount (so a closing pinch shows up at the camera rate)
        java.util.Arrays.fill(tuse, false);
        if (fo && cam.tipsOn) {
            float[] tn = cam.tipNow; float[] rt = rTip;
            if (tn != null && rt != null && tn.length >= 18 && rt.length >= 18) {
                for (int hnd = 0; hnd < nh; hnd++) {
                    if (!foh[hnd]) continue;
                    for (int j = 0; j < 3; j++) {
                        int sl = hnd * 3 + j, lm = NvCam.TIPS[j];
                        if (tn[sl * 3 + 2] > 0.5f && rt[sl * 3 + 2] > 0.5f) {
                            float ex = tn[sl * 3] - rt[sl * 3], ey = tn[sl * 3 + 1] - rt[sl * 3 + 1];
                            if (Math.abs(ex - hsx[hnd * 21 + lm]) < 0.08f && Math.abs(ey - hsy[hnd * 21 + lm]) < 0.08f) { tsx[sl] = ex; tsy[sl] = ey; tuse[sl] = true; }
                        }
                    }
                }
            }
        }
        if (dispDraw.length != r.length) { dispDraw = new float[r.length]; corr = new float[r.length]; newAns = false; }
        else if (newAns && dispDraw.length > 0 && dispDraw[0] >= 1 && r[0] >= 1) prevDraw = dispDraw.clone();
        drawRes = r;
        long fdt = lastPredT == 0 ? 16 : Math.max(1, Math.min(60, now - lastPredT));
        lastPredT = now;
        float decay = (float) Math.exp(-fdt / 90f);
        for (int i = 0; i < r.length; i++) {
            float v = r[i], vt = r[i];
            if (i > 0) {
                int m = (i - 1) % 3;
                int hnd = (i - 1) / 63, lm = ((i - 1) % 63) / 3;
                float ax = 0f, ay = 0f;                         // the outline / skeleton moves with the palm + fingers followers only: the single fingertips jitter a little and made it bounce
                if (m != 2 && hnd < 2 && foh[hnd]) { ax = hsx[hnd * 21 + lm]; ay = hsy[hnd * 21 + lm]; }
                float bx = ax, by = ay;                         // the pointer and the pinch look at the fingertips' own movement
                if (m != 2 && hnd < 2) {
                    int j = lm == 4 ? 0 : (lm == 8 ? 1 : (lm == 12 ? 2 : -1));
                    if (j >= 0 && tuse[hnd * 3 + j]) { bx = tsx[hnd * 3 + j]; by = tsy[hnd * 3 + j]; }
                }
                if (m == 0) { v += ax; vt += bx; } else if (m == 1) { v += ay; vt += by; }
            }
            disp[i] = vt;
            if (i == 0 || (i - 1) % 3 == 2) { dispDraw[i] = v; continue; }
            // a new model answer corrects the points a little (the follower drifts a bit): instead of a jump, the difference melts away over ~90 ms
            if (prevDraw != null && prevDraw.length == r.length) {
                float c = prevDraw[i] - v;
                corr[i] = Math.abs(c) < 0.06f ? c : 0f;
            }
            corr[i] *= decay;
            dispDraw[i] = v + corr[i];
        }
        // the hand outline / skeleton is drawn where the hand is in the camera picture that is on screen right now (that picture is already a bit old),
        // so it sticks to the real hand; the pointer below looks ahead instead, to feel quick
        if (r[0] >= 1) {                                    // a hand that is not followed by the camera is drawn from its speed instead
            float dl = Math.max(0, Math.min(now - handT - 16, 140)) / 1000f;
            for (int i = 1; i < r.length; i++) {
                if ((i - 1) % 3 == 2) continue;
                int hnd = (i - 1) / 63;
                if (hnd >= 2 || !foh[hnd]) dispDraw[i] += vel[i] * dl * 0.9f;
            }
        }
        if (predict && r[0] >= 1) {
            // from the moment of the newest picture that was followed (or of the model's picture) to the moment this frame is shown
            float leadF = Math.max(0, Math.min(now - cam.flowT + 18, 90)) / 1000f, leadM = Math.max(0, Math.min(now - handT + 18, 140)) / 1000f;
            predMs = (fo ? leadF : leadM) * 1000f;
            for (int i = 1; i < r.length; i++) {
                if ((i - 1) % 3 == 2) continue;
                int hnd = (i - 1) / 63;
                disp[i] += vel[i] * ((hnd < 2 && foh[hnd]) ? leadF : leadM) * 0.9f;
            }
        } else predMs = 0;
    }

    /** the video picture, straight from the player's texture, in the top part of the window (the control bar is drawn over the rest) */
    private void drawVideo(Win w, NvVideo v, android.graphics.Bitmap bm) {
        if (v.st == null) {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            v.glReady(new SurfaceTexture(t[0]), t[0]);
        }
        if (v.st == null) return;
        if (v.frameAvail) {
            v.frameAvail = false;
            try { v.st.updateTexImage(); v.st.getTransformMatrix(v.stm); } catch (Throwable ignored) { }
        }
        if (v.vw <= 0 || v.vh <= 0) return;
        float wa = (float) NvVideo.W / NvVideo.VH, va = (float) v.vw / v.vh;
        float fx = va > wa ? 1f : wa / va, fy = va > wa ? va / wa : 1f;
        float pw = w.widthM * w.scale * winZoom * w.animS;
        float hh = pw * bm.getHeight() / bm.getWidth() / 2f;
        float fr = (float) NvVideo.VH / bm.getHeight();
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(progVid);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, v.oes);
        GLES20.glUniform1i(ul(progVid, "tex"), 0);
        GLES20.glUniformMatrix4fv(ul(progVid, "mvp"), 1, false, w.mvp, 0);
        GLES20.glUniformMatrix4fv(ul(progVid, "stm"), 1, false, v.stm, 0);
        GLES20.glUniform2f(ul(progVid, "hs"), pw / 2f, hh * fr);
        GLES20.glUniform2f(ul(progVid, "off"), 0f, hh - hh * fr);
        GLES20.glUniform2f(ul(progVid, "fit"), fx, fy);
        drawQuad(progVid);
    }

    private void drawWins() {
        for (Win w : wins) {
            if (!shown(w)) continue;
            android.graphics.Bitmap bm = w.c.bitmap();
            if (bm == null) continue;
            boolean d = w.c.takeDirty();
            if (w.tex == 0) {
                int[] t = new int[1];
                GLES20.glGenTextures(1, t, 0);
                w.tex = t[0];
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, w.tex);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                w.c.markDirty();
                w.c.takeDirty();
                d = true;
            }
            if (d) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, w.tex);
                if (w.texW == bm.getWidth() && w.texH == bm.getHeight())
                    android.opengl.GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bm);   // same size: no new memory each time (smoother)
                else {
                    android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bm, 0);
                    w.texW = bm.getWidth(); w.texH = bm.getHeight();
                }
            }
            if (w.c instanceof NvVideo) drawVideo(w, (NvVideo) w.c, bm);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUseProgram(progPanel);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, w.tex);
            GLES20.glUniform1i(ul(progPanel, "tex"), 0);
            GLES20.glUniform1f(ul(progPanel, "al"), w.c instanceof NvVideo ? 1f : w.animA);
            GLES20.glUniformMatrix4fv(ul(progPanel, "mvp"), 1, false, w.mvp, 0);
            float pw = w.widthM * w.scale * winZoom * w.animS;
            GLES20.glUniform2f(ul(progPanel, "hs"), pw / 2f, pw * bm.getHeight() / bm.getWidth() / 2f);
            drawQuad(progPanel);
            GLES20.glDisable(GLES20.GL_BLEND);
        }
    }

    // finger pointer: index fingertip; pinch = thumb tip close to index tip (relative to hand size)
    private boolean pinching = false;
    private float pinchAmt = 0f, rippleX = 0f, rippleY = 0f;
    private float ratioF = 0f; private long ratioT = 0, onSince = 0, offSince = 0;     // the finger gap, eased; when a change of the pinch state started
    private long popT = 0; private float popX = 0f, popY = 0f; private boolean popOk = false;
    private void holdPop(float nx, float ny, boolean ok) { popT = System.currentTimeMillis(); popX = nx; popY = ny; popOk = ok; }
    private long rippleT = 0;
    private long lastPinchT = 0, lostT = 0, mpSince = 0;
    private float sizeEma = 0, dnx = 0, dny = 0;
    private boolean resizing = false, gripHot = false;
    private float rsD0 = 1f;
    private long dragT0 = 0;
    private boolean mpOn = false, mpLatched = false;
    private long mpT0 = 0, mpTapT = 0;
    private float mpProg = 0, mpNx = 0, mpNy = 0;

    // with two hands in view, one of them drives the pointer: the one that is pinching / poking, otherwise the one nearest to where the pointer was
    public volatile int mainHand = 0;                     // with two hands in view: 0 = the left hand drives the pointer, 1 = the right hand
    private volatile boolean resetLayoutReq = false;
    private long okT0 = 0; private float okProg = 0; private boolean okDone = false;
    private int selHand = 0, handBase = 0;
    private float selTx = 0, selTy = 0;
    private final float[] oneHand = new float[64];
    private float[] pickHand(float[] d) {
        int n = d.length > 0 ? (int) d[0] : 0;
        handBase = 0;
        if (n <= 1 || d.length < 1 + n * 63) { selHand = 0; return d; }
        float ar = (float) Math.max(1, imgW) / Math.max(1, imgH);
        int best = 0; float bestScore = 1e9f;
        if (palmMenu && n == 2) {                                      // the left hand carries the palm menu, so with two hands the right hand is always the pointer
            best = rot180 ? 0 : 1;                                    // (otherwise an open left palm would also be read as pinches / pokes of the pointer)
            selHand = best; handBase = best * 63;
            oneHand[0] = 1f;
            System.arraycopy(d, 1 + best * 63, oneHand, 1, 63);
            selTx = oneHand[25]; selTy = oneHand[26];
            return oneHand;
        }
        for (int i = 0; i < n; i++) {
            int b = 1 + i * 63;
            float tx = d[b + 24], ty = d[b + 25], ax = d[b + 12], ay = d[b + 13], kx = d[b + 36], ky = d[b + 37];
            float size = Math.max(1e-3f, (float) Math.hypot((d[b + 27] - d[b]) * ar, d[b + 28] - d[b + 1]));
            float rp = (float) Math.hypot((tx - ax) * ar, ty - ay) / size, rm = (float) Math.hypot((kx - ax) * ar, ky - ay) / size;
            boolean active = rp < 0.25f || rm < 0.25f;
            float score = (float) Math.hypot((tx - selTx) * ar, ty - selTy);
            if (active) score -= 10f;
            if (i == (n == 2 ? ((mainHand == 0) != rot180 ? 0 : 1) : i)) score -= 0.5f;     // the chosen main hand (hands are sorted left to right in the picture)
            if (i == selHand) score -= 0.08f;                          // a little loyalty to the hand already in use
            if (i == selHand && active) score -= 10f;                  // keep the hand that is in the middle of a gesture
            if (score < bestScore) { bestScore = score; best = i; }
        }
        selHand = best;
        handBase = best * 63;
        oneHand[0] = 1f;
        System.arraycopy(d, 1 + best * 63, oneHand, 1, 63);
        selTx = oneHand[25]; selTy = oneHand[26];
        return oneHand;
    }

    // ----- menu on the left palm (two hands): open the left hand with the palm to the camera and two round icons appear on it.
    // Poke one with the index finger of the other hand and hold it there for a moment: left icon = open / close the menu, right icon = put the screens back in front of you.
    public volatile boolean palmMenu = true;
    public volatile Runnable onPalmSettings;                  // the right icon: open / close the settings window (set by the activity)
    private long pmOpenT = 0, pmLostT = 0, pmHotT = 0;
    private float pmFade = 0f, pmDwell = 0f, pmAx = 0f, pmAy = 0f, pmBx = 0f, pmBy = 0f, pmR = 0f;
    private int pmHot = -1;
    private boolean pmLatch = false;

    private float spx(float[] d, int base, int k) { float hx = d[base + k * 3]; if (rot180) hx = 1f - hx; return (hx * 2f - 1f) * sceneW / 2f * camScale; }
    private float spy(float[] d, int base, int k) { float hy = d[base + k * 3 + 1]; if (rot180) hy = 1f - hy; return (1f - hy * 2f) * sceneH / 2f * camScale; }

    private float pmCal = 0f;                                    // >0: the model's "left" label = the user's left hand (as assumed), <0: the other way round (learned whenever two hands are in view)
    private void palmMenu(long nowMs) {
        float[] d = dispDraw;                                    // what is drawn on the picture: the icon and the fingertip are lined up with what you see
        boolean two = palmMenu && d != null && d.length >= 64 && d[0] >= 1f && sceneW > 0;     // one hand is enough to SHOW the icons (the left palm); the other hand is only needed to poke
        boolean both = two && d.length >= 127 && d[0] >= 2f;
        boolean open = false, fin = false;
        float tx = 0f, ty = 0f;
        if (two) {
            int lh = both ? (rot180 ? 1 : 0) : 0, rh = 1 - lh;     // hands are ordered left to right in the picture (the other way round when the picture is turned); a lone hand is read as the left one
            int bL = 1 + lh * 63, bR = 1 + rh * 63;
            float ar = (float) Math.max(1, imgW) / Math.max(1, imgH);
            float wx = d[bL], wy = d[bL + 1];
            float size = (float) Math.hypot((d[bL + 27] - wx) * ar, d[bL + 28] - wy);
            boolean ext = size > 1e-3f;
            int[] tp = {8, 12, 16, 20}, pp = {6, 10, 14, 18};
            for (int q = 0; q < 4 && ext; q++) {
                float dt = (float) Math.hypot((d[bL + tp[q] * 3] - wx) * ar, d[bL + tp[q] * 3 + 1] - wy);
                float dp = (float) Math.hypot((d[bL + pp[q] * 3] - wx) * ar, d[bL + pp[q] * 3 + 1] - wy);
                if (!(dt > dp * 1.12f)) ext = false;              // all four fingers stretched out
            }
            // palm to the camera: for a left hand that shows the palm, the index knuckle -> pinky knuckle turn is one way round (the back of the hand: the other way)
            float v1x = (d[bL + 15] - wx) * ar, v1y = d[bL + 16] - wy, v2x = (d[bL + 51] - wx) * ar, v2y = d[bL + 52] - wy;
            boolean facing = (v1x * v2y - v1y * v2x) > 0.10f * size * size;      // (on the phone the palm side gave the opposite sign to the first guess)
            if (both) {                                                  // two hands in view: the left one in the picture IS the left hand, so this shows which way the model's left/right labels run on this phone
                float l0 = cam.leftness(0), l1 = cam.leftness(1);
                if (l0 >= 0f && l1 >= 0f && Math.abs(l0 - l1) > 0.3f) pmCal += ((l0 > l1 ? 1f : -1f) - pmCal) * 0.25f;
            }
            if (!both) { float lf = cam.leftness(0); if (lf >= 0f && pmCal < 0f) lf = 1f - lf; if (lf >= 0f && lf < 0.5f) facing = false; }      // a lone hand has to be the user's LEFT one (the model's own guess), so a right hand coming in with its back to the camera shows nothing
            open = ext && facing;
            // where the icons go: on the palm, next to each other across it (index side / little finger side), so they stay on the right sides when the hand turns
            float x0 = spx(d, bL, 0), y0 = spy(d, bL, 0), x9 = spx(d, bL, 9), y9 = spy(d, bL, 9);
            float L = (float) Math.hypot(x9 - x0, y9 - y0);
            float sxv = spx(d, bL, 5) - spx(d, bL, 17), syv = spy(d, bL, 5) - spy(d, bL, 17);
            float sl = (float) Math.hypot(sxv, syv);
            if (L > 1f && sl > 1f) {
                sxv /= sl; syv /= sl;
                float cx = x0 + (x9 - x0) * 0.52f, cy = y0 + (y9 - y0) * 0.52f, off = 0.25f * L;
                float u = sceneH / 540f;
                pmAx = cx + sxv * off; pmAy = cy + syv * off;
                pmBx = cx - sxv * off; pmBy = cy - syv * off;
                pmR = Math.max(11f * u, Math.min(42f * u, 0.18f * L));
            } else open = false;
            // the finger of the other hand: its index tip, with the finger stretched
            if (both) {
            float rwx = d[bR], rwy = d[bR + 1];
            float d8 = (float) Math.hypot((d[bR + 24] - rwx) * ar, d[bR + 25] - rwy), d6 = (float) Math.hypot((d[bR + 18] - rwx) * ar, d[bR + 19] - rwy);
            fin = d8 > d6 * 1.1f;
            tx = spx(d, bR, 8); ty = spy(d, bR, 8);
            }
        }
        if (open) { pmLostT = 0; if (pmOpenT == 0) pmOpenT = nowMs; }
        else { if (pmLostT == 0) pmLostT = nowMs; if (nowMs - pmLostT > 150) pmOpenT = 0; }
        boolean show = open && pmOpenT != 0 && nowMs - pmOpenT > 300;       // the palm has to stay open for a moment
        pmFade += ((show ? 1f : 0f) - pmFade) * (show ? 0.25f : 0.35f);
        if (pmFade < 0.02f && !show) pmFade = 0f;
        int hot = -1;
        if (show && fin) {
            float dA = (float) Math.hypot(tx - pmAx, ty - pmAy), dB = (float) Math.hypot(tx - pmBx, ty - pmBy);
            if (dA < pmR * 1.25f && dA <= dB) hot = 0; else if (dB < pmR * 1.25f) hot = 1;
        }
        if (hot != pmHot) { pmHot = hot; pmHotT = nowMs; if (hot < 0) pmLatch = false; }
        pmDwell = (hot >= 0 && !pmLatch) ? Math.min(1f, (nowMs - pmHotT) / 380f) : 0f;
        if (hot >= 0 && !pmLatch && pmDwell >= 1f) {
            pmLatch = true;                                                // once per visit: take the finger away to use it again
            float kx = sceneW / 2f * camScale, ky = sceneH / 2f * camScale;
            if (hot == 0) { holdPop(pmAx / kx, pmAy / ky, false); toggleMenuGL(); }
            else { holdPop(pmBx / kx, pmBy / ky, false); Runnable rs = onPalmSettings; if (rs != null) { try { rs.run(); } catch (Throwable ignored) { } } }
        }
    }

    private void drawPalmMenu() {
        if (pmFade < 0.02f) return;
        float u = sceneH / 540f, a = pmFade;
        for (int i = 0; i < 2; i++) {
            float x = i == 0 ? pmAx : pmBx, y = i == 0 ? pmAy : pmBy;
            boolean hot = pmHot == i;
            float rr = pmR * (hot ? 1.12f : 1f);
            float cr = i == 0 ? 0.62f : 1f, cg = i == 0 ? 0.82f : 0.78f, cb = i == 0 ? 1f : 0.35f;
            aaFade = 0.50f * a; aaBegin(); aaSeg(x, y, x, y, rr + 1.5f * u); aaFlush(1f, 1f, 1f);          // rim
            aaFade = 0.88f * a; aaBegin(); aaSeg(x, y, x, y, rr); aaFlush(0.09f, 0.10f, 0.14f);            // dark glass
            if (pmDwell > 0f && hot) { aaFade = a; aaBegin(); aaArc(x, y, rr * 0.80f, 0f, pmDwell, 2.6f * u); aaFlush(cr, cg, cb); }
            else { aaFade = 0.22f * a; aaBegin(); aaArc(x, y, rr * 0.80f, 0f, 1f, 1.8f * u); aaFlush(1f, 1f, 1f); }
            aaFade = a;
            aaBegin();
            if (i == 0) {                                                    // menu: four dots (a little grid of apps)
                float g = rr * 0.26f;
                for (int sx = -1; sx <= 1; sx += 2) for (int sy = -1; sy <= 1; sy += 2) aaSeg(x + sx * g, y + sy * g, x + sx * g, y + sy * g, rr * 0.12f);
            } else {                                                         // settings: a gear (a ring with eight teeth)
                aaArc(x, y, rr * 0.30f, 0f, 1f, 2.2f * u);
                for (int q = 0; q < 8; q++) {
                    float an = q * 0.7853982f, c = (float) Math.cos(an), sn = (float) Math.sin(an);
                    aaSeg(x + c * rr * 0.42f, y + sn * rr * 0.42f, x + c * rr * 0.56f, y + sn * rr * 0.56f, 2.6f * u);
                }
            }
            aaFlush(cr, cg, cb);
        }
        aaFade = 1f;
    }

    private void cursor() {
        palmMenu(System.currentTimeMillis());
        float[] r = pickHand(disp);
        long nowMs = System.currentTimeMillis();
        boolean hand = r.length >= 64 && r[0] >= 1;
        if (!hand) {
            pinchAmt *= 0.6f;
            curOk = false; pkOn = false; pkProg = 0; pkInit = false;
            mpOn = false; mpSince = 0; mpProg = 0; mpLatched = false;
            if (lostT == 0) lostT = nowMs;
            // a short loss of the hand (detection blink) must not drop a grabbed window
            if (!(dragWin != null && nowMs - lostT < 400)) { pinching = false; prevPinch = false; endDrag(); }
            for (Win w : wins) if (shown(w)) w.c.update(false, 0, 0, false);
            return;
        }
        lostT = 0;
        float ar = (float) Math.max(1, imgW) / Math.max(1, imgH);
        float tx = r[1 + 8 * 3], ty = r[1 + 8 * 3 + 1];
        float ax = r[1 + 4 * 3], ay = r[1 + 4 * 3 + 1];
        float kx = r[1 + 12 * 3], ky = r[1 + 12 * 3 + 1];
        float wx = r[1], wy = r[2], mx = r[1 + 9 * 3], my = r[1 + 9 * 3 + 1];
        float size = (float) Math.hypot((mx - wx) * ar, my - wy);
        sizeEma = sizeEma <= 0f ? size : sizeEma + (size - sizeEma) * (dragWin != null ? 0.35f : 0.15f);
        // while moving a window the pointer is the middle of the knuckles: it stays steady when the fingers pinch
        float kxn = (r[1 + 5 * 3] + r[1 + 9 * 3] + r[1 + 13 * 3]) / 3f, kyn = (r[1 + 5 * 3 + 1] + r[1 + 9 * 3 + 1] + r[1 + 13 * 3 + 1]) / 3f;
        if (rot180) { kxn = 1f - kxn; kyn = 1f - kyn; }
        float tnx = kxn * 2f - 1f, tny = 1f - kyn * 2f;
        float d = (float) Math.hypot((tx - ax) * ar, ty - ay);
        // the hand size the finger gap is compared with: the length of the palm, but when the hand points at the camera that gets short, so the palm width counts too
        float palmW = (float) Math.hypot((r[1 + 5 * 3] - r[1 + 17 * 3]) * ar, r[1 + 5 * 3 + 1] - r[1 + 17 * 3 + 1]);
        float psize = Math.max(size, 1.15f * palmW);
        float ratio = psize > 1e-4f ? d / psize : 9f;
        float dm = (float) Math.hypot((kx - ax) * ar, ky - ay);
        float ratioM = psize > 1e-4f ? dm / psize : 9f;
        // the gap is eased a little (one noisy look must not flip the pinch), and a change of state has to hold for a moment:
        // closing: below 0.12 at once, below 0.18 for 30 ms; opening: above 0.45 at once, above 0.31 for 50 ms
        ratioF = ratioF <= 0f || nowMs - ratioT > 300 ? ratio : ratioF + (ratio - ratioF) * 0.6f;
        ratioT = nowMs;
        if (!pinching) {
            offSince = 0;
            if (ratioF < 0.15f) { pinching = true; onSince = 0; }
            else if (ratioF < 0.23f) { if (onSince == 0) onSince = nowMs; else if (nowMs - onSince >= 30) { pinching = true; onSince = 0; } }
            else onSince = 0;
        } else {
            onSince = 0;
            if (ratioF > 0.45f) { pinching = false; offSince = 0; }
            else if (ratioF > 0.34f) { if (offSince == 0) offSince = nowMs; else if (nowMs - offSince >= 50) { pinching = false; offSince = 0; } }
            else offSince = 0;
        }
        if (pinching) lastPinchT = nowMs;
        // how closed the fingers are (0..1), eased: the pointer ring tightens as the fingers close; a ripple goes out when the pinch lands
        float pinchTarget = pinching ? 1f : Math.max(0f, Math.min(0.85f, (0.36f - ratioF) / (0.36f - 0.17f)));
        pinchAmt += (pinchTarget - pinchAmt) * 0.35f;
        if (pinching && !prevPinch) { rippleT = nowMs; rippleX = cnx; rippleY = cny; }

        // air touch (as in the web version): the index finger is out and is pushed forward = the hand gets smaller in the picture.
        // Made calmer: the depth is smoothed, the pose has to be steady for a moment, a fast-moving hand does not count as a push,
        // the push must last a moment, and there is a short pause after a press.
        int selM = 0;                                   // air touch was taken out (too hard to use): pinching is the only way to press
        {
            float z8 = r[1 + 8 * 3 + 2], z0 = r[1 + 2];
            float sz = Math.max(1e-3f, size);
            float depthRaw = -((float) Math.log(sz) - 0.6f * (z8 - z0) / sz);
            float dtF = pkT2 == 0 ? 0.02f : Math.min(0.1f, (nowMs - pkT2) / 1000f);
            pkT2 = nowMs;
            pkDepthF = !pkInit ? depthRaw : pkDepthF + (depthRaw - pkDepthF) * (1f - (float) Math.exp(-dtF / 0.10f));
            float depth = pkDepthF;
            if (vel.length > handBase + 27) handSpd = (float) Math.hypot(vel[1 + handBase + 8 * 3], vel[1 + handBase + 8 * 3 + 1]);    // how fast the fingertip crosses the picture (picture widths per second)
            float d8 = (float) Math.hypot((tx - wx) * ar, ty - wy);
            float d6 = (float) Math.hypot((r[1 + 6 * 3] - wx) * ar, r[1 + 6 * 3 + 1] - wy);
            boolean idxOut = !pinching && ratio > 0.30f && d8 > d6 * 1.1f;
            if (!idxOut) idxSince = 0; else if (idxSince == 0) idxSince = nowMs;
            boolean ready = idxOut && nowMs - idxSince > 120 && nowMs - pkRelT > 300 && handSpd < 0.6f;
            if (selM == 0 || !idxOut) { pkOn = false; pkBase = depth; pkT = nowMs; pkProg = 0; pkInit = true; pkAbove = 0; }
            else if (!ready && !pkOn) { pkBase = depth; pkT = nowMs; pkProg = 0; pkAbove = 0; pkInit = true; }
            else {
                if (!pkInit) { pkBase = depth; pkT = nowMs; pkInit = true; }
                float th = POKE_TH[Math.max(0, Math.min(2, pokeS))] * 1.6f;
                pkProg = pkOn ? 1f : Math.max(0f, Math.min(1f, (depth - pkBase) / th));
                if (!pkOn) {
                    float dt = Math.min(0.5f, (nowMs - pkT) / 1000f); pkT = nowMs;
                    if (depth - pkBase > th) {
                        if (pkAbove == 0) pkAbove = nowMs;
                        if (nowMs - pkAbove > 70) { pkOn = true; pkPeak = depth; }      // must stay pushed for a moment
                    } else {
                        pkAbove = 0;
                        pkBase += (depth - pkBase) * Math.min(1f, dt / 0.6f);              // slow changes (the whole arm moving) are not a poke
                    }
                } else {
                    if (depth > pkPeak) pkPeak = depth;
                    if (depth - pkBase < th * 0.45f) { pkOn = false; pkRelT = nowMs; pkAbove = 0; }   // finger pulled back
                }
            }
        }
        final boolean press = selM == 0 ? pinching : (selM == 1 ? pkOn : (pinching || pkOn));

        // thumb + middle finger held together for a moment: show / hide the menu
        boolean mpPrev = mpOn;
        // with two hands the pointing pose (index out, thumb on the middle finger) IS this gesture, so poking a palm icon opened the menu first: it is off while the palm menu is in use
        boolean mpBlock = palmMenu && disp != null && disp.length > 0 && disp[0] >= 2f;
        mpOn = !mpBlock && (mpOn ? (ratioM < 0.32f) : (ratioM < 0.20f && ratio > 0.35f));
        if (mpOn && !mpPrev) mpT0 = nowMs;
        if (!mpOn && mpPrev && !mpLatched && nowMs - mpT0 < 500) {        // a quick tap; two quick taps = put the screens back in front of you
            if (nowMs - mpTapT < 1000) { recenter = true; mpTapT = 0; } else mpTapT = nowMs;
        }
        if (mpOn && !mpLatched) {
            if (mpSince == 0) mpSince = nowMs;
            mpProg = Math.min(1f, (nowMs - mpSince) / 700f);
            if (mpProg >= 1f) { holdPop(mpNx, mpNy, false); toggleMenuGL(); mpLatched = true; mpProg = 0; }
        } else if (!mpOn) { mpSince = 0; mpProg = 0; mpLatched = false; }
        float mhx = (kx + ax) * 0.5f, mhy = (ky + ay) * 0.5f;
        if (rot180) { mhx = 1f - mhx; mhy = 1f - mhy; }
        mpNx = mhx * 2f - 1f; mpNy = 1f - mhy * 2f;

        // the pointer sits between thumb tip and index tip, so closing the fingers does not push it off the target
        float hx = selM == 0 ? (tx + ax) * 0.5f : tx, hy = selM == 0 ? (ty + ay) * 0.5f : ty;     // air touch: the fingertip itself
        if (rot180) { hx = 1f - hx; hy = 1f - hy; }
        float nx = hx * 2f - 1f, ny = 1f - hy * 2f;
        boolean hold = selM != 0 && (ratio < 0.45f || pkProg > 0.5f);          // fingers closing / finger being pushed: the pointer stays where it was
        if (!curOk) { cnx = nx; cny = ny; } else if (!hold) {
            // adaptive follow: a slow, shaky fingertip is calmed down, a quick one is followed almost exactly (sticks to the hand)
            float sp = (float) Math.hypot(nx - cnx, ny - cny);
            float al = Math.max(0.50f, Math.min(0.95f, 0.50f + sp * 9f));      // the points are already steadied at the source, so this only needs to calm the last bit
            cnx += (nx - cnx) * al; cny += (ny - cny) * al;
        }
        curOk = true;
        if (dragWin != null) {
            float ddx = tnx - dnx, ddy = tny - dny;
            float dl = (float) Math.hypot(ddx, ddy);
            float stp = Math.min(dl * 0.55f, 0.10f);                     // smooth, and never a sudden big jump
            if (dl > 1e-5f) { dnx += ddx / dl * stp; dny += ddy / dl * stp; }
            rayYaw = (float) Math.toDegrees(Math.atan(dnx * tanHc));
            rayPitch = (float) Math.toDegrees(Math.atan(dny * tanVc));
        } else {
            rayYaw = (float) Math.toDegrees(Math.atan(cnx * tanHc));
            rayPitch = (float) Math.toDegrees(Math.atan(cny * tanVc));
        }

        float[] o = new float[2];
        Win target = null, gripWin = null;
        if (dragWin == null && !mpOn) {
            for (int i = wins.size() - 1; i >= 0; i--) {                 // the one drawn last is in front
                Win w = wins.get(i);
                if (!shown(w) || !hitWin(w, cnx, cny, o)) continue;
                if (w.c.inGrip(o[0], o[1])) { gripWin = w; break; }      // a bottom corner: pull it to resize
                if (w.c.covers(o[0], o[1])) { target = w; break; }
            }
        }
        gripHot = gripWin != null || (dragWin != null && resizing);
        // OK sign (thumb and index in a ring, the other three fingers stretched out) held for a moment over empty space:
        // all windows go back to the standard places and sizes
        {
            boolean ext = true;
            int[] tp = {12, 16, 20}, pp = {10, 14, 18};
            for (int q = 0; q < 3; q++) {
                float dt = (float) Math.hypot((r[1 + tp[q] * 3] - wx) * ar, r[1 + tp[q] * 3 + 1] - wy);
                float dp = (float) Math.hypot((r[1 + pp[q] * 3] - wx) * ar, r[1 + pp[q] * 3 + 1] - wy);
                if (!(dt > dp * 1.12f)) ext = false;
            }
            if (pinching && ext && target == null && gripWin == null && dragWin == null && !mpOn) {
                if (okT0 == 0) okT0 = nowMs;
                okProg = Math.min(1f, (nowMs - okT0) / 1500f);
                if (okProg >= 1f && !okDone) { okDone = true; resetLayoutReq = true; recenter = true; holdPop(cnx, cny, true); }
            } else { okT0 = 0; okProg = 0; if (!pinching) okDone = false; }
        }
        if (gripWin != null && pinching && !prevPinch) {
            if (motion != null && motion.enabled) rebase(gripWin, sux, suy, suz);
            dragWin = gripWin; resizing = true;
            dnx = tnx; dny = tny;
            rayYaw = (float) Math.toDegrees(Math.atan(dnx * tanHc)); rayPitch = (float) Math.toDegrees(Math.atan(dny * tanVc));
            rsD0 = Math.max(3f, (float) Math.hypot(rayYaw - gripWin.yawA, rayPitch - gripWin.pitchA));
            gripWin.dragS0 = gripWin.scale;
            gripWin.c.setDragging(true);
            wins.remove(gripWin); wins.add(gripWin);
        }
        if (target != null && pinching && !prevPinch && target.c.handleRecent()) {      // grabbed the bar under a window
            if (motion != null && motion.enabled) rebase(target, sux, suy, suz);
            dragWin = target;
            dnx = tnx; dny = tny;
            float gy = (float) Math.toDegrees(Math.atan(dnx * tanHc)), gp = (float) Math.toDegrees(Math.atan(dny * tanVc));
            rayYaw = gy; rayPitch = gp;
            target.dragYawOff = target.yawA - gy;
            target.dragPitchOff = target.pitchA - gp;
            target.dragS0 = target.scale;
            target.dragSize0 = Math.max(1e-3f, sizeEma);
            target.dragD0 = target.dist;
            dragT0 = nowMs;
            target.c.setDragging(true);
            wins.remove(target);                                          // bring to the front
            wins.add(target);
        }
        if (dragWin != null && resizing && pinching) {                 // the corner follows the hand: size = distance from the window centre
            float rd = (float) Math.hypot(rayYaw - dragWin.yawA, rayPitch - dragWin.pitchA);
            float tg = Math.max(0.5f, Math.min(2.5f, dragWin.dragS0 * rd / rsD0));
            dragWin.scale += (tg - dragWin.scale) * 0.3f;
        }
        if (dragWin != null && !resizing && pinching && nowMs - dragT0 > 180) {
            // push / pull: the hand coming closer to the camera brings the window closer, the hand going away pushes it away.
            // Small changes are ignored (soft dead zone) and it moves gently, so it does not stutter or run away.
            float lr = (float) Math.log(Math.max(1e-3f, sizeEma) / dragWin.dragSize0);
            float f = lr > 0.04f ? lr - 0.04f : (lr < -0.04f ? lr + 0.04f : 0f);
            float tgD = Math.max(0.5f, Math.min(Math.max(6f, dragWin.dragD0), dragWin.dragD0 * (float) Math.exp(-2.8f * f)));
            dragWin.dist += (tgD - dragWin.dist) * 0.25f;
        }
        // let go only when the pinch has been open for a moment (one bad detection must not drop the window)
        if (dragWin != null && !pinching && nowMs - lastPinchT > 250) endDrag();
        prevPinch = pinching;
        for (Win w : wins) {
            if (!shown(w)) continue;
            if (w == target && dragWin == null) w.c.update(true, o[0], o[1], press);
            else w.c.update(false, 0, 0, false);
        }
    }

    private void drawCursor() {
        float u = sceneH / 540f;
        // a small glass badge with three bars, one above the other, that light up from the top down while a gesture is held
        // (menu: thumb + middle finger; layout reset: OK sign). When all three are lit the badge pops and fades.
        drawPalmMenu();
        if (mpProg > 0f) drawHold(mpNx, mpNy, mpProg, false, 1f, 1f);
        if (okProg > 0f && curOk) drawHold(cnx, cny, okProg, true, 1f, 1f);
        long pa0 = System.currentTimeMillis() - popT;
        if (popT != 0 && pa0 >= 0 && pa0 < 380) {
            float k = pa0 / 380f;
            drawHold(popX, popY, 1f, popOk, 1f + 0.55f * (1f - (1f - k) * (1f - k)), 1f - k);
        }
        if (!curOk || !anyVisible()) return;
        float x = cnx * sceneW / 2f * camScale, y = cny * sceneH / 2f * camScale;
        // small and faint so it does not cover what you are pointing at. Like on Quest: the ring tightens as the fingers close,
        // the dot grows, and a soft ripple spreads from the point where the pinch landed.
        float[] ro = {8f, 12f, 18f}, ri = {2.6f, 4f, 6f}, fo = {0.55f, 0.7f, 1f};
        int cs = Math.max(0, Math.min(2, cursorSz));
        boolean pv = pinching || pkOn;
        float pa = Math.max(pinchAmt, pv ? 1f : 0f);
        long age = System.currentTimeMillis() - rippleT;
        if (rippleT != 0 && age >= 0 && age < 340) {
            float k = age / 340f, e = 1f - (1f - k) * (1f - k);
            float rx = rippleX * sceneW / 2f * camScale, ry = rippleY * sceneH / 2f * camScale;
            aaFade = 0.45f * (1f - k);
            aaBegin();
            aaSeg(rx, ry, rx, ry, ro[cs] * (0.9f + 2.6f * e) * u);
            aaFlush(1f, 1f, 1f);
        }
        aaFade = fo[cs];
        aaBegin();
        aaSeg(x, y, x, y, ro[cs] * (1f - 0.42f * pa) * (1f - 0.2f * pkProg) * u);
        if (gripHot) aaFlush(0.13f, 0.72f, 0.81f); else aaFlush(0.55f + 0.45f * pa, 0.9f - 0.35f * pa, 1f - pa);
        aaFade = Math.min(1f, fo[cs] + 0.2f);
        aaBegin();
        aaSeg(x, y, x, y, ri[cs] * (1f + 0.6f * pa) * u);
        aaFlush(1f, 1f, 1f);
        aaFade = 1f;
    }

    /** part of a circle drawn as short capsules. Angles in turns (0 = top, clockwise); scene y points up. */
    private void aaArc(float cx, float cy, float r, float t0, float t1, float hw) {
        if (t1 - t0 < 0.002f) return;
        int n = Math.max(1, (int) Math.ceil((t1 - t0) * 48f));
        float px = cx + r * (float) Math.sin(t0 * 6.2831853f), py = cy + r * (float) Math.cos(t0 * 6.2831853f);
        for (int i = 1; i <= n; i++) {
            float t = t0 + (t1 - t0) * i / n;
            float qx = cx + r * (float) Math.sin(t * 6.2831853f), qy = cy + r * (float) Math.cos(t * 6.2831853f);
            aaSeg(px, py, qx, qy, hw);
            px = qx; py = qy;
        }
    }

    /**
     * The badge shown above the hand while a gesture is held: a round glass disc with a ring and a small picture, two different looks:
     *  - menu (thumb + middle finger): the ring FILLS clockwise and a grid of 3 x 3 dots lights up row by row, top to bottom;
     *  - layout reset (OK sign): like a countdown, the ring EMPTIES, and four corner brackets (a screen frame) close in towards the middle. In green.
     * When the hold is complete the badge swells and fades.
     */
    private void drawHold(float nx, float ny, float prog, boolean ok, float scale, float alpha) {
        float u = sceneH / 540f * scale;
        float x = nx * sceneW / 2f * camScale, y = ny * sceneH / 2f * camScale + 58f * sceneH / 540f;      // a little above the hand
        float[] c = ok ? new float[]{0.25f, 0.88f, 0.45f} : new float[]{0.62f, 0.82f, 1f};
        // rim, then the dark glass disc
        aaFade = 0.50f * alpha;
        aaBegin(); aaSeg(x, y, x, y, 31.5f * u); aaFlush(1f, 1f, 1f);
        aaFade = 0.88f * alpha;
        aaBegin(); aaSeg(x, y, x, y, 30f * u); aaFlush(0.09f, 0.10f, 0.14f);
        // the ring: a faint track, and the bright part
        aaFade = 0.20f * alpha;
        aaBegin(); aaArc(x, y, 23.5f * u, 0f, 1f, 2.1f * u); aaFlush(1f, 1f, 1f);
        aaFade = alpha;
        aaBegin();
        if (ok) aaArc(x, y, 23.5f * u, prog, 1f, 2.6f * u);       // countdown: what is left
        else aaArc(x, y, 23.5f * u, 0f, prog, 2.6f * u);          // filling up
        aaFlush(c[0], c[1], c[2]);
        if (!ok) {
            // 3 x 3 dots; a row lights up when the hold has reached it (top row first)
            for (int r = 0; r < 3; r++) {
                float f = Math.max(0f, Math.min(1f, prog * 3f - r));
                for (int q = 0; q < 3; q++) {
                    float dx = x + (q - 1) * 8.5f * u, dy = y + (1 - r) * 8.5f * u;
                    aaFade = 0.28f * alpha;
                    aaBegin(); aaSeg(dx, dy, dx, dy, 2.2f * u); aaFlush(1f, 1f, 1f);
                    if (f > 0f) { aaFade = alpha * f; aaBegin(); aaSeg(dx, dy, dx, dy, 2.5f * u); aaFlush(1f, 1f, 1f); }
                }
            }
        } else {
            // four corner brackets that close in as the time runs
            float sq = (15f - 7f * prog) * u, arm = 6f * u;
            aaFade = alpha;
            aaBegin();
            for (int sx = -1; sx <= 1; sx += 2) for (int sy = -1; sy <= 1; sy += 2) {
                aaSeg(x + sx * sq, y + sy * sq, x + sx * (sq - arm), y + sy * sq, 1.9f * u);
                aaSeg(x + sx * sq, y + sy * sq, x + sx * sq, y + sy * (sq - arm), 1.9f * u);
            }
            aaFlush(c[0], c[1], c[2]);
            if (prog > 0.75f) { aaFade = alpha * Math.min(1f, (prog - 0.75f) * 4f); aaBegin(); aaSeg(x, y, x, y, 2.4f * u); aaFlush(c[0], c[1], c[2]); }
        }
        aaFade = 1f;
    }

    private final int[] alc = new int[256];                    // attribute places already asked for (location + 1; 0 = not asked yet)
    private int attr(int prog, String n, int slot) {
        int k = prog * 2 + slot;
        if (k >= 0 && k < alc.length) {
            if (alc[k] == 0) alc[k] = GLES20.glGetAttribLocation(prog, n) + 1;
            return alc[k] - 1;
        }
        return GLES20.glGetAttribLocation(prog, n);
    }

    private void drawQuad(int prog) {
        int a = attr(prog, "p", 0);
        GLES20.glEnableVertexAttribArray(a);
        GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(a);
    }

    private void ensureMask() {
        if (maskW != 0 && maskW == sceneW * 3 / 4) return;
        if (maskTex[0] != 0) { GLES20.glDeleteTextures(1, maskTex, 0); GLES20.glDeleteFramebuffers(1, maskFbo, 0); }
        maskW = Math.max(2, sceneW * 3 / 4); maskH = Math.max(2, sceneH * 3 / 4);
        GLES20.glGenTextures(1, maskTex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, maskW, maskH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glGenFramebuffers(1, maskFbo, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, maskFbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, maskTex[0], 0);
    }

    // hand shape (fingers as fat soft bars, palm filled) drawn to a small soft mask; the mask is blurred a little and a thin smooth line
    // is drawn along its middle value, with a faint glow outside: a clean single contour like the web version
    // step 1 of the outline: the soft hand shape into the small mask. This is done BEFORE the camera picture is started, so the big scene
    // picture is drawn in one go (switching away from it half way makes a phone GPU save and reload the whole picture: a lot of memory traffic)
    private boolean maskReady = false;
    private void renderMask(float[] r) {
        maskReady = false;
        int n = (int) r[0];
        if (n <= 0 || sceneW == 0) return;
        ensureMask();
        maskReady = true;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, maskFbo[0]);
        GLES20.glViewport(0, 0, maskW, maskH);
        GLES20.glClearColor(0, 0, 0, 0);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        float hwS = sceneW / 2f, hhS = sceneH / 2f;
        for (int i = 0; i < n; i++) {
            float[] X = ohX, Y = ohY;
            for (int k = 0; k < 21; k++) {
                float hx = r[1 + i * 63 + k * 3], hy = r[1 + i * 63 + k * 3 + 1];
                if (rot180) { hx = 1f - hx; hy = 1f - hy; }
                X[k] = (hx * 2f - 1f) * hwS * camScale; Y[k] = (1f - hy * 2f) * hhS * camScale;
            }
            float size = (float) Math.hypot(X[9] - X[0], Y[9] - Y[0]);
            float hw = Math.max(3f, size * 0.115f);
            aaBegin();
            for (int b = 0; b < BONES.length; b += 2) {
                int p0 = BONES[b], p1 = BONES[b + 1];
                float x1 = X[p1], y1 = Y[p1];
                if (p1 == 4 || p1 == 8 || p1 == 12 || p1 == 16 || p1 == 20) {   // the end cap must not stick out beyond the fingertip
                    float dx = x1 - X[p0], dy = y1 - Y[p0], l = (float) Math.hypot(dx, dy);
                    if (l > 1e-3f) { x1 -= dx / l * hw * 0.8f; y1 -= dy / l * hw * 0.8f; }
                }
                aaSeg(X[p0], Y[p0], x1, y1, hw);
            }
            // palm filling: a fat stroke between the wrist and the knuckles (kept inside the real palm)
            aaSeg(X[0] + (X[9] - X[0]) * 0.32f, Y[0] + (Y[9] - Y[0]) * 0.32f, X[0] + (X[9] - X[0]) * 0.78f, Y[0] + (Y[9] - Y[0]) * 0.78f, size * 0.38f);
            aaFlush(1f, 1f, 1f);
        }
    }

    // step 2 of the outline: a thin line along the middle of the blurred mask, drawn on the scene picture
    private void drawOutline(float[] r) {
        int n = (int) r[0];
        if (n <= 0 || !maskReady) return;
        float hwS = sceneW / 2f, hhS = sceneH / 2f;
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        // the thin outline only needs to be worked out around the hands, not over the whole picture (a big saving on a weak GPU)
        float bx0 = 1e9f, by0 = 1e9f, bx1 = -1e9f, by1 = -1e9f, mg = 0f;
        for (int i = 0; i < n; i++) {
            float sz0 = 0f;
            for (int k = 0; k < 21; k++) {
                float hx = r[1 + i * 63 + k * 3], hy = r[1 + i * 63 + k * 3 + 1];
                if (rot180) { hx = 1f - hx; hy = 1f - hy; }
                float px = (hx * 2f - 1f) * hwS * camScale + hwS, py = (1f - hy * 2f) * hhS * camScale + hhS;
                bx0 = Math.min(bx0, px); bx1 = Math.max(bx1, px); by0 = Math.min(by0, py); by1 = Math.max(by1, py);
            }
            sz0 = Math.max(bx1 - bx0, by1 - by0);
            mg = Math.max(mg, sz0 * 0.35f + 40f);
        }
        int sx0 = Math.max(0, (int) (bx0 - mg)), sy0 = Math.max(0, (int) (by0 - mg));
        int sx1 = Math.min(sceneW, (int) (bx1 + mg)), sy1 = Math.min(sceneH, (int) (by1 + mg));
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        GLES20.glScissor(sx0, sy0, Math.max(1, sx1 - sx0), Math.max(1, sy1 - sy0));
        GLES20.glUseProgram(progRing);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex[0]);
        GLES20.glUniform1i(ul(progRing, "tex"), 0);
        GLES20.glUniform2f(ul(progRing, "px"), 1f / maskW, 1f / maskH);
        GLES20.glUniform1f(ul(progRing, "rad"), 2.4f);
        drawQuad(progRing);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    // ---- soft-edged lines and dots: every stroke is a small quad whose edge fades over ~1.5 px, so nothing looks jagged ----
    private int progAA;
    private float aaFade = 1f;
    private FloatBuffer aaBuf;
    private int aaN = 0;
    private float aaSoft = 1.5f;

    private void aaBegin() { aaBuf.position(0); aaN = 0; }

    // one rounded stroke from (x0,y0) to (x1,y1), half width hw, all in scene pixels centred on the picture
    private void aaSeg(float x0, float y0, float x1, float y1, float hw) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1e-3f) { dx = 1f; dy = 0f; len = 0f; } else { dx /= len; dy /= len; }
        float nx = -dy, ny = dx;
        float hwS = sceneW / 2f, hhS = sceneH / 2f;
        float lh = len / hw;
        float pad = hw + aaSoft;
        float pa = pad / hw;
        // the four corners go into a fixed array (nothing new is made here: this runs dozens of times every frame)
        float[] c = aaC;
        c[0] = x0 - dx * pad + nx * pad;  c[1] = y0 - dy * pad + ny * pad;  c[2] = -pa;      c[3] = pa;
        c[4] = x0 - dx * pad - nx * pad;  c[5] = y0 - dy * pad - ny * pad;  c[6] = -pa;      c[7] = -pa;
        c[8] = x1 + dx * pad + nx * pad;  c[9] = y1 + dy * pad + ny * pad;  c[10] = lh + pa; c[11] = pa;
        c[12] = x1 + dx * pad - nx * pad; c[13] = y1 + dy * pad - ny * pad; c[14] = lh + pa; c[15] = -pa;
        for (int k : AA_IDX) {
            int o = k * 4;
            aaBuf.put(c[o] / hwS).put(c[o + 1] / hhS).put(c[o + 2]).put(c[o + 3]).put(lh).put(hw);
            aaN++;
        }
    }
    private final float[] aaC = new float[16];
    private static final int[] AA_IDX = {0, 1, 2, 1, 3, 2};
    private final float[] ohX = new float[21], ohY = new float[21];

    private void aaFlush(float cr, float cg, float cb) {
        if (aaN == 0) return;
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUseProgram(progAA);
        int ap = attr(progAA, "p", 0), aq = attr(progAA, "q", 1);
        GLES20.glEnableVertexAttribArray(ap);
        GLES20.glEnableVertexAttribArray(aq);
        aaBuf.position(0);
        GLES20.glVertexAttribPointer(ap, 2, GLES20.GL_FLOAT, false, 24, aaBuf);
        aaBuf.position(2);
        GLES20.glVertexAttribPointer(aq, 4, GLES20.GL_FLOAT, false, 24, aaBuf);
        GLES20.glUniform3f(ul(progAA, "col"), cr, cg, cb);
        GLES20.glUniform1f(ul(progAA, "sf"), aaSoft);
        GLES20.glUniform1f(ul(progAA, "fade"), aaFade);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, aaN);
        GLES20.glDisableVertexAttribArray(ap);
        GLES20.glDisableVertexAttribArray(aq);
        GLES20.glDisable(GLES20.GL_BLEND);
        aaBuf.position(0);
        aaN = 0;
    }

    private void drawSkeleton(float[] r) {
        int n = (int) r[0];
        if (n <= 0) return;
        float u = sceneH / 540f;
        float hwS = sceneW / 2f, hhS = sceneH / 2f;
        for (int i = 0; i < n; i++) {
            float[] X = ohX, Y = ohY;
            for (int k = 0; k < 21; k++) {
                float hx = r[1 + i * 63 + k * 3], hy = r[1 + i * 63 + k * 3 + 1];
                if (rot180) { hx = 1f - hx; hy = 1f - hy; }
                X[k] = (hx * 2f - 1f) * hwS * camScale; Y[k] = (1f - hy * 2f) * hhS * camScale;
            }
            aaBegin();
            for (int b = 0; b < BONES.length; b += 2) aaSeg(X[BONES[b]], Y[BONES[b]], X[BONES[b + 1]], Y[BONES[b + 1]], 5f * u);
            aaFlush(0.0f, 0.45f, 0.62f);
            aaBegin();
            for (int b = 0; b < BONES.length; b += 2) aaSeg(X[BONES[b]], Y[BONES[b]], X[BONES[b + 1]], Y[BONES[b + 1]], 2.4f * u);
            aaFlush(0.45f, 1f, 1f);
            aaBegin();
            for (int k = 0; k < 21; k++) aaSeg(X[k], Y[k], X[k], Y[k], 5.5f * u);
            aaFlush(1f, 1f, 1f);
        }
    }

    @Override public void onDrawFrame(GL10 gl) {
        int cap = fpsCap;
        if (cap > 0) {                                     // slow down: wait until it is time for the next picture
            long target = 1_000_000_000L / cap;
            long el = System.nanoTime() - lastFrameNs;
            if (el < target) { try { Thread.sleep((target - el) / 1_000_000L, (int) ((target - el) % 1_000_000L)); } catch (Throwable ignored) { } }
        }
        lastFrameNs = System.nanoTime();
        long now = System.nanoTime() / 1000000L;
        fc++;
        if (ft0 == 0) ft0 = now;
        if (now - ft0 >= 1000) {
            fps = fc * 1000f / (now - ft0); fc = 0; ft0 = now;
            // automatic picture size: step down 10 % when the frame rate is clearly under what we aim at, step up when it is at the aim for a while
            float aim = cap > 0 ? cap : 60f;
            if (autoRes) {
                if (fps < aim * 0.9f && now - lastScaleT > 2500 && sceneScale > 0.61f) { sceneScale = Math.max(0.6f, sceneScale - 0.1f); sceneW = 0; lastScaleT = now; }
                else if (fps >= aim - 1.5f && sceneScale < 0.99f && now - lastScaleT > 9000) { sceneScale = Math.min(1f, sceneScale + 0.1f); sceneW = 0; lastScaleT = now; }
            } else if (sceneScale < 1f) { sceneScale = 1f; sceneW = 0; }
        }

        updateView();
        int iw = imgW, ih = imgH;
        if (iw <= 0 || ih <= 0) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, vw, vh);
            GLES20.glClearColor(0, 0, 0, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            return;
        }
        if (sceneW == 0) makeScene(iw, ih);
        boolean upd;
        synchronized (lock) { upd = newFrame; newFrame = false; }
        if (upd && st != null) {
            try { st.updateTexImage(); st.getTransformMatrix(stm); } catch (Throwable ignored) { }
            // how old is this picture? the camera stamps it with the time it was taken; if that stamp makes sense, use it instead of a guess
            long lagNs = (long) camLagMs * 1_000_000L;
            try {
                long age = android.os.SystemClock.elapsedRealtimeNanos() - st.getTimestamp();
                if (age > 8_000_000L && age < 350_000_000L) {
                    camLagMeasured = camLagMeasured <= 0f ? age / 1e6f : camLagMeasured + (age / 1e6f - camLagMeasured) * 0.15f;
                    lagNs = (long) (camLagMeasured * 1e6f);
                } else camLagMeasured = 0f;
            } catch (Throwable ignored) { camLagMeasured = 0f; }
            frameRot = reproj ? rotBefore(System.nanoTime() - lagNs) : null;
        }
        int pc = panoCmd;
        if (pc != 0) {
            panoCmd = 0;
            if (pc == 2) { panoOn = false; panoBmp = null; }
            else {
                android.graphics.Bitmap pb = panoBmp; panoBmp = null;
                if (pb != null) {
                    try {
                        int[] mx = new int[1];
                        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, mx, 0);
                        int lim = Math.max(1024, mx[0]);
                        if (pb.getWidth() > lim) {
                            android.graphics.Bitmap b2 = android.graphics.Bitmap.createScaledBitmap(pb, lim, Math.max(1, (int) ((long) pb.getHeight() * lim / pb.getWidth())), true);
                            pb.recycle(); pb = b2;
                        }
                        if (panoTex == 0) { int[] t = new int[1]; GLES20.glGenTextures(1, t, 0); panoTex = t[0]; }
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, panoTex);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, pb, 0);
                        pb.recycle();
                        float[] m = rotView;                              // the picture's middle is where you are looking now
                        panoLon0 = -(float) Math.atan2(-m[2], -m[6]);
                        panoOn = true;
                    } catch (Throwable t) { panoOn = false; }
                }
            }
        }
        predictNow(now);                                    // where the hand is on screen right now (needed first: the outline mask is made before the scene picture)
        if (skel == 2) renderMask(dispDraw); else maskReady = false;
        // 1) scene picture: camera (or the 360 picture) + hand skeleton
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
        GLES20.glViewport(0, 0, sceneW, sceneH);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);         // always start clean: the GPU then does not have to load last frame's picture back in
        if (panoOn && panoTex != 0) {
            float[] m = rotView;
            rm3[0] = m[0]; rm3[1] = m[1]; rm3[2] = m[2]; rm3[3] = m[4]; rm3[4] = m[5]; rm3[5] = m[6]; rm3[6] = m[8]; rm3[7] = m[9]; rm3[8] = m[10];
            GLES20.glUseProgram(progPano);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, panoTex);
            GLES20.glUniform1i(ul(progPano, "tex"), 0);
            GLES20.glUniform2f(ul(progPano, "tn"), tanH, tanV);
            GLES20.glUniformMatrix3fv(ul(progPano, "rm"), 1, false, rm3, 0);
            GLES20.glUniform1f(ul(progPano, "lon0"), panoLon0);
            drawQuad(progPano);
        } else if (cinema) {
            GLES20.glClearColor(0.01f, 0.01f, 0.015f, 1f);          // the room goes dark: only the screen is left
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        } else {
        GLES20.glUseProgram(progCam);
        GLES20.glUniform1f(ul(progCam, "zs"), camScale);
        float[] fr = frameRot, rn0 = rotView;
        boolean wp = reproj && fr != null && rn0 != null;
        if (wp) {
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
                float sum = 0f;
                for (int k = 0; k < 3; k++) sum += fr[k * 4 + i] * rn0[k * 4 + j];
                dm3[j * 3 + i] = sum;
            }
            GLES20.glUniformMatrix3fv(ul(progCam, "dm"), 1, false, dm3, 0);
            GLES20.glUniform2f(ul(progCam, "tn"), tanH, tanV);
        }
        GLES20.glUniform1f(ul(progCam, "wp"), wp ? 1f : 0f);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex[0]);
        GLES20.glUniform1i(ul(progCam, "tex"), 0);
        GLES20.glUniformMatrix4fv(ul(progCam, "stm"), 1, false, stm, 0);
        GLES20.glUniform1f(ul(progCam, "rf"), rot180 ? 1f : 0f);
        GLES20.glUniform2f(ul(progCam, "px"), 1f / Math.max(1, imgW), 1f / Math.max(1, imgH));
        GLES20.glUniform1f(ul(progCam, "amt"), sharpen);
        drawQuad(progCam);
        }
        computePanel();
        cursor();
        drawWins();
        if (skel == 1) drawSkeleton(dispDraw); else if (skel == 2) drawOutline(dispDraw);
        drawCursor();

        // 2) screen: mono or split, with lens warp
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(progFin);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneTex[0]);
        GLES20.glUniform1i(ul(progFin, "tex"), 0);
        boolean s = stereo;
        int eyes = s ? 2 : 1;
        int ew = vw / eyes;
        float ea = (float) ew / vh, sa = (float) sceneW / sceneH;
        float scx = ea < sa ? ea / sa : 1f, scy = ea < sa ? 1f : sa / ea;
        GLES20.glUniform2f(ul(progFin, "sc"), scx, scy);
        GLES20.glUniform1f(ul(progFin, "k"), LENS_K[Math.max(0, Math.min(3, lens))]);
        GLES20.glUniform1f(ul(progFin, "ca"), stereo ? chroma : 0f);
        int ush = ul(progFin, "sh");
        for (int e = 0; e < eyes; e++) {
            GLES20.glViewport(e * ew, 0, ew, vh);
            GLES20.glUniform1f(ush, eyes == 2 ? (e == 0 ? -eyeShift : eyeShift) : 0f);   // each eye's picture is moved towards the nose so its middle sits in front of that lens
            drawQuad(progFin);
        }
    }
}
