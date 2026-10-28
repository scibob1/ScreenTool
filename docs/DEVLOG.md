# v1.4.2 — Notification independence + fail policy completion

## Changes
1. Clear app data: reuse existing 通用→维护→清除所有应用数据 pattern (already done)
2. Clear external auth file: add folder support + file list display in settings
3. Notification toggle: add notif_enabled switch in 悬浮窗→通知
4. Service resilience: work without POST_NOTIFICATIONS permission (API 33+)
5. Stability wizard: fix notification row to handle permanently-denied case
6. Chinese/English copy alignment throughout