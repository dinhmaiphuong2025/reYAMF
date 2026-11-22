// IYAMFManager.aidl
package com.mja.reyamf.xposed;

import com.mja.reyamf.xposed.IOpenCountListener;
import com.mja.reyamf.common.model.AppInfo;
import com.mja.reyamf.xposed.IAppListCallback;
import com.mja.reyamf.xposed.IAppIconCallback;
import com.mja.reyamf.xposed.ISystemUiCallback;
import android.view.Surface;
import android.view.MotionEvent;
// Declare any non-default types here with import statements

interface IYAMFManager {
    String getVersionName();

    int getVersionCode();

    int getUid();

    void createWindow();

    long getBuildTime();

    String getConfigJson();

    void updateConfig(String newConfig);

    void registerOpenCountListener(IOpenCountListener iOpenCountListener);

    void unregisterOpenCountListener(IOpenCountListener iOpenCountListener);

    void currentToWindow();

    void resetAllWindow();

    List<AppInfo> getAppList();

    void createWindowUserspace(in AppInfo appInfo);

    void getAppListAsync(IAppListCallback callback);

    void getAppIcon(IAppIconCallback callback, in AppInfo appInfo);

    void collapseStatusBarPanel();

    // --- SystemUI client (PLAN_SPLIT_SYSTEMUI). Chỉ caller com.android.systemui được gọi. ---

    long registerSystemUiClient(ISystemUiCallback callback);

    void unregisterSystemUiClient(long clientId);

    int createVirtualDisplay(long clientId, in Surface surface, int width, int height, int densityDpi, int flags);

    void setVirtualDisplaySurface(long clientId, int displayId, in Surface surface);

    void clearVirtualDisplaySurface(long clientId, int displayId);

    void resizeVirtualDisplay(long clientId, int displayId, int width, int height, int densityDpi);

    void releaseWindow(long clientId, int displayId);

    void startActivityOnDisplay(long clientId, int displayId, String componentName, int userId);

    void moveTaskToDisplay(long clientId, int taskId, int displayId);

    void moveTaskToDefaultDisplay(long clientId, int taskId);

    void closeTask(long clientId, int taskId);

    void launchStartCommand(long clientId, int displayId, String componentName, int userId, int taskId);

    void setDisplayImePolicy(long clientId, int displayId, int policy);

    oneway void injectMotionEvent(long clientId, int displayId, in MotionEvent event);

    oneway void injectBackKey(long clientId, int displayId);
}
