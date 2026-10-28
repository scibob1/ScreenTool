package com.wave.screentool;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 失败响应。永远有的基础：保持锁定（不会因为输错就放行）+ 内置冷却阶梯。
 * 可选叠加：逐步加长冷却、清除授权文件/文件夹、清除软件数据。
 *
 * 清理顺序和保证：
 *  1. 先在后台线程清除授权文件/文件夹，并逐项读取确认已经清空；
 *  2. 只有第 1 步全部确认成功，才会清除软件数据（清除软件数据会撤销文件授权，必须先删文件）；
 *  3. 第 1 步失败：软件数据保留，应用保持锁定，并记下“未完成”标记，之后每次失败/启动都会重试。
 */
final class FailPolicy {
    private FailPolicy() {}

    static final int NONE = 0, COOLDOWN = 1, WIPE = 2;
    private static final int P_FILES = 1, P_DATA = 2;
    private static final AtomicBoolean wiping = new AtomicBoolean(false);

    interface Callback {
        /** ok=true 表示清理已全部完成（清除软件数据时进程会直接结束，不会走到这里）。 */
        void done(boolean ok, String message);
    }

    static int limit(Context c) {
        return Math.max(1, Prefs.getInt(c, "pv_fail_limit"));
    }

    static boolean wipeFiles(Context c) {
        return Prefs.getBool(c, "pv_fail_wipe_files");
    }

    static boolean wipeData(Context c) {
        return Prefs.getBool(c, "pv_fail_wipe_data");
    }

    /** 一次错误的密码/恢复码之后调用，返回需要界面做什么。 */
    static int onWrong(Context c) {
        int n = PrivacyLock.onFail(c);
        if (n < limit(c)) return NONE;
        boolean cool = Prefs.getBool(c, "pv_fail_cool");
        if (cool) {
            int over = Math.max(0, n - limit(c));
            long minutes = Math.min(1440L, 1L << Math.min(over, 20));
            PrivacyLock.lockFor(c, minutes * 60_000L);
        }
        if (wipeFiles(c) || wipeData(c)) return WIPE;
        return cool ? COOLDOWN : NONE;
    }

    static boolean isWiping() {
        return wiping.get();
    }

    static boolean hasPending(Context c) {
        return Prefs.getInt(c, "pv_wipe_pending") != 0;
    }

    /** 启动时/失败时：如果上次清理没做完，继续做完（不弹界面）。 */
    static void resumePending(Context c) {
        if (hasPending(c) && Protect.armed(c)) runWipe(c, null);
    }

    /** 在后台线程执行清理；回调在主线程。 */
    static void runWipe(Context ctx, Callback cb) {
        final Context c = ctx.getApplicationContext();
        if (!wiping.compareAndSet(false, true)) return;   // 已经有一轮在跑
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            boolean ok = true;
            String msg = null;
            try {
                int pend = Prefs.getInt(c, "pv_wipe_pending");
                boolean doFiles = wipeFiles(c) || (pend & P_FILES) != 0;
                boolean doData = wipeData(c) || (pend & P_DATA) != 0;
                if (doFiles) {
                    Prefs.sp(c).edit().putInt("pv_wipe_pending", pend | P_FILES).commit();
                    AuthCleaner.Result r = AuthCleaner.cleanAll(c);
                    if (!r.ok) {
                        ok = false;
                        msg = S.f("Could not delete %d of %d authorized items. App data was kept and the app stays locked. It will try again.",
                                r.failed, r.items);
                    } else {
                        Prefs.sp(c).edit().putInt("pv_wipe_pending", Prefs.getInt(c, "pv_wipe_pending") & ~P_FILES).commit();
                    }
                }
                if (ok && doData) {
                    Prefs.sp(c).edit().putInt("pv_wipe_pending", Prefs.getInt(c, "pv_wipe_pending") | P_DATA).commit();
                    boolean cleared = false;
                    try {
                        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
                        cleared = am != null && am.clearApplicationUserData();
                    } catch (Exception ignored) {
                    }
                    if (!cleared) {
                        ok = false;
                        msg = S.t("Could not clear app data. The app stays locked. It will try again.");
                    }
                }
            } catch (Throwable t) {
                ok = false;
                msg = S.t("Cleanup failed. The app stays locked.");
            }
            final boolean fok = ok;
            final String fmsg = msg;
            wiping.set(false);
            ui.post(() -> {
                if (cb != null) cb.done(fok, fmsg);
            });
        }, "pv-wipe").start();
    }
}
