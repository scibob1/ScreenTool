package com.wave.screentool;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * 画在幕布之上的验证悬浮窗，只用于“只验证自定义密码”的场景。
 * 普通页面会被幕布（悬浮窗，层级更高）盖住，所以这里直接用同一种窗口类型，后加的窗口自然在上面。
 * 需要系统锁屏凭据时不走这里，而是由验证页（VerifyActivity）承载，同时服务会暂时撤掉自己的遮罩。
 */
final class VerifyOverlay {
    interface Host {
        void onVerified();
        void onCancel();
    }

    private final Context c;
    private final WindowManager wm;
    private final int type;
    private final Host host;
    private Root root;
    private VerifyPanel panel;

    private final class Root extends FrameLayout {
        Root(Context ctx) {
            super(ctx);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) host.onCancel();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
    }

    VerifyOverlay(Context c, WindowManager wm, int type, Host host) {
        this.c = c;
        this.wm = wm;
        this.type = type;
        this.host = host;
    }

    boolean isShown() {
        return root != null && root.isAttachedToWindow();
    }

    boolean exists() {
        return root != null;
    }

    /** 返回是否成功加上窗口。 */
    boolean show() {
        if (root != null) return true;
        S.acquire(c);
        root = new Root(c);
        root.setBackgroundColor(Color.BLACK);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        int pad = Sys.dp(c, 28);
        box.setPadding(pad, pad, pad, pad);
        root.addView(box, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        panel = new VerifyPanel(c, box, new VerifyPanel.Callback() {
            @Override public void ok() { host.onVerified(); }
            @Override public void cancel() { host.onCancel(); }
        });
        String problem = Protect.methodProblem(c);
        if (problem != null) panel.showUnavailable(problem);
        else panel.showPassword(null);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1, type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_SECURE
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE);
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        lp.screenBrightness = 0.2f;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            wm.addView(root, lp);
            return true;
        } catch (Exception e) {
            cleanup();
            return false;
        }
    }

    void dismiss() {
        if (root != null) {
            try {
                wm.removeView(root);
            } catch (Exception ignored) {
            }
        }
        cleanup();
    }

    private void cleanup() {
        if (panel != null) panel.release();
        panel = null;
        boolean had = root != null;
        root = null;
        if (had) S.release();
    }
}
