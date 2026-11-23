package com.mja.reyamf.xposed.services

import android.content.ComponentName
import android.hardware.display.VirtualDisplay
import android.app.ITaskStackListener
import android.app.ITaskStackListenerProxy
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
import com.mja.reyamf.xposed.utils.getTopRootTask
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

    // --- Window order/count: backend là nguồn duy nhất (stage 3b) ---
    private val windowList = mutableListOf<Int>()          // head = cửa sổ trên cùng
    private val taskToDisplay = mutableMapOf<Int, Int>()
    private var taskListener: ITaskStackListener? = null
    private var taskListenerPending = false
    private var nextRequestId = 1L
    @Volatile private var countListener: ((Int) -> Unit)? = null

    fun setCountListener(listener: ((Int) -> Unit)?) {
        countListener = listener
    }

    fun windowCount(): Int = synchronized(lock) { windowList.size }

    /** Gửi yêu cầu tạo cửa sổ sang SystemUI. Trả false khi không có client live. */
    fun requestCreateWindow(startCmd: StartCmd?): Boolean {
        val requestId: Long
        val clientId: Long
        val callback: ISystemUiCallback
        synchronized(lock) {
            val live = clients.entries.firstOrNull { !it.value.dead } ?: run {
                Log.w(TAG, "requestCreateWindow: no live SystemUI client")
                return false
            }
            requestId = nextRequestId++
            clientId = live.key
            callback = live.value.callback
        }
        val component = startCmd?.componentName?.flattenToString()
        val userId = startCmd?.userId ?: 0
        val taskId = startCmd?.taskId ?: 0
        val sent = runCatching { callback.onCreateWindow(requestId, component, userId, taskId) }
            .onFailure { Log.w(TAG, "onCreateWindow failed requestId=$requestId", it) }
            .isSuccess
        if (!sent) {
            synchronized(lock) { onClientDied(clientId) }
            return false
        }
        ensureTaskListener()
        Log.d(TAG, "requestCreateWindow: requestId=$requestId clientId=$clientId comp=$component user=$userId task=$taskId")
        return true
    }

    fun addWindow(displayId: Int) {
        if (displayId < 0) return
        val count: Int
        val changed: Boolean
        synchronized(lock) {
            changed = !windowList.contains(displayId)
            if (changed) windowList.add(0, displayId)
            count = windowList.size
        }
        if (changed) notifyCount(count)
    }

    fun removeWindow(displayId: Int) {
        val count: Int
        val changed: Boolean
        synchronized(lock) {
            changed = windowList.remove(displayId)
            taskToDisplay.entries.removeAll { it.value == displayId }
            if (windowList.isEmpty()) unregisterTaskListenerLocked()
            count = windowList.size
        }
        if (changed) notifyCount(count)
    }

    fun isTop(displayId: Int): Boolean =
        synchronized(lock) { windowList.isEmpty() || windowList[0] == displayId }

    fun moveToTop(displayId: Int) {
        synchronized(lock) {
            if (!windowList.remove(displayId)) return
            windowList.add(0, displayId)
        }
    }

    private fun notifyCount(count: Int) {
        runCatching { countListener?.invoke(count) }
            .onFailure { Log.w(TAG, "countListener failed count=$count", it) }
    }

    // --- Task lifecycle ---

    private fun ensureTaskListener() {
        synchronized(lock) {
            if (taskListener != null || taskListenerPending) return
            taskListenerPending = true
        }
        val listener = runCatching {
            ITaskStackListenerProxy.newInstance(SystemUiBackend::class.java.classLoader) { args, method ->
                onTaskStackEvent(method.name, args)
            }
        }.getOrElse { t ->
            synchronized(lock) { taskListenerPending = false }
            Log.w(TAG, "create task listener failed", t)
            return
        }
        val registered = runCatching {
            asSystem { Instances.activityTaskManager.registerTaskStackListener(listener) }
        }.isSuccess
        synchronized(lock) {
            taskListenerPending = false
            if (registered && taskListener == null) {
                taskListener = listener
            } else if (!registered) {
                Log.w(TAG, "registerTaskStackListener failed")
            }
        }
        if (registered) Log.d(TAG, "task listener registered")
    }

    private fun unregisterTaskListenerLocked() {
        val listener = taskListener ?: return
        taskListener = null
        runCatching { asSystem { Instances.activityTaskManager.unregisterTaskStackListener(listener) } }
            .onFailure { Log.w(TAG, "unregisterTaskStackListener failed", it) }
    }

    private fun onTaskStackEvent(methodName: String, args: Array<Any?>) {
        when (methodName) {
            "onTaskRemovalStarted", "onTaskRemoved" -> {
                val taskId = extractTaskId(args.getOrNull(0))
                Log.d(TAG, "task event=$methodName taskId=$taskId")
                if (taskId > 0) onTaskRemoved(taskId)
            }
            "onTaskCreated", "onTaskStackChanged", "onTaskMovedToFront" -> {
                Log.d(TAG, "task event=$methodName")
                refreshTaskMap()
            }
        }
    }

    private fun extractTaskId(arg: Any?): Int = when (arg) {
        is Int -> arg
        null -> -1
        else -> runCatching { arg.javaClass.getField("taskId").getInt(arg) }.getOrDefault(-1)
    }

    private fun refreshTaskMap() {
        val snapshot = synchronized(lock) { windowList.toList() }
        if (snapshot.isEmpty()) return
        snapshot.forEach { displayId ->
            val top = runCatching { asSystem { getTopRootTask(displayId) } }.getOrNull() ?: return@forEach
            synchronized(lock) {
                if (windowList.contains(displayId)) taskToDisplay[top.taskId] = displayId
            }
        }
    }

    private fun onTaskRemoved(taskId: Int) {
        var displayId = -1
        var callback: ISystemUiCallback? = null
        val count: Int
        synchronized(lock) {
            displayId = taskToDisplay.remove(taskId) ?: -1
            if (displayId >= 0 && windowList.remove(displayId)) {
                callback = clients.values.firstOrNull { !it.dead }?.callback
            }
            if (windowList.isEmpty()) unregisterTaskListenerLocked()
            count = windowList.size
        }
        notifyCount(count)
        if (displayId >= 0) {
            runCatching { callback?.onTaskClosed(displayId, taskId) }
                .onFailure { Log.w(TAG, "onTaskClosed failed displayId=$displayId", it) }
            Log.d(TAG, "onTaskRemoved: displayId=$displayId taskId=$taskId windows=$count")
        }
    }

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
        var callback: ISystemUiCallback? = null
        var taskId = 0
        synchronized(lock) {
            val client = ownedClient(clientId, displayId) ?: return
            taskId = taskToDisplay.entries.firstOrNull { it.value == displayId }?.key ?: 0
            val vd = displays.remove(displayId)
            displayOwners.remove(displayId)
            client.displays.remove(displayId)
            runCatching { vd?.surface = null }
            runCatching { vd?.release() }
            client.surfaces.remove(displayId)?.let { runCatching { it.release() } }
            callback = clients.values.firstOrNull { !it.dead }?.callback
            Log.d(TAG, "releaseWindow: clientId=$clientId displayId=$displayId taskId=$taskId")
        }
        removeWindow(displayId)
        // Báo cho connector dọn map; SystemUiAppWindow đã destroyed nên phía client là no-op.
        if (callback != null) {
            runCatching { callback?.onTaskClosed(displayId, taskId) }
                .onFailure { Log.w(TAG, "releaseWindow onTaskClosed failed displayId=$displayId", it) }
        }
    }

    /**
     * Chạy thao tác đặc quyền với identity của system_server.
     *
     * Backend chạy trên chính Binder thread của SystemUI, nên Binder.getCallingUid()
     * vẫn là uid SystemUI (10180) trong khi package là "android" -> ATMS/WMS/IMS
     * từ chối ("package=android does not belong to uid=10180").
     */
    private inline fun <T> asSystem(block: () -> T): T {
        val token = Binder.clearCallingIdentity()
        return try {
            block()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    fun startActivityOnDisplay(clientId: Long, displayId: Int, componentName: String, userId: Int) {
        synchronized(lock) { ownedClient(clientId, displayId) ?: return }
        val component = ComponentName.unflattenFromString(componentName) ?: run {
            Log.w(TAG, "startActivityOnDisplay: bad component '$componentName'")
            return
        }
        val ok = runCatching {
            asSystem { startActivity(Instances.systemContext, component, userId, displayId) }
        }.onFailure { Log.w(TAG, "startActivityOnDisplay failed $component", it) }.isSuccess
        if (ok) {
            addWindow(displayId)
            ensureTaskListener()
        }
    }

    fun moveTaskToDisplay(clientId: Long, taskId: Int, displayId: Int) {
        synchronized(lock) { ownedClient(clientId, displayId) ?: return }
        val ok = runCatching { asSystem { moveTask(taskId, displayId) } }
            .onFailure { Log.w(TAG, "moveTaskToDisplay failed taskId=$taskId displayId=$displayId", it) }
            .isSuccess
        if (ok) {
            synchronized(lock) { taskToDisplay[taskId] = displayId }
            addWindow(displayId)
            ensureTaskListener()
        }
    }

    fun moveTaskToDefaultDisplay(clientId: Long, taskId: Int) {
        var resolvedTaskId = taskId
        synchronized(lock) {
            if (clients[clientId] == null) return
        }
        if (resolvedTaskId <= 0) {
            // taskId <= 0: lấy task đang hiển thị trên cửa sổ của client này.
            val displayIds = synchronized(lock) { windowList.toList() }
            resolvedTaskId = displayIds.firstNotNullOfOrNull { d ->
                runCatching { asSystem { getTopRootTask(d) } }.getOrNull()?.taskId
            } ?: 0
        }
        if (resolvedTaskId <= 0) {
            Log.w(TAG, "moveTaskToDefaultDisplay: no task found for clientId=$clientId")
            return
        }
        val moved = runCatching { asSystem { moveTask(resolvedTaskId, 0) } }
            .onFailure { Log.w(TAG, "moveTaskToDefaultDisplay failed taskId=$resolvedTaskId", it) }
            .isSuccess
        if (moved) {
            synchronized(lock) { taskToDisplay.remove(resolvedTaskId) }
            Log.d(TAG, "moveTaskToDefaultDisplay: taskId=$resolvedTaskId")
        }
    }

    fun closeTask(clientId: Long, taskId: Int) {
        synchronized(lock) { if (clients[clientId] == null) return }
        runCatching {
            asSystem {
                Instances.activityTaskManager.invokeMethod(
                    "removeTask", args(taskId), argTypes(Integer.TYPE)
                )
            }
        }.onFailure { Log.w(TAG, "closeTask failed taskId=$taskId", it) }
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
        asSystem {
            (Instances.windowManager as WindowManagerHidden).setDisplayImePolicy(displayId, policy)
        }
    }

    fun injectMotionEvent(clientId: Long, displayId: Int, event: MotionEvent?) {
        if (event == null) return
        synchronized(lock) { ownedClient(clientId, displayId) ?: return }
        val clone = MotionEvent.obtain(event)
        try {
            asSystem {
                clone.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
                Instances.inputManager.injectInputEvent(clone, 0)
            }
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
            asSystem { Instances.inputManager.injectInputEvent(keyEvent, 0) }
        }
        inject(KeyEvent.ACTION_DOWN)
        inject(KeyEvent.ACTION_UP)
    }

    /**
     * Stage 3a: VirtualDisplay do SystemUI tạo (Surface thuộc SystemUI, tạo hộ từ
     * system_server sẽ bị "packageName must match the owner uid"). Backend chỉ xác thực
     * client + sự tồn tại của display tên "yamf..." rồi đăng ký quyền sở hữu.
     */
    private fun ownedClient(clientId: Long, displayId: Int): Client? {
        val client = clients[clientId]
        if (client == null) {
            Log.w(TAG, "unknown clientId=$clientId (displayId=$displayId)")
            return null
        }
        val display = runCatching { Instances.displayManager.getDisplay(displayId) }.getOrNull()
        if (display == null) {
            Log.w(TAG, "displayId=$displayId does not exist (clientId=$clientId)")
            return null
        }
        if (!display.name.orEmpty().startsWith("yamf")) {
            Log.w(TAG, "displayId=$displayId name=${display.name} is not a reYAMF display")
            return null
        }
        displayOwners[displayId] = clientId
        client.displays.add(displayId)
        return client
    }
}
