package com.mja.reyamf.xposed.services

import android.content.ComponentName
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManagerHidden
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.mja.reyamf.common.model.StartCmd
import com.mja.reyamf.xposed.ISystemUiCallback
import com.mja.reyamf.xposed.utils.Instances
import com.mja.reyamf.xposed.utils.moveTask
import com.mja.reyamf.xposed.utils.startActivity
import com.mja.reyamf.xposed.utils.startAuto

/**
 * Backend đặc quyền cho client SystemUI (PLAN_SPLIT_SYSTEMUI, giai đoạn 1).
 *
 * Chỉ giữ quyền display/surface/task/input/IME; không tham chiếu View, binding hay AppWindow.
 * Mọi method đặc quyền phải được gọi từ process com.android.systemui (kiểm tra ở YAMFManager).
 */
object SystemUiBackend {
    private const val TAG = "reYAMF_SystemUiBackend"
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    private class Client(val callback: ISystemUiCallback, val death: IBinder.DeathRecipient) {
        val displays = mutableSetOf<Int>()
        val surfaces = mutableMapOf<Int, Surface>()
        @Volatile
        var dead = false
    }

    private val lock = Any()
    private val clients = mutableMapOf<Long, Client>()
    private val displays = mutableMapOf<Int, VirtualDisplay>()
    private val displayOwners = mutableMapOf<Int, Long>()
    private var nextClientId = 1L

    /** Chặn caller không phải SystemUI. Gọi ở đầu mỗi method đặc quyền trong YAMFManager. */
    fun requireSystemUiCaller() {
        val callerUid = Binder.getCallingUid()
        val expectedUid = runCatching {
            Instances.packageManager.getPackageUid(SYSTEM_UI_PACKAGE, 0)
        }.getOrDefault(-1)
        if (expectedUid <= 0 || callerUid != expectedUid) {
            throw SecurityException("caller uid=$callerUid is not $SYSTEM_UI_PACKAGE")
        }
    }

    fun register(callback: ISystemUiCallback): Long {
        synchronized(lock) {
            val binder = callback.asBinder()
            clients.entries.firstOrNull { it.value.callback.asBinder() === binder }?.let {
                Log.d(TAG, "register: reuse clientId=${it.key}")
                return it.key
            }
            val clientId = nextClientId++
            val death = IBinder.DeathRecipient {
                Log.d(TAG, "binderDied clientId=$clientId")
                onClientDied(clientId)
            }
            binder.linkToDeath(death, 0)
            clients[clientId] = Client(callback, death)
            Log.d(TAG, "register: clientId=$clientId uid=${Binder.getCallingUid()}")
            return clientId
        }
    }

    fun unregister(clientId: Long) {
        synchronized(lock) {
            val client = clients.remove(clientId) ?: return
            client.dead = true
            runCatching { client.callback.asBinder().unlinkToDeath(client.death, 0) }
            releaseClientDisplays(clientId, client)
            Log.d(TAG, "unregister: clientId=$clientId")
        }
    }

    private fun onClientDied(clientId: Long) {
        synchronized(lock) {
            val client = clients.remove(clientId) ?: return
            client.dead = true
            releaseClientDisplays(clientId, client)
        }
    }

    private fun releaseClientDisplays(clientId: Long, client: Client) {
        client.displays.toList().forEach { displayId ->
            val vd = displays.remove(displayId)
            displayOwners.remove(displayId)
            runCatching { vd?.surface = null }
            runCatching { vd?.release() }
            client.surfaces.remove(displayId)?.let { runCatching { it.release() } }
            Log.d(TAG, "released displayId=$displayId of clientId=$clientId")
        }
        client.displays.clear()
    }

    fun createVirtualDisplay(
        clientId: Long,
        surface: Surface?,
        width: Int,
        height: Int,
        densityDpi: Int,
        flags: Int
    ): Int {
        if (surface == null || width <= 0 || height <= 0 || densityDpi <= 0) {
            Log.w(TAG, "createVirtualDisplay: invalid args ${width}x$height dpi=$densityDpi surface=$surface")
            return -1
        }
        synchronized(lock) {
            val client = clients[clientId] ?: run {
                Log.w(TAG, "createVirtualDisplay: unknown clientId=$clientId")
                return -1
            }
            val vd = Instances.displayManager.createVirtualDisplay(
                "yamf${System.currentTimeMillis()}", width, height, densityDpi, surface, flags
            )
            val displayId = vd.display.displayId
            displays[displayId] = vd
            displayOwners[displayId] = clientId
            client.displays.add(displayId)
            client.surfaces[displayId] = surface
            runCatching {
                (Instances.windowManager as WindowManagerHidden).setDisplayImePolicy(
                    displayId,
                    if (YAMFManager.config.showImeInWindow) WindowManagerHidden.DISPLAY_IME_POLICY_LOCAL
                    else WindowManagerHidden.DISPLAY_IME_POLICY_FALLBACK_DISPLAY
                )
            }.onFailure { Log.w(TAG, "setDisplayImePolicy failed displayId=$displayId", it) }
            Log.d(TAG, "createVirtualDisplay: clientId=$clientId displayId=$displayId ${width}x$height dpi=$densityDpi flags=$flags")
            return displayId
        }
    }

