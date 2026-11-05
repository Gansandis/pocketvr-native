package com.pocketvr.app;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * The look shared by every floating window (frosted glass, soft white rim lit from above, white-on-glass buttons, pill shapes),
 * in the spirit of Apple Vision Pro's visionOS. One place, so all windows stay consistent.
 */
final class NvGlass {
    private NvGlass() { }

    static final Typeface REG = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MED = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    static final int TEXT = 0xFFFFFFFF;          // main text
    static final int TEXT2 = 0xB3FFFFFF;         // secondary text (70 %)
    static final int FILL = 0x1FFFFFFF;          // a resting control (12 %)
    static final int FILL_HOT = 0x38FFFFFF;      // pointed at (22 %)
    static final int FILL_ON = 0x4DFFFFFF;       // chosen (30 %)
    static final int FILL_PRESS = 0x66FFFFFF;    // pressed (40 %)
    static final int RING = 0xCCFFFFFF;          // ring around the thing the pointer is on
    static final int ACCENT = 0xFF0A84FF;        // blue
    static final int GREEN = 0xFF34C759;         // switch on

    /** the glass sheet: body that is a little lighter at the top, a faint sheen, and a thin rim that is brightest at the top edge */
    static void panel(Canvas cv, Paint p, RectF r, float rad, float u) {
        int a = NvUi.glass;
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, (a << 24) | 0x4A4F5E, (a << 24) | 0x22252E, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        cv.drawRoundRect(r, rad, rad, p);
        p.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * 0.4f, 0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawRoundRect(r, rad, rad, p);
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xAAFFFFFF, 0x26FFFFFF, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.6f * u);
        cv.drawRoundRect(r, rad, rad, p);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
    }

    static void fill(Canvas cv, Paint p, RectF r, int color, float rad) {
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(color);
        cv.drawRoundRect(r, rad, rad, p);
    }

    static void ring(Canvas cv, Paint p, RectF r, int color, float w, float rad) {
        p.setShader(null); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(w); p.setColor(color);
        cv.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** a control on the glass: fill by state, hairline rim, and a ring when pointed at */
    static void control(Canvas cv, Paint p, RectF r, float rad, float u, boolean on, boolean hot, boolean pressed) {
        controlA(cv, p, r, rad, u, on, hot ? 1f : 0f, pressed ? 1f : 0f);
    }

    static int lerp(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int out = 0;
        for (int sh = 0; sh <= 24; sh += 8) {
            int ca = (a >>> sh) & 0xFF, cb = (b >>> sh) & 0xFF;
            out |= (Math.round(ca + (cb - ca) * t) & 0xFF) << sh;
        }
        return out;
    }

    /** same, with the pointed-at and pressed states as 0..1 amounts that are eased by the caller (so the change glides instead of jumping) */
    static void controlA(Canvas cv, Paint p, RectF r, float rad, float u, boolean on, float hot, float press) {
        int base = on ? FILL_ON : FILL;
        int c = lerp(base, on ? 0x5CFFFFFF : FILL_HOT, hot);
        c = lerp(c, FILL_PRESS, press);
        fill(cv, p, r, c, rad);
        ring(cv, p, r, lerp(on ? 0x99FFFFFF : 0x22FFFFFF, RING, hot), (1.2f + 1.2f * hot) * u, rad);
    }

    /** a circle badge (an icon on a soft coloured disc) */
    static void badge(Canvas cv, Paint p, RectF r, int c1, int c2) {
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, c1, c2, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        cv.drawOval(r, p);
        p.setShader(null);
    }

    static void font(Paint p, boolean medium) { p.setTypeface(medium ? MED : REG); }
}
