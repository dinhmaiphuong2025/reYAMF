package com.mja.reyamf.xposed.services

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import com.mja.reyamf.xposed.ISystemUiCallback
import com.mja.reyamf.xposed.IYAMFManager
import com.mja.reyamf.xposed.systemui.SystemUiAppWindow
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.ConcurrentHashMap

/**
 * Connector chạy trong process com.android.systemui.
 *
 * Stage 2: handshake provider -> registerSystemUiClient.
 * Stage 3a: tạo cửa sổ thật (SystemUiAppWindow) trong SystemUI qua client AIDL.
 */
object SystemUiConnector {
    private const val TAG = "reYAMF_SystemUiConn"

    private const val AUTHORITY = "com.mja.reyamf.ServiceProvider"
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val METHOD_GET_BINDER = "GET_SYSTEM_SERVER_BINDER"
    private const val KEY_STATUS = "status"
    private const val KEY_BINDER = "binder"
    private const val STATUS_READY = "READY"
    private const val STATUS_NOT_READY = "NOT_READY"

    private const val BACKOFF_MIN_MS = 250L
    private const val BACKOFF_MAX_MS = 2000L

    private const val DEBUG_CREATE_WINDOW = "com.mja.reyamf.DEBUG_CREATE_WINDOW"

    @Volatile private var appContext: Context? = null
    @Volatile private var generation = 0
    @Volatile private var clientId = -1L

    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var manager: IYAMFManager? = null
    private var binderRef: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null
    private var debugTriggerInstalled = false

