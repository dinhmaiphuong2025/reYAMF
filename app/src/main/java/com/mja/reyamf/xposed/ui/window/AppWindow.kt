package com.mja.reyamf.xposed.ui.window

import android.annotation.SuppressLint
import android.app.ActivityTaskManager
import android.app.ITaskStackListenerProxy
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.IRotationWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowManagerHidden
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.mja.reyamf.common.onException
import com.mja.reyamf.common.runMain
import com.mja.reyamf.databinding.WindowAppBinding
import com.mja.reyamf.xposed.services.YAMFManager
import com.mja.reyamf.xposed.services.YAMFManager.config
import com.mja.reyamf.xposed.utils.Instances
import com.mja.reyamf.xposed.utils.TipUtil
import com.mja.reyamf.xposed.utils.animateAlpha
import com.mja.reyamf.xposed.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class AppWindow(
    val context: Context,
    private val flags: Int,
    private val onVirtualDisplayCreated: (Int) -> Unit
) : TextureView.SurfaceTextureListener {

    companion object {
        const val TAG = "reYAMF_AppWindow"
        const val ACTION_RESET_ALL_WINDOW = "com.mja.reyamf.ui.window.action.ACTION_RESET_ALL_WINDOW"
        private const val QUICK_MENU_TIMEOUT = 2500L
        private const val BOTTOM_SWIPE_UP_CLOSE_PX = 60f
        private const val BOTTOM_SWIPE_DOWN_FULLSCREEN_PX = 80f
        private const val BOTTOM_TAP_BACK_PX = 15f
        private const val RESIZE_COMMIT_FALLBACK_MS = 120L
    }

    lateinit var binding: WindowAppBinding
    private var virtualDisplay: VirtualDisplay? = null
    private val taskStackListener =
        ITaskStackListenerProxy.newInstance(context.classLoader) { _, method ->
            when (method.name) {
                "onTaskRemovalStarted" -> onDestroy()
            }
        }
    private val rotationWatcher = RotationWatcher()
    private val surfaceOnTouchListener = SurfaceOnTouchListener()
    private val surfaceOnGenericMotionListener = SurfaceOnGenericMotionListener()

    var displayId = -1
        private set
    var rotateLock = false

    private var vdWidth = 0
    private var vdHeight = 0
    private var pendingWidth = 0
    private var pendingHeight = 0
    private var newDpi = 0
    private var orientation = 0
    private var params = WindowManager.LayoutParams()
    private var destroyed = false
    private var lifecycleRegistered = false
    private var createdCallbackSent = false

    private var state = WindowState.NORMAL
    private var quickMenuHideJob: Job? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minWidth = 120.ctxDp()
    private val minHeight = 160.ctxDp()

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_RESET_ALL_WINDOW) {
                val lp = currentParams()
                lp.apply {
                    x = 0
                    y = 0
                }
                Instances.windowManager.updateViewLayout(binding.root, lp)
            }
        }
    }

    init {
        runCatching {
            binding = WindowAppBinding.inflate(LayoutInflater.from(context))
        }.onException { e ->
            Log.e(TAG, "Failed to create new window, did you reboot?", e)
            TipUtil.showToast("Failed to create new window, did you reboot?")
        }.onSuccess {
            doInit()
        }
    }

    private fun doInit() {
        binding.viewTexture.visibility = View.VISIBLE
        binding.topQuickMenu.visibility = View.GONE

        val width = config.defaultWindowWidth.ctxDp()
        val height = config.defaultWindowHeight.ctxDp()

        params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        )

        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = display?.rotation ?: Surface.ROTATION_0
        orientation = when (rotation) {
            Surface.ROTATION_90, Surface.ROTATION_270 -> 1
            else -> 0
        }

        val metrics = context.resources.displayMetrics
        params.apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((metrics.widthPixels - width) / 2).coerceAtLeast(0)
            y = ((metrics.heightPixels - height) / 2).coerceAtLeast(0)
        }

        Instances.windowManager.addView(binding.root, params)

        binding.topHandle.setOnTouchListener(topHandleListener)
        binding.bottomBar.setOnTouchListener(bottomBarListener)
        binding.resizeBottomLeft.setOnTouchListener(cornerResizeListener(isLeft = true))
        binding.resizeBottomRight.setOnTouchListener(cornerResizeListener(isLeft = false))
        binding.topHandle.bringToFront()

        binding.btnFullscreen.setOnClickListener { transitionTo(WindowState.FULLSCREEN) }
        binding.btnMini.setOnClickListener {
            TipUtil.showToast("Mini mode is not implemented yet")
        }
        binding.btnClose.setOnClickListener { close() }

        binding.viewTexture.setOnTouchListener(surfaceOnTouchListener)
        binding.viewTexture.setOnGenericMotionListener(surfaceOnGenericMotionListener)
        binding.viewTexture.surfaceTextureListener = this

        context.registerReceiver(
            broadcastReceiver,
            IntentFilter(ACTION_RESET_ALL_WINDOW),
            Context.RECEIVER_EXPORTED
        )

        binding.root.post {
            animateAlpha(binding.root, 0f, 1f)
            Log.d("SIZE_DEBUG", "post: root w=${binding.root.width} h=${binding.root.height}")
            Log.d("SIZE_DEBUG", "post: topHandle w=${binding.topHandle.width} h=${binding.topHandle.height}")
            Log.d("SIZE_DEBUG", "post: bottomBar w=${binding.bottomBar.width} h=${binding.bottomBar.height}")
            Log.d("SIZE_DEBUG", "post: texture w=${binding.viewTexture.width} h=${binding.viewTexture.height}")
        }
    }

    // --- State machine ---

    private fun transitionTo(newState: WindowState) {
        if (destroyed || state == newState) return
        state = newState
        when (newState) {
            WindowState.NORMAL -> Unit
            WindowState.FULLSCREEN -> {
                val task = getTopRootTask()
                if (task == null) {
                    TipUtil.showToast("No task running in this window")
                    state = WindowState.NORMAL
                    return
                }
                runCatching {
                    Instances.activityTaskManager.moveRootTaskToDisplay(task.taskId, 0)
                }.onFailure { t ->
                    if (t is Error) throw t
                    TipUtil.showToast("${t.message}")
                }
                onDestroy()
            }
            WindowState.MINI, WindowState.EDGE_HIDE -> {
                // Phase 2
                log(TAG, "transition to $newState not implemented yet")
                state = WindowState.NORMAL
            }
        }
    }

    private fun close() {
        if (state == WindowState.FULLSCREEN) return
        onDestroy()
    }

    private fun onDestroy() {
        if (destroyed) return
        destroyed = true
        quickMenuHideJob?.cancel()
        quickMenuHideJob = null
        runCatching { context.unregisterReceiver(broadcastReceiver) }
        if (lifecycleRegistered) {
            runCatching { Instances.iWindowManager.removeRotationWatcher(rotationWatcher) }
            runCatching { Instances.activityTaskManager.unregisterTaskStackListener(taskStackListener) }
            lifecycleRegistered = false
        }
        if (displayId >= 0) {
            YAMFManager.removeWindow(displayId)
        }
        virtualDisplay?.let {
            runCatching { it.release() }
        }
        virtualDisplay = null
        runCatching { Instances.windowManager.removeView(binding.root) }
    }

    private fun getTopRootTask(): ActivityTaskManager.RootTaskInfo? {
        if (displayId < 0) return null
        Instances.activityTaskManager.getAllRootTaskInfosOnDisplay(displayId).forEach { task ->
            if (task.visible) return task
        }
        return null
    }

    private fun moveToTop() {
        Instances.windowManager.removeView(binding.root)
        Instances.windowManager.addView(binding.root, binding.root.layoutParams)
        YAMFManager.moveToTop(displayId)
    }

    private fun moveToTopIfNeed(event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_UP && !YAMFManager.isTop(displayId)) {
            moveToTop()
        }
    }

    private fun currentParams() = binding.root.layoutParams as WindowManager.LayoutParams

    // --- VirtualDisplay lifecycle ---

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val w = if (width > 0) width else binding.viewTexture.width
        val h = if (height > 0) height else binding.viewTexture.height
        if (w <= 0 || h <= 0) {
            Log.d("SIZE_DEBUG", "onSurfaceTextureAvailable skipped, invalid size ${width}x$height")
            return
        }
        Log.d("SIZE_DEBUG", "onSurfaceTextureAvailable callback=${width}x$height texture=${binding.viewTexture.width}x${binding.viewTexture.height}")
        Log.d("SIZE_DEBUG", "density context=${context.resources.displayMetrics.density} system=${Resources.getSystem().displayMetrics.density}")

        newDpi = calculateDpi(w, h, calculateScreenInches(w, h)) - config.reduceDPI
        surface.setDefaultBufferSize(w, h)

        val existing = virtualDisplay
        if (existing == null) {
            val vd = Instances.displayManager.createVirtualDisplay(
                "yamf${System.currentTimeMillis()}", w, h, newDpi, Surface(surface), flags
            )
            virtualDisplay = vd
            displayId = vd.display.displayId
            vdWidth = w
            vdHeight = h
            Log.d("SIZE_DEBUG", "created vd=${vd.display.width}x${vd.display.height} dpi=$newDpi flags=$flags")
            (Instances.windowManager as WindowManagerHidden).setDisplayImePolicy(
                displayId,
                if (config.showImeInWindow) WindowManagerHidden.DISPLAY_IME_POLICY_LOCAL
                else WindowManagerHidden.DISPLAY_IME_POLICY_FALLBACK_DISPLAY
            )
            registerDisplayLifecycle()
            if (!createdCallbackSent) {
                createdCallbackSent = true
                onVirtualDisplayCreated(displayId)
            }
        } else {
            existing.surface = Surface(surface)
            if (w != vdWidth || h != vdHeight) {
                existing.resize(w, h, newDpi)
                vdWidth = w
                vdHeight = h
            }
            Log.d("SIZE_DEBUG", "reattached vd=${existing.display.width}x${existing.display.height} dpi=$newDpi")
            registerDisplayLifecycle()
        }

        if (pendingWidth > 0 && pendingHeight > 0) {
            val pw = pendingWidth
            val ph = pendingHeight
            pendingWidth = 0
            pendingHeight = 0
            applyVirtualDisplaySize(pw, ph)
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        // Intentionally empty: VirtualDisplay resize is committed only on resize ACTION_UP.
        Log.d("SIZE_DEBUG", "onSurfaceTextureSizeChanged ${width}x$height (no vd resize)")
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        virtualDisplay?.surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    private fun registerDisplayLifecycle() {
        if (lifecycleRegistered) return
        lifecycleRegistered = true
        Instances.activityTaskManager.registerTaskStackListener(taskStackListener)
        var failCount = 0
        fun watchRotation() {
            runCatching {
                Instances.iWindowManager.watchRotation(rotationWatcher, displayId)
            }.onFailure {
                failCount++
                Log.d(TAG, "watchRotation: fail $failCount")
                if (failCount < 20) watchRotation()
            }
        }
        watchRotation()
    }

    private fun applyVirtualDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || destroyed) return
        val vd = virtualDisplay
        val texture = binding.viewTexture.surfaceTexture
        if (vd == null || texture == null) {
            pendingWidth = width
            pendingHeight = height
            return
        }
        newDpi = calculateDpi(width, height, calculateScreenInches(width, height)) - config.reduceDPI
        texture.setDefaultBufferSize(width, height)
        if (width == vdWidth && height == vdHeight) return
        vd.resize(width, height, newDpi)
        vdWidth = width
        vdHeight = height
        Log.d("SIZE_DEBUG", "applied vd=${vd.display.width}x${vd.display.height} dpi=$newDpi")
    }

    private fun commitVirtualDisplaySize() {
        val texture = binding.viewTexture
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int
            ) {
                if (right - left <= 0 || bottom - top <= 0) return
                texture.removeOnLayoutChangeListener(this)
                applyVirtualDisplaySize(right - left, bottom - top)
            }
        }
        texture.addOnLayoutChangeListener(listener)
        texture.requestLayout()
        texture.postDelayed({
            texture.removeOnLayoutChangeListener(listener)
            if (texture.width > 0 && texture.height > 0) {
                applyVirtualDisplaySize(texture.width, texture.height)
            }
        }, RESIZE_COMMIT_FALLBACK_MS)
    }

    // --- Gestures ---

    private val topHandleDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            showQuickMenu()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            hideQuickMenu()
            transitionTo(WindowState.FULLSCREEN)
            return true
        }
    })

    private val topHandleListener = object : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startWindowX = 0
        private var startWindowY = 0
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            topHandleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val lp = currentParams()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startWindowX = lp.x
                    startWindowY = lp.y
                    dragging = false
                    Log.d("TOP_DEBUG", "topHandle DOWN raw=${event.rawX},${event.rawY} w=${v.width} h=${v.height}")
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                    if (dragging) {
                        val lp = currentParams()
                        lp.x = startWindowX + dx.toInt()
                        lp.y = startWindowY + dy.toInt()
                        Instances.windowManager.updateViewLayout(binding.root, lp)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    moveToTopIfNeed(event)
                    return true
                }
            }
            return false
        }
    }

    private val bottomBarListener = object : View.OnTouchListener {
        private var downRawY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = event.rawY
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val dy = event.rawY - downRawY
                    when {
                        dy < -BOTTOM_SWIPE_UP_CLOSE_PX -> close()
                        dy > BOTTOM_SWIPE_DOWN_FULLSCREEN_PX -> transitionTo(WindowState.FULLSCREEN)
                        abs(dy) < BOTTOM_TAP_BACK_PX -> sendBackKey()
                    }
                    if (!destroyed) moveToTopIfNeed(event)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return true
        }
    }

    private fun cornerResizeListener(isLeft: Boolean) = object : View.OnTouchListener {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startWidth = 0
        private var startHeight = 0
        private var startWindowX = 0
        private var targetWidth = 0
        private var targetHeight = 0
        private var targetWindowX = 0

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val lp = currentParams()
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startWidth = if (lp.width > 0) lp.width else binding.viewTexture.width
                    startHeight = if (lp.height > 0) lp.height else binding.viewTexture.height
                    startWindowX = lp.x
                    targetWidth = startWidth
                    targetHeight = startHeight
                    targetWindowX = startWindowX
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startRawX).toInt()
                    val dy = (event.rawY - startRawY).toInt()
                    val rawWidth = if (isLeft) startWidth - dx else startWidth + dx
                    val rawHeight = startHeight + dy
                    targetWidth = rawWidth.coerceAtLeast(minWidth)
                    targetHeight = rawHeight.coerceAtLeast(minHeight)
                    targetWindowX = if (isLeft) startWindowX + startWidth - targetWidth else startWindowX
                    val lp = currentParams()
                    lp.width = targetWidth
                    lp.height = targetHeight
                    if (isLeft) lp.x = targetWindowX
                    Instances.windowManager.updateViewLayout(binding.root, lp)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val lp = currentParams()
                    lp.width = targetWidth
                    lp.height = targetHeight
                    if (isLeft) lp.x = targetWindowX
                    Instances.windowManager.updateViewLayout(binding.root, lp)
                    commitVirtualDisplaySize()
                    moveToTopIfNeed(event)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    val lp = currentParams()
                    lp.width = startWidth
                    lp.height = startHeight
                    lp.x = startWindowX
                    Instances.windowManager.updateViewLayout(binding.root, lp)
                    return true
                }
            }
            return false
        }
    }

    private fun showQuickMenu() {
        binding.topQuickMenu.visibility = View.VISIBLE
        quickMenuHideJob?.cancel()
        quickMenuHideJob = CoroutineScope(Dispatchers.Main).launch {
            delay(QUICK_MENU_TIMEOUT)
            hideQuickMenu()
        }
    }

    private fun hideQuickMenu() {
        quickMenuHideJob?.cancel()
        quickMenuHideJob = null
        binding.topQuickMenu.visibility = View.GONE
    }

    private fun sendBackKey() {
        if (displayId < 0) return
        fun inject(action: Int) {
            val event = KeyEvent(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                action,
                KeyEvent.KEYCODE_BACK,
                0
            )
            event.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
            Instances.inputManager.injectInputEvent(event, 0)
        }
        inject(KeyEvent.ACTION_DOWN)
        inject(KeyEvent.ACTION_UP)
    }

    fun forwardMotionEvent(event: MotionEvent) {
        if (displayId < 0) return
        val newEvent = MotionEvent.obtain(event)
        newEvent.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
        Instances.inputManager.injectInputEvent(newEvent, 0)
        newEvent.recycle()
    }

    inner class SurfaceOnTouchListener : View.OnTouchListener {
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                hideQuickMenu()
            }
            forwardMotionEvent(event)
            moveToTopIfNeed(event)
            return true
        }
    }

    inner class SurfaceOnGenericMotionListener : View.OnGenericMotionListener {
        override fun onGenericMotion(v: View, event: MotionEvent): Boolean {
            forwardMotionEvent(event)
            return true
        }
    }

    inner class RotationWatcher : IRotationWatcher.Stub() {
        override fun onRotationChanged(rotation: Int) {
            runMain {
                if (!rotateLock) rotate(rotation)
            }
        }
    }

    fun rotate(rotation: Int) {
        // Phase 1: rotation must not resize the window/surface outside resize ACTION_UP.
        Log.d("SIZE_DEBUG", "rotation changed to $rotation (ignored in phase 1)")
    }

    private fun Int.ctxDp(): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), context.resources.displayMetrics).toInt()

    private fun calculateScreenInches(width: Int, height: Int): Float {
        val x = (width / context.resources.displayMetrics.xdpi).pow(2)
        val y = (height / context.resources.displayMetrics.ydpi).pow(2)
        return sqrt(x + y)
    }

    private fun calculateDpi(width: Int, height: Int, screenSizeInInches: Float): Int {
        val widthSqr = width.toFloat().pow(2)
        val heightSqr = height.toFloat().pow(2)
        val diagonalPixels = sqrt(widthSqr + heightSqr)
        return floor(diagonalPixels / screenSizeInInches).toInt()
    }
}
