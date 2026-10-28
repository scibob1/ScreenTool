package com.wave.screentool;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 隐私保护设置页。五个维度可以自由组合，但不是所有开关都能任意叠加：
 * 不允许的组合会被拒绝并说明原因（见 Protect.check），不会悄悄改成别的东西。
 *
 *  1. 验证方式：自定义密码 / 系统锁屏凭据 / 两者都要
 *  2. 触发条件：手动 / 定时 / 熄屏后 / 离开本应用后
 *  3. 界面限制：点击不能关闭 / 返回需验证 / 防截图 / 增强覆盖
 *  4. 授权范围：解除遮罩 / 打开设置 / 备份 / 修改保护策略
 *  5. 失败响应：保持锁定 / 冷却 / 清除授权文件 / 清除软件数据
 *
 * 进入本页本身就需要验证（保护开启时），这是“修改保护策略”的授权范围。
 */
public class PrivacyActivity extends android.app.Activity {
    private static final int REQ_LOCK = 4, REQ_AUTH = 6;
    private Ui.Page ui;
    private Ui.SwipeBack swipe;
    private boolean unlocking;
    private int passEpoch = -1;   // 本页已验证过的“代”（见 Protect.epoch）

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        S.acquire(this);
        swipe = new Ui.SwipeBack(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (unlocking) return;
        if (Protect.needVerify(this, Protect.Purpose.POLICY, passEpoch)) {
            unlocking = true;
            startActivityForResult(VerifyActivity.intent(this, Protect.Purpose.POLICY, false), REQ_LOCK);
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

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        swipe.feed(ev);
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        if (intent.getComponent() == null || !getPackageName().equals(intent.getComponent().getPackageName())) {
            Protect.expectExternal();
        }
        super.startActivityForResult(intent, requestCode, options);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_LOCK) {
            unlocking = false;
            if (res != RESULT_OK) finish();
            else {
                passEpoch = Protect.epoch();
                S.init(this);
                build();
            }
            return;
        }
        if (req == REQ_AUTH && res == RESULT_OK && data != null && data.getData() != null) {
            saveAuth(data.getData());
            build();
        }
    }

    // ================= 页面 =================