    private val windows = ConcurrentHashMap<Int, SystemUiAppWindow>()
    private val taskByDisplay = ConcurrentHashMap<Int, Int>()

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            // Không hook theo tên class SystemUIApplication: trên ROM này
            // XposedHelpers.findClass không resolve được tên đó (ClassNotFound).
            // Application.onCreate không đủ vì subclass có thể không gọi super,
            // nên hook entry point của framework: Instrumentation.callApplicationOnCreate
            // và Application.attach(Context). start() idempotent nên hook trùng vô hại.
            val hook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.args.getOrNull(0) as? Context ?: return
                    if (app.packageName != SYSTEM_UI_PACKAGE) return
                    start(app)
                }
            }
            XposedHelpers.findAndHookMethod(
                "android.app.Instrumentation",
                lpparam.classLoader,
                "callApplicationOnCreate",
                android.app.Application::class.java,
                hook
            )
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "attach",
                Context::class.java,
                hook
            )
            Log.d(TAG, "hook installed (callApplicationOnCreate + Application.attach)")
        }.onFailure { Log.e(TAG, "hook install failed", it) }
    }

    @Synchronized
    private fun start(app: Context) {
        if (appContext != null) return
        appContext = app
        val thread = HandlerThread("reYAMF-connector").apply { start() }
        worker = thread
        handler = Handler(thread.looper)
        Log.d(TAG, "handshake start")
        connect(generation, attempt = 0)
    }

    private fun connect(gen: Int, attempt: Int) {
        val ctx = appContext ?: return
        if (gen != generation) return

        val reply: Bundle? = runCatching {
            ctx.contentResolver.call(
                Uri.parse("content://$AUTHORITY"),
                METHOD_GET_BINDER,
                null,
                Bundle()
            )
        }.getOrNull()

        when (val status = reply?.getString(KEY_STATUS)) {
            STATUS_READY -> {
                val binder = reply.getBinder(KEY_BINDER)
                if (binder == null) {
                    Log.w(TAG, "READY without binder, retry")
                    scheduleRetry(gen, attempt)
                } else {
                    Log.d(TAG, "binder received")
                    register(gen, binder)
                }
            }
            STATUS_NOT_READY -> {
                Log.d(TAG, "provider not ready (attempt=$attempt)")
                scheduleRetry(gen, attempt)
            }
            else -> {
                // DENIED hoặc reply hỏng: lỗi quyền/contract, không retry loop.
                Log.e(TAG, "permission denied or malformed reply: status=$status")
            }
        }
    }

    private fun scheduleRetry(gen: Int, attempt: Int) {
        if (gen != generation) return
        val delay = (BACKOFF_MIN_MS shl attempt.coerceAtMost(4)).coerceAtMost(BACKOFF_MAX_MS)
        handler?.postDelayed({ connect(gen, attempt + 1) }, delay)
    }

    private fun register(gen: Int, binder: IBinder) {
        if (gen != generation) return
        val iface = IYAMFManager.Stub.asInterface(binder)
        val death = IBinder.DeathRecipient { onBinderDied(gen) }
        val linked = runCatching { binder.linkToDeath(death, 0) }.isSuccess
        if (!linked) {
            Log.e(TAG, "linkToDeath failed")
            return
        }
        val id = runCatching { iface.registerSystemUiClient(callback) }.getOrElse { t ->
            Log.e(TAG, "register failed", t)
            runCatching { binder.unlinkToDeath(death, 0) }
            return
        }
        if (id <= 0) {
            Log.e(TAG, "register returned invalid clientId=$id")
            runCatching { binder.unlinkToDeath(death, 0) }
            return
        }
        manager = iface
        binderRef = binder
        deathRecipient = death
        clientId = id
        Log.d(TAG, "client registered clientId=$id")
        installDebugTrigger(appContext)
    }

    private fun onBinderDied(gen: Int) {
        handler?.post {
            if (gen != generation) return@post
            Log.w(TAG, "binder died")
            manager = null
            binderRef = null
            deathRecipient = null
            clientId = -1L
            generation++
            Log.d(TAG, "reconnect scheduled")
            connect(generation, attempt = 0)
        }
    }

    private val callback = object : ISystemUiCallback.Stub() {
        override fun onCreateWindow(requestId: Long, componentName: String?, userId: Int, taskId: Int) {
            Log.d(TAG, "onCreateWindow req=$requestId comp=$componentName user=$userId task=$taskId")
            Handler(Looper.getMainLooper()).post {
                createWindowOnMain(componentName, userId, taskId)
            }
        }

        override fun onTaskStateChanged(displayId: Int, taskId: Int, visible: Boolean) {
            if (visible && taskId > 0) taskByDisplay[displayId] = taskId
            Log.d(TAG, "onTaskStateChanged display=$displayId task=$taskId visible=$visible")
        }

        override fun onTaskClosed(displayId: Int, taskId: Int) {
            Log.d(TAG, "onTaskClosed display=$displayId task=$taskId")
            Handler(Looper.getMainLooper()).post {
                windows.remove(displayId)?.onDestroy()
            }
        }

        override fun onDisplayError(requestId: Long, displayId: Int, errorCode: Int, message: String?) {
            Log.w(TAG, "onDisplayError req=$requestId display=$displayId code=$errorCode msg=$message")
        }
    }

    /** Stage 3a: tạo cửa sổ thật trong SystemUI. Chỉ gọi trên main thread. */
    private fun createWindowOnMain(componentName: String?, userId: Int, taskId: Int) {
        val ctx = appContext ?: return
        val iface = manager ?: return
        val id = clientId
        if (id <= 0) return
        if (windows.isNotEmpty()) {
            Log.d(TAG, "createWindow ignored: ${windows.size} window(s) already open")
            return
        }
        runCatching {
            val dm = ctx.getSystemService(DisplayManager::class.java) ?: error("no DisplayManager")
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: error("no default display")
            val displayContext = ctx.createDisplayContext(display)
            lateinit var window: SystemUiAppWindow
            window = SystemUiAppWindow(
                context = displayContext,
                client = iface,
                clientId = id,
                flags = 1164,
                taskIdProvider = { taskByDisplay[window.displayId] ?: taskId },
                onClosed = { d -> windows.remove(d) }
            )
            // Surface của TextureView đến bất đồng bộ: displayId chỉ hợp lệ trong callback này.
            window.setup { d ->
                windows[d] = window
                Log.d(TAG, "window ready displayId=$d taskId=$taskId")
                if (taskId > 0) {
                    runCatching {
                        iface.launchStartCommand(id, d, componentName ?: "", userId, taskId)
                    }.onFailure { Log.e(TAG, "launchStartCommand failed displayId=$d", it) }
                }
            }
        }.onFailure { Log.e(TAG, "createWindow failed", it) }
    }

    /** Stage 3a: trigger test qua broadcast, sẽ xoá ở stage 3b. */
    private fun installDebugTrigger(ctx: Context?) {
        if (ctx == null || debugTriggerInstalled) return
        debugTriggerInstalled = true
        runCatching {
            ctx.registerReceiver(
                object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        val taskId = i.getIntExtra("taskId", 0)
                        val comp = i.getStringExtra("component")
                        Log.d(TAG, "DEBUG_CREATE_WINDOW taskId=$taskId comp=$comp")
                        Handler(Looper.getMainLooper()).post { createWindowOnMain(comp, 0, taskId) }
                    }
                },
                android.content.IntentFilter(DEBUG_CREATE_WINDOW),
                Context.RECEIVER_EXPORTED
            )
            Log.d(TAG, "debug trigger installed")
        }.onFailure { Log.w(TAG, "debug trigger install failed", it) }
    }
}