    fun setVirtualDisplaySurface(clientId: Long, displayId: Int, surface: Surface?) {
        if (surface == null) return
        synchronized(lock) {
            val client = ownedClient(clientId, displayId) ?: return
            val vd = displays[displayId] ?: return
            runCatching { vd.surface = null }
            client.surfaces.remove(displayId)?.let { runCatching { it.release() } }
            vd.surface = surface
            client.surfaces[displayId] = surface
            Log.d(TAG, "setVirtualDisplaySurface: displayId=$displayId")
        }
    }

    fun clearVirtualDisplaySurface(clientId: Long, displayId: Int) {
        synchronized(lock) {
            val client = ownedClient(clientId, displayId) ?: return
            val vd = displays[displayId] ?: return
            runCatching { vd.surface = null }
            client.surfaces.remove(displayId)?.let { runCatching { it.release() } }
            Log.d(TAG, "clearVirtualDisplaySurface: displayId=$displayId")
        }
    }

    fun resizeVirtualDisplay(clientId: Long, displayId: Int, width: Int, height: Int, densityDpi: Int) {
        if (width <= 0 || height <= 0 || densityDpi <= 0) return
        synchronized(lock) {
            ownedClient(clientId, displayId) ?: return
            val vd = displays[displayId] ?: return
            vd.resize(width, height, densityDpi)
            Log.d(TAG, "resizeVirtualDisplay: displayId=$displayId ${width}x$height dpi=$densityDpi")
        }
    }

    fun releaseWindow(clientId: Long, displayId: Int) {
        synchronized(lock) {
            val client = ownedClient(clientId, displayId) ?: return
            val vd = displays.remove(displayId)
            displayOwners.remove(displayId)
            client.displays.remove(displayId)
            runCatching { vd?.surface = null }
            runCatching { vd?.release() }
            client.surfaces.remove(displayId)?.let { runCatching { it.release() } }
            Log.d(TAG, "releaseWindow: clientId=$clientId displayId=$displayId")
        }
    }

    fun startActivityOnDisplay(clientId: Long, displayId: Int, componentName: String, userId: Int) {
        synchronized(lock) { ownedClient(clientId, displayId) ?: return }
        val component = ComponentName.unflattenFromString(componentName) ?: run {
            Log.w(TAG, "startActivityOnDisplay: bad component '$componentName'")
            return
        }
        startActivity(Instances.systemContext, component, userId, displayId)
    }

    fun moveTaskToDisplay(clientId: Long, taskId: Int, displayId: Int) {
        synchronized(lock) { ownedClient(clientId, displayId) ?: return }
        moveTask(taskId, displayId)
    }

    fun moveTaskToDefaultDisplay(clientId: Long, taskId: Int) {
        synchronized(lock) {
            if (clients[clientId] == null) return
            moveTask(taskId, 0)
        }
    }

    fun closeTask(clientId: Long, taskId: Int) {
        synchronized(lock) { if (clients[clientId] == null) return }
        runCatching { Instances.activityTaskManager.removeTask(taskId) }
            .onFailure { Log.w(TAG, "closeTask failed taskId=$taskId", it) }
    }

    fun launchStartCommand(clientId: Long, displayId: Int, componentName: String, userId: Int, taskId: Int) {
        synchronized(lock) { ownedClient(clientId, displayId) ?: return }
        val component = componentName.takeIf { it.isNotEmpty() }?.let { ComponentName.unflattenFromString(it) }
        val startCmd = StartCmd(
            componentName = component,
            userId = if (userId >= 0) userId else null,
            taskId = if (taskId != 0) taskId else null
        )
        startCmd.startAuto(displayId)
    }

    fun setDisplayImePolicy(clientId: Long, displayId: Int, policy: Int) {
        synchronized(lock) { ownedClient(clientId, displayId) ?: return }
        (Instances.windowManager as WindowManagerHidden).setDisplayImePolicy(displayId, policy)
    }

    fun injectMotionEvent(clientId: Long, displayId: Int, event: MotionEvent?) {
        if (event == null) return
        synchronized(lock) { ownedClient(clientId, displayId) ?: return }
        val clone = MotionEvent.obtain(event)
        try {
            clone.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
            Instances.inputManager.injectInputEvent(clone, 0)
        } finally {
            clone.recycle()
            event.recycle()
        }
    }

    fun injectBackKey(clientId: Long, displayId: Int) {
        synchronized(lock) { ownedClient(clientId, displayId) ?: return }
        fun inject(action: Int) {
            val keyEvent = KeyEvent(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                action,
                KeyEvent.KEYCODE_BACK,
                0
            )
            keyEvent.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
            Instances.inputManager.injectInputEvent(keyEvent, 0)
        }
        inject(KeyEvent.ACTION_DOWN)
        inject(KeyEvent.ACTION_UP)
    }

    /** Trả về client nếu clientId tồn tại và sở hữu displayId; ngược lại log và trả null. */
    private fun ownedClient(clientId: Long, displayId: Int): Client? {
        val client = clients[clientId]
        if (client == null) {
            Log.w(TAG, "unknown clientId=$clientId (displayId=$displayId)")
            return null
        }
        if (displayOwners[displayId] != clientId) {
            Log.w(TAG, "displayId=$displayId not owned by clientId=$clientId")
            return null
        }
        return client
    }
}
