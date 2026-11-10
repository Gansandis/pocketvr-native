package com.pocketvr.app;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;

/** A tiny 3D helper: a lit-colour shader and a few basic solids (sphere, box, cone, cylinder; Z is up) to build creatures from. */
final class Nv3D {
    static final class Mesh { FloatBuffer vb; int n; }

    private int prog = 0, aP = -1, aN = -1, uMvp = -1, uMdl = -1, uCol = -1, uFl = -1, uAl = -1;
    Mesh sphere, box, cone, cyl;

    boolean ready() { return prog != 0; }
    void lost() { prog = 0; sphere = box = cone = cyl = null; }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    void init() {
        String vs = "attribute vec3 p; attribute vec3 n; uniform mat4 mvp; uniform mat4 mdl; varying vec3 nw;" +
                "void main(){ gl_Position=mvp*vec4(p,1.); nw=(mdl*vec4(n,0.)).xyz; }";
        String fs = "precision mediump float; uniform vec3 col; uniform float fl; uniform float al; varying vec3 nw;" +
                "void main(){ vec3 L=normalize(vec3(0.35,0.25,0.9)); float d=max(dot(normalize(nw),L),0.); vec3 c=col*(0.40+0.70*d); c=mix(c,vec3(1.),fl); gl_FragColor=vec4(c*al,al); }";
        int v = shader(GLES20.GL_VERTEX_SHADER, vs), f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
        int pr = GLES20.glCreateProgram();
        GLES20.glAttachShader(pr, v); GLES20.glAttachShader(pr, f);
        GLES20.glLinkProgram(pr);
        aP = GLES20.glGetAttribLocation(pr, "p"); aN = GLES20.glGetAttribLocation(pr, "n");
        uMvp = GLES20.glGetUniformLocation(pr, "mvp"); uMdl = GLES20.glGetUniformLocation(pr, "mdl");
        uCol = GLES20.glGetUniformLocation(pr, "col"); uFl = GLES20.glGetUniformLocation(pr, "fl"); uAl = GLES20.glGetUniformLocation(pr, "al");
        prog = pr;
        sphere = buildSphere(14, 10); box = buildBox(); cone = buildCone(16); cyl = buildCyl(16);
    }

    // ---- shapes (triangle lists: position + normal) ----
    private static final class B {
        final ArrayList<Float> a = new ArrayList<>();
        void v(float x, float y, float z, float nx, float ny, float nz) { a.add(x); a.add(y); a.add(z); a.add(nx); a.add(ny); a.add(nz); }
        void tri(float[] p, float[] q, float[] r, float[] nq) {
            v(p[0], p[1], p[2], nq[0], nq[1], nq[2]); v(q[0], q[1], q[2], nq[0], nq[1], nq[2]); v(r[0], r[1], r[2], nq[0], nq[1], nq[2]);
        }
        Mesh done() {
            Mesh m = new Mesh();
            m.n = a.size() / 6;
            ByteBuffer bb = ByteBuffer.allocateDirect(a.size() * 4).order(ByteOrder.nativeOrder());
            m.vb = bb.asFloatBuffer();
            for (float f : a) m.vb.put(f);
            m.vb.position(0);
            return m;
        }
    }

    private static Mesh buildSphere(int segs, int rings) {
        B b = new B();
        for (int i = 0; i < rings; i++) for (int j = 0; j < segs; j++) {
            float t0 = (float) (Math.PI * i / rings), t1 = (float) (Math.PI * (i + 1) / rings);
            float p0 = (float) (2 * Math.PI * j / segs), p1 = (float) (2 * Math.PI * (j + 1) / segs);
            float[] a = sp(t0, p0), c = sp(t1, p0), d = sp(t1, p1), e = sp(t0, p1);
            b.v(a[0], a[1], a[2], a[0], a[1], a[2]); b.v(c[0], c[1], c[2], c[0], c[1], c[2]); b.v(d[0], d[1], d[2], d[0], d[1], d[2]);
            b.v(a[0], a[1], a[2], a[0], a[1], a[2]); b.v(d[0], d[1], d[2], d[0], d[1], d[2]); b.v(e[0], e[1], e[2], e[0], e[1], e[2]);
        }
        return b.done();
    }
    private static float[] sp(float th, float ph) { return new float[]{(float) (Math.sin(th) * Math.cos(ph)), (float) (Math.sin(th) * Math.sin(ph)), (float) Math.cos(th)}; }

