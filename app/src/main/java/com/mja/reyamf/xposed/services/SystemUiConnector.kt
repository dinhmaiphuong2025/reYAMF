package com.mja.reyamf.xposed.services

import android.content.Context
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.mja.reyamf.R
import com.mja.reyamf.databinding.WindowAppBinding
import com.mja.reyamf.xposed.ISystemUiCallback
import com.mja.reyamf.xposed.IYAMFManager
import com.qauxv.ui.CommonContextWrapper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlin.math.roundToInt

/**
 * Connector chạy trong process com.android.systemui (PLAN_STAGE2_CONNECTOR).
 *
 * Không giữ View/thao tác UI trên Binder thread; không dùng Instances/YAMFManager/hidden API.
 * Chỉ: handshake provider -> registerSystemUiClient -> smoke inflate (Stage 2b).
 */
object SystemUiConnector {
    private const val TAG = "reYAMF_SystemUiConn"

    private const val AUTHORITY = "com.mja.reyamf.ServiceProvider"
    private const val METHOD_GET_BINDER = "GET_SYSTEM_SERVER_BINDER"
    private const val KEY_STATUS = "status"
    private const val KEY_BINDER = "binder"
    private const val STATUS_READY = "READY"
    private const val STATUS_NOT_READY = "NOT_READY"

    private const val BACKOFF_MIN_MS = 250L
    private const val BACKOFF_MAX_MS = 2000L

    private const val SMOKE_WIDTH_DP = 280
    private const val SMOKE_HEIGHT_DP = 380

    @Volatile private var appContext: Context? = null
    @Volatile private var generation = 0
    @Volatile private var clientId = -1L

    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var manager: IYAMFManager? = null
    private var binderRef: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.SystemUIApplication",
                lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Context
                        if (app == null) {
                            Log.e(TAG, "SystemUIApplication is not a Context")
                            return
                        }
                        start(app)
                    }
                }
            )
            Log.d(TAG, "hook installed")
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
        smokeInflate(gen)
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
            // Stage 3: tạo AppWindow trong SystemUI. Stage 2 chỉ log.
        }

        override fun onTaskStateChanged(displayId: Int, taskId: Int, visible: Boolean) {
            Log.d(TAG, "onTaskStateChanged display=$displayId task=$taskId visible=$visible")
        }

        override fun onTaskClosed(displayId: Int, taskId: Int) {
            Log.d(TAG, "onTaskClosed display=$displayId task=$taskId")
        }

        override fun onDisplayError(requestId: Long, displayId: Int, errorCode: Int, message: String?) {
            Log.w(TAG, "onDisplayError req=$requestId display=$displayId code=$errorCode msg=$message")
        }
    }

    /** Stage 2b: chỉ inflate + measure, KHÔNG addView (gate 0b đã chứng minh overlay). */
    private fun smokeInflate(gen: Int) {
        val ctx = appContext ?: return
        Handler(Looper.getMainLooper()).post {
            if (gen != generation || clientId <= 0) return@post
            runCatching {
                val dm = ctx.getSystemService(DisplayManager::class.java)
                    ?: error("no DisplayManager")
                val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
                    ?: error("no default display")
                val displayContext = ctx.createDisplayContext(display)
                val themeContext = CommonContextWrapper(displayContext, R.style.Base_Theme_Reyamf)

                val binding = WindowAppBinding.inflate(LayoutInflater.from(themeContext))
                val density = themeContext.resources.displayMetrics.density
                val w = (SMOKE_WIDTH_DP * density).roundToInt().coerceAtLeast(1)
                val h = (SMOKE_HEIGHT_DP * density).roundToInt().coerceAtLeast(1)

                val root = binding.root
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                )
                root.layout(0, 0, w, h)

                val wm = displayContext.getSystemService(WindowManager::class.java)
                Log.d(
                    TAG,
                    "inflate smoke PASS root=${root.javaClass.simpleName} ${root.width}x${root.height} " +
                        "contentFrame=${binding.contentFrame.width}x${binding.contentFrame.height} " +
                        "texture=${binding.viewTexture.width}x${binding.viewTexture.height} " +
                        "bottomBar=${binding.bottomBar.width}x${binding.bottomBar.height} " +
                        "wm=${wm?.javaClass?.name} density=$density"
                )
            }.onFailure { Log.e(TAG, "inflate smoke FAIL", it) }
        }
    }
}
