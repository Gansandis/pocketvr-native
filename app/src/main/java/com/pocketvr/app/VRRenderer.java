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

    private volatile int imgW = 0, imgH = 0;
    private volatile boolean rot180 = false;
    private int sceneW = 0, sceneH = 0;
    private int[] fbo = new int[1], sceneTex = new int[1], camTex = new int[1];
    private SurfaceTexture st;
    private final Object lock = new Object();
    private boolean newFrame = false;
    private final float[] stm = new float[16];
    private int vw = 1, vh = 1;
    private int progCam, progCol, progFin;
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
            "void main(){ gl_Position=vec4(p,0.,1.); vec2 t=p*0.5+0.5; t=mix(t,1.-t,rf); uv=(stm*vec4(t,0.,1.)).xy; }",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES tex; varying vec2 uv;" +
            "void main(){ gl_FragColor=texture2D(tex,uv); }");
        progCol = prog(
            "attribute vec2 p; uniform float ps; void main(){ gl_Position=vec4(p,0.,1.); gl_PointSize=ps; }",
            "precision mediump float; uniform vec4 col; void main(){ gl_FragColor=col; }");
        progFin = prog(
            "attribute vec2 p; varying vec2 c; void main(){ gl_Position=vec4(p,0.,1.); c=p; }",
            "precision mediump float; uniform sampler2D tex; uniform vec2 sc; uniform float k; uniform float sh; varying vec2 c;" +
            "void main(){ vec2 q=c; q.x+=sh; float r2=dot(q,q); q*=1.+k*r2; vec2 uv=0.5+0.5*q*sc;" +
            " if(uv.x<0.||uv.x>1.||uv.y<0.||uv.y>1.){ gl_FragColor=vec4(0.,0.,0.,1.); } else { gl_FragColor=vec4(texture2D(tex,uv).rgb,1.); } }");
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
        int w = Math.min(iw, 960);
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
    }

    private void drawQuad(int prog) {
        int a = GLES20.glGetAttribLocation(prog, "p");
        GLES20.glEnableVertexAttribArray(a);
        GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(a);
    }

    private void drawSkeleton(float[] r) {
        int n = (int) r[0];
        if (n <= 0) return;
        boolean flip = rot180;
        GLES20.glUseProgram(progCol);
        int a = GLES20.glGetAttribLocation(progCol, "p");
        int uc = GLES20.glGetUniformLocation(progCol, "col");
        int up = GLES20.glGetUniformLocation(progCol, "ps");
        GLES20.glEnableVertexAttribArray(a);
        for (int i = 0; i < n; i++) {
            float[] x = new float[21], y = new float[21];
            for (int k = 0; k < 21; k++) {
                float hx = r[1 + i * 63 + k * 3], hy = r[1 + i * 63 + k * 3 + 1];
                if (flip) { hx = 1f - hx; hy = 1f - hy; }
                x[k] = hx * 2f - 1f; y[k] = 1f - hy * 2f;
            }
            lineBuf.position(0);
            for (int b = 0; b < BONES.length; b++) { lineBuf.put(x[BONES[b]]); lineBuf.put(y[BONES[b]]); }
            lineBuf.position(0);
            GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, lineBuf);
            GLES20.glUniform1f(up, 1f);
            GLES20.glLineWidth(7f);
            GLES20.glUniform4f(uc, 0f, 0.5f, 0.7f, 1f);
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, BONES.length);
            GLES20.glLineWidth(3f);
            GLES20.glUniform4f(uc, 0.4f, 1f, 1f, 1f);
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, BONES.length);
            ptBuf.position(0);
            for (int k = 0; k < 21; k++) { ptBuf.put(x[k]); ptBuf.put(y[k]); }
            ptBuf.position(0);
            GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, ptBuf);
            GLES20.glUniform1f(up, 10f);
            GLES20.glUniform4f(uc, 1f, 1f, 1f, 1f);
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 21);
        }
        GLES20.glDisableVertexAttribArray(a);
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
        drawSkeleton(cam.result);

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
