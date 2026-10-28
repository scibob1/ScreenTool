package com.wave.screentool;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.service.quicksettings.TileService;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * 悬浮前台服务：让屏幕保持常亮的核心。
 *
 * 保持常亮的手段：
 *  1. 悬浮窗带 FLAG_KEEP_SCREEN_ON：窗口显示着，系统就不会自动熄屏；
 *  2. 屏幕唤醒锁（WakeLock）：不需要任何窗口。部分系统（如鸿蒙）会无视它，
 *     所以“无窗口”状态下还会放一个看不见的 1 像素窗口来兜底。
 *
 * 形态：悬浮球 / 悬浮幕布 / 无窗口（仅通知）。每种视图用到才创建，不用就销毁。
 */
public class FloatService extends Service implements CurtainView.Host {
    static final String ACT_STOP = "com.wave.screentool.STOP";
    static final String ACT_RESET = "com.wave.screentool.RESET";
    static final String ACT_TOGGLE = "com.wave.screentool.TOGGLE";
    static final String ACT_REFRESH = "com.wave.screentool.REFRESH";
    static final String ACT_SETMODE = "com.wave.screentool.SETMODE";
    static final String ACT_BRIGHT_UP = "com.wave.screentool.BRIGHT_UP";
    static final String ACT_BRIGHT_DOWN = "com.wave.screentool.BRIGHT_DOWN";
    static final String ACT_SKIP_REST = "com.wave.screentool.SKIP_REST";
    static final String ACT_VERIFY_BEGIN = "com.wave.screentool.VERIFY_BEGIN";
    static final String ACT_VERIFY_END = "com.wave.screentool.VERIFY_END";
    static final String ACT_LOCK = "com.wave.screentool.LOCK";
    private static final String CHANNEL = "keepon";
    private static final int NOTIF_ID = 1;

    static volatile boolean running;
    static volatile int lostCount;   // 悬浮窗被系统移除的次数（诊断信息里显示）
    static volatile int fgsMode;     // 前台通知形态：0 无 1 完整 2 静默兜底（自检页显示）

    private static final int[] OX = {0, 1, 1, 0}, OY = {0, 0, 1, 1};   // 防烧屏微移的 4 个位置

    private WindowManager wm;
    private boolean usingA11y;
    private Handler h;
    private BallView ball;
    private CurtainView curtain;
    private KeeperView keeper;                 // 无窗口状态下的隐形 1 像素常亮窗口
    private WindowManager.LayoutParams ballLp, curLp, keeperLp;
    private SlideShow curShow;
    private boolean curtainMode;   // 最近一次的窗口形态是幕布
    private boolean resting;       // 护眼休息中
    private boolean inited;
    private boolean charging = true;
    private boolean battReg, screenReg;
    private long base;             // 秒表起点
    private long sessionStart;     // 服务启动时刻
    private long restEnd;
    private long eyeAt;            // 下次护眼休息的时刻（elapsedRealtime）
    private float bright;
    private StyleModel st, eyeSt;
    private PowerManager.WakeLock wl;
    private Vibrator vib;
    private String forcedMode;     // 本次启动指定的形态（快捷方式/自动启动）
    private boolean screenOn = true;
    private boolean channelMade;
    private int dpBall, dpCur;     // 防烧屏位移的像素值（只算一次）
    private long autoOffAt;        // 定时关闭的时刻（elapsedRealtime）
    private int recoverTries;
    private boolean ballHidden;        // 悬浮球当前是否处于“贴边半隐藏”状态
    private boolean verifying;         // 验证页正在显示：遮罩/悬浮球已撤掉，暂停自动重新挂载
    private VerifyOverlay overlay;     // 画在幕布之上的验证悬浮窗（只验证自定义密码时用）
    private Protect.Purpose pendingPurpose;
    private Runnable pendingCont;      // 验证通过后要继续做的事（收起幕布 / 停止服务 ...）
    private boolean keepStopped;       // 低电量/定时关闭后：常亮已停止，但上锁中所以服务和遮罩保留
    private boolean vibOn;
    private PowerManager.WakeLock vibWl;
    private long ballAt, curAt, keeperAt;   // 各窗口最近一次成功挂载的时刻
    private long lockAt;                    // 定时上锁的时刻（elapsedRealtime），0=无
    private long lastHint;
    private boolean warnedEnh;
    private final Protect.Listener lockListener = this::onLockChanged;
    private ValueAnimator edgeAnim;
    private String lastSig = "";   // 影响窗口外观的设置“指纹”，没变就不重建窗口

