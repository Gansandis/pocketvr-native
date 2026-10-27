package com.pocketvr.app;

import android.graphics.Bitmap;

/** Something that can be shown as a floating window: a picture, plus pointer input in that picture's pixels. */
public interface NvContent {
    Bitmap bitmap();
    boolean takeDirty();                       // true = the picture changed and must be uploaded again
    void markDirty();
    void update(boolean valid, float x, float y, boolean pinch);
    boolean handleRecent();                    // was the grab bar under the pointer a moment ago?
    void setDragging(boolean d);
    boolean covers(float x, float y);          // is something drawn here (false = see-through, pointer goes to the window behind)
}
