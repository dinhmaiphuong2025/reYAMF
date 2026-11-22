// Callback từ system_server (reYAMF backend) tới process com.android.systemui.
// Toàn bộ oneway để không block system_server khi SystemUI chậm/chết.
package com.mja.reyamf.xposed;

oneway interface ISystemUiCallback {
    void onCreateWindow(long requestId, String componentName, int userId, int taskId);

    void onTaskStateChanged(int displayId, int taskId, boolean visible);

    void onTaskClosed(int displayId, int taskId);

    void onDisplayError(long requestId, int displayId, int errorCode, String message);
}