    // 缓存的设置，避免每秒读取
    private boolean allowBall, allowCurtain, cBallSw, cCurSw, cCurTime, cBurn, cBallDim, cBallGrow,
            cChargeOnly, cResetOnBall, cVol, cEnhance, cEyeOn, cNoteBtn, cVib, cCover, cEdge, cSecure,
            cNotifEnabled, cPvEnh;
    private int cBallClock, cAdapt, cOpSec, cBallDp, cBallShape, cLowBatt, cEyeIv, cEyeLen, cEyeWarn, cVibIv, cEmerg, cLockMin;
    private boolean cEmergOn = true;
    private boolean cTrigExpand;
    private boolean launching;          // 验证页已发出启动请求、还没收到“已显示”回报（期间不重复发请求）
    private int curFlagsBackup;
    private String cKeep, cNoWin;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshTexts();
            scheduleTick();
        }
    };
    private final Runnable autoExpand = () -> {
        if (ball != null) showCurtain();
    };
    private final Runnable autoOff = this::checkAutoOff;
    private final Runnable recoverRun = this::ensureWindows;
    private final Runnable verifyTimeout = () -> {      // 验证页异常没有回报：90 秒后自己恢复遮罩
        verifying = false;
        pendingCont = null;
        restoreWindows();
    };
    private final Runnable verifyLaunchWd = () -> {     // 系统验证页没能打开：不放行，保持上锁并说明
        launching = false;
        if (!verifying) {
            pendingCont = null;
            Toast.makeText(FloatService.this, S.t("The system verification screen could not be opened, so the cover stays locked. Hold the curtain for the emergency release, or open the app and verify there."), Toast.LENGTH_LONG).show();
        }
    };
    private final Runnable lockTimerRun = this::lockTimerFire;
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (inited) {
                if (screenOn) ensureWindows();
                h.postDelayed(this, 15_000L);
            }
        }
    };
    private final Runnable vibRun = new Runnable() {
        @Override
        public void run() {
            if (!vibOn || !inited) return;
            pulse();
            h.postDelayed(this, cVibIv * 1000L);
        }
    };
    private final Runnable stableRun = () -> recoverTries = 0;
    private final Runnable edgeRun = this::hideBall;
    private final Runnable eyeCheck = this::checkEye;
    private final Runnable eyeEnd = this::endRest;
    private final Runnable eyeWarnR = () -> {
        // 提前预告：短 Toast
        if (cEyeOn && !resting && SystemClock.elapsedRealtime() < eyeAt) {
            Toast.makeText(this, S.f("Eye rest starts in %d s", cEyeWarn), Toast.LENGTH_SHORT).show();
        }
    };

    private final BroadcastReceiver battReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            boolean ch = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            int lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int pct = sc > 0 ? lvl * 100 / sc : -1;
            if (ch != charging) {
                charging = ch;
                updateKeepAll();
            }
            if (!ch && cLowBatt > 0 && pct >= 0 && pct <= cLowBatt) {
                stopKeepOrSelf(S.t("Battery is low, keep-on stopped"));
            }
        }
    };

    /**
     * 屏幕熄灭：暂停秒表刷新和幻灯片（没人看就不耗电），按设置开始间歇震动；
     * 屏幕点亮：恢复刷新，并补算护眼休息/定时关闭（手机休眠时定时器会被拉长），检查悬浮窗是否还在。
     */
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                screenOn = false;
                h.removeCallbacks(tick);
                if (curShow != null) curShow.stop();
                // 触发条件“熄屏后上锁”：必须统一走保护控制器，上锁后遮罩和解除规则都一样
                if (Protect.armed(FloatService.this) && Prefs.getBool(FloatService.this, "pv_trig_screenoff")) {
                    Protect.lockNow(FloatService.this, false);
                }
                if (cVib) startVibration();
            } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                screenOn = true;
                stopVibration();
                refreshTexts();
                scheduleTick();
                scheduleEdge();
                if (curShow != null) curShow.start();
                checkEye();
                checkAutoOff();
                lockTimerFire0();
                recheckWindowsSoon();
            } else {   // 解锁屏幕
                stopVibration();
                recheckWindowsSoon();
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent == null ? null : intent.getAction();
        String mode = intent == null ? null : intent.getStringExtra("mode");
        if (ACT_STOP.equals(act)) {
            if (inited && Protect.needVerify(this, Protect.Purpose.STOP)) {
                // 上锁期间停止服务等于拿掉遮罩：必须先验证，不能让任何旧入口绕过
                Toast.makeText(this, S.t("Locked: verify to stop"), Toast.LENGTH_SHORT).show();
                requestVerify(Protect.Purpose.STOP, this::stopNow);
                return START_STICKY;
            }
            stopNow();
            return START_NOT_STICKY;
        }
        if (!inited) {
            forcedMode = mode;
            if (!init()) {
                stopSelf();
                return START_NOT_STICKY;
            }
            return START_STICKY;
        }
        if (act == null) {
            if (mode != null) applyMode(mode);
            return START_STICKY;
        }
        switch (act) {
            case ACT_RESET:
                resetWatch();
                break;
            case ACT_TOGGLE:
                if (resting) break;
                if (curtain != null) requestCollapse();
                else showCurtainOrBall();
                break;
            case ACT_REFRESH:
                reloadAll();
                break;
            case ACT_SETMODE:
                applyMode(mode);
                break;
            case ACT_BRIGHT_UP:
            case ACT_BRIGHT_DOWN:
                bright = Math.max(0f, Math.min(1f, bright + (ACT_BRIGHT_UP.equals(act) ? 0.1f : -0.1f)));
                Prefs.putFloat(this, "brightness", bright);
                applyBrightnessForPhase();
                break;
            case ACT_SKIP_REST:
                skipRest();
                break;
            case ACT_LOCK:
                if (Protect.armed(this)) Protect.lockNow(this, false);
                break;
            case ACT_VERIFY_BEGIN:
                beginVerifyUi();
                break;
            case ACT_VERIFY_END:
                endVerifyUi(intent.getBooleanExtra("ok", false), intent.getBooleanExtra("for_service", false));
                break;
            default:
                break;
        }
        return START_STICKY;
    }

    private boolean init() {
        S.initLite(this);
        h = new Handler(Looper.getMainLooper());
        base = SystemClock.elapsedRealtime();
        sessionStart = base;
        PowerManager pmi = (PowerManager) getSystemService(POWER_SERVICE);
        screenOn = pmi == null || pmi.isInteractive();
        loadCfg();
        try {
            // API 33+: 若系统拒绝了通知权限而用户又想看通知，用完整版通知（含操作按钮/文本），
            // 它在系统层面被视为“合法的”前台通知，能通过国产 ROM 的幽灵服务检测；
            // 同时因为系统已拒绝通知权限，用户并不会在通知栏看到它。
            boolean useFull = cNotifEnabled || !hasNotificationPermission();
            Notification n = useFull ? buildNotification() : buildSilentNotification();
            startForegroundSafe(n);
        } catch (Exception e) {
            // 通知/前台服务失败：Bucket1 用完整通知（防幽灵检测），Bucket2 用静默版兜底
            try {
                startForegroundSafe(buildNotification());
            } catch (Exception e2) {
                try {
                    startForegroundSafe(buildSilentNotification());
                } catch (Exception e3) {
                    return false;
                }
            }
        }
        fgsMode = (cNotifEnabled || !hasNotificationPermission()) ? 1 : 2;
        inited = true;
        running = true;
        Protect.listener = lockListener;
        Prefs.putBool(this, "svc_alive", true);   // 被系统杀掉后，下次打开应用时据此自动恢复
        lastSig = viewSig();
        updateBatteryReceiver();
        updateScreenReceiver();
        scheduleAutoOff();
        scheduleEye();
        enterStartMode();
        scheduleLockTimer();
        h.postDelayed(watchdog, 15_000L);
        FailPolicy.resumePending(this);
        if (!screenOn && cVib) startVibration();
        notifyTile();
        return true;
    }

    private void startForegroundSafe(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void enterStartMode() {
        if (lockedNow()) {   // 重启/被杀后恢复：上锁状态是持久的，直接回到上锁的遮罩
            showCurtain();
            return;
        }
        boolean startCurtain = "curtain".equals(forcedMode)
                || (forcedMode == null && "curtain".equals(Prefs.getStr(this, "start_mode")));
        if (allowBall && !allowCurtain) startCurtain = false;
        if (!allowBall && allowCurtain) startCurtain = true;
        if (startCurtain && allowCurtain) showCurtain();
        else if (allowBall) showBall();
        else goNone();
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void loadCfg() {
        st = StyleStore.active(this);
        bright = Prefs.getFloat(this, "brightness");
        cBallSw = Prefs.getBool(this, "ball_sw");
        cCurSw = Prefs.getBool(this, "cur_sw");
        cCurTime = Prefs.getBool(this, "cur_time");
        cBurn = Prefs.getBool(this, "burn");
        cBallDim = Prefs.getBool(this, "ball_dim");
        cBallGrow = Prefs.getBool(this, "ball_grow");
        cChargeOnly = Prefs.getBool(this, "charge_only");
        cResetOnBall = Prefs.getBool(this, "reset_on_ball");
        cVol = Prefs.getBool(this, "vol_keys");
        cEnhance = Prefs.getBool(this, "enhance");
        cEyeOn = Prefs.getBool(this, "eye_on");
        cNoteBtn = Prefs.getBool(this, "note_actions");
        cVib = Prefs.getBool(this, "vib_off");
        cCover = Prefs.getBool(this, "cover_bars");
        cEdge = Prefs.getBool(this, "ball_edge");
        cSecure = Protect.armed(this) && Prefs.getBool(this, "pv_ui_secure");
        cPvEnh = Protect.armed(this) && Prefs.getBool(this, "pv_ui_enhanced");
        cEmerg = Math.max(5, Prefs.getInt(this, "pv_emerg_sec"));
        cEmergOn = Prefs.getBool(this, "pv_emerg_on");
        cTrigExpand = Protect.armed(this) && Prefs.getBool(this, "pv_trig_expand");
        cLockMin = Protect.armed(this) ? Prefs.getInt(this, "pv_trig_timer") : 0;
        cNotifEnabled = Prefs.getBool(this, "notif_enabled");
        cBallClock = Prefs.getInt(this, "ball_clock");
        cAdapt = Math.max(1, Prefs.getInt(this, "adapt_min"));
        cOpSec = Prefs.getInt(this, "op_sec");
        cBallDp = Math.max(24, Prefs.getInt(this, "ball_dp"));
        cBallShape = Prefs.getInt(this, "ball_shape");
        cLowBatt = Prefs.getInt(this, "low_batt");
        cEyeIv = Math.max(1, Prefs.getInt(this, "eye_iv"));
        cEyeLen = Math.max(5, Prefs.getInt(this, "eye_len"));
        cEyeWarn = Math.max(0, Prefs.getInt(this, "eye_warn"));
        cVibIv = Math.max(2, Prefs.getInt(this, "vib_iv"));
        dpBall = Sys.dp(this, 2);
        dpCur = Sys.dp(this, 6);
        cKeep = Prefs.getStr(this, "keep_method");
        cNoWin = Prefs.getStr(this, "nowin_method");
        eyeSt = st;
        if (cEyeOn) {   // 护眼专用风格：用到才读取
            String es = Prefs.getStr(this, "eye_style");
            if (es.length() > 0) {
                StyleModel x = StyleStore.get(this, es);
                if (x != null) eyeSt = x;
            }
        }
        boolean ub = Prefs.getBool(this, "use_ball"), uc = Prefs.getBool(this, "use_curtain");
        if ("notif".equals(forcedMode)) {
            allowBall = false;
            allowCurtain = false;
        } else if ("ball".equals(forcedMode)) {
            allowBall = true;
            allowCurtain = uc;
        } else if ("curtain".equals(forcedMode)) {
            allowCurtain = true;
            allowBall = ub;
        } else {
            allowBall = ub;
            allowCurtain = uc;
        }
    }

    /** 会影响窗口外观/结构的设置的“指纹”。指纹没变就只更新数据，不重建窗口（避免闪烁和无谓开销）。 */
    private String viewSig() {
        StringBuilder sb = new StringBuilder(200);
        sb.append(Prefs.getStr(this, "style_active")).append('|')
                .append(Prefs.getStr(this, "active_colors")).append('|')
                .append(Prefs.getStr(this, "active_imgs")).append('|')
                .append(Prefs.getStr(this, "imgsets").hashCode()).append('|')
                .append(cBallDp).append(cBallShape).append(cBallGrow)
                .append(cSecure).append(cPvEnh).append(cEmerg).append(cEmergOn).append(cTrigExpand).append(allowBall).append(allowCurtain).append(cEnhance).append(cCover).append(cVol).append(cNoteBtn)
                .append(Prefs.getFloat(this, "cur_l")).append(',').append(Prefs.getFloat(this, "cur_t")).append(',')
                .append(Prefs.getFloat(this, "cur_r")).append(',').append(Prefs.getFloat(this, "cur_b"));
        return sb.toString();
    }

    private void reloadAll() {
        S.initLite(this);
        loadCfg();
        updateBatteryReceiver();
        scheduleAutoOff();
        scheduleLockTimer();
        if (!screenOn) {
            if (cVib) startVibration();
            else stopVibration();
        }
        if (!Protect.armed(this) && keepStopped) keepStopped = false;
        String sig = viewSig();
        boolean same = sig.equals(lastSig);
        lastSig = sig;
        if (resting) return;
        scheduleEye();
        if (same && (ball != null || curtain != null || keeper != null)) {
            updateKeepAll();
            applyBrightnessForPhase();
            updateNotification();
            refreshTexts();
            scheduleTick();
            if (cEdge) scheduleEdge();
            else revealBall();
            return;
        }
        removeKeeper();
        if (curtain != null && (allowCurtain || lockedNow())) {
            removeCurtain();
            showCurtain();
        } else if (ball != null && allowBall) {
            removeBall();
            showBall();
        } else {
            removeBall();
            removeCurtain();
            enterStartMode();
        }
    }

    /** 运行中切换形态（快捷方式/通知）。 */
    private void applyMode(final String mode) {
        if (mode == null || resting) return;
        if (lockedNow() && !"curtain".equals(mode)) {
            // 上锁期间切到球/仅通知会拿掉遮罩：先验证
            requestVerify(Protect.Purpose.UNLOCK, () -> applyMode(mode));
            return;
        }
        forcedMode = mode;
        loadCfg();
        if ("notif".equals(mode)) {
            goNone();
        } else if ("curtain".equals(mode)) {
            showCurtain();
        } else {
            showBall();
        }
    }

    // ---------- 通知 ----------

    private PendingIntent svcIntent(String act, int code) {
        int pf = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, code, new Intent(this, FloatService.class).setAction(act), pf);
    }

    /** 通知按钮：保护开启时，会降低保护的按钮直接指向验证页（Activity），不经过后台跳转，也不绕过保护。 */
    private PendingIntent actIntent(String mode, int code) {
        int pf = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Intent i = new Intent(this, ToggleActivity.class).putExtra("mode", mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return PendingIntent.getActivity(this, code, i, pf);
    }

    private Notification buildNotification() {
        ensureChannel();
        int pf = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), pf);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        long wallBase = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - base);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(S.t("Screen keep-on is running"))
                .setContentText(S.t("Tap to open the app"))
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(true)
                .setWhen(wallBase)
                .setUsesChronometer(true);   // 由系统绘制的秒表，应用本身不用每秒唤醒
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        if (cNoteBtn) {   // 设置里可以关闭通知栏操作按钮
            boolean windowless = ball == null && curtain == null;
            boolean armed = Protect.armed(this);
            PendingIntent stopPi = armed ? actIntent("stop", 4) : svcIntent(ACT_STOP, 4);
            PendingIntent togglePi = armed ? actIntent("toggle", 2) : svcIntent(ACT_TOGGLE, 2);
            if (resting) {
                b.addAction(R.drawable.ic_stat, S.t("Skip rest"), svcIntent(ACT_SKIP_REST, 5));
                b.addAction(R.drawable.ic_stat, S.t("Stop"), stopPi);
            } else if (windowless && !allowBall && !allowCurtain) {
                b.addAction(R.drawable.ic_stat, S.t("Dimmer"), svcIntent(ACT_BRIGHT_DOWN, 6));
                b.addAction(R.drawable.ic_stat, S.t("Brighter"), svcIntent(ACT_BRIGHT_UP, 7));
                b.addAction(R.drawable.ic_stat, S.t("Stop"), stopPi);
            } else if (windowless) {
                b.addAction(R.drawable.ic_stat, S.t("Expand"), togglePi);
                if (armed && !lockedNow()) b.addAction(R.drawable.ic_stat, S.t("Lock"), actIntent("lock", 8));   // 通知最多显示 3 个按钮：保护开着时“上锁”顶替“重置计时”
                else b.addAction(R.drawable.ic_stat, S.t("Reset timer"), svcIntent(ACT_RESET, 3));
                b.addAction(R.drawable.ic_stat, S.t("Stop"), stopPi);
            } else {
                b.addAction(R.drawable.ic_stat, S.t("Ball/Curtain"), togglePi);
                if (armed && !lockedNow()) b.addAction(R.drawable.ic_stat, S.t("Lock"), actIntent("lock", 8));   // 通知最多显示 3 个按钮：保护开着时“上锁”顶替“重置计时”
                else b.addAction(R.drawable.ic_stat, S.t("Reset timer"), svcIntent(ACT_RESET, 3));
                b.addAction(R.drawable.ic_stat, S.t("Stop"), stopPi);
            }
        }
        return b.build();
    }

    /**
     * 最低限度的静默通知：用于不需要在通知栏显示内容的场景。
     * 必须包含 contentTitle + contentText，部分国产 ROM 会根据通知内容判断
     * 前台服务是否“合法”——仅有标题的极简通知会被视为幽灵服务并杀掉进程。
     */
    @SuppressWarnings("deprecation")
    private Notification buildSilentNotification() {
        ensureChannel();
        int pf = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), pf);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(S.t("Screen Keep-On"))
                .setContentText(S.t("Screen keep-on is running"))
                .setOngoing(true)
                .setContentIntent(open)
                .setShowWhen(false);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        return b.build();
    }

    private void updateNotification() {
        if (!inited) return;
        try {
            Notification n = cNotifEnabled ? buildNotification() : buildSilentNotification();
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, n);
        } catch (Exception ignored) {
        }
    }

    private void ensureChannel() {
        if (channelMade) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, S.t("Keep-on service"),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            try { nm.createNotificationChannel(ch); } catch (Exception ignored) {}
        }
        channelMade = true;
    }

    private void notifyTile() {
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                TileService.requestListeningState(this, new ComponentName(this, ToggleTileService.class));
            } catch (Exception ignored) {
            }
        }
    }

    // ---------- 常亮手段、亮度、电池、震动 ----------

    private boolean keepFlagOn() {
        return !keepStopped && !"wakelock".equals(cKeep) && (!cChargeOnly || charging);
    }

    @SuppressWarnings("deprecation")
    private void updateWake() {
        boolean windowless = ball == null && curtain == null;
        boolean allowed = !(windowless && "keeper".equals(cNoWin));
        boolean want = allowed && !keepStopped && (!"window".equals(cKeep) || windowless) && (!cChargeOnly || charging);
        try {
            if (want) {
                if (wl == null) {
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "screentool:keepon");
                    wl.setReferenceCounted(false);
                }
                if (!wl.isHeld()) wl.acquire();
            } else if (wl != null && wl.isHeld()) {
                wl.release();
            }
        } catch (Exception ignored) {
        }
    }

    private void applyKeepFlag() {
        int keep = keepFlagOn() ? WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON : 0;
        if (ball != null && ballLp != null) {
            ballLp.flags = (ballLp.flags & ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) | keep;
            safeUpdate(ball, ballLp);
        }
        if (curtain != null && curLp != null) {
            curLp.flags = (curLp.flags & ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) | keep;
            safeUpdate(curtain, curLp);
        }
        if (keeper != null && keeperLp != null) {
            keeperLp.flags = (keeperLp.flags & ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) | keep;
            safeUpdate(keeper, keeperLp);
        }
    }

    private void updateKeepAll() {
        applyKeepFlag();
        updateWake();
    }

    /** 当前阶段是否需要低亮度：幕布/休息一律；悬浮球和无窗口按设置（纯通知模式默认低亮度）。 */
    private boolean wantDim() {
        if (resting || curtain != null) return true;
        if (ball != null) return cBallDim;
        return cBallDim || (!allowBall && !allowCurtain);
    }

    private void applyBrightnessForPhase() {
        boolean dim = wantDim();
        if (dim) BrightnessCtl.apply(this, bright);
        else BrightnessCtl.restore(this);
        float win = dim ? Prefs.windowBrightness(bright) : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        if (curtain != null && curLp != null) {
            curLp.screenBrightness = Prefs.windowBrightness(bright);
            safeUpdate(curtain, curLp);
        }
        if (ball != null && ballLp != null) {
            ballLp.screenBrightness = win;
            safeUpdate(ball, ballLp);
        }
    }

    private void updateBatteryReceiver() {
        boolean need = cChargeOnly || cLowBatt > 0;
        if (need && !battReg) {
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent sticky;
            if (Build.VERSION.SDK_INT >= 33) {
                sticky = registerReceiver(battReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                sticky = registerReceiver(battReceiver, f);
            }
            battReg = true;
            if (sticky != null) charging = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        } else if (!need && battReg) {
            try {
                unregisterReceiver(battReceiver);
            } catch (Exception ignored) {
            }
            battReg = false;
            charging = true;
        }
    }

    private void updateScreenReceiver() {
        if (screenReg) return;   // 只在服务启动时注册一次：亮灭屏事件几乎没有开销
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver, f);
        screenReg = true;
    }

    private Vibrator vibrator() {
        if (vib == null) {
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) vib = vm.getDefaultVibrator();
            } else {
                vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }
        }
        return vib;
    }

    /**
     * 熄屏后每隔一段时间震动一次。
     * 1.5.0 起改为“每个间隔单独震动一次”并在熄屏期间持有一个带超时的 CPU 唤醒锁：
     * 旧版把整段循环波形交给硬件，不少系统在熄屏后会取消循环震动或让进程休眠，所以经常没有提醒。
     * 代价是熄屏期间 CPU 不能深度休眠，耗电会比平时高；不需要时请关闭这个功能。
     */
    private void startVibration() {
        if (vibOn) return;
        Vibrator v = vibrator();
        if (v == null || !v.hasVibrator()) return;
        vibOn = true;
        h.removeCallbacks(vibRun);
        h.post(vibRun);
    }

    @SuppressWarnings("deprecation")
    private void pulse() {
        try {
            if (vibWl == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                vibWl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "screentool:vibrate");
                vibWl.setReferenceCounted(false);
            }
            vibWl.acquire((cVibIv + 5) * 1000L);   // 每次震动都续一次，进程被杀也会自己超时释放
        } catch (Exception ignored) {
        }
        try {
            Vibrator v = vibrator();
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE),
                        new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            } else {
                v.vibrate(500);
            }
        } catch (Exception ignored) {
        }
    }

    private void stopVibration() {
        vibOn = false;
        if (h != null) h.removeCallbacks(vibRun);
        try {
            if (vib != null) vib.cancel();
        } catch (Exception ignored) {
        }
        try {
            if (vibWl != null && vibWl.isHeld()) vibWl.release();
        } catch (Exception ignored) {
        }
    }

    // ---------- 窗口管理 ----------

    private void ensureWm() {
        if (ball != null || curtain != null || keeper != null) return;  // 已有视图时保持原来的 WindowManager
        if ((cEnhance || cPvEnh) && KeepAccessibilityService.inst != null) {
            usingA11y = true;
            wm = (WindowManager) KeepAccessibilityService.inst.getSystemService(WINDOW_SERVICE);
        } else {
            if (cPvEnh && !warnedEnh) {   // 不悄悄降级：选了增强覆盖却用不了，明确说一声
                warnedEnh = true;
                Toast.makeText(this, S.t("The enhanced cover is not available because the accessibility service is not connected. The normal overlay is used instead."), Toast.LENGTH_LONG).show();
            }
            usingA11y = false;
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        }
    }

    private int winType() {
        return usingA11y ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY : Sys.overlayType();
    }

    private boolean canShowWindow() {
        return usingA11y || Sys.canOverlay(this);
    }

    private int baseFlags() {
        int f = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        if (keepFlagOn()) f |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        return f;
    }

    private void safeUpdate(View v, WindowManager.LayoutParams lp) {
        try {
            wm.updateViewLayout(v, lp);
        } catch (Exception ignored) {
        }
    }

    private void phaseChanged() {
        updateKeeper();
        updateWake();
        applyBrightnessForPhase();
        updateNotification();
        refreshTexts();
        scheduleTick();
    }

    /** 无窗口状态：放一个看不见的 1 像素窗口兜底（有的系统会无视唤醒锁）。 */
    private void updateKeeper() {
        if (wantKeeper()) addKeeper();
        else removeKeeper();
    }

    private boolean wantKeeper() {
        return ball == null && curtain == null && !resting
                && !"wakelock".equals(cNoWin) && !"wakelock".equals(cKeep);
    }

    private void addKeeper() {
        if (keeper != null) return;
        ensureWm();
        if (!canShowWindow()) return;
        final KeeperView v = new KeeperView(this);
        v.setBackgroundColor(0x01000000);
        v.onDetach = () -> lost(v);
        int f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        if (keepFlagOn()) f |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        keeperLp = new WindowManager.LayoutParams(1, 1, winType(), f, PixelFormat.TRANSLUCENT);
        keeperLp.gravity = Gravity.TOP | Gravity.LEFT;
        try {
            wm.addView(v, keeperLp);
            keeper = v;
            keeperAt = SystemClock.elapsedRealtime();
        } catch (Exception ignored) {
        }
    }

    private void removeKeeper() {
        if (keeper != null) {
            keeper.onDetach = null;
            try {
                wm.removeView(keeper);
            } catch (Exception ignored) {
            }
            keeper = null;
        }
    }

    /** 隐形的 1 像素窗口，只用来让系统认为屏幕上有“要求常亮”的窗口。 */
    private static final class KeeperView extends View {
        Runnable onDetach;

        KeeperView(Context c) {
            super(c);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (onDetach != null) onDetach.run();
        }
    }

    /** 窗口被系统移除了（比如托管它的应用被清理、无障碍服务断开）：稍后自动重新挂载。 */
    private void lost(View v) {
        if (!inited) return;
        if (v == ball) {
            ball = null;
        } else if (v == curtain) {
            if (curShow != null) {
                curShow.stop();
                curShow = null;
            }
            curtain = null;
        } else if (v == keeper) {
            keeper = null;
        } else {
            return;
        }
        lostCount++;
        scheduleRecover();
    }

    private void scheduleRecover() {
        h.removeCallbacks(recoverRun);
        h.removeCallbacks(stableRun);
        long d = Math.min(30_000L, 800L << Math.min(recoverTries, 6));   // 0.8s、1.6s … 最多 30s
        recoverTries++;
        h.postDelayed(recoverRun, d);
    }

    /** 亮屏/解锁后先立刻检查一次，再在 0.7 秒和 2.5 秒后各补查一次（系统有时在亮屏后才清理窗口）。 */
    private void recheckWindowsSoon() {
        h.removeCallbacks(recoverRun);   // 取消旧的重试定时器，亮屏后立刻重试
        h.removeCallbacks(stableRun);
        recoverTries = 0;
        ensureWindows();
        h.postDelayed(this::ensureWindows, 700);
        h.postDelayed(this::ensureWindows, 2500);
    }

    private static boolean stale(View v, long addedAt) {
        // 刚 addView 的视图要等下一帧才会 attach，不能把“还没 attach”当成“被系统移除”
        return v != null && !v.isAttachedToWindow() && SystemClock.elapsedRealtime() - addedAt > 1500;
    }

    /**
     * 检查“应该显示的窗口”是否还在，不在就重新创建。亮屏、解锁、窗口被移除、看门狗定时都会调用。
     *
     * 1.5.0 修复：旧版把“还没 attach”的新窗口当成丢失，丢掉引用却没有 removeView，结果旧窗口泄漏、又新建一个，
     * 窗口数量失控后系统会清理悬浮窗，之后的所有功能都受影响。现在只处理确实已经 detach 的窗口，并先 removeView。
     */
    private void ensureWindows() {
        if (!inited || verifying) return;
        if (stale(ball, ballAt)) {
            ball.setOnDetach(null);
            try { wm.removeView(ball); } catch (Exception ignored) { }
            ball = null;
        }
        if (stale(curtain, curAt)) {
            if (curShow != null) { curShow.stop(); curShow = null; }
            curtain.setOnDetach(null);
            try { wm.removeView(curtain); } catch (Exception ignored) { }
            curtain = null;
        }
        if (stale(keeper, keeperAt)) {
            keeper.onDetach = null;
            try { wm.removeView(keeper); } catch (Exception ignored) { }
            keeper = null;
        }
        boolean missing = false;
        if (resting) {
            if (curtain == null) {
                buildCurtain(true);
                missing = true;
            }
        } else if (lockedNow()) {
            if (curtain == null) {
                showCurtain();
                missing = true;
            }
        } else if (curtainMode && allowCurtain) {
            if (curtain == null) {
                showCurtain();
                missing = true;
            }
        } else if (!curtainMode && allowBall) {
            if (ball == null) {
                showBall();
                missing = true;
            }
        } else if (!allowBall && !allowCurtain && keeper == null && wantKeeper()) {
            updateKeeper();
            missing = true;
        }
        if (missing) {   // 重新挂载后稳定存活 60 秒，才清零重试间隔
            h.removeCallbacks(stableRun);
            h.postDelayed(stableRun, 60_000L);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // 双击主页退出/划掉任务卡片：部分系统会在这之后清理悬浮窗。稍后主动核对并重新挂载。
        if (!inited) return;
        h.postDelayed(this::ensureWindows, 600);
        h.postDelayed(this::ensureWindows, 3000);
        h.postDelayed(this::ensureWindows, 8000);
    }

    /** 无窗口：只剩通知（和唤醒锁、隐形窗口）。 */
    private void goNone() {
        if (lockedNow() && !resting) {   // 上锁期间遮罩不能被拿掉
            showCurtain();
            return;
        }
        curtainMode = false;
        h.removeCallbacks(autoExpand);
        removeBall();
        removeCurtain();
        phaseChanged();
    }

    private void showCurtainOrBall() {
        if (allowCurtain) showCurtain();
        else if (allowBall) showBall();
    }

    /** 幕布收起：有球就变成球，否则收成纯通知。 */
    private void collapseCurtain() {
        if (lockedNow()) return;   // 双保险：上锁状态下任何路径都不能把幕布收起
        if (allowBall) showBall();
        else goNone();
    }

    private void resetWatch() {
        base = SystemClock.elapsedRealtime();
        refreshTexts();
        updateNotification();
    }

    // ---------- 悬浮球 ----------

    private void showBall() {
        if (verifying) return;
        if (lockedNow() && !resting) {
            showCurtain();
            return;
        }
        if (!allowBall) {
            showCurtainOrBall();
            return;
        }
        boolean fromCurtain = curtainMode;
        curtainMode = false;
        h.removeCallbacks(autoExpand);
        removeCurtain();
        removeKeeper();
        if (fromCurtain && cResetOnBall) base = SystemClock.elapsedRealtime();
        ensureWm();
        if (!canShowWindow()) {
            phaseChanged();
            return;
        }
        if (ball == null) {
            final BallView bv = new BallView(this);
            ball = bv;
            bv.setup(Sys.dp(this, cBallDp), cBallShape, cBallGrow,
                    st.c[StyleModel.BALL], st.c[StyleModel.WATCH]);
            bv.setOnTouchListener(new BallTouch());
            bv.setOnDetach(() -> lost(bv));
            ballLp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    winType(), baseFlags() | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            dock();
            try {
                wm.addView(ball, ballLp);
                ballAt = SystemClock.elapsedRealtime();
            } catch (Exception e) {
                ball.setOnDetach(null);
                ball = null;
                scheduleRecover();
                phaseChanged();
                return;
            }
        }
        if (allowCurtain && cOpSec > 0) h.postDelayed(autoExpand, cOpSec * 1000L);
        scheduleEdge();
        phaseChanged();
    }

    // ---------- 悬浮球 5 秒无操作自动贴边 ----------

    private void scheduleEdge() {
        h.removeCallbacks(edgeRun);
        if (cEdge && ball != null && !ballHidden && screenOn) h.postDelayed(edgeRun, 5000);
    }

    private void hideBall() {
        if (!cEdge || ball == null || ballHidden) return;
        ballHidden = true;
        animateBall(true);
    }

    private void revealBall() {
        if (!ballHidden) return;
        ballHidden = false;
        animateBall(false);
        scheduleEdge();
    }

    /** 贴边：向屏幕外滑出 75% 并变得更淡；弹出：回到原位。只在状态切换时有一段 0.18 秒的动画。 */
    private void animateBall(boolean hide) {
        if (ball == null || ballLp == null) return;
        if (edgeAnim != null) edgeAnim.cancel();
        final int fromX = ballLp.x;
        final float fromA = ballLp.alpha;
        final int toX = hide ? -hiddenOffsetPx() : 0;
        final float toA = hide ? 0.5f : 1f;
        edgeAnim = ValueAnimator.ofFloat(0f, 1f);
        edgeAnim.setDuration(180);
        edgeAnim.addUpdateListener(a -> {
            if (ball == null) return;
            float t = (float) a.getAnimatedValue();
            ballLp.x = Math.round(fromX + (toX - fromX) * t);
            ballLp.alpha = fromA + (toA - fromA) * t;
            safeUpdate(ball, ballLp);
        });
        edgeAnim.start();
    }

    /** 取消贴边计时和动画，并回到未隐藏状态（移除、重建悬浮球时用）。 */
    private void stopEdge() {
        h.removeCallbacks(edgeRun);
        if (edgeAnim != null) {
            edgeAnim.cancel();
            edgeAnim = null;
        }
        ballHidden = false;
    }

    private void removeBall() {
        stopEdge();
        if (ball != null) {
            ball.setOnDetach(null);
            try {
                wm.removeView(ball);
            } catch (Exception ignored) {
            }
            ball = null;
        }
    }

    /** 把悬浮球贴在屏幕左/右侧。 */
    /** 贴边隐藏的偏移量：和 animateBall() 共用同一份计算，避免两处算出不同的值。 */
    private int hiddenOffsetPx() {
        if (ball == null) return 0;
        int w = ball.getWidth();
        if (w <= 0) {   // 视图还没被测量过（极少见），用配置的大小兜底，不要算出 0
            w = cBallShape == 1 ? Math.round(Sys.dp(this, cBallDp) * 1.5f) : Sys.dp(this, cBallDp);
        }
        return Math.round(w * 0.75f);
    }

    private void dock() {
        int[] s = Sys.screen(this);
        int side = Prefs.getInt(this, "ball_side");
        float yf = Prefs.getFloat(this, "ball_y");
        ballLp.gravity = Gravity.TOP | (side == 0 ? Gravity.LEFT : Gravity.RIGHT);
        ballLp.x = ballHidden ? -hiddenOffsetPx() : 0;
        ballLp.y = Math.max(0, Math.round(yf * s[1]));
    }

    private final class BallTouch implements View.OnTouchListener {
        private float x0, y0;
        private int sx, sy;
        private boolean moved, wasHidden;
        private final float slop = ViewConfiguration.get(FloatService.this).getScaledTouchSlop();

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    x0 = e.getRawX();
                    y0 = e.getRawY();
                    int[] loc = new int[2];
                    v.getLocationOnScreen(loc);
                    sx = loc[0];
                    sy = loc[1];
                    moved = false;
                    h.removeCallbacks(autoExpand);
                    h.removeCallbacks(edgeRun);
                    wasHidden = ballHidden;
                    if (ballHidden) revealBall();   // 先弹出来
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                    if (!moved && Math.abs(dx) < slop && Math.abs(dy) < slop) return true;
                    if (!moved) {
                        // 开始拖动：停掉弹出动画，从球当前的实际位置起算
                        if (edgeAnim != null) edgeAnim.cancel();
                        ballHidden = false;
                        ballLp.alpha = 1f;
                        int[] loc = new int[2];
                        v.getLocationOnScreen(loc);
                        sx = loc[0];
                        sy = loc[1];
                        x0 = e.getRawX();
                        y0 = e.getRawY();
                        moved = true;
                        ballLp.gravity = Gravity.TOP | Gravity.LEFT;
                        ballLp.x = sx;
                        ballLp.y = sy;
                        safeUpdate(ball, ballLp);
                        return true;
                    }
                    ballLp.gravity = Gravity.TOP | Gravity.LEFT;
                    ballLp.x = Math.round(sx + dx);
                    ballLp.y = Math.round(sy + dy);
                    safeUpdate(ball, ballLp);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (moved) {
                        int[] s = Sys.screen(FloatService.this);
                        float cx = ballLp.x + v.getWidth() / 2f;
                        Prefs.putInt(FloatService.this, "ball_side", cx < s[0] / 2f ? 0 : 1);
                        float yf = ballLp.y / (float) s[1];
                        Prefs.putFloat(FloatService.this, "ball_y",
                                Math.max(0f, Math.min(1f - v.getHeight() / (float) s[1], yf)));
                        dock();
                        safeUpdate(ball, ballLp);
                        if (allowCurtain && cOpSec > 0) h.postDelayed(autoExpand, cOpSec * 1000L);
                        scheduleEdge();
                    } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                        if (wasHidden) {
                            // 球本来是藏着的：这一下只负责弹出，不切换，避免误触
                            if (allowCurtain && cOpSec > 0) h.postDelayed(autoExpand, cOpSec * 1000L);
                            scheduleEdge();
                        } else if (allowCurtain) {
                            showCurtain();
                        } else {
                            // 没有启用幕布：点球打开应用主页
                            try {
                                startActivity(new Intent(FloatService.this, MainActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            } catch (Exception ignored) {
                            }
                            scheduleEdge();
                        }
                    } else {
                        scheduleEdge();
                    }
                    return true;
                }
                default:
                    return true;
            }
        }
    }

    // ---------- 悬浮幕布（含护眼休息幕布） ----------

    private boolean lockedNow() {
        return Protect.isLocked(this);
    }

    private void showCurtain() {
        if (verifying) return;   // 验证期间窗口都是撤掉的，结束后由 restoreWindows 统一恢复
        // 上锁时即使“启用幕布”被关掉，也要用幕布盖住屏幕
        if (!allowCurtain && !lockedNow()) {
            if (allowBall) showBall();
            else goNone();
            return;
        }
        // 触发条件“展开幕布时”：每次幕布弹出就上锁，关闭它需要验证（护眼休息的幕布不算）
        if (cTrigExpand && !resting && !lockedNow()) {
            Protect.setLocked(this, true);   // 监听器会重建成上锁的全屏幕布
            if (curtain != null) return;
        }
        curtainMode = true;
        h.removeCallbacks(autoExpand);
        removeBall();
        buildCurtain(false);
    }

    private boolean fullRect() {
        return Prefs.getFloat(this, "cur_l") <= 0.001f && Prefs.getFloat(this, "cur_t") <= 0.001f
                && Prefs.getFloat(this, "cur_r") >= 0.999f && Prefs.getFloat(this, "cur_b") >= 0.999f;
    }

    private void buildCurtain(boolean rest) {
        removeKeeper();
        ensureWm();
        if (!canShowWindow()) {
            phaseChanged();
            return;
        }
        if (curtain == null) {
            StyleModel use = rest ? eyeSt : st;
            int bgSlot = rest ? StyleModel.EYEBG : StyleModel.CURTAIN;
            int txtW = use.c[rest ? StyleModel.EYETEXT : StyleModel.WATCH];
            int txtC = use.c[rest ? StyleModel.EYETEXT : StyleModel.TIME];
            int bg = use.c[bgSlot];
            if (rest) bg = Sys.opaque(bg);
            boolean locked = lockedNow() && !rest;
            boolean full = rest || locked || fullRect();
            final CurtainView cv = new CurtainView(this, this);
            curtain = cv;
            cv.setOnDetach(() -> lost(cv));
            curtain.setup(bg, txtW, txtC, bright);
            curtain.setLocked(rest);
            curtain.setVolKeys(cVol);
            // 增强模式（无障碍窗口）本身高于状态栏/导航栏；普通悬浮窗层级低，只能请求隐藏系统栏（尽力而为）
            curtain.setHideBars(cCover && full && !usingA11y);
            // 护眼休息的紧急退出：通知按钮关了，或者通知根本看不到（没权限/被系统屏蔽）时，长按幕布 5 秒
            curtain.setEmergency(rest && (!cNoteBtn || !Caps.notifVisible(this)));
            curtain.setHold(locked && cEmergOn ? cEmerg * 1000L : 0);   // 上锁时：长按幕布 N 秒＝紧急释放遮罩（不依赖通知）
            curLp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    winType(), baseFlags() | (cSecure ? WindowManager.LayoutParams.FLAG_SECURE : 0),
                    PixelFormat.TRANSLUCENT);
            if (Build.VERSION.SDK_INT >= 28) {
                curLp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                        ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            curLp.screenBrightness = Prefs.windowBrightness(bright);
            geometry(rest || locked);
            try {
                wm.addView(curtain, curLp);
                curAt = SystemClock.elapsedRealtime();
            } catch (Exception e) {
                curtain.setOnDetach(null);
                curtain = null;
                scheduleRecover();
                phaseChanged();
                return;
            }
            // 图片背景 / 幻灯片：只有这个槽位绑定了图片集才会创建
            String setId = use.img[bgSlot];
            if (setId.length() > 0) {
                ImgSet set = ImgStore.get(this, setId);
                if (set != null && !set.uris.isEmpty()) {
                    curShow = new SlideShow(this, set, curLp.width, curLp.height, (b, f, fit) -> {
                        if (curtain != null) curtain.setBg(b, f, fit);
                    });
                    curShow.start();
                }
            }
        }
        phaseChanged();
    }

    private void removeCurtain() {
        curInert = false;
        if (curShow != null) {
            curShow.stop();
            curShow = null;
        }
        if (curtain != null) {
            curtain.setOnDetach(null);
            try {
                wm.removeView(curtain);
            } catch (Exception ignored) {
            }
            curtain = null;
        }
    }

    /** 幕布范围：用屏幕比例保存的 左/上/右/下。休息幕布永远全屏。 */
    private void geometry(boolean full) {
        int[] s = Sys.screen(this);
        float l = 0f, t = 0f, r = 1f, b = 1f;
        if (!full) {
            l = clamp(Prefs.getFloat(this, "cur_l"), 0f, 0.9f);
            t = clamp(Prefs.getFloat(this, "cur_t"), 0f, 0.9f);
            r = clamp(Prefs.getFloat(this, "cur_r"), l + 0.05f, 1f);
            b = clamp(Prefs.getFloat(this, "cur_b"), t + 0.05f, 1f);
        }
        curLp.gravity = Gravity.TOP | Gravity.LEFT;
        curLp.x = Math.round(l * s[0]);
        curLp.y = Math.round(t * s[1]);
        curLp.width = Math.round((r - l) * s[0]);
        curLp.height = Math.round((b - t) * s[1]);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public void onCurtainTap() {
        if (resting || verifying || overlay != null) return;
        if (lockedNow()) {
            // 上锁：点击要么被忽略（“点击不能关闭”/防误触），要么只是请求验证，绝不直接收起
            if (Prefs.getBool(this, "pv_ui_notap") || cOpSec == 0) {
                hintLocked();
                return;
            }
            requestCollapse();
            return;
        }
        // 操作窗口期为 0 秒＝防误触：点按幕布无效，只能用返回键切回悬浮球
        if (cOpSec > 0) collapseCurtain();
    }

    @Override
    public void onCurtainBack() {
        if (resting || verifying || overlay != null) return;
        if (lockedNow()) {
            if (Prefs.getBool(this, "pv_ui_back")) requestCollapse();
            else hintLocked();
            return;
        }
        collapseCurtain();
    }

    @Override
    public void onCurtainHoldHint() {
        Toast.makeText(this, S.f("Keep holding to release the cover (%d s)", cEmerg), Toast.LENGTH_SHORT).show();
    }

    private void hintLocked() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastHint < 3000) return;
        lastHint = now;
        boolean back = Prefs.getBool(this, "pv_ui_back");
        Toast.makeText(this, back ? S.t("Locked. Press Back to unlock.") : S.t("Locked. Tap the curtain to unlock."),
                Toast.LENGTH_SHORT).show();
    }

    // ---------- 上锁 / 验证 ----------

    /** 想收起幕布：没上锁直接收起；上锁则请求验证，通过后才收起。 */
    private void requestCollapse() {
        if (lockedNow()) requestVerify(Protect.Purpose.UNLOCK, this::collapseCurtain);
        else collapseCurtain();
    }

    /** 保护控制器通知：上锁状态变了（手动、定时、熄屏、离开应用、验证通过、紧急释放都会走到这里）。 */
    private void onLockChanged(boolean locked) {
        if (!inited) return;
        updateNotification();
        if (locked) {
            h.removeCallbacks(lockTimerRun);
            lockAt = 0;
            if (!resting && !verifying) {
                h.removeCallbacks(autoExpand);
                removeBall();
                removeCurtain();
                showCurtain();
            }
        } else {
            scheduleLockTimer();
            if (keepStopped) h.postDelayed(this::stopNow, 400);   // 低电量/定时关闭时只是为了保住遮罩才没停
        }
    }

    private void scheduleLockTimer() {
        h.removeCallbacks(lockTimerRun);
        lockAt = 0;
        if (cLockMin <= 0 || !Protect.armed(this) || lockedNow()) return;
        lockAt = SystemClock.elapsedRealtime() + cLockMin * 60_000L;
        h.postDelayed(lockTimerRun, cLockMin * 60_000L);
    }

    private void lockTimerFire() {
        if (lockAt == 0 || !inited) return;
        Protect.lockNow(this, false);
    }

    /** 亮屏后补算：手机休眠时定时器会被拉长，到点没触发就在这里补上。 */
    private void lockTimerFire0() {
        if (lockAt != 0 && SystemClock.elapsedRealtime() >= lockAt) lockTimerFire();
    }

    private void requestVerify(Protect.Purpose p, Runnable cont) {
        if (verifying || launching || overlay != null) return;   // 已经在验证（或正在拉起验证页）：不要再弹第二个
        pendingPurpose = p;
        pendingCont = cont;
        String problem = Protect.methodProblem(this);
        if (problem == null && Protect.needSys(this)) {
            // 系统验证必须由验证页承载。趁遮罩还在（应用仍算有可见窗口）先把页面拉起，再由页面通知我们撤掉遮罩。
            try {
                startActivity(VerifyActivity.intent(this, p, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                launching = true;
                h.removeCallbacks(verifyLaunchWd);
                h.postDelayed(verifyLaunchWd, 3500);
            } catch (Exception e) {
                pendingCont = null;
                Toast.makeText(this, S.t("The system verification screen could not be opened, so the cover stays locked. Hold the curtain for the emergency release, or open the app and verify there."), Toast.LENGTH_LONG).show();
            }
            return;
        }
        // 只验证自定义密码（或验证方式不可用：悬浮窗里显示原因，不放行）
        ensureWm();
        if (wm == null || !canShowWindow()) {
            pendingCont = null;
            Toast.makeText(this, S.t("The verification screen cannot be shown without the \"Display over other apps\" permission. Open the app and verify there."), Toast.LENGTH_LONG).show();
            return;
        }
        overlay = new VerifyOverlay(this, wm, winType(), new VerifyOverlay.Host() {
            @Override public void onVerified() { overlayDone(true); }
            @Override public void onCancel() { overlayDone(false); }
        });
        setCurtainInert(true);   // 验证窗口在上面时，下面的幕布不再抢焦点/触摸，否则输入法会被反复收起
        if (!overlay.show()) {
            setCurtainInert(false);
            overlay = null;
            pendingCont = null;
            Toast.makeText(this, S.t("The verification screen could not be shown. The cover stays locked."), Toast.LENGTH_LONG).show();
        }
    }

    private void overlayDone(boolean ok) {
        if (overlay != null) {
            overlay.dismiss();
            overlay = null;
        }
        setCurtainInert(false);
        Runnable cont = pendingCont;
        Protect.Purpose p = pendingPurpose;
        pendingCont = null;
        pendingPurpose = null;
        if (!ok) return;
        if (p == Protect.Purpose.UNLOCK || p == Protect.Purpose.STOP) Protect.completeUnlock(this);
        else Protect.grantSession(this);
        if (cont != null) cont.run();
    }

    private boolean curInert;

    /** 让幕布暂时“不可聚焦、不可触摸”（验证窗口盖在它上面时用），结束后恢复。 */
    private void setCurtainInert(boolean on) {
        if (curtain == null || curLp == null || wm == null || curInert == on) {
            if (!on) curInert = false;
            return;
        }
        if (on) {
            curFlagsBackup = curLp.flags;
            curLp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            curLp.flags = curFlagsBackup;
        }
        curInert = on;
        safeUpdate(curtain, curLp);
    }

    /** 验证页出现：撤掉自己的悬浮窗并暂停自动重新挂载，避免挡住系统输入界面。 */
    private void beginVerifyUi() {
        if (!inited) return;
        verifying = true;
        launching = false;
        setCurtainInert(false);
        h.removeCallbacks(verifyLaunchWd);
        h.removeCallbacks(recoverRun);
        h.removeCallbacks(stableRun);
        h.removeCallbacks(autoExpand);
        if (overlay != null) {
            overlay.dismiss();
            overlay = null;
        }
        removeBall();
        removeCurtain();
        removeKeeper();
        h.removeCallbacks(verifyTimeout);
        h.postDelayed(verifyTimeout, 90_000L);
    }

    /** 验证页结束：恢复悬浮窗；通过且是服务自己发起的，就继续原来要做的事。 */
    private void endVerifyUi(boolean ok, boolean forService) {
        if (!inited) return;
        h.removeCallbacks(verifyTimeout);
        h.removeCallbacks(verifyLaunchWd);
        launching = false;
        boolean was = verifying;
        verifying = false;
        Runnable cont = ok && forService ? pendingCont : null;
        pendingCont = null;
        pendingPurpose = null;
        if (cont != null) cont.run();
        else if (was || ball == null && curtain == null) restoreWindows();
    }

    private void restoreWindows() {
        recoverTries = 0;
        ensureWindows();
        phaseChanged();
    }

    /** 紧急释放：只撤掉遮罩，不授予验证会话——设置、备份、保护策略仍然需要验证。 */
    private void emergencyRelease() {
        Protect.emergencyRelease(this);
        Toast.makeText(this, S.t("Cover released. Protected settings and data still need verification."), Toast.LENGTH_LONG).show();
        collapseCurtain();
    }

    /** 低电量/定时关闭：上锁时只停止“常亮”，服务和遮罩保留；这绝不等于验证通过。 */
    private void stopKeepOrSelf(String msg) {
        if (lockedNow()) {
            if (keepStopped) return;
            keepStopped = true;
            Toast.makeText(this, msg + " - " + S.t("the privacy cover stays until you verify"), Toast.LENGTH_LONG).show();
            h.removeCallbacks(autoOff);
            updateKeepAll();
            updateNotification();
            return;
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        stopNow();
    }

    private void stopNow() {
        stopSelf();
    }

    @Override
    public void onCurtainBright(float p) {
        if (resting) return;
        bright = p;
        if (curtain != null) {
            curLp.screenBrightness = Prefs.windowBrightness(p);
            safeUpdate(curtain, curLp);
            curtain.showHud(p);
            BrightnessCtl.apply(this, p);
        }
    }

    @Override
    public void onCurtainBrightEnd(float p) {
        Prefs.putFloat(this, "brightness", p);
    }

    @Override
    public void onCurtainEmergency() {
        if (resting) skipRest();
        else if (lockedNow() && cEmergOn) emergencyRelease();
    }

    // ---------- 护眼强制休息 ----------

    private void scheduleEye() {
        h.removeCallbacks(eyeCheck);
        h.removeCallbacks(eyeWarnR);
        if (!cEyeOn || resting) return;
        long ms = cEyeIv * 60_000L;
        eyeAt = SystemClock.elapsedRealtime() + ms;
        h.postDelayed(eyeCheck, ms);
        long w = cEyeWarn * 1000L;
        if (w > 0 && ms > w) h.postDelayed(eyeWarnR, ms - w);
    }

    /** 定时到点，或手机休眠醒来（亮屏）后补算：时间到了就开始休息，没到就继续等。 */
    private void checkEye() {
        if (!cEyeOn || resting || !inited) return;
        long left = eyeAt - SystemClock.elapsedRealtime();
        if (left <= 500) {
            startRest();
        } else {
            h.removeCallbacks(eyeCheck);
            h.postDelayed(eyeCheck, left);
        }
    }

    private void startRest() {
        if (resting) return;
        if (verifying) {   // 验证页在显示：不要挡住它，顺延到下一个周期
            scheduleEye();
            return;
        }
        removeKeeper();
        ensureWm();
        if (!canShowWindow()) {   // 没有窗口权限就无法强制休息，顺延到下一个周期
            scheduleEye();
            return;
        }
        resting = true;
        h.removeCallbacks(autoExpand);
        removeBall();
        removeCurtain();
        restEnd = SystemClock.elapsedRealtime() + cEyeLen * 1000L;
        h.postDelayed(eyeEnd, cEyeLen * 1000L);
        buildCurtain(true);
        if (curtain == null) {   // 创建失败
            resting = false;
            h.removeCallbacks(eyeEnd);
            scheduleEye();
        }
    }

    private void endRest() {
        if (!resting) return;
        resting = false;
        removeCurtain();
        scheduleEye();
        if (lockedNow()) showCurtain();   // 护眼休息结束只解除休息，不解除隐私锁
        else if (curtainMode && allowCurtain) showCurtain();
        else if (allowBall) showBall();
        else if (allowCurtain) showCurtain();
        else goNone();
    }

    private void skipRest() {
        if (!resting) return;
        h.removeCallbacks(eyeEnd);
        endRest();
    }

    // ---------- 计时刷新（没有文字要显示时完全不运行） ----------

    private boolean needTick() {
        if (!screenOn) return false;
        if (resting) return true;
        if (curtain != null) return cCurSw || cCurTime;
        if (ball != null) return cBallSw;
        return false;
    }

    private void scheduleTick() {
        h.removeCallbacks(tick);
        if (!needTick()) return;
        long delay = 1000 - (System.currentTimeMillis() % 1000) + 8;
        // 自适应风格进入“只显示分钟”后，悬浮球每分钟才刷新一次
        if (!resting && curtain == null && cBallClock == 2) {
            long elapsed = SystemClock.elapsedRealtime() - base;
            if (elapsed / 1000 >= cAdapt * 60L) delay = 60_000 - (elapsed % 60_000) + 8;
        }
        h.postDelayed(tick, delay);
    }

    private void refreshTexts() {
        if (!screenOn) return;   // 屏幕熄灭时不刷新
        long sec = (SystemClock.elapsedRealtime() - base) / 1000;
        long now = System.currentTimeMillis();
        // 防烧屏：每 3 分钟让文字微移一个位置
        int k = cBurn ? (int) ((sec / 180) % 4) : 0;
        if (ball != null) {
            ball.setText(cBallSw ? Fmt.watch(sec, cBallClock, cAdapt) : "");
            ball.setShift(OX[k] * dpBall, OY[k] * dpBall);
        }
        if (curtain != null) {
            if (resting) {
                long left = Math.max(0, (restEnd - SystemClock.elapsedRealtime() + 999) / 1000);
                curtain.setTexts(Long.toString(left), S.t("Rest your eyes - look into the distance"));
            } else {
                curtain.setTexts(cCurSw ? Fmt.watch(sec, cBallClock, cAdapt) : "",
                        cCurTime ? Fmt.clock(now) : "");
            }
            curtain.setShift(OX[k] * dpCur, OY[k] * dpCur);
        }
    }

    private void scheduleAutoOff() {
        h.removeCallbacks(autoOff);
        int min = Prefs.getInt(this, "auto_off");
        if (min <= 0) {
            autoOffAt = 0;
            return;
        }
        autoOffAt = sessionStart + min * 60_000L;
        h.postDelayed(autoOff, Math.max(1000, autoOffAt - SystemClock.elapsedRealtime()));
    }

    /** 到点就停止；手机休眠醒来后也会补算，不会因为休眠而拖长。 */
    private void checkAutoOff() {
        if (!inited || autoOffAt == 0) return;
        long left = autoOffAt - SystemClock.elapsedRealtime();
        if (left <= 500) {
            stopKeepOrSelf(S.t("Auto stop time reached"));
        } else {
            h.removeCallbacks(autoOff);
            h.postDelayed(autoOff, left);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!inited) return;
        // 旋转屏幕后重新计算位置和大小
        if (curtain != null) {
            geometry(resting || lockedNow());
            safeUpdate(curtain, curLp);
        }
        if (ball != null) {
            dock();
            safeUpdate(ball, ballLp);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        fgsMode = 0;
        if (Protect.listener == lockListener) Protect.listener = null;
        Prefs.putBool(this, "svc_alive", false);
        if (h != null) h.removeCallbacksAndMessages(null);
        if (overlay != null) { overlay.dismiss(); overlay = null; }
        removeBall();
        removeCurtain();
        removeKeeper();
        try {
            if (battReg) unregisterReceiver(battReceiver);
        } catch (Exception ignored) {
        }
        try {
            if (screenReg) unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
        }
        battReg = false;
        screenReg = false;
        stopVibration();
        try {
            if (wl != null && wl.isHeld()) wl.release();
        } catch (Exception ignored) {
        }
        if (inited) BrightnessCtl.restore(this);
        inited = false;
        notifyTile();
        super.onDestroy();
    }
}
