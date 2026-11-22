package com.mja.reyamf.manager.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.mja.reyamf.manager.services.YAMFManagerProxy

class ServiceProvider : ContentProvider() {

    companion object {
        private const val TAG = "reYAMF_ServiceProvider"

        /** Method SystemUI dùng để kéo Binder của system_server từ app process. */
        const val METHOD_GET_SYSTEM_SERVER_BINDER = "GET_SYSTEM_SERVER_BINDER"

        const val KEY_STATUS = "status"
        const val KEY_BINDER = "binder"

        const val STATUS_READY = "READY"
        const val STATUS_NOT_READY = "NOT_READY"
        const val STATUS_DENIED = "DENIED"

        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }

    override fun onCreate() = false

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ) = null

    override fun getType(uri: Uri) = null

    override fun insert(uri: Uri, values: ContentValues?) = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ) = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val callerUid = Binder.getCallingUid()
        val callerPkg = runCatching { callingPackage }.getOrNull()

        // 1) Relay cũ: system_server đẩy Binder IYAMFManager vào app process.
        val pushed = extras?.getBinder(KEY_BINDER)
        if (pushed != null) {
            if (callerUid != Process.SYSTEM_UID) {
                Log.w(TAG, "push rejected: uid=$callerUid pkg=$callerPkg")
                return denied()
            }
            YAMFManagerProxy.linkService(pushed)
            Log.d(TAG, "push accepted: uid=$callerUid")
            return ready(null)
        }

        // 2) Pull: chỉ SystemUI được lấy Binder đã relay.
        if (method != METHOD_GET_SYSTEM_SERVER_BINDER) {
            Log.w(TAG, "unknown method '$method' from uid=$callerUid pkg=$callerPkg")
            return denied()
        }
        if (!isSystemUiCaller(callerUid, callerPkg)) {
            Log.w(TAG, "pull denied: uid=$callerUid pkg=$callerPkg")
            return denied()
        }
        val binder = YAMFManagerProxy.asBinder()
        if (binder == null) {
            Log.d(TAG, "pull not_ready: uid=$callerUid")
            return Bundle().apply { putString(KEY_STATUS, STATUS_NOT_READY) }
        }
        Log.d(TAG, "pull ready: uid=$callerUid")
        return ready(binder)
    }

    private fun isSystemUiCaller(uid: Int, pkg: String?): Boolean {
        if (pkg != SYSTEM_UI_PACKAGE) return false
        val ctx = context ?: return false
        val expectedUid = runCatching {
            ctx.packageManager.getPackageUid(SYSTEM_UI_PACKAGE, 0)
        }.getOrDefault(-1)
        if (expectedUid <= 0 || uid != expectedUid) return false
        // callingPackage do AMS cung cấp; uid == uid của package SystemUI là đủ để chặn caller khác.
        // AppOpsManager.checkPackage không resolve được trên compileSdk 36 nên không dùng.
        return true
    }

    private fun ready(binder: android.os.IBinder?): Bundle = Bundle().apply {
        putString(KEY_STATUS, STATUS_READY)
        if (binder != null) putBinder(KEY_BINDER, binder)
    }

    private fun denied(): Bundle = Bundle().apply { putString(KEY_STATUS, STATUS_DENIED) }
}
