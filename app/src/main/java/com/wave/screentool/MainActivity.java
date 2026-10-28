package com.wave.screentool;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 主页：全黑页面。
 * 上滑调亮、下滑调暗、左滑进设置、右滑启动悬浮常亮、双击退出。
 * 支持系统小窗 / 分屏（如华为小窗）。
 */
public class MainActivity extends Activity implements GestureHelper.Listener {
    private FrameLayout root;
    private TextView timeView, hud;
    private BgView bgView;
    private SlideShow show;
    private GestureHelper g;
    private float p;
    private boolean resumed, showTime, burn;
    private static final int[] BURN_X = {0, 1, 1, 0}, BURN_Y = {0, 0, 1, 1};
    private int dp6;
    private final Handler h = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateTime();
            scheduleTick();
        }
    };
    private final Runnable hideHud = () -> hud.setVisibility(View.INVISIBLE);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        S.initLite(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        g = new GestureHelper(this, this);
        dp6 = Sys.dp(this, 6);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        bgView = new BgView(this);   // 背景图片层（没有图片时只是纯色，几乎不占资源）
        root.addView(bgView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        timeView = new TextView(this);
        timeView.setTextSize(44);
        timeView.setGravity(Gravity.CENTER);
        root.addView(timeView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        hud = new TextView(this);
        hud.setTextSize(16);
        hud.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hp.bottomMargin = Sys.dp(this, 48);
        root.addView(hud, hp);
        root.setOnTouchListener((v, e) -> g.onTouch(e, root.getHeight(), p));
        setContentView(root);
        Sys.immersive(this);

        BrightnessCtl.restoreIfStale(this);   // 上次被异常杀掉时补恢复系统亮度
        if (b == null) handleLaunch(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunch(intent);
    }

    /**
     * 从最近任务返回：如果上次停在设置页，就回到设置页。
     * 从桌面图标启动：进入主页，并按设置自动启动悬浮/通知。
     */
    private void handleLaunch(Intent intent) {
        boolean fromHistory = (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0;
        if (fromHistory) {
            if (Prefs.getInt(this, "last_screen") == 1) {
                startActivity(new Intent(this, SettingsActivity.class));
            }
            return;
        }
        // 服务被系统杀掉后（上次没有正常停止），打开应用时自动恢复，不用等用户发现悬浮窗没了
        boolean wasAlive = Prefs.getBool(this, "svc_alive");
        String a = Prefs.getStr(this, "auto_start");
        if (!FloatCtl.running() && (wasAlive || !"none".equals(a))) {
            FloatCtl.start(this, !wasAlive && "notif".equals(a) ? "notif" : null, false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Prefs.putInt(this, "last_screen", 0);
        S.initLite(this);
        StyleModel st = StyleStore.active(this);
        root.setBackgroundColor(Sys.opaque(st.c[StyleModel.HOME]));
        bgView.setBase(Sys.opaque(st.c[StyleModel.HOME]));
        startShow(st.img[StyleModel.HOME]);
        timeView.setTextColor(st.c[StyleModel.TIME]);
        hud.setTextColor(st.c[StyleModel.WATCH]);
        p = Prefs.getFloat(this, "brightness");
        applyBrightness();
        showTime = Prefs.getBool(this, "home_time");
        burn = Prefs.getBool(this, "burn");
        timeView.setVisibility(showTime ? View.VISIBLE : View.INVISIBLE);
        Sys.immersive(this);
        h.removeCallbacks(tick);
        if (showTime) {
            updateTime();
            scheduleTick();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        h.removeCallbacks(tick);
        stopShow();
        super.onPause();
    }

    /** 主页背景图片/幻灯片：只有风格里给主页绑定了图片集才会创建，页面不可见时立即停止。 */
    private void startShow(String setId) {
        stopShow();
        bgView.clear();
        if (setId == null || setId.length() == 0) return;
        ImgSet set = ImgStore.get(this, setId);
        if (set == null || set.uris.isEmpty()) return;
        int[] sc = Sys.screen(this);
        show = new SlideShow(this, set, sc[0], sc[1], (b, f, fit) -> bgView.show(b, f, fit));
        show.start();
    }

    private void stopShow() {
        if (show != null) {
            show.stop();
            show = null;
        }
    }

    /**
     * 按返回键：退到后台但不销毁页面，最近任务里保留的是真实的黑色画面（销毁后系统只能拿窗口底色来垫，
     * 在有的系统上会是刺眼的白色）。页面在后台时没有任何刷新，几乎不占资源。
     */
    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Sys.immersive(this);
    }

    private void scheduleTick() {
        h.removeCallbacks(tick);
        if (resumed && showTime) {
            h.postDelayed(tick, 1000 - (System.currentTimeMillis() % 1000) + 8);
        }
    }

    private void updateTime() {
        long now = System.currentTimeMillis();
        timeView.setText(Fmt.clock(now));
        int k = burn ? (int) ((now / 180000) % 4) : 0;   // 防烧屏：每 3 分钟微移
        float dx = BURN_X[k] * dp6, dy = BURN_Y[k] * dp6;
        if (dx != timeView.getTranslationX() || dy != timeView.getTranslationY()) {
            timeView.setTranslationX(dx);
            timeView.setTranslationY(dy);
        }
    }

    private void applyBrightness() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = Prefs.windowBrightness(p);
        getWindow().setAttributes(lp);
    }

    // ---- 手势 ----

    @Override
    public void onBrightness(float v) {
        p = v;
        applyBrightness();
        hud.setText(S.t("Brightness") + " " + Math.round(v * 100) + "%");
        hud.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideHud);
        h.postDelayed(hideHud, 1000);
    }

    @Override
    public void onBrightnessEnd(float v) {
        Prefs.putFloat(this, "brightness", v);
    }

    @Override public void onTap() { }

    /**
     * 双击退出。退出不需要验证（受保护的是设置和遮罩，不是“关掉主页”）。
     *
     * 悬浮服务还在运行时不用 finishAndRemoveTask()：部分系统（如鸿蒙）把“移除任务”当成“清理应用”，
     * 随后会把悬浮窗一起清掉，亮屏时就看到悬浮球不见了。这时改为：先把任务从最近任务里隐藏，再普通结束页面，
     * 效果相同（最近任务里没有卡片）但不触发系统清理。服务没在运行时才彻底移除任务。
     */
    @Override
    public void onDoubleTap() {
        if (FloatCtl.running() && !Prefs.getBool(this, "exit_remove_task")) {
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                java.util.List<android.app.ActivityManager.AppTask> tasks = am == null ? null : am.getAppTasks();
                if (tasks != null) for (android.app.ActivityManager.AppTask t : tasks) t.setExcludeFromRecents(true);
            } catch (Exception ignored) {
            }
            finish();
        } else {
            finishAndRemoveTask();
        }
    }

    @Override
    public void onSwipeLeft() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    @Override
    public void onSwipeRight() {
        FloatCtl.start(this);
    }
}
