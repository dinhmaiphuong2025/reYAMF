// TEMPORARY PROBE (PLAN_SPLIT_SYSTEMUI bước 0). Xóa cùng IProbeCallback.aidl và ProbeAidlImpl.kt sau khi bước 0 pass.
package com.mja.reyamf.xposed;

import android.view.Surface;
import android.view.MotionEvent;
import com.mja.reyamf.xposed.IProbeCallback;

interface IProbeAidl {
    int probeCreate(in Surface surface, int width, int height, int densityDpi, int flags);

    void probeResize(int displayId, int width, int height, int densityDpi);

    oneway void probeInject(in MotionEvent event, int displayId);

    void probeRegister(IProbeCallback callback);
}
