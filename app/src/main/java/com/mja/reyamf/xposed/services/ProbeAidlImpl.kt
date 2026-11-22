package com.mja.reyamf.xposed.services

import android.view.MotionEvent
import android.view.Surface
import com.mja.reyamf.xposed.IProbeAidl
import com.mja.reyamf.xposed.IProbeCallback

/**
 * TEMPORARY PROBE (PLAN_SPLIT_SYSTEMUI bước 0).
 * Mục đích duy nhất: chứng minh AIDL generator + Kotlin compiler của repo (compileSdk 36, AGP hiện tại)
 * chấp nhận android.view.Surface và android.view.MotionEvent qua Binder, kèm callback oneway.
 * Xóa file này cùng IProbeAidl.aidl/IProbeCallback.aidl sau khi bước 0 pass.
 */
@Suppress("unused")
class ProbeAidlImpl : IProbeAidl.Stub() {

    override fun probeCreate(surface: Surface?, width: Int, height: Int, densityDpi: Int, flags: Int): Int {
        val s = surface ?: return -1
        return s.hashCode() xor width xor height xor densityDpi xor flags
    }

    override fun probeResize(displayId: Int, width: Int, height: Int, densityDpi: Int) {
        // probe only
    }

    override fun probeInject(event: MotionEvent?, displayId: Int) {
        event?.recycle()
    }

    override fun probeRegister(callback: IProbeCallback?) {
        callback?.onProbeEvent(0, 0)
    }
}
