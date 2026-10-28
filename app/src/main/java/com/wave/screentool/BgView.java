package com.wave.screentool;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

/** 只负责画背景色 + 背景图片的视图（主页、预览窗使用）。 */
final class BgView extends View {
    private final BgPainter painter = new BgPainter(this);
    private int base = 0xFF000000;

    BgView(Context c) {
        super(c);
    }

    void setBase(int color) {
        base = color;
        invalidate();
    }

    void show(Bitmap b, boolean fade, int fit) {
        painter.show(b, fade, fit);
    }

    void clear() {
        painter.clear();
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(base);
        painter.draw(c, getWidth(), getHeight());
    }
}
