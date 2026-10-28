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
    public volatile float fps = 0;
    public volatile boolean predict = true;
    public volatile int skel = 1;           // 0 off, 1 skeleton, 2 glowing outline (like the web version)
    public volatile float predMs = 0;
    public NvUi ui;
    private int progUi, uiTex = 0;
    private volatile float[] rotNow = new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    public volatile boolean recenter = false;
    private final float[] mP = new float[16], mM = new float[16];
    private float tanH = 0.65f, tanV = 0.37f;
    private static final float PANEL_D = 1.5f;
    private int progPanel;
    private float cnx = 0, cny = 0;
    public void setRotation(float[] m) { rotNow = m.clone(); }
    public float[] rotCopy() { return rotNow; }
    public volatile NvMotion motion;
    private final float[] tE = new float[16], tF = new float[16];
    private void shiftBy(Win w, float[] m, float ux, float uy, float uz) {   // the user is at (ux,uy,uz); the window was set up at its base
        android.opengl.Matrix.setIdentityM(tE, 0);
        android.opengl.Matrix.translateM(tE, 0, w.wx - ux, w.wy - uy, w.wz - uz);
        android.opengl.Matrix.multiplyMM(tF, 0, tE, 0, m, 0);
        System.arraycopy(tF, 0, m, 0, 16);
    }
    public volatile boolean floaty = true;
    private final float[] tA = new float[16], tB = new float[16], tC = new float[16], tD = new float[16];

    /** A floating window in the room: it keeps its place when the phone turns, can float a little, be grabbed by its bar, and be pulled bigger or smaller. */
    public static class Win {
        public final NvContent c;
        public final float widthM;
        public volatile boolean visible = false, placeReq = false;
        public volatile float offYaw = 0, offPitch = 0;      // where it appears, relative to the view centre (degrees)
        float[] rot = null;
        float scale = 1f;
        int tex = 0;
        final float[] mv = new float[16], mvp = new float[16], inv = new float[16];
        float wx, wy, wz;                                  // where the user stood when this window was put up (walk-around)
        float yawA, pitchA, dragYawOff, dragPitchOff, dragS0 = 1f, dragSize0 = 1f;
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
        if (c instanceof NvUi) menuWin = w;
        return w;
    }

    /** show / hide a window (it appears in front of you, shifted by the given angles) */
    public void setVisible(Win w, boolean v, float offYaw, float offPitch) {
        w.offYaw = offYaw; w.offPitch = offPitch;
        if (v && !w.visible) w.placeReq = true;
        w.visible = v;
    }
    public void toggle(Win w, float offYaw, float offPitch) { setVisible(w, !w.visible, offYaw, offPitch); }
    public void hideMenu() { if (menuWin != null) menuWin.visible = false; }

    private void toggleMenuGL() {
        if (menuWin == null) return;
        if (menuWin.visible) { menuWin.visible = false; if (dragWin == menuWin) endDrag(); }
        else { menuWin.offYaw = 0; menuWin.offPitch = 0; menuWin.placeReq = true; menuWin.visible = true; if (ui != null) ui.setOpen(true); }
    }

    private boolean anyVisible() { for (Win w : wins) if (w.visible) return true; return false; }

    private void computeProj() {
        float aspect = (float) sceneW / Math.max(1, sceneH);
        float hf = Math.max(0.8f, Math.min(1.9f, cam.hFov));
        tanH = (float) Math.tan(hf / 2f);
        tanV = tanH / aspect;
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

    private void updateWin(Win w, float[] rn, float t) {
        NvMotion mo = motion;
        float ux = 0, uy = 0, uz = 0;
        if (mo != null && mo.enabled) { ux = mo.px; uy = mo.py; uz = mo.pz; }
        if (w.placeReq || w.rot == null) {
            android.opengl.Matrix.transposeM(tA, 0, rn, 0);
            w.rot = tA.clone();
            if (w.offYaw != 0f || w.offPitch != 0f) turnWin(w, rn, -w.offYaw, w.offPitch);
            w.scale = 1f;
            w.wx = ux; w.wy = uy; w.wz = uz;
            w.placeReq = false;
        }
        // where the window centre is now, seen from the phone: angle to the right and angle upwards
        android.opengl.Matrix.translateM(mM, 0, w.rot, 0, 0f, 0f, -PANEL_D);
        shiftBy(w, mM, ux, uy, uz);
        android.opengl.Matrix.multiplyMM(w.mv, 0, rn, 0, mM, 0);
        w.yawA = (float) Math.toDegrees(Math.atan2(w.mv[12], -w.mv[14]));
        w.pitchA = (float) Math.toDegrees(Math.atan2(w.mv[13], -w.mv[14]));
        if (dragWin == w && !resizing) {
            float th = (w.yawA - (rayYaw + w.dragYawOff)) * 0.5f;
            float ph = ((rayPitch + w.dragPitchOff) - w.pitchA) * 0.5f;
            th = Math.max(-5f, Math.min(5f, th));              // at most a few degrees per frame, so nothing can snap away
            ph = Math.max(-5f, Math.min(5f, ph));
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
        android.opengl.Matrix.translateM(mM, 0, w.rot, 0, bx, by, -PANEL_D + bz);
        shiftBy(w, mM, ux, uy, uz);
        android.opengl.Matrix.multiplyMM(w.mv, 0, rn, 0, mM, 0);
        android.opengl.Matrix.multiplyMM(w.mvp, 0, mP, 0, w.mv, 0);
        android.opengl.Matrix.invertM(w.inv, 0, w.mv, 0);
    }

    private void computePanel() {
        float[] rn = rotNow;
        if (recenter) { recenter = false; if (menuWin != null) menuWin.placeReq = true; }
        computeProj();
        float t = (System.nanoTime() / 1e9f) % 600f;
        for (Win w : wins) if (w.visible) updateWin(w, rn, t);
    }

    // where the pointing finger meets the window, in that window's picture pixels
    private boolean hitWin(Win w, float nx, float ny, float[] out) {
        android.graphics.Bitmap bm = w.c.bitmap();
        if (bm == null) return false;
        float dx = nx * tanH, dy = ny * tanV, dz = -1f;
        float[] mInv = w.inv;
        float ox = mInv[12], oy = mInv[13], oz = mInv[14];
        float Dz = mInv[2] * dx + mInv[6] * dy + mInv[10] * dz;
        float Dx = mInv[0] * dx + mInv[4] * dy + mInv[8] * dz;
        float Dy = mInv[1] * dx + mInv[5] * dy + mInv[9] * dz;
        if (Math.abs(Dz) < 1e-5f) return false;
        float t = -oz / Dz;
        if (t <= 0) return false;
        float lx = ox + t * Dx, ly = oy + t * Dy;
        float pw = w.widthM * w.scale;
        float ph = pw * bm.getHeight() / bm.getWidth();
        float u = lx / pw + 0.5f, v = 0.5f - ly / ph;
        if (u < -0.05f || u > 1.05f || v < -0.05f || v > 1.05f) return false;
        out[0] = u * bm.getWidth();
        out[1] = v * bm.getHeight();
        return true;
    }

    private void endDrag() {
        if (dragWin != null) dragWin.c.setDragging(false);
        dragWin = null;
        resizing = false;
    }

    private float[] lastRes, vel = new float[1];
    private long tLast = 0;
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

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
        progCam = prog(
            "attribute vec2 p; uniform mat4 stm; uniform float rf; varying vec2 uv;" +
            "void main(){ gl_Position=vec4(p,0.,1.); vec2 t=p*0.5+0.5; t=mix(t,1.-t,rf); uv=vec2(t.x,1.-t.y); }",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES tex; varying vec2 uv;" +
            "void main(){ gl_FragColor=texture2D(tex,uv); }");
        progCol = prog(
            "attribute vec2 p; uniform float ps; void main(){ gl_Position=vec4(p,0.,1.); gl_PointSize=ps; }",
            "precision mediump float; uniform vec4 col; uniform float rnd; void main(){ if (rnd>0.5 && length(gl_PointCoord-0.5)>0.5) discard; gl_FragColor=col; }");
        progFin = prog(
            "attribute vec2 p; varying vec2 c; void main(){ gl_Position=vec4(p,0.,1.); c=p; }",
            "precision mediump float; uniform sampler2D tex; uniform vec2 sc; uniform float k; uniform float sh; varying vec2 c;" +
            "void main(){ vec2 q=c; q.x+=sh; float r2=dot(q,q); q*=1.+k*r2; vec2 uv=0.5+0.5*q*sc;" +
            " if(uv.x<0.||uv.x>1.||uv.y<0.||uv.y>1.){ gl_FragColor=vec4(0.,0.,0.,1.); } else { gl_FragColor=vec4(texture2D(tex,uv).rgb,1.); } }");
        progUi = prog(
            "attribute vec2 p; varying vec2 uv; void main(){ gl_Position=vec4(p,0.,1.); uv=vec2(p.x*0.5+0.5, 0.5-p.y*0.5); }",
            "precision mediump float; uniform sampler2D tex; varying vec2 uv; void main(){ gl_FragColor=texture2D(tex,uv); }");
        uiTex = 0;
        for (Win w : wins) w.tex = 0;
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
            "precision mediump float; uniform vec3 col; uniform float sf; varying vec4 vq;" +
            "void main(){ float t=clamp(vq.x,0.,vq.z); float d=length(vec2(vq.x-t,vq.y)); float al=clamp((1.-d)*vq.w/sf,0.,1.); gl_FragColor=vec4(col*al,al); }");
        aaBuf = fb(6 * 6 * 60);
        triBuf = fb(4096);
        maskW = 0; maskFbo[0] = 0; maskTex[0] = 0;
        progPanel = prog(
            "attribute vec2 p; uniform mat4 mvp; uniform vec2 hs; varying vec2 uv; void main(){ gl_Position=mvp*vec4(p*hs,0.,1.); uv=vec2(p.x*0.5+0.5, 0.5-p.y*0.5); }",
            "precision mediump float; uniform sampler2D tex; varying vec2 uv; void main(){ gl_FragColor=texture2D(tex,uv); }");
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
        int w = Math.min(iw, 1280);
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

    // the hand model answers about 10 times a second; between answers the points are moved on by their last measured speed
    private void predictNow(long now) {
        float[] r = cam.result;
        if (r != lastRes) {
            float[] prev = lastRes;
            long tPrev = tLast;
            lastRes = r; tLast = now;
            if (vel.length != r.length) vel = new float[r.length];
            if (prev != null && prev.length == r.length && r[0] >= 1 && now - tPrev > 10 && now - tPrev < 350) {
                float dt = (now - tPrev) / 1000f;
                for (int i = 1; i < r.length; i++) {
                    if ((i - 1) % 3 == 2) continue;
                    float v = (r[i] - prev[i]) / dt;
                    v = Math.max(-3f, Math.min(3f, v));
                    vel[i] = vel[i] * 0.5f + v * 0.5f;
                }
            } else {
                java.util.Arrays.fill(vel, 0f);
            }
        }
        if (disp.length != r.length) disp = new float[r.length];
        System.arraycopy(r, 0, disp, 0, r.length);
        if (predict && r[0] >= 1) {
            float lead = Math.min(now - tLast + 40, 130) / 1000f;
            predMs = lead * 1000f;
            for (int i = 1; i < r.length; i++) {
                if ((i - 1) % 3 == 2) continue;
                disp[i] = r[i] + vel[i] * lead * 0.8f;
            }
        } else predMs = 0;
    }

    private void drawWins() {
        for (Win w : wins) {
            if (!w.visible) continue;
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
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bm, 0);
            }
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUseProgram(progPanel);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, w.tex);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(progPanel, "tex"), 0);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progPanel, "mvp"), 1, false, w.mvp, 0);
            float pw = w.widthM * w.scale;
            GLES20.glUniform2f(GLES20.glGetUniformLocation(progPanel, "hs"), pw / 2f, pw * bm.getHeight() / bm.getWidth() / 2f);
            drawQuad(progPanel);
            GLES20.glDisable(GLES20.GL_BLEND);
        }
    }

    // finger pointer: index fingertip; pinch = thumb tip close to index tip (relative to hand size)
    private boolean pinching = false;
    private long lastPinchT = 0, lostT = 0, mpSince = 0;
    private float sizeEma = 0, dnx = 0, dny = 0;
    private boolean resizing = false, gripHot = false;
    private float rsD0 = 1f;
    private long dragT0 = 0;
    private boolean mpOn = false, mpLatched = false;
    private float mpProg = 0, mpNx = 0, mpNy = 0;

    private void cursor() {
        float[] r = disp;
        long nowMs = System.currentTimeMillis();
        boolean hand = r.length >= 64 && r[0] >= 1;
        if (!hand) {
            curOk = false;
            mpOn = false; mpSince = 0; mpProg = 0; mpLatched = false;
            if (lostT == 0) lostT = nowMs;
            // a short loss of the hand (detection blink) must not drop a grabbed window
            if (!(dragWin != null && nowMs - lostT < 400)) { pinching = false; prevPinch = false; endDrag(); }
            for (Win w : wins) if (w.visible) w.c.update(false, 0, 0, false);
            return;
        }
        lostT = 0;
        float ar = (float) Math.max(1, imgW) / Math.max(1, imgH);
        float tx = r[1 + 8 * 3], ty = r[1 + 8 * 3 + 1];
        float ax = r[1 + 4 * 3], ay = r[1 + 4 * 3 + 1];
        float kx = r[1 + 12 * 3], ky = r[1 + 12 * 3 + 1];
        float wx = r[1], wy = r[2], mx = r[1 + 9 * 3], my = r[1 + 9 * 3 + 1];
        float size = (float) Math.hypot((mx - wx) * ar, my - wy);
        sizeEma = sizeEma <= 0f ? size : sizeEma + (size - sizeEma) * 0.15f;
        // while moving a window the pointer is the middle of the knuckles: it stays steady when the fingers pinch
        float kxn = (r[1 + 5 * 3] + r[1 + 9 * 3] + r[1 + 13 * 3]) / 3f, kyn = (r[1 + 5 * 3 + 1] + r[1 + 9 * 3 + 1] + r[1 + 13 * 3 + 1]) / 3f;
        if (rot180) { kxn = 1f - kxn; kyn = 1f - kyn; }
        float tnx = kxn * 2f - 1f, tny = 1f - kyn * 2f;
        float d = (float) Math.hypot((tx - ax) * ar, ty - ay);
        float ratio = size > 1e-4f ? d / size : 9f;
        float dm = (float) Math.hypot((kx - ax) * ar, ky - ay);
        float ratioM = size > 1e-4f ? dm / size : 9f;
        if (pinching) { if (ratio > 0.30f) pinching = false; } else { if (ratio < 0.17f) pinching = true; }
        if (pinching) lastPinchT = nowMs;

        // thumb + middle finger held together for a moment: show / hide the menu
        mpOn = mpOn ? (ratioM < 0.32f) : (ratioM < 0.20f && ratio > 0.35f);
        if (mpOn && !mpLatched) {
            if (mpSince == 0) mpSince = nowMs;
            mpProg = Math.min(1f, (nowMs - mpSince) / 700f);
            if (mpProg >= 1f) { toggleMenuGL(); mpLatched = true; mpProg = 0; }
        } else if (!mpOn) { mpSince = 0; mpProg = 0; mpLatched = false; }
        float mhx = (kx + ax) * 0.5f, mhy = (ky + ay) * 0.5f;
        if (rot180) { mhx = 1f - mhx; mhy = 1f - mhy; }
        mpNx = mhx * 2f - 1f; mpNy = 1f - mhy * 2f;

        // the pointer sits between thumb tip and index tip, so closing the fingers does not push it off the target
        float hx = (tx + ax) * 0.5f, hy = (ty + ay) * 0.5f;
        if (rot180) { hx = 1f - hx; hy = 1f - hy; }
        float nx = hx * 2f - 1f, ny = 1f - hy * 2f;
        if (!curOk) { cnx = nx; cny = ny; } else { cnx += (nx - cnx) * 0.6f; cny += (ny - cny) * 0.6f; }
        curOk = true;
        if (dragWin != null) {
            float ddx = tnx - dnx, ddy = tny - dny;
            float dl = (float) Math.hypot(ddx, ddy);
            float stp = Math.min(dl * 0.35f, 0.05f);                     // smooth, and never a sudden big jump
            if (dl > 1e-5f) { dnx += ddx / dl * stp; dny += ddy / dl * stp; }
            rayYaw = (float) Math.toDegrees(Math.atan(dnx * tanH));
            rayPitch = (float) Math.toDegrees(Math.atan(dny * tanV));
        } else {
            rayYaw = (float) Math.toDegrees(Math.atan(cnx * tanH));
            rayPitch = (float) Math.toDegrees(Math.atan(cny * tanV));
        }

        float[] o = new float[2];
        Win target = null, gripWin = null;
        if (dragWin == null && !mpOn) {
            for (int i = wins.size() - 1; i >= 0; i--) {                 // the one drawn last is in front
                Win w = wins.get(i);
                if (!w.visible || !hitWin(w, cnx, cny, o)) continue;
                if (w.c.inGrip(o[0], o[1])) { gripWin = w; break; }      // a bottom corner: pull it to resize
                if (w.c.covers(o[0], o[1])) { target = w; break; }
            }
        }
        gripHot = gripWin != null || (dragWin != null && resizing);
        if (gripWin != null && pinching && !prevPinch) {
            dragWin = gripWin; resizing = true;
            dnx = tnx; dny = tny;
            rayYaw = (float) Math.toDegrees(Math.atan(dnx * tanH)); rayPitch = (float) Math.toDegrees(Math.atan(dny * tanV));
            rsD0 = Math.max(3f, (float) Math.hypot(rayYaw - gripWin.yawA, rayPitch - gripWin.pitchA));
            gripWin.dragS0 = gripWin.scale;
            gripWin.c.setDragging(true);
            wins.remove(gripWin); wins.add(gripWin);
        }
        if (target != null && pinching && !prevPinch && target.c.handleRecent()) {      // grabbed the bar under a window
            dragWin = target;
            dnx = tnx; dny = tny;
            float gy = (float) Math.toDegrees(Math.atan(dnx * tanH)), gp = (float) Math.toDegrees(Math.atan(dny * tanV));
            rayYaw = gy; rayPitch = gp;
            target.dragYawOff = target.yawA - gy;
            target.dragPitchOff = target.pitchA - gp;
            target.dragS0 = target.scale;
            target.dragSize0 = Math.max(1e-3f, sizeEma);
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
        // let go only when the pinch has been open for a moment (one bad detection must not drop the window)
        if (dragWin != null && !pinching && nowMs - lastPinchT > 250) endDrag();
        prevPinch = pinching;
        for (Win w : wins) {
            if (!w.visible) continue;
            if (w == target && dragWin == null) w.c.update(true, o[0], o[1], pinching);
            else w.c.update(false, 0, 0, false);
        }
    }

    private void drawCursor() {
        float u = sceneH / 540f;
        if (mpProg > 0f) {                                   // growing dot while the menu gesture is being held
            float x = mpNx * sceneW / 2f, y = mpNy * sceneH / 2f;
            aaBegin();
            aaSeg(x, y, x, y, (8f + 20f * mpProg) * u);
            aaFlush(1f, 0.7f, 0.15f);
        }
        if (!curOk || !anyVisible()) return;
        float x = cnx * sceneW / 2f, y = cny * sceneH / 2f;
        boolean grab = dragWin != null;
        aaBegin();
        aaSeg(x, y, x, y, (pinching ? 15f : 19f) * u);
        if (gripHot) aaFlush(0.13f, 0.72f, 0.81f); else aaFlush(pinching ? 1f : 0.15f, pinching ? 0.55f : 0.85f, pinching ? 0f : 1f);
        aaBegin();
        aaSeg(x, y, x, y, 7f * u);
        aaFlush(1f, 1f, 1f);
    }

    private void drawQuad(int prog) {
        int a = GLES20.glGetAttribLocation(prog, "p");
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
    private void drawOutline(float[] r) {
        int n = (int) r[0];
        if (n <= 0) return;
        ensureMask();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, maskFbo[0]);
        GLES20.glViewport(0, 0, maskW, maskH);
        GLES20.glClearColor(0, 0, 0, 0);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        float hwS = sceneW / 2f, hhS = sceneH / 2f;
        for (int i = 0; i < n; i++) {
            float[] X = new float[21], Y = new float[21];
            for (int k = 0; k < 21; k++) {
                float hx = r[1 + i * 63 + k * 3], hy = r[1 + i * 63 + k * 3 + 1];
                if (rot180) { hx = 1f - hx; hy = 1f - hy; }
                X[k] = (hx * 2f - 1f) * hwS; Y[k] = (1f - hy * 2f) * hhS;
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
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
        GLES20.glViewport(0, 0, sceneW, sceneH);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUseProgram(progRing);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex[0]);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progRing, "tex"), 0);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progRing, "px"), 1f / maskW, 1f / maskH);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progRing, "rad"), 2.4f);
        drawQuad(progRing);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    // ---- soft-edged lines and dots: every stroke is a small quad whose edge fades over ~1.5 px, so nothing looks jagged ----
    private int progAA;
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
        float[][] c = {
            {x0 - dx * pad + nx * pad, y0 - dy * pad + ny * pad, -pa, pa},
            {x0 - dx * pad - nx * pad, y0 - dy * pad - ny * pad, -pa, -pa},
            {x1 + dx * pad + nx * pad, y1 + dy * pad + ny * pad, lh + pa, pa},
            {x1 + dx * pad - nx * pad, y1 + dy * pad - ny * pad, lh + pa, -pa}};
        int[] idx = {0, 1, 2, 1, 3, 2};
        for (int k : idx) {
            aaBuf.put(c[k][0] / hwS).put(c[k][1] / hhS).put(c[k][2]).put(c[k][3]).put(lh).put(hw);
            aaN++;
        }
    }

    private void aaFlush(float cr, float cg, float cb) {
        if (aaN == 0) return;
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUseProgram(progAA);
        int ap = GLES20.glGetAttribLocation(progAA, "p"), aq = GLES20.glGetAttribLocation(progAA, "q");
        GLES20.glEnableVertexAttribArray(ap);
        GLES20.glEnableVertexAttribArray(aq);
        aaBuf.position(0);
        GLES20.glVertexAttribPointer(ap, 2, GLES20.GL_FLOAT, false, 24, aaBuf);
        aaBuf.position(2);
        GLES20.glVertexAttribPointer(aq, 4, GLES20.GL_FLOAT, false, 24, aaBuf);
        GLES20.glUniform3f(GLES20.glGetUniformLocation(progAA, "col"), cr, cg, cb);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progAA, "sf"), aaSoft);
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
            float[] X = new float[21], Y = new float[21];
            for (int k = 0; k < 21; k++) {
                float hx = r[1 + i * 63 + k * 3], hy = r[1 + i * 63 + k * 3 + 1];
                if (rot180) { hx = 1f - hx; hy = 1f - hy; }
                X[k] = (hx * 2f - 1f) * hwS; Y[k] = (1f - hy * 2f) * hhS;
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
        long now = System.nanoTime() / 1000000L;
        fc++;
        if (ft0 == 0) ft0 = now;
        if (now - ft0 >= 1000) { fps = fc * 1000f / (now - ft0); fc = 0; ft0 = now; }

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
        }
        // 1) scene picture: camera + hand skeleton
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
        GLES20.glViewport(0, 0, sceneW, sceneH);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(progCam);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex[0]);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progCam, "tex"), 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progCam, "stm"), 1, false, stm, 0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progCam, "rf"), rot180 ? 1f : 0f);
        drawQuad(progCam);
        predictNow(now);
        computePanel();
        cursor();
        drawWins();
        if (skel == 1) drawSkeleton(disp); else if (skel == 2) drawOutline(disp);
        drawCursor();

        // 2) screen: mono or split, with lens warp
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(progFin);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneTex[0]);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progFin, "tex"), 0);
        boolean s = stereo;
        int eyes = s ? 2 : 1;
        int ew = vw / eyes;
        float ea = (float) ew / vh, sa = (float) sceneW / sceneH;
        float scx = ea < sa ? ea / sa : 1f, scy = ea < sa ? 1f : sa / ea;
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progFin, "sc"), scx, scy);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progFin, "k"), LENS_K[Math.max(0, Math.min(3, lens))]);
        int ush = GLES20.glGetUniformLocation(progFin, "sh");
        for (int e = 0; e < eyes; e++) {
            GLES20.glViewport(e * ew, 0, ew, vh);
            GLES20.glUniform1f(ush, 0f);
            drawQuad(progFin);
        }
    }
}
