package com.wave.screentool;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

/** 滑动手势：上下滑调亮度、左右滑切换、点按、双击。 */
final class GestureHelper {
    interface Listener {
        void onBrightness(float p);
        void onBrightnessEnd(float p);
        void onTap();
        void onDoubleTap();
        void onSwipeLeft();
        void onSwipeRight();
    }

    private final Listener l;
    private final float slop, swipeDist;
    private float downX, downY, startP, lastP;
    private int mode; // 0未定 1竖向 2横向
    private long lastTapUp;

    GestureHelper(Context c, Listener l) {
        this.l = l;
        this.slop = ViewConfiguration.get(c).getScaledTouchSlop();
        this.swipeDist = Sys.dp(c, 80);
    }

    boolean onTouch(MotionEvent e, int viewH, float curP) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                startP = curP;
                lastP = curP;
                mode = 0;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                if (mode == 0 && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                    mode = Math.abs(dy) > Math.abs(dx) ? 1 : 2;
                }
                if (mode == 1) {
                    float p = startP - dy / (Math.max(1, viewH) * 0.75f);
                    p = Math.max(0f, Math.min(1f, p));
                    lastP = p;
                    l.onBrightness(p);
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (mode == 0) {
                    long now = e.getEventTime();
                    boolean dbl = now - lastTapUp < 300;
                    lastTapUp = dbl ? 0 : now;
                    l.onTap();
                    if (dbl) l.onDoubleTap();
                } else if (mode == 1) {
                    l.onBrightnessEnd(lastP);
                } else {
                    float dx = e.getRawX() - downX;
                    if (dx < -swipeDist) l.onSwipeLeft();
                    else if (dx > swipeDist) l.onSwipeRight();
                }
                mode = 0;
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                if (mode == 1) l.onBrightnessEnd(lastP);
                mode = 0;
                return true;
            default:
                return true;
        }
    }
}
