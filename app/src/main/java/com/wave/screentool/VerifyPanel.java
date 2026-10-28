package com.wave.screentool;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 密码/恢复码输入界面（深色）。验证页（Activity）和幕布上方的验证悬浮窗共用同一份，行为完全一致：
 * 冷却倒计时、连续失败计数、失败响应（逐步加长冷却 / 清除授权文件 / 清除软件数据）都在这里统一处理。
 */
final class VerifyPanel {
    interface Callback {
        void ok();       // 验证通过
        void cancel();   // 用户取消
    }

    private final Context c;
    private final LinearLayout box;
    private final Callback cb;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView err;
    private EditText input;
    private Button okBtn;
    private boolean released;
    private boolean busy;

    private final Runnable ticker = this::refreshCooldown;

    VerifyPanel(Context c, LinearLayout box, Callback cb) {
        this.c = c;
        this.box = box;
        this.cb = cb;
    }

    void release() {
        released = true;
        h.removeCallbacksAndMessages(null);
    }

    private TextView title(String s) {
        TextView t = Ui.tv(c, s, 20, Color.WHITE);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView note(String s, int color) {
        TextView t = Ui.tv(c, s, 13, color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Sys.dp(c, 8), 0, 0);
        return t;
    }

    private Button btn(String s, android.view.View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(s);
        b.setOnClickListener(l);
        return b;
    }

    /** 验证方式不可用：说明原因，不降级；有恢复码时可以用恢复码重置。 */
    void showUnavailable(String reason) {
        box.removeAllViews();
        box.addView(title(S.t("Verification is unavailable")));
        box.addView(note(reason, 0xFFFF8A80));
        err = note("", 0xFFFF8A80);
        box.addView(err);
        if (PrivacyLock.hasRecoveryCode(c)) {
            box.addView(btn(S.t("Use recovery code"), v -> showRecovery()));
        } else {
            box.addView(note(S.t("No recovery code was saved. You can clear this app's data in the system settings to remove the lock."), 0xFFAAAAAA));
        }
        box.addView(btn(S.t("Cancel"), v -> cb.cancel()));
    }

    void showPassword(String noteText) {
        box.removeAllViews();
        box.addView(title(S.t("Enter your privacy password")));
        if (noteText != null) box.addView(note(noteText, 0xFFAAAAAA));
        err = note("", 0xFFFF8A80);
        box.addView(err);

        input = new EditText(c);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF888888);
        input.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Sys.dp(c, 220), -2);
        lp.topMargin = Sys.dp(c, 16);
        box.addView(input, lp);

        okBtn = btn(S.t("Unlock"), v -> tryPassword());
        box.addView(okBtn);
        input.setOnEditorActionListener((tv, id, ev) -> {
            tryPassword();
            return true;
        });
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        box.addView(btn(S.t("Forgot password"), v -> {
            if (!PrivacyLock.hasRecoveryCode(c)) {
                err.setText(S.t("No recovery code was saved. You will need to clear this app's data to remove the privacy lock."));
                return;
            }
            showRecovery();
        }));
        box.addView(btn(S.t("Cancel"), v -> cb.cancel()));
        refreshCooldown();
        focusInput();
    }

    void showRecovery() {
        box.removeAllViews();
        box.addView(title(S.t("Recovery code")));
        box.addView(note(S.t("Enter your recovery code. This removes the password and turns protection off; you can set it up again afterwards."), 0xFFAAAAAA));
        err = note("", 0xFFFF8A80);
        box.addView(err);
        input = new EditText(c);
        input.setHint(S.t("Recovery code"));
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF888888);
        input.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Sys.dp(c, 260), -2);
        lp.topMargin = Sys.dp(c, 16);
        box.addView(input, lp);
        okBtn = btn(S.t("OK"), v -> tryRecovery());
        box.addView(okBtn);
        box.addView(btn(S.t("Try again"), v -> {
            String p = Protect.methodProblem(c);
            if (p != null) showUnavailable(p);
            else showPassword(null);
        }));
        refreshCooldown();
    }

    private void refreshCooldown() {
        h.removeCallbacks(ticker);
        if (released || input == null || err == null) return;
        if (busy || FailPolicy.isWiping()) {
            setEnabled(false);
            return;
        }
        long left = PrivacyLock.cooldownSecondsLeft(c);
        if (left > 0) {
            setEnabled(false);
            err.setText(S.f("Too many attempts. Try again in %d s", left));
            h.postDelayed(ticker, 1000);
        } else {
            setEnabled(true);
        }
    }

    private void setEnabled(boolean e) {
        if (input != null) input.setEnabled(e);
        if (okBtn != null) okBtn.setEnabled(e);
    }

    private void tryPassword() {
        if (busy || PrivacyLock.cooldownSecondsLeft(c) > 0) return;
        String p = input.getText().toString();
        if (p.isEmpty()) {   // 什么都没输入不算一次失败，也不会触发冷却
            err.setText(S.t("Enter your password first"));
            focusInput();
            return;
        }
        if (PrivacyLock.verifyPassword(c, p)) {
            PrivacyLock.clearBackoff(c);
            cb.ok();
        } else {
            wrong(S.t("Wrong password"));
        }
    }

    private void tryRecovery() {
        if (busy || PrivacyLock.cooldownSecondsLeft(c) > 0) return;
        if (input.getText().toString().trim().isEmpty()) {
            err.setText(S.t("Enter your recovery code first"));
            focusInput();
            return;
        }
        if (PrivacyLock.verifyRecoveryCode(c, input.getText().toString())) {
            Protect.resetAfterRecovery(c);
            android.widget.Toast.makeText(c, S.t("Protection was turned off and the password removed."), android.widget.Toast.LENGTH_LONG).show();
            cb.ok();
        } else {
            wrong(S.t("Wrong recovery code"));
        }
    }

    private void wrong(String msg) {
        int out = FailPolicy.onWrong(c);
        input.setText("");
        if (out == FailPolicy.WIPE) {
            busy = true;
            setEnabled(false);
            err.setText(S.t("Too many failed attempts. Cleaning up, please wait..."));
            FailPolicy.runWipe(c, (ok, m) -> {
                busy = false;
                if (released) return;
                refreshCooldown();
                if (err != null) {
                    err.setText(ok ? S.t("Clean-up finished. The app stays locked.") : (m != null ? m : S.t("Cleanup failed. The app stays locked.")));
                }
            });
            return;
        }
        err.setText(msg);
        refreshCooldown();
        focusInput();
    }

    /** 让输入框重新获得焦点并保持键盘打开（输错一次后键盘不要被收起）。 */
    private void focusInput() {
        if (input == null || !input.isEnabled()) return;
        input.post(() -> {
            if (released || input == null) return;
            input.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
    }
}