    private void build() {
        ui = Ui.page(this, S.t("Privacy protection"));
        final boolean armed = Protect.armed(this);

        Ui.row(ui, S.t("What this protects"),
                S.t("A full-screen privacy cover for the curtain, plus a password (and/or your device lock screen) in front of this app's Settings, backup and protection settings. Everything is off by default and your existing eye-care and keep-awake settings never turn into a lock by themselves. It only protects this app: the system can always stop or uninstall it, and no protection can be perfect."), null);

        Ui.header(ui, S.t("Status"));
        vsw(S.t("Enable privacy protection"),
                S.t("Master switch. When off, nothing on this page has any effect."), "pv_on");
        action(S.t("Protection check"),
                S.t("See which functions can run right now, with and without notifications, and test the cover window and the verification screen."),
                this::selfCheck);
        if (armed) {
            boolean locked = Protect.isLocked(this);
            Ui.row(ui, S.t("Current state"), locked ? S.t("Locked") : S.t("Unlocked"), null);
            if (!locked) {
                action(S.t("Lock now"),
                        S.t("Covers the screen full screen right away. Starts the keep-awake service if it is not running."), () -> {
                            if (FloatCtl.lock(this)) toast(S.t("Locked"));
                            else toast(S.t("Cannot lock: the cover needs the \"Display over other apps\" permission or the enhanced overlay."));
                            build();
                        });
            }
        }

        // ---- 1 验证方式 ----
        Ui.header(ui, S.t("1. How to verify"));
        final boolean has = PrivacyLock.hasPassword(this);
        action(has ? S.t("Change password") : S.t("Set password"),
                has ? S.t("A password is set.") : S.t("Not set yet."), this::setPasswordDialog);
        vchoice(S.t("Verification method"), "pv_mode", new String[]{
                S.t("Custom password"), S.t("System lock screen (PIN, pattern or password)"),
                S.t("Both: system lock screen, then password")});
        Ui.row(ui, S.t("About verification"),
                S.t("The system lock screen is checked in the system's own screen; this app never sees your system password. If the method you chose becomes unavailable, for example you remove the device lock screen, the app does not quietly switch to something weaker: it stays locked and tells you why."), null);
        action(S.t("Recovery code"),
                PrivacyLock.hasRecoveryCode(this) ? S.t("Already generated. Tap to generate a new one.") : S.t("Not generated yet."),
                this::recoveryCodeDialog);
        if (has) {
            action(S.t("Remove password"), S.t("Not possible while protection uses the password."), () -> {
                String why = Protect.checkRemovePassword(this);
                if (why != null) {
                    explain(why);
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle(S.t("Remove password"))
                        .setPositiveButton(S.t("Remove"), (d, w) -> {
                            PrivacyLock.clearPassword(this);
                            toast(S.t("Removed"));
                            build();
                        })
                        .setNegativeButton(S.t("Cancel"), null)
                        .show();
            });
        }

        // ---- 2 触发条件 ----
        Ui.header(ui, S.t("2. When it locks"));
        Ui.row(ui, S.t("Manual"), S.t("Always available: Lock now on this page, the Lock button in the notification, or the Lock shortcut."), null);
        vnum(S.t("After a time"), S.t("Lock this many minutes after the service starts or after each unlock. 0 = off."),
                "pv_trig_timer", 0, 1440, " " + S.t("min"));
        vsw(S.t("Whenever the curtain expands"),
                S.t("Every time the curtain comes up it is locked at once, full screen, and closing it needs verification."), "pv_trig_expand");
        vsw(S.t("When the screen turns off"), null, "pv_trig_screenoff");
        vsw(S.t("After you leave this app"),
                S.t("Locks when this app's pages are no longer on screen (not while you are in a file picker or a system settings page opened from here)."),
                "pv_trig_leave");
        Ui.row(ui, S.t("Good to know"),
                S.t("Triggers lock the cover, so they only act while the keep-awake service is running. A trigger that fires while it is not running only ends the current verification, so Settings asks again."), null);

        // ---- 3 界面限制 ----
        Ui.header(ui, S.t("3. How the locked curtain behaves"));
        vsw(S.t("Taps cannot close it"),
                S.t("A tap does nothing at all, not even a verification prompt. The Back key then becomes the way to unlock, so it is switched on too."), "pv_ui_notap");
        vsw(S.t("Back key needs verification"),
                S.t("Back opens the verification screen. With this off, Back does nothing while locked and a tap asks to verify instead."), "pv_ui_back");
        vsw(S.t("Block screenshots and screen recording"),
                S.t("Marks the curtain and the verification screen as secure. Some systems ignore this for overlay windows."), "pv_ui_secure");
        vsw(S.t("Enhanced cover"),
                S.t("Draws the cover with the accessibility overlay, which can sit above the status bar and navigation bar. Needs the accessibility service (Settings > Stability guide)."),
                "pv_ui_enhanced");

        // ---- 4 授权范围 ----
        Ui.header(ui, S.t("4. What verification authorizes"));
        Ui.row(ui, S.t("Remove the cover"), S.t("Always needs a fresh verification while locked."), null);
        Ui.row(ui, S.t("How scopes work"),
                S.t("With protection on, Settings, backup and this page always need verification. These switches choose what one successful verification also opens for the \"stay verified\" time. On: no second prompt for that area. Off: that area asks again every time you enter it."), null);
        vsw(S.t("Open Settings"), null, "pv_sc_settings");
        vsw(S.t("Backup and restore"), null, "pv_sc_backup");
        vsw(S.t("Change protection settings"),
                S.t("This page, clearing app data. Off by default, so changing protection always asks again."), "pv_sc_policy");
        vnum(S.t("Stay verified for"), S.t("0 = only while this app stays open. Locking always ends it."),
                "pv_relock_min", 0, 240, " " + S.t("min"));
        Ui.row(ui, S.t("What it does not do"),
                S.t("Eye rest ending or being skipped, low battery, and the auto stop timer never count as verification. When they would stop keep-awake while locked, only the keep-awake part stops and the cover stays until you verify."), null);

        // ---- 5 失败响应 ----
        Ui.header(ui, S.t("5. After failed attempts"));
        Ui.row(ui, S.t("Stay locked"), S.t("Always on. A wrong password never lets anything through, and a built-in cooldown slows guessing down."), null);
        vnum(S.t("Failed attempts before the options below run"), S.t("Counts wrong passwords and wrong recovery codes, not cancelled system verification."),
                "pv_fail_limit", 1, 100, "");
        vsw(S.t("Longer and longer cooldown"),
                S.t("After the limit, each further failure doubles the lock-out (1, 2, 4, 8... minutes, up to a day)."), "pv_fail_cool");
        vsw(S.t("Clear authorized files and folders"),
                S.t("Deletes the files and folders you authorize below and checks that they are really gone. Needs at least one working authorization."), "pv_fail_wipe_files");
        vsw(S.t("Clear app data"),
                S.t("Erases everything this app stores, like the system's \"Clear storage\". If clearing files is also on, app data is only cleared after the files were confirmed deleted; if that fails the data is kept and the app stays locked."), "pv_fail_wipe_data");
        if (FailPolicy.hasPending(this)) {
            Ui.row(ui, S.t("Unfinished clean-up"), S.t("A previous clean-up did not finish. It is retried automatically."), null);
        }
        authList();
        Ui.row(ui, S.t("Not a safe, only a speed bump"),
                S.t("Clean-up needs a limit of at least 3. Anyone who can use system settings can still clear the app, so do not rely on this for secrets you cannot lose."), null);

        // ---- 紧急释放 ----
        Ui.header(ui, S.t("Emergency release"));
        vsw(S.t("Emergency release"),
                S.t("Press and hold the locked curtain to remove the cover without verifying, in case verification cannot be used. It does not open Settings, backup or protection settings, and it does not depend on notifications. On by default. With it off, a locked cover can only be removed by verifying."),
                "pv_emerg_on");
        if (Prefs.getBool(this, "pv_emerg_on")) {
            vnum(S.t("Press and hold the locked curtain for"), null, "pv_emerg_sec", 5, 60, " s");
        }

        Ui.header(ui, S.t("Reset"));
        action(S.t("Clear all app data"),
                S.t("A full reset, like the system's \"Clear storage\": also removes pictures, granted permissions and the privacy password. The app closes immediately afterwards."),
                this::clearAllDataFlow);
        setContentView(ui.root);   // 漏了这一行：页面内容建好了却从没设为界面，所以点进来是空白/进不去
    }

    // ================= 行 =================

    private View action(String title, String sub, final Runnable r) {
        View v = Ui.row(ui, title, sub, null);
        v.setOnClickListener(x -> r.run());
        return v;
    }

    private void explain(String why) {
        new AlertDialog.Builder(this)
                .setTitle(S.t("Not possible"))
                .setMessage(why)
                .setPositiveButton(S.t("OK"), null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** 带约束检查的开关：不允许就弹出原因并保持关闭，不会悄悄改成别的。 */
    private void vsw(String title, String sub, final String key) {
        final Switch sw = new Switch(this);
        sw.setChecked(Prefs.getBool(this, key));
        final boolean[] guard = {false};
        sw.setOnCheckedChangeListener((b, on) -> {
            if (guard[0]) return;
            String why = Protect.check(this, key, on);
            if (why != null) {
                guard[0] = true;
                sw.setChecked(!on);
                guard[0] = false;
                explain(why);
                return;
            }
            if (on && "pv_fail_wipe_data".equals(key)) {
                guard[0] = true;
                sw.setChecked(false);
                guard[0] = false;
                new AlertDialog.Builder(this)
                        .setTitle(S.t("Clear app data after failed attempts?"))
                        .setMessage(S.f("After %d failed attempts everything this app stores will be erased, including the privacy password. This cannot be undone.", FailPolicy.limit(this)))
                        .setPositiveButton(S.t("Turn on"), (d, w) -> {
                            Prefs.putBool(this, key, true);
                            FloatCtl.refresh(this);
                            build();
                        })
                        .setNegativeButton(S.t("Cancel"), null)
                        .show();
                return;
            }
            apply(key, on);
            if (isSideEffectKey(key)) build();
        });
        View r = Ui.row(ui, title, sub, sw);
        r.setOnClickListener(v -> sw.toggle());
    }

    private static boolean isSideEffectKey(String k) {
        return "pv_on".equals(k) || "pv_ui_notap".equals(k) || "pv_emerg_on".equals(k);
    }

    private void apply(String key, boolean on) {
        Prefs.putBool(this, key, on);
        if ("pv_on".equals(key)) {
            if (on) {
                Protect.grantSession(this);   // 刚刚设置好保护的人，不用立刻被自己锁在门外
                passEpoch = Protect.epoch();
            } else {
                Protect.setLocked(this, false);
            }
        }
        if ("pv_ui_notap".equals(key) && on) Prefs.putBool(this, "pv_ui_back", true);
        FloatCtl.refresh(this);
    }

    private void vnum(final String title, final String hint, final String key, final int min, final int max, final String unit) {
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
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(min + " - " + max + unit + (hint != null ? "\n\n" + hint : ""))
                    .setView(box)
                    .setPositiveButton(S.t("OK"), (d, w) -> {
                        try {
                            int val = Math.max(min, Math.min(max, Integer.parseInt(et.getText().toString().trim())));
                            String why = Protect.check(this, key, val);
                            if (why != null) {
                                explain(why);
                                return;
                            }
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

    /** 以 1 开始的数字选择（pv_mode：1/2/3）。 */
    private void vchoice(final String title, final String key, final String[] labels) {
        final int cur = Math.max(1, Math.min(labels.length, Prefs.getInt(this, key)));
        View r = Ui.row(ui, title, labels[cur - 1], null);
        r.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, cur - 1, (d, w) -> {
                    d.dismiss();
                    String why = Protect.check(this, key, w + 1);
                    if (why != null) {
                        explain(why);
                        return;
                    }
                    Prefs.putInt(this, key, w + 1);
                    FloatCtl.refresh(this);
                    build();
                }).show());
    }

    // ================= 自检 =================

    private void selfCheck() {
        StringBuilder sb = new StringBuilder();
        for (Caps.Item it : Caps.report(this)) {
            sb.append(it.level == Caps.OK ? "✓ " : it.level == Caps.WARN ? "! " : "✗ ")
                    .append(it.title).append('\n').append(it.detail).append("\n\n");
        }
        new AlertDialog.Builder(this)
                .setTitle(S.t("Protection check"))
                .setMessage(sb.toString().trim())
                .setPositiveButton(S.t("Test cover window"), (d, w) ->
                        toast(Caps.probeOverlay(this) ? S.t("The cover window can be shown.") : S.t("The cover window could not be shown.")))
                .setNeutralButton(S.t("Test verification"), (d, w) ->
                        startActivity(VerifyActivity.intent(this, Protect.Purpose.TEST, false)))
                .setNegativeButton(S.t("Close"), null)
                .show();
    }

    // ================= 密码 / 恢复码 =================

    private void setPasswordDialog() {
        final EditText a = new EditText(this), b2 = new EditText(this);
        for (EditText e : new EditText[]{a, b2}) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        a.setHint(S.t("4-16 characters"));
        b2.setHint(S.t("Repeat the password"));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Sys.dp(this, 20);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(a);
        box.addView(b2);
        new AlertDialog.Builder(this)
                .setTitle(PrivacyLock.hasPassword(this) ? S.t("Change password") : S.t("Set password"))
                .setView(box)
                .setPositiveButton(S.t("OK"), (d, w) -> {
                    String p = a.getText().toString();
                    if (p.length() < 4 || p.length() > 16) {
                        toast(S.t("Password must be 4 to 16 characters"));
                        return;
                    }
                    if (!p.equals(b2.getText().toString())) {
                        toast(S.t("The two passwords do not match"));
                        return;
                    }
                    PrivacyLock.setPassword(this, p);
                    toast(S.t("Saved"));
                    if (!PrivacyLock.hasRecoveryCode(this)) recoveryCodeDialog();
                    else build();
                })
                .setNegativeButton(S.t("Cancel"), null)
                .show();
    }

    private void recoveryCodeDialog() {
        if (PrivacyLock.hasRecoveryCode(this)) {
            new AlertDialog.Builder(this)
                    .setTitle(S.t("Recovery code"))
                    .setMessage(S.t("Generate a new recovery code? The old one stops working."))
                    .setPositiveButton(S.t("Generate"), (d, w) -> showNewRecoveryCode())
                    .setNegativeButton(S.t("Cancel"), null)
                    .show();
        } else {
            showNewRecoveryCode();
        }
    }

    private void showNewRecoveryCode() {
        String code = PrivacyLock.generateRecoveryCode(this);
        new AlertDialog.Builder(this)
                .setTitle(S.t("Recovery code"))
                .setMessage(S.t("Write this down and keep it somewhere safe. It is the only way back in if you forget your password, and it is shown only this once.") + "\n\n" + code)
                .setPositiveButton(S.t("I have saved it"), (d, w) -> build())
                .setCancelable(false)
                .show();
    }

    // ================= 授权文件 / 文件夹 =================

    private void authList() {
        Ui.header(ui, S.t("Authorized files and folders"));
        List<String> uris = Prefs.authUris(this);
        if (uris.isEmpty()) Ui.row(ui, S.t("Not set"), S.t("Pick a file or a folder below."), null);
        for (final String s : uris) {
            final Uri u;
            try {
                u = Uri.parse(s);
            } catch (Exception e) {
                continue;
            }
            boolean folder = DocumentsContract.isTreeUri(u);
            final boolean ok = AuthCleaner.canAccess(this, u);
            final String name = display(u);
            String sub = (folder ? S.t("Auth folder") : S.t("Auth file")) + " · "
                    + (ok ? S.t("Read/write OK") : S.t("Permission lost — tap to re-grant"));
            View r = Ui.row(ui, name, sub, null);
            r.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle(name)
                    .setMessage(ok ? S.t("Remove this item from the authorized list? The files themselves are not deleted.")
                            : S.t("Permission lost. Remove it and add it again with the buttons below."))
                    .setPositiveButton(S.t("Remove"), (dlg, w) -> removeAuth(s))
                    .setNegativeButton(S.t("Cancel"), null)
                    .show());
        }
        action(S.t("Choose file"), S.t("Add a file that can be deleted after repeated failed attempts."), () -> pick(false));
        action(S.t("Choose folder"), S.t("Add a folder. Everything inside is deleted, and the folder itself too when the system allows it."), () -> pick(true));
        if (!uris.isEmpty()) {
            action(S.t("Clear all authorized files now"),
                    S.t("Deletes everything right now and checks the result."), this::cleanNowDialog);
        }
    }

    private void pick(boolean folder) {
        Intent i = folder ? new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                : new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_AUTH);
        } catch (ActivityNotFoundException e) {
            toast(S.t("No file manager available"));
        }
    }

    private void saveAuth(Uri u) {
        try {
            getContentResolver().takePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
        List<String> uris = Prefs.authUris(this);
        if (!uris.contains(u.toString())) {
            uris.add(u.toString());
            Prefs.putAuthUris(this, uris);
        }
        toast(AuthCleaner.canAccess(this, u) ? S.t("Saved") : S.t("Permission lost. Remove it and add it again with the buttons below."));
    }

    private void removeAuth(String s) {
        String why = Protect.checkRemoveAuth(this);
        if (why != null) {
            explain(why);
            return;
        }
        List<String> uris = Prefs.authUris(this);
        uris.remove(s);
        Prefs.putAuthUris(this, uris);
        try {
            getContentResolver().releasePersistableUriPermission(Uri.parse(s),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        build();
    }

    private void cleanNowDialog() {
        new AlertDialog.Builder(this)
                .setTitle(S.t("Clear all authorized files now"))
                .setMessage(S.t("Delete every authorized file and empty every authorized folder right now. This cannot be undone. Continue?"))
                .setPositiveButton(S.t("Delete all"), (d, w) -> {
                    toast(S.t("Working..."));
                    final Context app = getApplicationContext();
                    new Thread(() -> {
                        final AuthCleaner.Result r = AuthCleaner.cleanAll(app);
                        runOnUiThread(() -> {
                            if (isFinishing()) return;
                            new AlertDialog.Builder(this)
                                    .setTitle(r.ok ? S.t("Done") : S.t("Not everything could be deleted"))
                                    .setMessage(S.f("%d of %d items cleared, %d failed.", r.cleaned, r.items, r.failed))
                                    .setPositiveButton(S.t("OK"), null)
                                    .show();
                            build();
                        });
                    }, "pv-clean-now").start();
                })
                .setNegativeButton(S.t("Cancel"), null)
                .show();
    }

    private String display(Uri u) {
        try {
            String s = u.toString();
            String last = s.substring(s.lastIndexOf('/') + 1);
            int q = last.indexOf('?');
            if (q >= 0) last = last.substring(0, q);
            return Uri.decode(last);
        } catch (Exception e) {
            return u.toString();
        }
    }

    // ================= 清除全部数据 =================

    private void clearAllDataFlow() {
        new AlertDialog.Builder(this)
                .setTitle(S.t("Clear all app data"))
                .setMessage(S.t("This erases everything Screen Keep-On has stored - settings, styles, background pictures, granted picture permissions, and the privacy password - the same as the system's own \"Clear storage\" button. It cannot be undone, and the app closes right away."))
                .setPositiveButton(S.t("Continue"), (d, w) -> new AlertDialog.Builder(this)
                        .setTitle(S.t("Clear everything now?"))
                        .setPositiveButton(S.t("Clear everything"), (d2, w2) -> {
                            try {
                                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                                if (am == null || !am.clearApplicationUserData()) toast(S.t("Could not clear app data"));
                            } catch (Exception e) {
                                toast(S.t("Could not clear app data"));
                            }
                        })
                        .setNegativeButton(S.t("Cancel"), null)
                        .show())
                .setNegativeButton(S.t("Cancel"), null)
                .show();
    }
}
