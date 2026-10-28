package com.wave.screentool;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/**
 * 悬浮幕布：纯色/图片背景 + 可选的秒表和系统时间。
 * 上下滑或音量键调亮度，点按或返回键切回悬浮球。护眼休息时进入“锁定”状态，屏蔽触摸和按键。
 */
final class CurtainView extends View implements GestureHelper.Listener {
    interface Host {
        void onCurtainTap();
        void onCurtainBack();
        void onCurtainBright(float p);
        void onCurtainBrightEnd(float p);
        void onCurtainEmergency();
        void onCurtainHoldHint();
    }

    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GestureHelper g;
    private final Host host;
    private final BgPainter bg = new BgPainter(this);
    private int bgColor, watchColor, clockColor;
    private String watchText = "", clockText = "";
    private float shiftX, shiftY;
    private float p;
    private String hud = "";
    private long hudUntil;
    private boolean locked;     // 护眼休息：屏蔽一切触摸和按键
    private boolean volKeys;    // 音量键调亮度
    private boolean hideBars;   // 尽量隐藏状态栏和导航栏（普通悬浮窗层级低于系统栏，只能尽力而为）
    private boolean emergency;  // 锁定状态下，长按 5 秒可紧急退出
    private Runnable onDetach;
    private long holdMs;          // 上锁时：长按多久紧急释放遮罩（0=不启用）
    private boolean holdFired;    // 长按已触发：抬手时不要再当作点按
    private float holdX, holdY;
    private final float holdSlop;
    private final Runnable holdRun = new Runnable() {
        @Override
        public void run() {
            holdFired = true;
            host.onCurtainEmergency();
        }
    };
    private final Runnable holdHintRun = new Runnable() {
        @Override
        public void run() {
            host.onCurtainHoldHint();
        }
    };
    private final Runnable emergencyRun = new Runnable() {
        @Override
        public void run() {
            host.onCurtainEmergency();
        }
    };

    CurtainView(Context c, Host host) {
        super(c);
        this.host = host;
        this.g = new GestureHelper(c, this);
        tp.setTextAlign(Paint.Align.CENTER);
        setFocusable(true);
        holdSlop = android.view.ViewConfiguration.get(c).getScaledTouchSlop() * 2f;
    }

    void setOnDetach(Runnable r) {
        onDetach = r;
    }

    void setLocked(boolean l) {
        locked = l;
    }

    void setVolKeys(boolean v) {
        volKeys = v;
    }

    void setHideBars(boolean v) {
        hideBars = v;
    }

    void setEmergency(boolean v) {
        emergency = v;
    }

    void setHold(long ms) {
        holdMs = ms;
    }

    private void cancelHold() {
        removeCallbacks(holdRun);
        removeCallbacks(holdHintRun);
    }

    void setup(int bgColor, int watchColor, int clockColor, float brightness) {
        this.bgColor = bgColor;
        this.watchColor = watchColor;
        this.clockColor = clockColor;
        this.p = brightness;
        invalidate();
    }

    void setBg(Bitmap b, boolean fade, int fit) {
        bg.show(b, fade, fit);
    }

    void clearBg() {
        bg.clear();
    }

    void setTexts(String watch, String clock) {
        if (watch.equals(watchText) && clock.equals(clockText)) return;
        watchText = watch;
        clockText = clock;
        invalidate();
    }

    void setShift(float x, float y) {
        if (x == shiftX && y == shiftY) return;
        shiftX = x;
        shiftY = y;
        invalidate();
    }

    void showHud(float pv) {
        p = pv;
        hud = S.t("Brightness") + " " + Math.round(pv * 100) + "%";
        hudUntil = System.currentTimeMillis() + 1000;
        invalidate();
        postInvalidateDelayed(1100);
    }

    @SuppressWarnings("deprecation")
    private void applyBars() {
        if (!hideBars) return;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindowInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        applyBars();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyBars();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(emergencyRun);
        cancelHold();
        bg.clear();
        super.onDetachedFromWindow();
        if (onDetach != null) onDetach.run();
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(bgColor);
        int w = getWidth(), h = getHeight();
        bg.draw(c, w, h);
        float m = Math.min(w, h);
        boolean hasW = watchText.length() > 0, hasC = clockText.length() > 0;
        if (hasW || hasC) {
            float wsz = fit(watchText, m * 0.22f, w * 0.9f);
            float csz = fit(clockText, m * 0.10f, w * 0.9f);
            float cx = w / 2f + shiftX, cy = h / 2f + shiftY;
            if (hasW && hasC) {
                float yW = cy + wsz * 0.35f;
                float yC = yW - wsz * 0.95f;
                draw(c, watchText, cx, yW, wsz, watchColor);
                draw(c, clockText, cx, yC, csz, clockColor);
            } else if (hasW) {
                draw(c, watchText, cx, cy + wsz * 0.35f, wsz, watchColor);
            } else {
                draw(c, clockText, cx, cy + csz * 0.35f, csz, clockColor);
            }
        }
        if (System.currentTimeMillis() < hudUntil) {
            draw(c, hud, w / 2f, h - m * 0.08f, m * 0.05f, watchColor);
        }
    }

    private float fit(String s, float size, float maxW) {
        if (s.length() == 0) return size;
        tp.setTextSize(size);
        float tw = tp.measureText(s);
        return tw > maxW ? size * maxW / tw : size;
    }

    private void draw(Canvas c, String s, float x, float y, float size, int color) {
        tp.setTextSize(size);
        tp.setColor(color);
        c.drawText(s, x, y, tp);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (locked) {
            int a = e.getActionMasked();
            if (emergency) {
                if (a == MotionEvent.ACTION_DOWN) postDelayed(emergencyRun, 5000);
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) removeCallbacks(emergencyRun);
            }
            return true;
        }
        if (holdMs > 0) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    holdFired = false;
                    holdX = e.getRawX();
                    holdY = e.getRawY();
                    postDelayed(holdHintRun, Math.min(2500L, holdMs / 2));
                    postDelayed(holdRun, holdMs);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getRawX() - holdX) > holdSlop || Math.abs(e.getRawY() - holdY) > holdSlop) cancelHold();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    cancelHold();
                    if (holdFired) {
                        holdFired = false;
                        return true;   // 已经紧急释放，抬手不再当作点按
                    }
                    break;
                default:
                    break;
            }
        }
        return g.onTouch(e, getHeight(), p);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (locked) return true;  // 休息期间不响应任何按键
        if (k == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) host.onCurtainBack();
            return true;
        }
        if (volKeys && (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                float np = p + (k == KeyEvent.KEYCODE_VOLUME_UP ? 0.05f : -0.05f);
                np = Math.max(0f, Math.min(1f, np));
                host.onCurtainBright(np);
                host.onCurtainBrightEnd(np);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public void onBrightness(float pv) { host.onCurtainBright(pv); }
    @Override public void onBrightnessEnd(float pv) { host.onCurtainBrightEnd(pv); }
    @Override public void onTap() { host.onCurtainTap(); }
    @Override public void onDoubleTap() { }
    @Override public void onSwipeLeft() { }
    @Override public void onSwipeRight() { }
}
