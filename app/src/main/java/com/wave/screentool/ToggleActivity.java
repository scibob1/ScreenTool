package com.wave.screentool;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * 无界面：给桌面长按快捷方式、快速设置磁贴、通知按钮（保护开启时）使用。
 * mode: 空=开/关切换；ball / curtain / notif=切到该形态（未运行则启动）；stop=停止；
 *       toggle=球/幕布切换；lock=立即上锁。
 *
 * 所有会降低保护的操作（停止、切换形态、收起幕布）在上锁期间都要先过统一的保护控制器，
 * 需要验证时在这里拉起验证页，通过后才执行——快捷方式和磁贴不能绕过保护。
 */
public class ToggleActivity extends Activity {
    private static final int REQ_VERIFY = 51;
    private String mode;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        S.initLite(this);
        mode = getIntent().getStringExtra("mode");
        if (b != null) {
            finish();
            return;
        }
        if ("lock".equals(mode)) {
            if (!Protect.armed(this)) {
                Toast.makeText(this, S.t("Turn on privacy protection in Settings first."), Toast.LENGTH_SHORT).show();
            } else if (!Protect.lockNow(this, true)) {
                Toast.makeText(this, S.t("Cannot lock: the cover needs the \"Display over other apps\" permission or the enhanced overlay."), Toast.LENGTH_LONG).show();
            }
            finish();
            return;
        }
        Protect.Purpose p = purpose();
        if (p != null && Protect.needVerify(this, p)) {
            startActivityForResult(VerifyActivity.intent(this, p, false), REQ_VERIFY);
            return;
        }
        act();
        finish();
    }

    /** 这次操作会不会降低保护（null=不会）。 */
    private Protect.Purpose purpose() {
        boolean running = FloatCtl.running();
        if ("stop".equals(mode) || mode == null) return running ? Protect.Purpose.STOP : null;
        if (!running) return null;
        if ("toggle".equals(mode) || "ball".equals(mode) || "notif".equals(mode)) return Protect.Purpose.UNLOCK;
        return null;   // curtain：只会加强
    }

    private void act() {
        if ("stop".equals(mode)) {
            FloatCtl.stop(this);
        } else if ("toggle".equals(mode)) {
            if (FloatCtl.running()) {
                startService(new Intent(this, FloatService.class).setAction(FloatService.ACT_TOGGLE));
            }
        } else if (mode == null) {
            if (FloatCtl.running()) FloatCtl.stop(this);
            else FloatCtl.start(this, null, true);
        } else if (FloatCtl.running()) {
            FloatCtl.setMode(this, mode);
        } else {
            FloatCtl.start(this, mode, true);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VERIFY && res == RESULT_OK) act();
        finish();
    }
}
