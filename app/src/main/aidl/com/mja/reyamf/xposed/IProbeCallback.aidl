// TEMPORARY PROBE (PLAN_SPLIT_SYSTEMUI bước 0). Xóa cùng IProbeAidl.aidl và ProbeAidlImpl.kt sau khi bước 0 pass.
package com.mja.reyamf.xposed;

oneway interface IProbeCallback {
    void onProbeEvent(int displayId, int taskId);
}
