package com.wave.screentool;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 可选的“增强模式”：只提供一个比普通悬浮窗层级更高的窗口类型，并让系统托管本服务。
 * 不读取、不收集任何屏幕内容，不处理任何事件。
 */
public class KeepAccessibilityService extends AccessibilityService {
    static volatile KeepAccessibilityService inst;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        inst = this;
        FloatCtl.refresh(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        inst = null;
        FloatCtl.refresh(this);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        inst = null;
        super.onDestroy();
    }
}
