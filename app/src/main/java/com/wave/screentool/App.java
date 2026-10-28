package com.wave.screentool;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * 应用入口：做一次性迁移，并统计本应用页面是否在前台——用来结束“本次打开应用期间有效”的验证会话，
 * 以及实现“离开本应用后上锁”的触发条件。
 *
 * 验证页和无界面的开关页（ToggleActivity）只算辅助页面：它们出现/消失不会触发“离开应用”，
 * 否则刚验证通过解锁，马上又被判定“离开应用”而重新上锁。
 */
public class App extends Application implements Application.ActivityLifecycleCallbacks {
    private final Handler h = new Handler(Looper.getMainLooper());
    private int main;            // 前台的“主要页面”数量
    private boolean mainLeft;    // 主要页面刚刚全部离开，等待确认
    private final Runnable check = this::checkLeave;

    @Override
    public void onCreate() {
        super.onCreate();
        PrivacyLock.migrateLegacy(this);
        Protect.migrate(this);
        FailPolicy.resumePending(this);
        registerActivityLifecycleCallbacks(this);
    }

    private static boolean aux(Activity a) {
        return a instanceof VerifyActivity || a instanceof ToggleActivity;
    }

    @Override
    public void onActivityStarted(Activity a) {
        Protect.uiStarted();
        if (!aux(a)) {
            main++;
            mainLeft = false;
        }
        h.removeCallbacks(check);
    }

    @Override
    public void onActivityStopped(Activity a) {
        Protect.uiStopped();
        if (!aux(a) && main > 0) {
            main--;
            if (main == 0) mainLeft = true;
        }
        if (Protect.uiCount() == 0 || mainLeft) {
            h.removeCallbacks(check);
            h.postDelayed(check, 1500);
        }
    }

    private void checkLeave() {
        if (Protect.externalExpected()) {
            // 去了系统文件选择器/权限页：等它的宽限期结束后再判断一次
            long left = 500;
            h.postDelayed(check, left + 1000);
            return;
        }
        if (Protect.uiCount() == 0) Protect.endUiSession();
        if (main == 0 && mainLeft) {
            mainLeft = false;
            if (Prefs.getBool(this, "pv_trig_leave") && Protect.armed(this) && FloatCtl.running()) {
                Protect.lockNow(this, false);
            }
        }
    }

    @Override public void onActivityCreated(Activity a, Bundle b) { }
    @Override public void onActivityResumed(Activity a) { }
    @Override public void onActivityPaused(Activity a) { }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
    @Override public void onActivityDestroyed(Activity a) { }
}
