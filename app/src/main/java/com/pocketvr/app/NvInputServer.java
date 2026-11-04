package com.pocketvr.app;

import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Runs as the shell user (started through Shizuku with app_process) and turns lines it reads into real touch / key events on one display:
 *   d x y displayId   finger down        m x y displayId   finger moves        u x y displayId   finger up
 *   k keycode displayId   a key press
 * Much faster than starting the "input" command for every tap, so dragging and scrolling follow the hand.
 * It prints PVR-READY when it can inject events. It stops by itself when PocketVR closes its end of the pipe.
 */
public class NvInputServer {
    private static Object im;
    private static Method inject, setDisplay;
    private static long downTime = 0;

    private static void send(InputEvent e, int display) throws Exception {
        setDisplay.invoke(e, display);
        inject.invoke(im, e, 0);                           // 0 = do not wait for the app to take it
    }

    private static void touch(int action, float x, float y, int display) throws Exception {
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) downTime = now;
        MotionEvent e = MotionEvent.obtain(downTime, now, action, x, y, 1f, 1f, 0, 1f, 1f, 0, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        send(e, display);
        e.recycle();
    }

    private static void key(int code, int display) throws Exception {
        long now = SystemClock.uptimeMillis();
        for (int a = 0; a < 2; a++) {
            KeyEvent k = new KeyEvent(now, now, a == 0 ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, code, 0);
            k.setSource(InputDevice.SOURCE_KEYBOARD);
            send(k, display);
        }
    }

    public static void main(String[] args) {
        try {
            Method gi = InputManager.class.getDeclaredMethod("getInstance");
            gi.setAccessible(true);
            im = gi.invoke(null);
            inject = InputManager.class.getDeclaredMethod("injectInputEvent", InputEvent.class, int.class);
            inject.setAccessible(true);
            setDisplay = InputEvent.class.getDeclaredMethod("setDisplayId", int.class);
            setDisplay.setAccessible(true);
        } catch (Throwable t) {
            System.out.println("PVR-ERR setup " + t);
            System.out.flush();
            return;
        }
        System.out.println("PVR-READY");
        System.out.flush();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = r.readLine()) != null) {
                try {
                    String[] p = line.trim().split(" ");
                    if (p.length < 3) continue;
                    char c = p[0].charAt(0);
                    if (c == 'k') key(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                    else if (p.length >= 4) {
                        float x = Float.parseFloat(p[1]), y = Float.parseFloat(p[2]);
                        int d = Integer.parseInt(p[3]);
                        touch(c == 'd' ? MotionEvent.ACTION_DOWN : (c == 'u' ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE), x, y, d);
                    }
                } catch (Throwable t) {
                    System.out.println("PVR-ERR " + t);
                    System.out.flush();
                }
            }
        } catch (Throwable ignored) { }
    }
}
