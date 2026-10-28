package com.wave.screentool;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** 颜色选择：色盘 + 明度条 + 透明度条 + #AARRGGBB 输入框 + 大预览。用时创建，关闭即销毁。 */
final class ColorPickerDialog {
    interface Callback {
        void onColor(int argb);
    }

    private final float[] hsv = new float[3];
    private int alpha;
    private boolean busy;
    private Wheel wheel;
    private Preview preview;
    private SeekBar valBar, alphaBar;
    private EditText hex;

    static void show(Context ctx, int initial, Callback cb) {
        new ColorPickerDialog().open(ctx, initial, cb);
    }

    private int color() {
        return (alpha << 24) | (Color.HSVToColor(hsv) & 0x00FFFFFF);
    }

    private void open(Context ctx, int initial, final Callback cb) {
        Color.colorToHSV(initial, hsv);
        alpha = Color.alpha(initial);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Sys.dp(ctx, 16);
        box.setPadding(pad, pad / 2, pad, 0);

        preview = new Preview(ctx);
        box.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Sys.dp(ctx, 90)));

        wheel = new Wheel(ctx);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Sys.dp(ctx, 240));
        wl.topMargin = Sys.dp(ctx, 8);
        box.addView(wheel, wl);

        box.addView(label(ctx, S.t("Brightness of color")));
        valBar = new SeekBar(ctx);
        valBar.setMax(100);
        box.addView(valBar);

        box.addView(label(ctx, S.t("Opacity")));
        alphaBar = new SeekBar(ctx);
        alphaBar.setMax(255);
        box.addView(alphaBar);

        hex = new EditText(ctx);
        hex.setSingleLine(true);
        hex.setFilters(new InputFilter[]{new InputFilter.LengthFilter(9)});
        hex.setHint("#AARRGGBB");
        box.addView(hex);

        SeekBar.OnSeekBarChangeListener sl = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                if (!fromUser) return;
                if (s == valBar) hsv[2] = v / 100f;
                else alpha = v;
                sync(false);
            }

            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        };
        valBar.setOnSeekBarChangeListener(sl);
        alphaBar.setOnSeekBarChangeListener(sl);

        wheel.listener = (h, s) -> {
            hsv[0] = h;
            hsv[1] = s;
            sync(false);
        };

        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable e) {
                if (busy) return;
                String t = e.toString().trim();
                if (!t.startsWith("#")) t = "#" + t;
                if (t.length() != 7 && t.length() != 9) return;
                try {
                    int c = Color.parseColor(t);
                    Color.colorToHSV(c, hsv);
                    alpha = Color.alpha(c);
                    sync(true);
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

        sync(false);

        ScrollView sv = new ScrollView(ctx);
        sv.addView(box);
        new AlertDialog.Builder(ctx)
                .setTitle(S.t("Pick color"))
                .setView(sv)
                .setPositiveButton(S.t("OK"), (d, w) -> cb.onColor(color()))
                .setNegativeButton(S.t("Cancel"), null)
                .show();
    }

    private TextView label(Context ctx, String t) {
        TextView v = new TextView(ctx);
        v.setText(t);
        v.setTextSize(13);
        v.setPadding(0, Sys.dp(ctx, 10), 0, 0);
        return v;
    }

    private void sync(boolean fromHex) {
        busy = true;
        int c = color();
        preview.color = c;
        preview.invalidate();
        wheel.set(hsv);
        valBar.setProgress(Math.round(hsv[2] * 100));
        alphaBar.setProgress(alpha);
        if (!fromHex) {
            hex.setText(String.format("#%08X", c));
            hex.setSelection(hex.getText().length());
        }
        busy = false;
    }

    // ---- 大预览（带棋盘格，能看出透明度） ----
    private static final class Preview extends View {
        int color;
        private final Paint p = new Paint();

        Preview(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas c) {
            int sq = Sys.dp(getContext(), 10);
            for (int y = 0, r = 0; y < getHeight(); y += sq, r++) {
                for (int x = 0, k = 0; x < getWidth(); x += sq, k++) {
                    p.setColor(((r + k) & 1) == 0 ? 0xFFCCCCCC : 0xFF888888);
                    c.drawRect(x, y, Math.min(x + sq, getWidth()), Math.min(y + sq, getHeight()), p);
                }
            }
            p.setColor(color);
            c.drawRect(0, 0, getWidth(), getHeight(), p);
        }
    }

    // ---- 色盘：角度＝色相，半径＝饱和度 ----
    private static final class Wheel extends View {
        interface L {
            void onPick(float hue, float sat);
        }

        L listener;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float cx, cy, r;
        private Shader sweep, radial;
        private float hue, sat, val = 1f;

        Wheel(Context c) {
            super(c);
        }

        void set(float[] hsv) {
            hue = hsv[0];
            sat = hsv[1];
            val = hsv[2];
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            cx = w / 2f;
            cy = h / 2f;
            r = Math.min(w, h) / 2f - Sys.dp(getContext(), 8);
            sweep = new SweepGradient(cx, cy, new int[]{
                    0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000}, null);
            radial = new RadialGradient(cx, cy, r, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (sweep == null) return;
            p.setStyle(Paint.Style.FILL);
            p.setShader(sweep);
            c.drawCircle(cx, cy, r, p);
            p.setShader(radial);
            c.drawCircle(cx, cy, r, p);
            p.setShader(null);
            p.setColor(Color.argb(Math.round((1f - val) * 255), 0, 0, 0));
            c.drawCircle(cx, cy, r, p);
            double a = Math.toRadians(hue);
            float mx = cx + (float) Math.cos(a) * sat * r;
            float my = cy + (float) Math.sin(a) * sat * r;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Sys.dp(getContext(), 3));
            p.setColor(0xFFFFFFFF);
            c.drawCircle(mx, my, Sys.dp(getContext(), 10), p);
            p.setStrokeWidth(Sys.dp(getContext(), 1));
            p.setColor(0xFF000000);
            c.drawCircle(mx, my, Sys.dp(getContext(), 12), p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            float dx = e.getX() - cx, dy = e.getY() - cy;
            float dist = (float) Math.hypot(dx, dy);
            float h = (float) Math.toDegrees(Math.atan2(dy, dx));
            if (h < 0) h += 360f;
            if (listener != null) listener.onPick(h, Math.min(1f, dist / r));
            return true;
        }
    }
}
