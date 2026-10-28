package com.wave.screentool;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.biometrics.BiometricManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 统一的验证页。所有“需要验证”的入口（设置、备份、策略、通知/磁贴/快捷方式的停止与切换、幕布解锁中需要系统凭据的情况）
 * 最终都落到这里或者幕布上方的验证悬浮窗，验证方式和失败处理完全一致。
 *
 * 系统验证期间：服务会撤掉自己的悬浮窗（遮罩/悬浮球）并暂停自动重新挂载，避免盖住系统输入界面，验证结束后自动恢复。
 *
 * 系统验证：API 30+ 用 BiometricPrompt（只允许“设备凭据”，不采集生物特征），API 21–29 用 KeyguardManager。
 * 本应用不会读取你的系统密码。系统验证通过后若还需要自定义密码，60 秒内完成，超时重来。
 * 验证方式不可用（没设置密码 / 设备没有锁屏）时不会降级放行，而是明确说明原因。
 */
public class VerifyActivity extends Activity {
    static final String EXTRA_PURPOSE = "purpose";
    static final String EXTRA_FOR_SERVICE = "for_service";
    private static final int REQ_SYS = 41;

    private final Handler h = new Handler(Looper.getMainLooper());
    private LinearLayout box;
    private VerifyPanel panel;
    private Protect.Purpose purpose = Protect.Purpose.SETTINGS;
    private boolean forService, inSys, done;
    private long step2Deadline;
    private CancellationSignal bpCancel;
    private static VerifyActivity current;   // 同一时刻只允许一个验证页

    static boolean alive() {
        VerifyActivity c = current;
        return c != null && !c.isFinishing() && !c.done;
    }


    static Intent intent(android.content.Context c, Protect.Purpose p, boolean forService) {
        return new Intent(c, VerifyActivity.class)
                .putExtra(EXTRA_PURPOSE, p.name())
                .putExtra(EXTRA_FOR_SERVICE, forService);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        forService = getIntent().getBooleanExtra(EXTRA_FOR_SERVICE, false);
        try {
            purpose = Protect.Purpose.valueOf(getIntent().getStringExtra(EXTRA_PURPOSE));
        } catch (Exception ignored) {
        }
        if (b != null) {   // 被系统重建：不能沿用旧流程，视为取消
            finishWith(false);
            return;
        }
        if (current != null && !current.isFinishing() && !current.done) {
            // 已经有一个验证页在显示（比如连点两下发出了两次请求）：多出来的这个直接丢掉，不再弹第二次
            done = true;
            finish();
            return;
        }
        current = this;
        S.acquire(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        int pad = Sys.dp(this, 28);
        box.setPadding(pad, pad, pad, pad);
        root.addView(box, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        Sys.immersive(this);
        panel = new VerifyPanel(this, box, new VerifyPanel.Callback() {
            @Override public void ok() { succeed(); }
            @Override public void cancel() { finishWith(false); }
        });
        FloatCtl.verifyBegin(this);   // 通知服务撤掉遮罩，避免盖住本页
        startFlow();
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        else if (panel == null) {   // 被丢掉的重复实例：什么都没初始化过，直接结束
            super.onDestroy();
            return;
        }
        if (bpCancel != null) {
            bpCancel.cancel();
            bpCancel = null;
        }
        if (panel != null) panel.release();
        h.removeCallbacksAndMessages(null);
        if (!done) FloatCtl.verifyEnd(this, false, purpose, forService);   // 异常结束也要让服务恢复
        S.release();
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 去了别处（按了 Home 等）就取消；但系统验证界面在上面时本页也会 onStop，那种情况不算
        if (!inSys && !isFinishing() && !isChangingConfigurations()) finishWith(false);
    }

    @Override
    public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) Sys.immersive(this);
    }

    @Override
    public void onBackPressed() {
        finishWith(false);
    }

    private void finishWith(boolean ok) {
        if (done) return;
        done = true;
        if (ok) {
            // 把授权结果统一交给保护控制器
            switch (purpose) {
                case TEST:
                    Toast.makeText(this, S.t("Verification works."), Toast.LENGTH_SHORT).show();
                    break;
                case UNLOCK:
                case STOP:
                    Protect.completeUnlock(this);
                    break;
                default:
                    Protect.grantSession(this);
                    break;
            }
        }
        FloatCtl.verifyEnd(this, ok, purpose, forService);
        setResult(ok ? RESULT_OK : RESULT_CANCELED);
        finish();
    }

