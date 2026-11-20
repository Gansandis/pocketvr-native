package com.pocketvr.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * The picture of the game's numbers: hp, ammo, score, wave, and the title / game-over screens.
 * It is only a bitmap (bitmap() + takeDirty(), like NvContent) plus a little GL code to show it as a floating panel.
 * When merged into PocketVR the bitmap can be shown by a normal NvContent window instead and the GL part is simply not used.
 */
public final class NvGunHud {
    public static final int W = 640, H = 170;
    private final NvGunCore core;
    private final Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Canvas cv = new Canvas(bmp);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    // what the picture was last drawn from: when any of it changes the picture is drawn again
    private int lState = -1, lHp = -1, lAmmo = -1, lScore = -1, lWave = -1, lCombo = -1, lWeapon = -1, lRel = -1, lBest = -1;
    private boolean lBanner = false;
    public String hint = "";                 // a short line of help shown at the bottom while playing (set by NvGunWorld)
    private String lHint = "";
    private boolean forceDraw = true;

    public NvGunHud(NvGunCore core) { this.core = core; }

    public Bitmap bitmap() { return bmp; }
    public void markDirty() { forceDraw = true; }

    /** true = the picture changed and must be uploaded again */
    public boolean takeDirty() {
        NvGunCore c = core;
        int rel = c.reloadT > 0f ? 1 + (int) (c.reloadProgress() * 20f) : 0;
        boolean ban = c.bannerT > 0f;
        int ammo = c.ammo[c.weapon];
        boolean same = !forceDraw && lState == c.state && lHp == c.hp && lAmmo == ammo && lScore == c.score && lWave == c.wave
                && lCombo == c.combo && lWeapon == c.weapon && lRel == rel && lBanner == ban && lBest == NvGunCore.best && lHint.equals(hint);
        if (same) return false;
        forceDraw = false;
        lState = c.state; lHp = c.hp; lAmmo = ammo; lScore = c.score; lWave = c.wave; lCombo = c.combo; lWeapon = c.weapon;
        lRel = rel; lBanner = ban; lBest = NvGunCore.best; lHint = hint;
        draw();
        return true;
    }

    private void text(String s, float x, float y, float size, int color, Paint.Align al, boolean bold) {
        s = Tr.t(s);
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(color); p.setTextSize(size); p.setTextAlign(al);
        p.setFakeBoldText(bold);
        cv.drawText(s, x, y + size * 0.35f, p);
    }

    private void box(float l, float t, float rr, float b, float rad, int color) {
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(color);
        r.set(l, t, rr, b);
        cv.drawRoundRect(r, rad, rad, p);
    }

    private void draw() {
        NvGunCore c = core;
        cv.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
        box(2, 2, W - 2, H - 2, 34f, 0xA60B1018);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2f); p.setColor(0x44FFFFFF);
        r.set(2, 2, W - 2, H - 2); cv.drawRoundRect(r, 34f, 34f, p);

        if (c.state == NvGunCore.TITLE) {
            text("ยิงมอนสเตอร์ FPS", W / 2f, 52, 40f, 0xFFFFFFFF, Paint.Align.CENTER, true);
            text("กดเริ่มเล่นด้านล่าง แล้วมองลงหาปืน", W / 2f, 106, 26f, 0xFFB8D4FF, Paint.Align.CENTER, false);
            if (NvGunCore.best > 0) text("คะแนนสูงสุด " + NvGunCore.best, W / 2f, 142, 22f, 0xFFFFE27A, Paint.Align.CENTER, false);
            return;
        }
        if (c.state == NvGunCore.OVER) {
            text("พ่ายแพ้", W / 2f, 44, 40f, 0xFFFF7A7A, Paint.Align.CENTER, true);
            text("คะแนน " + c.score + "   รอบที่ " + c.wave + "   ฆ่า " + c.kills, W / 2f, 92, 28f, 0xFFFFFFFF, Paint.Align.CENTER, false);
            text("คะแนนสูงสุด " + NvGunCore.best + "   กดเล่นอีกครั้งด้านล่าง", W / 2f, 134, 22f, 0xFFFFE27A, Paint.Align.CENTER, false);
            return;
        }

