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
    default boolean inGrip(float x, float y) { return false; }   // a bottom corner of the window: pulling it resizes
    default float visFrac() { return 1f; }
    default boolean occupies() { return true; }          // false: nothing of it is in the way of other windows (the menu when it is folded to its dock)       // how much of the picture's width is really drawn (centred): a window beside it sits against that, not against the empty margin
    boolean covers(float x, float y);          // is something drawn here (false = see-through, pointer goes to the window behind)
}
