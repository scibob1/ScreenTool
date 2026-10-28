package com.wave.screentool;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** 把图片画成背景（铺满裁剪 / 完整显示），支持渐变切换。主页、幕布、预览共用。 */
final class BgPainter {
    private final View host;
    private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private Bitmap cur, prev;
    private float t = 1f;
    private int fit;
    private ValueAnimator anim;

    BgPainter(View host) {
        this.host = host;
    }

    boolean has() {
        return cur != null;
    }

    void show(Bitmap b, boolean fade, int fit) {
        this.fit = fit;
        if (anim != null) anim.cancel();   // 取消时 onAnimationEnd 会回收上一张
        if (fade && cur != null) {
            prev = cur;
            cur = b;
            t = 0f;
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(700);
            anim.addUpdateListener(a -> {
                t = (float) a.getAnimatedValue();
                host.invalidate();
            });
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator a) {
                    recycle(prev);
                    prev = null;
                    t = 1f;
                    host.invalidate();
                }
            });
            anim.start();
        } else {
            Bitmap old = cur;
            cur = b;
            prev = null;
            t = 1f;
            if (old != b) recycle(old);
            host.invalidate();
        }
    }

    void clear() {
        if (anim != null) anim.cancel();
        recycle(cur);
        recycle(prev);
        cur = null;
        prev = null;
        t = 1f;
        host.invalidate();
    }

    private static void recycle(Bitmap b) {
        if (b != null && !b.isRecycled()) b.recycle();
    }

    void draw(Canvas c, int w, int h) {
        if (prev != null && t < 1f) drawBmp(c, prev, w, h, 255);
        if (cur != null) drawBmp(c, cur, w, h, Math.round(255 * t));
    }

    private void drawBmp(Canvas c, Bitmap b, int w, int h, int alpha) {
        float s = fit == 0 ? Math.max(w / (float) b.getWidth(), h / (float) b.getHeight())
                : Math.min(w / (float) b.getWidth(), h / (float) b.getHeight());
        float dw = b.getWidth() * s, dh = b.getHeight() * s;
        dst.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
        p.setAlpha(alpha);
        c.drawBitmap(b, null, dst, p);
    }
}
