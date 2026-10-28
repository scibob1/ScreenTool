package com.wave.screentool;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/** 拖动四条边调整悬浮幕布的范围。只在需要时打开，保存后立即关闭。 */
public class CurtainEditActivity extends Activity {
    private RectView rv;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        S.acquire(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF1E1E1E);
        rv = new RectView(this);
        root.addView(rv, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setBackgroundColor(0xCC000000);
        bar.addView(btn(S.t("Reset"), v -> rv.reset()));
        bar.addView(btn(S.t("Cancel"), v -> finish()));
        bar.addView(btn(S.t("Save"), v -> {
            rv.save();
            FloatCtl.refresh(this);
            finish();
        }));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(bar, bp);
        setContentView(root);
        Sys.immersive(this);
    }

    @Override
    protected void onDestroy() {
        S.release();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) Sys.immersive(this);
    }

    private Button btn(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        return b;
    }

    private static final class RectView extends View {
        private float l, t, r, b;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float slop;
        private final int fill;
        private boolean eL, eT, eR, eB, move;
        private float lx, ly;

        RectView(Context c) {
            super(c);
            slop = Sys.dp(c, 32);
            fill = (StyleStore.active(c).c[StyleModel.CURTAIN] & 0x00FFFFFF) | 0xAA000000;
            l = Prefs.getFloat(c, "cur_l");
            t = Prefs.getFloat(c, "cur_t");
            r = Prefs.getFloat(c, "cur_r");
            b = Prefs.getFloat(c, "cur_b");
        }

        void reset() {
            l = 0;
            t = 0;
            r = 1;
            b = 1;
            invalidate();
        }

        void save() {
            Context c = getContext();
            Prefs.putFloat(c, "cur_l", l);
            Prefs.putFloat(c, "cur_t", t);
            Prefs.putFloat(c, "cur_r", r);
            Prefs.putFloat(c, "cur_b", b);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float L = l * w, T = t * h, R = r * w, B = b * h;
            p.setStyle(Paint.Style.FILL);
            p.setColor(fill);
            c.drawRect(L, T, R, B, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Sys.dp(getContext(), 3));
            p.setColor(0xFF00E5FF);
            c.drawRect(L, T, R, B, p);
            p.setStyle(Paint.Style.FILL);
            float hs = Sys.dp(getContext(), 9);
            c.drawCircle(L, (T + B) / 2, hs, p);
            c.drawCircle(R, (T + B) / 2, hs, p);
            c.drawCircle((L + R) / 2, T, hs, p);
            c.drawCircle((L + R) / 2, B, hs, p);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(Sys.dp(getContext(), 16));
            p.setColor(0xFFFFFFFF);
            String s = S.t("Left") + " " + Math.round(l * 100) + "%   " + S.t("Top") + " " + Math.round(t * 100)
                    + "%   " + S.t("Right") + " " + Math.round(r * 100) + "%   " + S.t("Bottom") + " "
                    + Math.round(b * 100) + "%";
            p.setShadowLayer(4, 0, 0, 0xFF000000);
            c.drawText(s, w / 2f, (T + B) / 2, p);
            p.setShadowLayer(0, 0, 0, 0);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int w = getWidth(), h = getHeight();
            float x = e.getX(), y = e.getY();
            float L = l * w, T = t * h, R = r * w, B = b * h;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    boolean inY = y > T - slop && y < B + slop, inX = x > L - slop && x < R + slop;
                    eL = inY && Math.abs(x - L) < slop;
                    eR = inY && Math.abs(x - R) < slop;
                    eT = inX && Math.abs(y - T) < slop;
                    eB = inX && Math.abs(y - B) < slop;
                    move = !(eL || eR || eT || eB) && x > L && x < R && y > T && y < B;
                    lx = x;
                    ly = y;
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    float dx = (x - lx) / w, dy = (y - ly) / h;
                    lx = x;
                    ly = y;
                    float min = 0.1f;
                    if (move) {
                        float ww = r - l, hh = b - t;
                        l = Math.max(0, Math.min(1 - ww, l + dx));
                        t = Math.max(0, Math.min(1 - hh, t + dy));
                        r = l + ww;
                        b = t + hh;
                    } else {
                        if (eL) l = Math.max(0, Math.min(r - min, l + dx));
                        if (eR) r = Math.min(1, Math.max(l + min, r + dx));
                        if (eT) t = Math.max(0, Math.min(b - min, t + dy));
                        if (eB) b = Math.min(1, Math.max(t + min, b + dy));
                    }
                    invalidate();
                    return true;
                }
                default:
                    return true;
            }
        }
    }
}
