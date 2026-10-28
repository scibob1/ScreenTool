package com.wave.screentool;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 设置类页面共用的界面小零件（全部用代码生成，不用 XML 布局，省资源）。 */
final class Ui {
    private Ui() {}

    static final class Page {
        Activity a;
        int bg, fg, fg2;
        LinearLayout root, list;
        ScrollView sv;
    }

    /** 向右滑返回：在 Activity 的 dispatchTouchEvent 里调用，只观察不拦截，滑动结束时才判断一次。 */
    static final class SwipeBack {
        private final Activity a;
        private float x0, y0;
        private boolean ok;
        private final int edge, dist, vslop;

        SwipeBack(Activity a) {
            this.a = a;
            edge = Sys.dp(a, 24);
            dist = Sys.dp(a, 110);
            vslop = Sys.dp(a, 70);
        }

        void feed(android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    x0 = e.getRawX();
                    y0 = e.getRawY();
                    ok = x0 > edge;    // 避开屏幕边缘，免得和系统返回手势冲突
                    break;
                case android.view.MotionEvent.ACTION_UP:
                    if (ok && e.getRawX() - x0 > dist && Math.abs(e.getRawY() - y0) < vslop) a.finish();
                    ok = false;
                    break;
                case android.view.MotionEvent.ACTION_CANCEL:
                    ok = false;
                    break;
                default:
                    break;
            }
        }
    }

    static TextView tv(Context c, String s, int sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    /** 建一个带标题栏（左上角返回）、可滚动的页面，并按当前风格着色。 */
    @SuppressWarnings("deprecation")
    static Page page(final Activity a, String title) {
        Page p = new Page();
        p.a = a;
        StyleModel st = StyleStore.active(a);
        p.bg = Sys.opaque(st.c[StyleModel.HOME]);
        p.fg = Sys.opaque(st.c[StyleModel.TEXT]);
        p.fg2 = (p.fg & 0x00FFFFFF) | 0xAA000000;

        p.root = new LinearLayout(a);
        p.root.setOrientation(LinearLayout.VERTICAL);
        a.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(p.bg));  // 只画一层，少一次过度绘制
        Sys.applyInsets(p.root);

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = tv(a, "‹", 30, p.fg);
        back.setPadding(Sys.dp(a, 20), 0, Sys.dp(a, 12), Sys.dp(a, 4));
        back.setOnClickListener(v -> a.finish());
        bar.addView(back);
        TextView t = tv(a, title, 20, p.fg);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        bar.addView(t);
        p.root.addView(bar, new LinearLayout.LayoutParams(-1, Sys.dp(a, 56)));

        p.sv = new ScrollView(a);
        p.list = new LinearLayout(a);
        p.list.setOrientation(LinearLayout.VERTICAL);
        p.sv.addView(p.list, new ScrollView.LayoutParams(-1, -2));
        p.root.addView(p.sv, new LinearLayout.LayoutParams(-1, 0, 1));

        a.getWindow().setStatusBarColor(p.bg);
        a.getWindow().setNavigationBarColor(p.bg);
        if (Build.VERSION.SDK_INT >= 23) {
            int f = Sys.isLight(p.bg) ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0;
            if (Build.VERSION.SDK_INT >= 26 && Sys.isLight(p.bg)) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            a.getWindow().getDecorView().setSystemUiVisibility(f);
        }
        return p;
    }

    static View row(Page p, String title, String sub, View right) {
        Context c = p.a;
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Sys.dp(c, 20), Sys.dp(c, 10), Sys.dp(c, 20), Sys.dp(c, 10));
        r.setMinimumHeight(Sys.dp(c, 58));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(tv(c, title, 16, p.fg));
        if (sub != null && sub.length() > 0) col.addView(tv(c, sub, 13, p.fg2));
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        if (right != null) r.addView(right);
        TypedValue tvv = new TypedValue();
        if (c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tvv, true)) {
            r.setBackgroundResource(tvv.resourceId);
        }
        p.list.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    static void header(Page p, String text) {
        TextView t = tv(p.a, text, 13, p.fg2);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(Sys.dp(p.a, 20), Sys.dp(p.a, 18), Sys.dp(p.a, 20), Sys.dp(p.a, 4));
        p.list.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    static View swatch(Context c, int color, int stroke) {
        View v = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(Sys.dp(c, 6));
        d.setStroke(Sys.dp(c, 1), stroke);
        v.setBackground(d);
        v.setLayoutParams(new LinearLayout.LayoutParams(Sys.dp(c, 40), Sys.dp(c, 28)));
        return v;
    }
}
