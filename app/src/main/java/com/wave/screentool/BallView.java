package com.wave.screentool;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;

import java.util.Collections;

/** 悬浮球：圆形或长方形，中心可显示秒表文字。 */
final class BallView extends View {
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String text = "";
    private int shape;
    private boolean grow;
    private int basePx;
    private float shiftX, shiftY;
    private Runnable onDetach;

    /** 窗口被系统移除时通知服务（例如托管它的应用被清理）。服务自己移除前要先设为 null。 */
    void setOnDetach(Runnable r) {
        onDetach = r;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (onDetach != null) onDetach.run();
    }

    BallView(Context c) {
        super(c);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setFakeBoldText(true);
    }

    void setup(int basePx, int shape, boolean grow, int bgColor, int textColor) {
        this.basePx = basePx;
        this.shape = shape;
        this.grow = grow;
        bg.setColor(bgColor);
        tp.setColor(textColor);
        requestLayout();
        invalidate();
    }

    void setText(String t) {
        if (t.equals(text)) return;
        text = t;
        if (grow) requestLayout();
        invalidate();
    }

    void setShift(float x, float y) {
        if (x == shiftX && y == shiftY) return;
        shiftX = x;
        shiftY = y;
        invalidate();
    }

    /** 请求系统把悬浮球这块区域排除在边缘返回手势之外，减少贴边时被当成返回手势吞掉的概率（部分系统仍可能不生效）。 */
    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (Build.VERSION.SDK_INT >= 29 && w > 0 && h > 0) {
            try {
                setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, w, h)));
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w, h;
        if (shape == 0) {
            w = h = basePx;
        } else {
            h = Math.round(basePx * 0.8f);
            w = Math.round(basePx * 1.5f);
        }
        if (grow && text.length() > 0) {
            tp.setTextSize(basePx * 0.4f);
            int need = Math.round(tp.measureText(text) + basePx * 0.35f);
            if (need > w) {
                w = need;
                if (shape == 0) h = w;
            }
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (shape == 0) {
            c.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, bg);
        } else {
            float r = h * 0.28f;
            c.drawRoundRect(0, 0, w, h, r, r, bg);
        }
        if (text.length() == 0) return;
        float ts = basePx * 0.4f;
        tp.setTextSize(ts);
        float avail = w * (shape == 0 ? 0.78f : 0.86f);
        float tw = tp.measureText(text);
        if (tw > avail) {
            ts *= avail / tw;
            tp.setTextSize(ts);
        }
        float y = h / 2f - (tp.ascent() + tp.descent()) / 2f;
        c.drawText(text, w / 2f + shiftX, y + shiftY, tp);
    }
}