    private void succeed() {
        finishWith(true);
    }

    // ---------------- 流程 ----------------

    private void startFlow() {
        String problem = Protect.methodProblem(this);
        if (problem != null) {
            panel.showUnavailable(problem);   // 不降级：只给出原因、恢复码入口和取消
            return;
        }
        if (Protect.needSys(this)) askSystem();
        else panel.showPassword(null);
    }

    private void afterSystem() {
        inSys = false;
        if (Protect.needApp(this)) {
            step2Deadline = android.os.SystemClock.elapsedRealtime() + 60_000L;
            panel.showPassword(S.t("System verification passed. Now enter your privacy password."));
            h.removeCallbacksAndMessages(null);
            h.postDelayed(() -> {
                if (!done && step2Deadline != 0 && android.os.SystemClock.elapsedRealtime() >= step2Deadline) showExpired();
            }, 61_000L);
        } else {
            succeed();
        }
    }

    private void showExpired() {
        box.removeAllViews();
        TextView t = Ui.tv(this, S.t("Session expired. Please verify again."), 16, Color.WHITE);
        t.setGravity(Gravity.CENTER);
        box.addView(t);
        Button retry = new Button(this);
        retry.setText(S.t("Try again"));
        retry.setOnClickListener(v -> startFlow());
        box.addView(retry);
        Button cancel = new Button(this);
        cancel.setText(S.t("Cancel"));
        cancel.setOnClickListener(v -> finishWith(false));
        box.addView(cancel);
    }

    private void sysCancelled() {
        inSys = false;
        box.removeAllViews();
        TextView t = Ui.tv(this, S.t("System verification was cancelled."), 16, Color.WHITE);
        t.setGravity(Gravity.CENTER);
        box.addView(t);
        Button retry = new Button(this);
        retry.setText(S.t("Try again"));
        retry.setOnClickListener(v -> askSystem());
        box.addView(retry);
        Button cancel = new Button(this);
        cancel.setText(S.t("Cancel"));
        cancel.setOnClickListener(v -> finishWith(false));
        box.addView(cancel);
    }

    @SuppressWarnings("deprecation")
    private void askSystem() {
        inSys = true;
        if (Build.VERSION.SDK_INT >= 30) {
            askBiometric();
            return;
        }
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        Intent i = km == null ? null : km.createConfirmDeviceCredentialIntent(S.t("Screen Keep-On"),
                S.t("Confirm your device PIN, pattern or password"));
        if (i == null) {
            sysUnavailable();
            return;
        }
        try {
            startActivityForResult(i, REQ_SYS);
        } catch (Exception e) {
            sysUnavailable();
        }
    }

    @android.annotation.TargetApi(30)
    private void askBiometric() {
        try {
            BiometricManager bm = (BiometricManager) getSystemService(BIOMETRIC_SERVICE);
            if (bm != null && bm.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    != BiometricManager.BIOMETRIC_SUCCESS) {
                sysUnavailable();
                return;
            }
            bpCancel = new CancellationSignal();
            android.hardware.biometrics.BiometricPrompt prompt = new android.hardware.biometrics.BiometricPrompt.Builder(this)
                    .setTitle(S.t("Screen Keep-On"))
                    .setDescription(S.t("Confirm your device PIN, pattern or password"))
                    .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build();
            prompt.authenticate(bpCancel, getMainExecutor(), new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) {
                    bpCancel = null;
                    afterSystem();
                }

                @Override
                public void onAuthenticationError(int code, CharSequence msg) {
                    bpCancel = null;
                    if (!done) sysCancelled();
                }
            });
        } catch (Exception e) {
            sysUnavailable();
        }
    }

    /** 系统验证在运行时变得不可用：明确告知，不降级。 */
    private void sysUnavailable() {
        inSys = false;
        panel.showUnavailable(S.t("System verification could not be started on this device. The app does not fall back to a weaker method."));
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SYS) return;
        if (res == RESULT_OK) afterSystem();
        else sysCancelled();
    }
}