        // ---- left: hp ----
        float hpF = Math.max(0f, Math.min(1f, c.hp / (float) c.maxHp));
        text("HP  " + c.hp, 30, 38, 26f, 0xFFFFFFFF, Paint.Align.LEFT, true);
        box(30, 66, 230, 92, 13f, 0x55FFFFFF);
        int col = hpF > 0.5f ? 0xFF4CD16B : (hpF > 0.25f ? 0xFFFFC24D : 0xFFFF5C5C);
        if (hpF > 0f) box(30, 66, 30 + 200 * hpF, 92, 13f, col);
        text("รอบที่ " + c.wave, 30, 128, 24f, 0xCCFFFFFF, Paint.Align.LEFT, false);

        // ---- middle: banner, or score and combo ----
        if (c.bannerT > 0f) {
            text(c.bannerBoss ? "ด่านบอส!" : "รอบที่ " + c.wave, W / 2f, 62, 44f, c.bannerBoss ? 0xFFFF7A7A : 0xFFFFE27A, Paint.Align.CENTER, true);
            text(c.bannerBoss ? "ระวังตัวใหญ่" : "เตรียมตัว", W / 2f, 112, 24f, 0xCCFFFFFF, Paint.Align.CENTER, false);
        } else {
            text("" + c.score, W / 2f, 62, 48f, 0xFFFFFFFF, Paint.Align.CENTER, true);
            if (c.combo >= 2) text("คอมโบ x" + c.combo, W / 2f, 112, 28f, 0xFFFFE27A, Paint.Align.CENTER, true);
            else text("ฆ่า " + c.kills, W / 2f, 112, 22f, 0x99FFFFFF, Paint.Align.CENTER, false);
        }

        // ---- right: ammo ----
        int w = c.weapon;
        boolean rel = c.reloadT > 0f;
        int am = c.ammo[w];
        text(rel ? "--" : "" + am, W - 150, 56, 58f, rel ? 0x88FFFFFF : (am <= 3 ? 0xFFFF7A7A : 0xFFFFFFFF), Paint.Align.RIGHT, true);
        text("/ " + NvGunCore.W_MAG[w], W - 146, 66, 26f, 0xAAFFFFFF, Paint.Align.LEFT, false);
        text(NvGunCore.W_NAME[w], W - 30, 108, 22f, 0xCCFFFFFF, Paint.Align.RIGHT, false);
        if (rel) {
            box(W - 230, 128, W - 30, 148, 10f, 0x55FFFFFF);
            box(W - 230, 128, W - 230 + 200 * c.reloadProgress(), 148, 10f, 0xFFFFB347);
        }
        if (hint.length() > 0) text(hint, W / 2f, 152, 19f, 0xCCFFE27A, Paint.Align.CENTER, false);
    }

    // ================= GL: the picture as a floating panel =================
    private int prog = 0, aP = -1, uMvp = -1, uHs = -1, uTex = -1, tex = 0;
    private FloatBuffer vb;

    public void lost() { prog = 0; tex = 0; vb = null; forceDraw = true; }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    private void init() {
        String vs = "attribute vec2 p; uniform mat4 mvp; uniform vec2 hs; varying vec2 uv;" +
                "void main(){ gl_Position=mvp*vec4(p*hs,0.,1.); uv=vec2(p.x*.5+.5,.5-p.y*.5); }";
        String fs = "precision mediump float; uniform sampler2D tex; varying vec2 uv; void main(){ gl_FragColor=texture2D(tex,uv); }";
        int pr = GLES20.glCreateProgram();
        GLES20.glAttachShader(pr, shader(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(pr, shader(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(pr);
        aP = GLES20.glGetAttribLocation(pr, "p");
        uMvp = GLES20.glGetUniformLocation(pr, "mvp"); uHs = GLES20.glGetUniformLocation(pr, "hs"); uTex = GLES20.glGetUniformLocation(pr, "tex");
        vb = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vb.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}); vb.position(0);
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        tex = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        prog = pr;
        forceDraw = true;
    }

    /** draw the panel; mvp maps the panel's unit square (-1..1) to the screen, halfW / halfH = its half size in metres */
    public void drawQuad(float[] projView, float halfW, float halfH) {
        if (prog == 0) init();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        if (takeDirty()) GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUseProgram(prog);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, projView, 0);
        GLES20.glUniform2f(uHs, halfW, halfH);
        vb.position(0);
        GLES20.glEnableVertexAttribArray(aP);
        GLES20.glVertexAttribPointer(aP, 2, GLES20.GL_FLOAT, false, 8, vb);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aP);
        GLES20.glDisable(GLES20.GL_BLEND);
    }
}