    private static Mesh buildBox() {
        B b = new B();
        float[][] nrm = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (float[] n : nrm) {
            float[] u, w;
            if (n[0] != 0) { u = new float[]{0, 1, 0}; w = new float[]{0, 0, 1}; }
            else if (n[1] != 0) { u = new float[]{1, 0, 0}; w = new float[]{0, 0, 1}; }
            else { u = new float[]{1, 0, 0}; w = new float[]{0, 1, 0}; }
            float[][] c = new float[4][3];
            int[][] sg = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
            for (int k = 0; k < 4; k++) for (int i = 0; i < 3; i++) c[k][i] = n[i] + sg[k][0] * u[i] + sg[k][1] * w[i];
            b.tri(c[0], c[1], c[2], n); b.tri(c[0], c[2], c[3], n);
        }
        return b.done();
    }

    private static Mesh buildCone(int segs) {
        B b = new B();
        for (int j = 0; j < segs; j++) {
            float p0 = (float) (2 * Math.PI * j / segs), p1 = (float) (2 * Math.PI * (j + 1) / segs);
            float c0 = (float) Math.cos(p0), s0 = (float) Math.sin(p0), c1 = (float) Math.cos(p1), s1 = (float) Math.sin(p1);
            float k = (float) (1.0 / Math.sqrt(5.0));
            b.v(0, 0, 1, (c0 + c1) * k, (s0 + s1) * k, k * 0.5f); b.v(c0, s0, -1, c0 * 2 * k, s0 * 2 * k, k); b.v(c1, s1, -1, c1 * 2 * k, s1 * 2 * k, k);
            b.v(0, 0, -1, 0, 0, -1); b.v(c1, s1, -1, 0, 0, -1); b.v(c0, s0, -1, 0, 0, -1);
        }
        return b.done();
    }

    private static Mesh buildCyl(int segs) {
        B b = new B();
        for (int j = 0; j < segs; j++) {
            float p0 = (float) (2 * Math.PI * j / segs), p1 = (float) (2 * Math.PI * (j + 1) / segs);
            float c0 = (float) Math.cos(p0), s0 = (float) Math.sin(p0), c1 = (float) Math.cos(p1), s1 = (float) Math.sin(p1);
            b.v(c0, s0, -1, c0, s0, 0); b.v(c1, s1, -1, c1, s1, 0); b.v(c1, s1, 1, c1, s1, 0);
            b.v(c0, s0, -1, c0, s0, 0); b.v(c1, s1, 1, c1, s1, 0); b.v(c0, s0, 1, c0, s0, 0);
            b.v(0, 0, 1, 0, 0, 1); b.v(c0, s0, 1, 0, 0, 1); b.v(c1, s1, 1, 0, 0, 1);
            b.v(0, 0, -1, 0, 0, -1); b.v(c1, s1, -1, 0, 0, -1); b.v(c0, s0, -1, 0, 0, -1);
        }
        return b.done();
    }

    // ---- drawing ----
    void begin() { GLES20.glUseProgram(prog); }

    void draw(Mesh m, float[] mvp, float[] mdl, int argb, float flash, float alpha) {
        if (m == null || prog == 0) return;
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uMdl, 1, false, mdl, 0);
        GLES20.glUniform3f(uCol, ((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f);
        GLES20.glUniform1f(uFl, flash);
        GLES20.glUniform1f(uAl, alpha);
        m.vb.position(0);
        GLES20.glEnableVertexAttribArray(aP);
        GLES20.glVertexAttribPointer(aP, 3, GLES20.GL_FLOAT, false, 24, m.vb);
        m.vb.position(3);
        GLES20.glEnableVertexAttribArray(aN);
        GLES20.glVertexAttribPointer(aN, 3, GLES20.GL_FLOAT, false, 24, m.vb);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, m.n);
        GLES20.glDisableVertexAttribArray(aP);
        GLES20.glDisableVertexAttribArray(aN);
        m.vb.position(0);
    }
}
