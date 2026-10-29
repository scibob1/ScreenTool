package com.wave.screentool;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 设置页。同一个 Activity 按 page 参数只生成当前这一页的内容，
 * 切换页面时旧页面直接销毁（按需加载）。
 */
public class SettingsActivity extends Activity {
    static final int P_MAIN = 0, P_FLOAT = 1, P_BALL = 2, P_CURTAIN = 3, P_GENERAL = 4, P_BACKUP = 5, P_ABOUT = 6,
            P_EYE = 7, P_STABLE = 8, P_PRIVACY = 9;
    private static final int REQ_EXPORT = 1, REQ_IMPORT = 2, REQ_NOTIF = 3, REQ_LOCK = 4, REQ_LOCK_RESET = 5,
            REQ_LOCK_CLEAR = 7, REQ_LOCK_BACKUP = 8;
    private boolean unlocking;
    private int passEpoch = -1;        // 这个页面已经验证过的“代”（见 Protect.epoch）
    private Runnable afterVerify;     // 备份/恢复验证通过后接着做的事

    private int page;
    private Ui.Page ui;
    private Ui.SwipeBack swipe;
    private boolean pendingZip;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        S.acquire(this);
        swipe = new Ui.SwipeBack(this);
        page = getIntent().getIntExtra("page", P_MAIN);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (unlocking) return;   // 正在等验证页的结果，这次 onResume 不重复处理
        Prefs.putInt(this, "last_screen", 1);   // 记住停在设置页，从最近任务返回时直接回到这里
        // 每个页面按自己的授权范围检查：设置页 / 备份页分别对应不同的范围开关
        Protect.Purpose need = page == P_BACKUP ? Protect.Purpose.BACKUP : Protect.Purpose.SETTINGS;
        if (Protect.needVerify(this, need, passEpoch)) {
            unlocking = true;
            startActivityForResult(VerifyActivity.intent(this, need, false), REQ_LOCK);
            return;
        }
        passEpoch = Protect.epoch();
        S.init(this);
        build();
    }

    @Override
    protected void onDestroy() {
        S.release();
        super.onDestroy();
    }

    /** 去系统页面（文件选择器、权限页）不算“离开本应用”，否则回来还得重新验证。 */
    @Override
    public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        if (intent.getComponent() == null || !getPackageName().equals(intent.getComponent().getPackageName())) {
            Protect.expectExternal();
        }
        super.startActivityForResult(intent, requestCode, options);
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        swipe.feed(ev);
        return super.dispatchTouchEvent(ev);
    }

    private String title() {
        switch (page) {
            case P_FLOAT: return S.t("Floating window");
            case P_BALL: return S.t("Floating ball");
            case P_CURTAIN: return S.t("Curtain");
            case P_GENERAL: return S.t("General");
            case P_BACKUP: return S.t("Backup & restore");
            case P_ABOUT: return S.t("About");
            case P_EYE: return S.t("Eye rest");
            case P_STABLE: return S.t("Stability guide");
            default: return S.t("Settings");
        }
    }

    private void build() {
        ui = Ui.page(this, title());
        switch (page) {
            case P_FLOAT: pageFloat(); break;
            case P_BALL: pageBall(); break;
            case P_CURTAIN: pageCurtain(); break;
            case P_GENERAL: pageGeneral(); break;
            case P_BACKUP: pageBackup(); break;
            case P_ABOUT: pageAbout(); break;
            case P_EYE: pageEye(); break;
            case P_STABLE: pageStable(); break;
            default: pageMain(); break;
        }
        setContentView(ui.root);
    }

    // ================= 各页面 =================

    private void pageMain() {
        boolean on = FloatCtl.running();
        action(on ? S.t("Turn off keep-awake") : S.t("Turn on keep-awake"),
                on ? S.t("Running: the screen will not turn off by itself")
                        : S.t("Same as swiping right on the home page"),
                () -> {
                    if (FloatCtl.running()) FloatCtl.stop(this);
                    else FloatCtl.start(this);
                    ui.list.postDelayed(() -> {
                        if (!isFinishing()) build();
                    }, 600);
                });
        if (!on) {
            action(S.t("Turn on in notification-only mode"),
                    S.t("No windows are shown; the notification and a wake lock keep the screen on"), () -> {
                        FloatCtl.start(this, "notif", true);
                        ui.list.postDelayed(() -> {
                            if (!isFinishing()) build();
                        }, 600);
                    });
        }
        Ui.header(ui, S.t("Settings"));
        nav(S.t("Floating window"), S.t("Ball / curtain switches, brightness, start options"), P_FLOAT);
        nav(S.t("Floating ball"), S.t("Shape, size, stopwatch"), P_BALL);
        nav(S.t("Curtain"), S.t("Size, stopwatch and clock"), P_CURTAIN);
        nav(S.t("Eye rest"), S.t("Forced short breaks on a schedule"), P_EYE);
        action(S.t("Styles"), S.t("Colors and background pictures"), () ->
                startActivity(new Intent(this, StyleActivity.class)));
        View pv = Ui.row(ui, S.t("Privacy protection"),
                !Protect.armed(this) ? S.t("Off") : (Protect.isLocked(this) ? S.t("On, locked") : S.t("On")),
                Ui.tv(this, "›", 22, ui.fg2));
        pv.setOnClickListener(x -> startActivity(new Intent(this, PrivacyActivity.class)));
        nav(S.t("Stability guide"), S.t("Permissions, background protection, enhanced mode"), P_STABLE);
        nav(S.t("General"), S.t("Language, power saving, alerts"), P_GENERAL);
        nav(S.t("Backup & restore"), S.t("Back up or restore settings, styles and pictures"), P_BACKUP);
        nav(S.t("About"), S.t("How to use, gestures and license"), P_ABOUT);
    }

    private void pageFloat() {
        Ui.header(ui, S.t("Modes"));
        sw(S.t("Enable floating ball"), S.t("A small ball docked at the screen edge, with an optional stopwatch"), "use_ball");
        sw(S.t("Enable curtain"), S.t("A black cover that saves power without interrupting background apps"), "use_curtain");
        Ui.row(ui, S.t("If both are disabled"),
                S.t("Only the notification and a wake lock keep the screen on, and no window is shown. The notification then gets brightness buttons and a stopwatch."), null);
        choiceStr(S.t("Start as"), "start_mode", new String[]{"ball", "curtain"},
                new String[]{S.t("Floating ball"), S.t("Curtain")});
        num(S.t("Operation window"),
                S.t("After this many seconds the ball turns into the curtain by itself. Set 0 for anti-mistouch: the curtain ignores taps (only the Back key returns) and the ball never expands by itself."),
                "op_sec", 0, 3600, " s");
        sw(S.t("Reset stopwatch when curtain switches to ball"), null, "reset_on_ball");

        Ui.header(ui, S.t("Keeping the screen on"));
        choiceStr(S.t("How to keep the screen on"), "keep_method", new String[]{"both", "window", "wakelock"},
                new String[]{S.t("Window flag + wake lock (recommended)"), S.t("Window flag only"),
                        S.t("Wake lock only (no window needed)")});
        choiceStr(S.t("Keep-awake without a window"), "nowin_method", new String[]{"auto", "wakelock", "keeper"},
                new String[]{S.t("Automatic (wake lock + invisible 1-pixel window)"), S.t("Wake lock only"),
                        S.t("Invisible 1-pixel window only")});
        Ui.row(ui, S.t("About the invisible window"),
                S.t("Some systems (for example HarmonyOS) ignore the wake lock and turn the screen off anyway. The invisible window fixes that. A normal floating window makes the system show its own \"displaying over other apps\" notice; the enhanced mode avoids it."), null);
        sw(S.t("Dim screen in ball / no-window state"),
                S.t("Keep the low brightness even when only the ball or the notification is active"), "ball_dim");

        Ui.header(ui, S.t("When the app opens"));
        sw(S.t("Remove the task card when exiting"),
                S.t("Double-tap exit on the home page. Off (recommended): the page closes and is hidden from recent apps, which keeps the floating windows safest. On: the task card is removed completely, but some systems then clear the floating windows when the screen turns on."),
                "exit_remove_task");
        choiceStr(S.t("Auto start"), "auto_start", new String[]{"none", "float", "notif"},
                new String[]{S.t("Do nothing"), S.t("Start with floating windows"), S.t("Start in notification-only mode")});

        Ui.header(ui, S.t("Notification"));
        sw(S.t("Show notification"),
                S.t("When off, no notification is shown: it is posted to a hidden channel, so the service still runs in the background (Android requires the service to have one). On Android 13 and later you can also deny the notification permission in system settings. If the system stops the service in the background while this is off, turn it back on."),
                "notif_enabled");
        sw(S.t("Notification action buttons"),
                S.t("Quick buttons in the notification: stop, dimmer, brighter, expand. Turn this off for a minimal notification. During an eye break, long-pressing the curtain for 5 seconds is the emergency exit."),
                "note_actions");

        Ui.header(ui, S.t("Brightness"));
        choiceStr(S.t("Brightness method"), "bright_mode", new String[]{"auto", "window", "system"},
                new String[]{S.t("Automatic (system brightness if permitted)"), S.t("Window brightness only"),
                        S.t("System brightness only")});
        final boolean wr = BrightnessCtl.canWrite(this);
        action(S.t("Modify system settings"),
                wr ? S.t("Granted: system brightness is adjusted and restored when you stop")
                        : S.t("Not granted - some systems ignore window brightness and need this permission"),
                () -> {
                    try {
                        Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch (Exception e) {
                        toast(S.t("Cannot open this system page"));
                    }
                });
    }

    private void pageBall() {
        Ui.header(ui, S.t("Stopwatch"));
        sw(S.t("Show stopwatch in the ball"), null, "ball_sw");
        choiceInt(S.t("Stopwatch style"), "ball_clock", new String[]{
                S.t("All seconds (e.g. 125)"), S.t("Dynamic H:M:S (e.g. 2:05)"),
                S.t("Adaptive (seconds first, then minutes)")});
        num(S.t("Adaptive: show seconds for the first"), null, "adapt_min", 1, 600, " min");
        Ui.header(ui, S.t("Appearance"));
        sw(S.t("Dock to the edge when idle"),
                S.t("After 5 seconds without a touch the ball slides three quarters into the screen edge and fades. Touch it once to bring it back, then tap again to open the curtain."),
                "ball_edge");
        choiceInt(S.t("Shape"), "ball_shape", new String[]{S.t("Circle"), S.t("Rectangle")});
        num(S.t("Ball size"), null, "ball_dp", 24, 200, " dp");
        choiceInt(S.t("When the text gets longer"), "ball_grow",
                new String[]{S.t("Text shrinks, ball size fixed"), S.t("Ball grows with the text")}, true);
        colorRow(S.t("Ball color / opacity"), StyleModel.BALL);
        colorRow(S.t("Stopwatch text color"), StyleModel.WATCH);
    }

    private void pageCurtain() {
        Ui.header(ui, S.t("Content"));
        sw(S.t("Show stopwatch on the curtain"), null, "cur_sw");
        sw(S.t("Show system time on the curtain"), S.t("Down to the second"), "cur_time");
        Ui.header(ui, S.t("Size"));
        sw(S.t("Cover the status bar and navigation bar"),
                S.t("Normal floating windows sit below the system bars, so this asks the system to hide them while the full-screen curtain is shown (best effort). Enhanced mode covers them natively."),
                "cover_bars");
        final int[] v = {pct("cur_l"), pct("cur_t"), pct("cur_r"), pct("cur_b")};
        action(S.t("Curtain area (numbers)"),
                S.t("Left") + " " + v[0] + "%  " + S.t("Top") + " " + v[1] + "%  "
                        + S.t("Right") + " " + v[2] + "%  " + S.t("Bottom") + " " + v[3] + "%",
                this::rectDialog);
        action(S.t("Drag the edges"), S.t("Adjust the area by hand on a full-screen editor"), () ->
                startActivity(new Intent(this, CurtainEditActivity.class)));
        action(S.t("Reset to full screen"), null, () -> {
            Prefs.putFloat(this, "cur_l", 0f);
            Prefs.putFloat(this, "cur_t", 0f);
            Prefs.putFloat(this, "cur_r", 1f);
            Prefs.putFloat(this, "cur_b", 1f);
            FloatCtl.refresh(this);
            build();
        });
        Ui.header(ui, S.t("Colors"));
        colorRow(S.t("Curtain color / opacity"), StyleModel.CURTAIN);
        colorRow(S.t("System time color"), StyleModel.TIME);
        colorRow(S.t("Stopwatch text color"), StyleModel.WATCH);

        Ui.header(ui, S.t("Privacy"));
        View pv = Ui.row(ui, S.t("Lock the curtain"),
                S.t("Locking the curtain (manually, on a timer, when the screen turns off or when you leave the app) is set up under Privacy protection. A locked curtain is always full screen and can only be removed by verifying."),
                Ui.tv(this, "›", 22, ui.fg2));
        pv.setOnClickListener(x -> startActivity(new Intent(this, PrivacyActivity.class)));
    }

    private void pageEye() {
        sw(S.t("Enable eye rest"), S.t("A curtain pops up on a schedule and cannot be dismissed during the break"), "eye_on");
        num(S.t("Rest every"), null, "eye_iv", 1, 240, " min");
        num(S.t("Each break lasts"), null, "eye_len", 10, 600, " s");
        num(S.t("Warn me in advance"), S.t("A short message is shown. 0 means no warning"), "eye_warn", 0, 120, " s");
        String es = Prefs.getStr(this, "eye_style");
        StyleModel cur = es.length() > 0 ? StyleStore.get(this, es) : null;
        action(S.t("Eye rest style"), cur != null ? cur.displayName() : S.t("Follow current style"),
                this::eyeStyleDialog);
        Ui.row(ui, S.t("Good to know"),
                S.t("During a break the curtain blocks all touches and keys. Emergency exit: the \"Skip rest\" button in the notification. If the notification buttons are off or the notification cannot be seen (no permission, or blocked in the system), press and hold the curtain for 5 seconds. Skipping or finishing a break only ends the break; it never unlocks the privacy lock. It needs the floating window permission and may not cover some system apps."), null);
    }

    private void eyeStyleDialog() {
        final java.util.List<StyleModel> all = StyleStore.all(this);
        String[] names = new String[all.size() + 1];
        names[0] = S.t("Follow current style");
        String cur = Prefs.getStr(this, "eye_style");
        int sel = 0;
        for (int i = 0; i < all.size(); i++) {
            names[i + 1] = all.get(i).displayName();
            if (all.get(i).id.equals(cur)) sel = i + 1;
        }
        new AlertDialog.Builder(this)
                .setTitle(S.t("Eye rest style"))
                .setSingleChoiceItems(names, sel, (d, w) -> {
                    Prefs.putStr(this, "eye_style", w == 0 ? "" : all.get(w - 1).id);
                    d.dismiss();
                    FloatCtl.refresh(this);
                    build();
                }).show();
    }

    private void pageStable() {
        Ui.header(ui, S.t("Permissions"));
        action(S.t("Display over other apps"),
                Sys.canOverlay(this) ? S.t("Granted") : S.t("Not granted - tap to grant"),
                () -> FloatCtl.openOverlaySettings(this));
        if (Build.VERSION.SDK_INT >= 33) {
            final boolean ng = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
            action(S.t("Notifications"),
                    (ng ? S.t("Granted") : S.t("Not granted - tap to grant")) + "\n"
                            + S.t("Without notifications the service, the privacy cover, verification and the emergency release keep working; only the notification buttons are missing."),
                    () -> {
                        if (ng) {
                            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                            safeStart(i);
                        } else if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                        } else {
                            // 用户选了"不再询问"：直接跳系统通知设置页
                            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                            safeStart(i);
                        }
                    });
        }
        if (Build.VERSION.SDK_INT >= 23) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            final boolean ig = pm.isIgnoringBatteryOptimizations(getPackageName());
            action(S.t("Ignore battery optimization"),
                    ig ? S.t("Already ignored (recommended)") : S.t("Recommended: keeps the service from being killed"),
                    () -> {
                        Intent i = ig ? new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                : new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName()));
                        safeStart(i);
                    });
        }

        Ui.header(ui, S.t("System notice"));
        action(S.t("\"Displaying over other apps\" notice"),
                S.t("This notification is added by the system whenever an app shows a normal floating window; the app cannot remove it. Long-press it to turn it off, or open this page. Enhanced mode avoids it."),
                () -> safeStart(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));

        Ui.header(ui, S.t("Keep it running"));
        action(S.t("Auto-start & background activity"),
                S.t("Allow auto-start, associated start and background activity for this app. If the system page cannot be opened directly, the app info page opens instead."),
                this::openStartupManager);
        Ui.row(ui, S.t("Lock the app card"),
                S.t("In the recent-tasks screen, pull the app card down or long-press it and choose Lock, so one-tap cleanup will not close it."), null);
        sw(S.t("Start automatically after boot"),
                S.t("Uses the \"Auto start\" option of the floating window settings; also allow auto-start in system settings"),
                "boot_start");

        Ui.header(ui, S.t("Enhanced mode (optional)"));
        Ui.row(ui, S.t("What it does"),
                S.t("Uses an accessibility service only to get a window layer above normal floating windows (helps with some system apps such as Notes) and to let the system keep this app alive. It does not read or collect anything on your screen."), null);
        sw(S.t("Use enhanced overlay when available"), null, "enhance");
        action(S.t("Accessibility service"),
                KeepAccessibilityService.inst != null ? S.t("Connected")
                        : S.t("Not enabled - tap to open accessibility settings"),
                () -> safeStart(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        action(S.t("Open app info"),
                S.t("If the system says the setting is restricted, open the menu here and choose \"Allow restricted settings\""),
                () -> safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))));
    }

    private void openStartupManager() {
        String[][] cands = {
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
                {"com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
        };
        for (String[] c : cands) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(c[0], c[1]));
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    private void pageGeneral() {
        choiceStr(S.t("Language"), "lang", new String[]{"auto", "zh", "en"},
                new String[]{S.t("Follow system"), "中文", "English"});
        Ui.header(ui, S.t("Home page"));
        sw(S.t("Show system time on the home page"), null, "home_time");
        Ui.header(ui, S.t("Power saving & protection"));
        sw(S.t("Anti burn-in"), S.t("Shift the text slightly every 3 minutes"), "burn");
        num(S.t("Auto stop after"), S.t("0 = never"), "auto_off", 0, 1440, " min");
        sw(S.t("Only keep awake while charging"), S.t("When unplugged the screen may time out normally"),
                "charge_only");
        num(S.t("Stop when battery is below"), S.t("0 = off"), "low_batt", 0, 90, " %");
        sw(S.t("Volume keys adjust brightness"), S.t("Works while the curtain is shown"), "vol_keys");
        Ui.header(ui, S.t("Alerts"));
        sw(S.t("Vibrate periodically after the screen turns off"),
                S.t("Warns you when the screen turns off. It stops when the screen turns on or you turn off keep-awake, and it also vibrates if you turn the screen off yourself. While the screen is off the CPU is kept awake so the vibration is reliable, which uses more battery. Some systems (for example HarmonyOS) also need background activity allowed for this app."),
                "vib_off");
        action(S.t("Test vibration"),
                S.t("Vibrates once right now, the same way the screen-off reminder does, and reports the result. If this works but the reminder does not, the system is restricting background vibration: allow background activity and auto-start for this app and leave battery optimization off."),
                this::testVibration);
        num(S.t("Vibration interval"), null, "vib_iv", 2, 600, " s");
        Ui.header(ui, S.t("Maintenance"));
        action(S.t("Copy diagnostics"), S.t("Copies device and permission info, handy for feedback"), this::copyDiagnostics);
        action(S.t("Restore default settings"), S.t("Styles, pictures and all privacy protection settings are kept"), () ->
                new AlertDialog.Builder(this)
                        .setTitle(S.t("Restore default settings"))
                        .setMessage(S.t("Styles, pictures and all privacy protection settings are kept"))
                        .setPositiveButton(S.t("OK"), (d, w) -> {
                            if (Protect.needVerify(this, Protect.Purpose.RESET, page == P_BACKUP ? -1 : passEpoch)) {
                                unlocking = true;
                                startActivityForResult(VerifyActivity.intent(this, Protect.Purpose.RESET, false), REQ_LOCK_RESET);
                            } else {
                                resetDefaults();
                            }
                        })
                        .setNegativeButton(S.t("Cancel"), null)
                        .show());
        action(S.t("Clear all app data"),
                S.t("A full reset, like the system's \"Clear storage\": also removes pictures, granted permissions and the privacy password. The app closes immediately afterwards."),
                this::clearAllDataFlow);
    }

    private void clearAllDataFlow() {
        new AlertDialog.Builder(this)
                .setTitle(S.t("Clear all app data"))
                .setMessage(S.t("This erases everything Screen Keep-On has stored - settings, styles, background pictures, granted picture permissions, and the privacy password - the same as the system's own \"Clear storage\" button. It cannot be undone, and the app closes right away."))
                .setPositiveButton(S.t("Continue"), (d, w) -> new AlertDialog.Builder(this)
                        .setTitle(S.t("Clear everything now?"))
                        .setPositiveButton(S.t("Clear everything"), (d2, w2) -> {
                            if (Protect.needVerify(this, Protect.Purpose.POLICY)) {
                                unlocking = true;
                                startActivityForResult(VerifyActivity.intent(this, Protect.Purpose.POLICY, false), REQ_LOCK_CLEAR);
                            } else {
                                doClearAllData();
                            }
                        })
                        .setNegativeButton(S.t("Cancel"), null)
                        .show())
                .setNegativeButton(S.t("Cancel"), null)
                .show();
    }

    /**
     * 系统级的"清除此应用数据"，等同于 系统设置 → 应用信息 → 存储 → 清除存储空间：
     * 重置本应用的全部数据，包括已授予的图片访问权限（持久化 URI 授权），不只是设置项。
     * 不需要额外的文件读写权限。调用后系统通常会立刻结束本应用进程，之后的代码一般不会再执行到。
     */
    private void doClearAllData() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            boolean ok = am != null && am.clearApplicationUserData();
            if (!ok) toast(S.t("Could not clear app data"));
        } catch (Exception e) {
            toast(S.t("Could not clear app data"));
        }
    }

    private void resetDefaults() {
        String[] keep = {"style_active", "style_order", "styles", "active_colors", "active_imgs", "imgsets"};
        java.util.HashMap<String, Object> saved = new java.util.HashMap<>();
        for (String k : keep) saved.put(k, Prefs.getStr(this, k));
        // 隐私保护的全部配置和状态（pv_*、上锁状态、授权列表）以及服务运行标记永远保留：
        // 恢复默认设置不会关掉、改弱或清空保护。密码哈希、恢复码在单独的文件里，本来就不受影响。
        for (java.util.Map.Entry<String, ?> e : Prefs.sp(this).getAll().entrySet()) {
            if (Prefs.isProtectedKey(e.getKey())) saved.put(e.getKey(), e.getValue());
        }
        Prefs.sp(this).edit().clear().commit();
        for (java.util.Map.Entry<String, Object> e : saved.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Boolean) Prefs.putBool(this, e.getKey(), (Boolean) v);
            else if (v instanceof Integer) Prefs.putInt(this, e.getKey(), (Integer) v);
            else Prefs.putStr(this, e.getKey(), String.valueOf(v));
        }
        S.init(this);
        FloatCtl.refresh(this);
        toast(S.t("Restored"));
        build();
    }

    private void copyDiagnostics() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        StringBuilder sb = new StringBuilder();
        sb.append("Screen Keep-On ").append(version()).append("\n");
        sb.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT)
                .append(")\n").append(Build.DISPLAY).append('\n');
        sb.append("overlay=").append(Sys.canOverlay(this)).append('\n');
        sb.append("writeSettings=").append(BrightnessCtl.canWrite(this)).append('\n');
        if (Build.VERSION.SDK_INT >= 23) {
            sb.append("ignoreBatteryOpt=").append(pm.isIgnoringBatteryOptimizations(getPackageName())).append('\n');
        }
        if (Build.VERSION.SDK_INT >= 33) {
            sb.append("notifications=").append(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED).append('\n');
        }
        sb.append("a11yConnected=").append(KeepAccessibilityService.inst != null).append('\n');
        sb.append("running=").append(FloatCtl.running()).append('\n');
        sb.append("overlayLost=").append(FloatService.lostCount).append('\n');
        sb.append("curtainFails=").append(FloatService.curtainFails).append('\n');
        sb.append("vib: screenOffEvents=").append(FloatService.screenOffEvents)
                .append(" starts=").append(FloatService.vibStarts)
                .append(" pulses=").append(FloatService.vibPulses)
                .append(" alarmTicks=").append(FloatService.alarmTicks)
                .append(" err=").append(FloatService.vibError).append('\n');
        for (String k : new String[]{"use_ball", "use_curtain", "keep_method", "nowin_method", "bright_mode",
                "start_mode", "op_sec", "ball_edge", "auto_start", "enhance", "eye_on", "notif_enabled", "vib_off", "vib_iv",
                "pv_on", "pv_mode", "pv_locked", "pv_trig_timer", "pv_trig_screenoff", "pv_trig_leave", "pv_ui_notap",
                "pv_ui_back", "pv_ui_secure", "pv_ui_enhanced", "pv_sc_settings", "pv_sc_backup", "pv_fail_limit",
                "pv_fail_cool", "pv_fail_wipe_files", "pv_fail_wipe_data", "svc_alive"}) {
            if (Prefs.DEF.get(k) instanceof Boolean) sb.append(k).append('=').append(Prefs.getBool(this, k));
            else if (Prefs.DEF.get(k) instanceof Integer) sb.append(k).append('=').append(Prefs.getInt(this, k));
            else sb.append(k).append('=').append(Prefs.getStr(this, k));
            sb.append('\n');
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("diagnostics", sb.toString()));
        toast(S.t("Copied"));
    }

    private void pageBackup() {
        Ui.header(ui, S.t("Backup & restore"));
        action(S.t("Back up to a file"), S.t("Settings and styles, optionally with the imported pictures"), () ->
                new AlertDialog.Builder(this)
                        .setTitle(S.t("Back up to a file"))
                        .setMessage(S.t("Include the pictures used as backgrounds? Pictures that can no longer be found are skipped. Restoring overwrites pictures with the same file name."))
                        .setPositiveButton(S.t("With pictures (.zip)"), (d, w) -> startExport(true))
                        .setNegativeButton(S.t("Settings only (.json)"), (d, w) -> startExport(false))
                        .setNeutralButton(S.t("Cancel"), null)
                        .show());
        action(S.t("Restore from a file"), S.t("Overwrites the current settings and styles"), () -> {
            if (backupBlocked(() -> startRestorePicker())) return;
            startRestorePicker();
        });
    }

    @SuppressWarnings("deprecation")
    private void testVibration() {
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) {
                toast(S.t("This device has no vibrator."));
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createOneShot(500, android.os.VibrationEffect.DEFAULT_AMPLITUDE),
                        new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            } else {
                v.vibrate(500);
            }
            toast(S.t("Vibration was requested. If you felt nothing, check the system vibration and Do Not Disturb settings."));
        } catch (Exception e) {
            toast(S.t("Vibration failed: ") + e);
        }
    }

    private void startRestorePicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (ActivityNotFoundException e) {
            toast(S.t("No file manager available"));
        }
    }

    /** 备份/恢复执行前再确认一次授权（验证有效期可能已经过了）。返回 true 表示已转去验证，调用方应中止。 */
    private boolean backupBlocked(Runnable then) {
        if (!Protect.needVerify(this, Protect.Purpose.BACKUP, page == P_BACKUP ? passEpoch : -1)) return false;
        afterVerify = then;
        unlocking = true;
        startActivityForResult(VerifyActivity.intent(this, Protect.Purpose.BACKUP, false), REQ_LOCK_BACKUP);
        return true;
    }

    private void startExport(boolean zip) {
        if (backupBlocked(() -> startExport(zip))) return;
        pendingZip = zip;
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH_mm_ss", Locale.US).format(new Date());
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(zip ? "application/zip" : "application/json")
                .putExtra(Intent.EXTRA_TITLE, "ScreenTool_backup_" + stamp + (zip ? ".zip" : ".json"));
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (ActivityNotFoundException e) {
            toast(S.t("No file manager available"));
        }
    }

    private void pageAbout() {
        String ver = version();
        Ui.row(ui, S.t("Screen Keep-On"), S.t("Version") + " " + ver + "  ·  MIT License", null);
        Ui.row(ui, S.t("About this app"),
                S.t("Keeps the screen on while long-running apps do their work. A black curtain with adjustable brightness saves power, and everything keeps running in the background. Features load only when you use them, and nothing runs when there is nothing to show."), null);
        Ui.row(ui, S.t("Three ways to keep awake"),
                S.t("Floating ball: a small ball docked at the screen edge with an optional stopwatch. Tap it to open the curtain.\nCurtain: a black cover over the screen. Tap it or press Back to return to the ball.\nNotification only: no windows at all; the notification and a wake lock keep the screen on."), null);
        Ui.row(ui, S.t("Home page gestures"),
                S.t("Swipe up or down: adjust brightness\nSwipe left: open settings\nSwipe right: turn on keep-awake\nDouble-tap: exit the home page\nBack key: hide the app (it stays black in the recent tasks)"), null);
        Ui.row(ui, S.t("Curtain gestures"),
                S.t("Swipe up or down, or use the volume keys: adjust brightness\nTap or press Back: return to the ball (taps are ignored when the operation window is 0). When the curtain is locked, a tap or Back asks you to verify first, depending on the privacy settings."), null);
        Ui.row(ui, S.t("Ball tips"),
                S.t("After 5 seconds without a touch the ball slides three quarters into the screen edge. Touch it to bring it back, then tap again to open the curtain. Drag it to move it."), null);
        Ui.row(ui, S.t("Settings pages"), S.t("Swipe right to go back"), null);
        Ui.row(ui, S.t("Quick switches"),
                S.t("Long-press the app icon for shortcuts: floating ball, curtain, notification only, stop. Some systems also offer a quick-settings tile, but not all (for example HarmonyOS)."), null);
        Ui.row(ui, S.t("Background pictures"),
                S.t("In the style editor, tap \"Image\" next to a background color to use one or more pictures as the background. Several pictures play as a slideshow. Pictures are only linked, and no storage permission is used."), null);
        Ui.row(ui, S.t("Known limits"),
                S.t("• Some system apps (for example Notes) use a higher window layer and may cover or intercept the floating windows.\n• On HarmonyOS the floating windows may only appear above other apps, not on the desktop, and they can disappear when the app hosting them is cleared. The app tries to restore them automatically.\n• If you cannot see a floating window, turn on the enhanced mode.\n• Normal floating windows cannot cover the status bar; the enhanced mode can."), null);
        Ui.row(ui, S.t("Privacy protection"),
                S.t("Settings > Privacy protection can lock the curtain full screen and protect this app's Settings, backup and protection settings. You choose how strongly: how to verify, what locks it, how it behaves, what a verification authorizes and what happens after repeated failures. Everything is off by default. It only protects this app: it cannot lock other apps, and the system can always stop or uninstall the app. Emergency release: press and hold the locked curtain; it removes the cover but does not open protected data."), null);
        action(S.t("Open source"),
                S.t("Released under the MIT License. Source code, documentation and releases: https://github.com/scibob1/ScreenTool\nThe app does not check for updates by itself; this link is only a guide. Tap to open it."),
                () -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/scibob1/ScreenTool")));
                    } catch (Exception e) {
                        ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
                                android.content.ClipData.newPlainText("url", "https://github.com/scibob1/ScreenTool"));
                        toast(S.t("No browser found. The link was copied."));
                    }
                });
    }

    // ================= 行的构造 =================

    private View action(String title, String sub, final Runnable r) {
        View v = Ui.row(ui, title, sub, null);
        v.setOnClickListener(x -> r.run());
        return v;
    }

    private void nav(String title, String sub, final int target) {
        TextView arrow = Ui.tv(this, "›", 22, ui.fg2);
        View v = Ui.row(ui, title, sub, arrow);
        v.setOnClickListener(x -> startActivity(new Intent(this, SettingsActivity.class).putExtra("page", target)));
    }

    private void sw(String title, String sub, final String key) {
        final Switch s = new Switch(this);
        s.setChecked(Prefs.getBool(this, key));
        s.setOnCheckedChangeListener((b, on) -> {
            Prefs.putBool(this, key, on);
            FloatCtl.refresh(this);
        });
        View r = Ui.row(ui, title, sub, s);
        r.setOnClickListener(v -> s.toggle());
    }

    private void choiceInt(String title, String key, String[] labels) {
        choiceInt(title, key, labels, false);
    }

    /** 以数字序号保存的单选；asBool 为 true 时按布尔值保存（序号 1 = true）。 */
    private void choiceInt(final String title, final String key, final String[] labels, final boolean asBool) {
        int c = asBool ? (Prefs.getBool(this, key) ? 1 : 0) : Prefs.getInt(this, key);
        final int cur = Math.max(0, Math.min(labels.length - 1, c));
        View r = Ui.row(ui, title, labels[cur], null);
        r.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, cur, (d, w) -> {
                    if (asBool) Prefs.putBool(this, key, w == 1);
                    else Prefs.putInt(this, key, w);
                    d.dismiss();
                    FloatCtl.refresh(this);
                    build();
                }).show());
    }

    private void choiceStr(final String title, final String key, final String[] vals, final String[] labels) {
        String c = Prefs.getStr(this, key);
        int idx = 0;
        for (int i = 0; i < vals.length; i++) if (vals[i].equals(c)) idx = i;
        final int cur = idx;
        View r = Ui.row(ui, title, labels[cur], null);
        r.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, cur, (d, w) -> {
                    Prefs.putStr(this, key, vals[w]);
                    d.dismiss();
                    S.init(this);
                    FloatCtl.refresh(this);
                    build();
                }).show());
    }

    private void num(final String title, final String hint, final String key, final int min, final int max,
                     final String unit) {
        final int cur = Prefs.getInt(this, key);
        View r = Ui.row(ui, title, cur + unit + (hint != null ? "\n" + hint : ""), null);
        r.setOnClickListener(v -> {
            final EditText et = new EditText(this);
            et.setInputType(InputType.TYPE_CLASS_NUMBER);
            et.setText(String.valueOf(cur));
            et.setSelectAllOnFocus(true);
            FrameLayout box = new FrameLayout(this);
            int pad = Sys.dp(this, 20);
            box.setPadding(pad, pad / 2, pad, 0);
            box.addView(et);
            String msg = min + " – " + max + unit + (hint != null ? "\n\n" + hint : "");
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(msg)
                    .setView(box)
                    .setPositiveButton(S.t("OK"), (d, w) -> {
                        try {
                            int val = Integer.parseInt(et.getText().toString().trim());
                            val = Math.max(min, Math.min(max, val));
                            Prefs.putInt(this, key, val);
                            FloatCtl.refresh(this);
                            build();
                        } catch (NumberFormatException ignored) {
                        }
                    })
                    .setNegativeButton(S.t("Cancel"), null)
                    .show();
        });
    }

    /** 颜色行：修改当前风格里对应的颜色槽位；若当前是内置风格，会先复制成自定义风格。 */
    private void colorRow(String title, final int slot) {
        final StyleModel st = StyleStore.active(this);
        final int cur = st.c[slot];
        View r = Ui.row(ui, title, String.format("#%08X", cur), Ui.swatch(this, cur, ui.fg2));
        r.setOnClickListener(v -> ColorPickerDialog.show(this, cur, col -> {
            StyleModel e = StyleStore.editableActive(this);
            e.c[slot] = col;
            StyleStore.upsert(this, e);
            FloatCtl.refresh(this);
            build();
        }));
    }

    // ================= 对话框与回调 =================

    private int pct(String key) {
        return Math.round(Prefs.getFloat(this, key) * 100);
    }

    private void rectDialog() {
        final String[] keys = {"cur_l", "cur_t", "cur_r", "cur_b"};
        final String[] names = {S.t("Left"), S.t("Top"), S.t("Right"), S.t("Bottom")};
        final EditText[] ets = new EditText[4];
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Sys.dp(this, 20);
        box.setPadding(pad, pad / 2, pad, 0);
        for (int i = 0; i < 4; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView n = new TextView(this);
            n.setText(names[i] + " (%)");
            row.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
            ets[i] = new EditText(this);
            ets[i].setInputType(InputType.TYPE_CLASS_NUMBER);
            ets[i].setText(String.valueOf(pct(keys[i])));
            row.addView(ets[i], new LinearLayout.LayoutParams(Sys.dp(this, 90), -2));
            box.addView(row);
        }
        new AlertDialog.Builder(this)
                .setTitle(S.t("Curtain area (numbers)"))
                .setView(box)
                .setPositiveButton(S.t("OK"), (d, w) -> {
                    try {
                        int[] v = new int[4];
                        for (int i = 0; i < 4; i++) {
                            v[i] = Math.max(0, Math.min(100, Integer.parseInt(ets[i].getText().toString().trim())));
                        }
                        if (v[2] - v[0] < 5 || v[3] - v[1] < 5) {
                            toast(S.t("The area is too small"));
                            return;
                        }
                        for (int i = 0; i < 4; i++) Prefs.putFloat(this, keys[i], v[i] / 100f);
                        FloatCtl.refresh(this);
                        build();
                    } catch (NumberFormatException e) {
                        toast(S.t("Please enter numbers"));
                    }
                })
                .setNegativeButton(S.t("Cancel"), null)
                .show();
    }

    private void safeStart(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            toast(S.t("Cannot open this system page"));
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_LOCK) {
            unlocking = false;
            if (res != Activity.RESULT_OK) {
                finish();   // 没有通过验证就不进入设置页
            } else {
                passEpoch = Protect.epoch();
                S.init(this);
                build();
            }
            return;
        }
        if (req == REQ_LOCK_RESET) {
            unlocking = false;
            if (res == Activity.RESULT_OK) resetDefaults();
            return;
        }
        if (req == REQ_LOCK_BACKUP) {
            unlocking = false;
            Runnable r = afterVerify;
            afterVerify = null;
            if (res == Activity.RESULT_OK) {
                passEpoch = Protect.epoch();
                if (r != null) r.run();
            }
            return;
        }
        if (req == REQ_LOCK_CLEAR) {
            unlocking = false;
            if (res == Activity.RESULT_OK) doClearAllData();
            return;
        }
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        try {
            if (req == REQ_EXPORT) {
                Backup.write(this, u, pendingZip);
                toast(S.t("Backup saved"));
            } else if (req == REQ_IMPORT) {
                Backup.read(this, u);
                S.init(this);
                FloatCtl.refresh(this);
                toast(S.t("Restored"));
                build();
            }
        } catch (Exception e) {
            toast(S.t("Failed") + ": " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_NOTIF) build();
    }
}
