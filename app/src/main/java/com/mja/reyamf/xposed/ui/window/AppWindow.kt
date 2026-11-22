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
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowManagerHidden
import androidx.core.view.updateLayoutParams
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
import com.mja.reyamf.xposed.utils.dpToPx
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
) : TextureView.SurfaceTextureListener, SurfaceHolder.Callback {

    companion object {
        const val TAG = "reYAMF_AppWindow"
        const val ACTION_RESET_ALL_WINDOW = "com.mja.reyamf.ui.window.action.ACTION_RESET_ALL_WINDOW"
        private const val DOUBLE_TAP_DELTA = 300L
        private const val FLING_THRESHOLD = 1000f
    }

    lateinit var binding: WindowAppBinding
    private lateinit var virtualDisplay: VirtualDisplay
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
    var rotateLock = false
    private var halfWidth = 0
    private var halfHeight = 0
    lateinit var surfaceView: View
    private var newDpi = calculateDpi(
        config.defaultWindowWidth, config.defaultWindowHeight,
        calculateScreenInches(config.defaultWindowWidth, config.defaultWindowHeight)
    ) - config.reduceDPI
    private var originalWidth: Int = 0
    private var originalHeight: Int = 0
    private var isResize: Boolean = true
    private var orientation = 0
    private var params = WindowManager.LayoutParams()

    private var state = WindowState.NORMAL
    private var quickToolbarHideJob: Job? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minWidth = 120.dpToPx().toInt()
    private val minHeight = 160.dpToPx().toInt()

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_RESET_ALL_WINDOW) {
                val lp = binding.root.layoutParams as WindowManager.LayoutParams
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
        when (config.surfaceView) {
            0 -> {
                surfaceView = binding.viewSurface
                binding.viewTexture.visibility = View.GONE
            }
            else -> {
                surfaceView = binding.viewTexture
                binding.viewSurface.visibility = View.GONE
            }
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
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

        params.apply {
            if (orientation == 0) {
                gravity = Gravity.CENTER
                y = -80.dpToPx().toInt()
            } else {
                gravity = Gravity.TOP or Gravity.START
                y = 0
            }
            x = 0
        }

        binding.root.let { layout ->
            Instances.windowManager.addView(layout, params)
        }

        binding.topBar.setOnTouchListener(topBarListener)
        binding.bottomBarTouch.setOnTouchListener { _, event -> bottomBarDetector.onTouchEvent(event) }
        binding.bottomLeftResize.setOnTouchListener(cornerResizeListener(isLeft = true))
        binding.bottomRightResize.setOnTouchListener(cornerResizeListener(isLeft = false))
        binding.topBar.bringToFront()

        binding.btnFullscreen.setOnClickListener { transitionTo(WindowState.FULLSCREEN) }
        binding.btnEdgeHide.setOnClickListener { transitionTo(WindowState.EDGE_HIDE) }
        binding.btnClose.setOnClickListener { close() }

        surfaceView.setOnTouchListener(surfaceOnTouchListener)
        surfaceView.setOnGenericMotionListener(surfaceOnGenericMotionListener)

        virtualDisplay = Instances.displayManager.createVirtualDisplay(
            "yamf${System.currentTimeMillis()}", config.defaultWindowWidth.ctxDp(), config.defaultWindowHeight.ctxDp(), newDpi - config.reduceDPI, null, flags
        )
        displayId = virtualDisplay.display.displayId
        (Instances.windowManager as WindowManagerHidden).setDisplayImePolicy(displayId, if (config.showImeInWindow) WindowManagerHidden.DISPLAY_IME_POLICY_LOCAL else WindowManagerHidden.DISPLAY_IME_POLICY_FALLBACK_DISPLAY)
        Instances.activityTaskManager.registerTaskStackListener(taskStackListener)
        (surfaceView as? TextureView)?.surfaceTextureListener = this
        (surfaceView as? SurfaceView)?.holder?.addCallback(this)

        var failCount = 0
        fun watchRotation() {
            runCatching {
                Instances.iWindowManager.watchRotation(rotationWatcher, displayId)
            }.onFailure {
                failCount++
                Log.d(TAG, "watchRotation: fail $failCount")
                watchRotation()
            }
        }
        watchRotation()
        context.registerReceiver(broadcastReceiver, IntentFilter(ACTION_RESET_ALL_WINDOW), Context.RECEIVER_EXPORTED)

        val width = config.defaultWindowWidth.ctxDp()
        val height = config.defaultWindowHeight.ctxDp()
        surfaceView.updateLayoutParams {
            this.width = width
            this.height = height
        }
        onVirtualDisplayCreated(displayId)

        isResize = false
        binding.cvBackground.post {
            originalWidth = binding.cvBackground.width
            originalHeight = binding.cvBackground.height
            binding.cvBackground.radius = config.windowRoundedCorner.dpToPx()
            binding.cvBackground.visibility = View.VISIBLE
            animateAlpha(binding.root, 0f, 1f)
            isResize = true
            Log.d("TOP_DEBUG", "post: topBar w=${binding.topBar.width} h=${binding.topBar.height} l=${binding.topBar.left} t=${binding.topBar.top} r=${binding.topBar.right} b=${binding.topBar.bottom}")
            Log.d("TOP_DEBUG", "post: bottomBar w=${binding.bottomBarTouch.width} h=${binding.bottomBarTouch.height} t=${binding.bottomBarTouch.top} b=${binding.bottomBarTouch.bottom}")
            Log.d("TOP_DEBUG", "post: surface w=${surfaceView.width} h=${surfaceView.height} card w=${binding.cvBackground.width} h=${binding.cvBackground.height}")
        }
    }

    // --- State machine ---

    private fun transitionTo(newState: WindowState) {
        if (state == newState) return
        state = newState
        when (newState) {
            WindowState.NORMAL -> Unit
            WindowState.FULLSCREEN -> {
                getTopRootTask()?.runCatching {
                    Instances.activityTaskManager.moveRootTaskToDisplay(taskId, 0)
                }?.onFailure { t ->
                    if (t is Error) throw t
                    TipUtil.showToast("${t.message}")
                }
                onDestroy()
            }
            WindowState.MINI, WindowState.EDGE_HIDE -> {
                // Phase 2
                log(TAG, "transition to $newState not implemented yet")
            }
        }
    }

    private fun close() {
        if (state == WindowState.FULLSCREEN) return
        onDestroy()
    }

    private fun onDestroy() {
        context.unregisterReceiver(broadcastReceiver)
        Instances.iWindowManager.removeRotationWatcher(rotationWatcher)
        Instances.activityTaskManager.unregisterTaskStackListener(taskStackListener)
        YAMFManager.removeWindow(displayId)
        virtualDisplay.release()
        Instances.windowManager.removeView(binding.root)
    }

    private fun getTopRootTask(): ActivityTaskManager.RootTaskInfo? {
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

    // --- Gestures ---

    private val topBarListener = object : View.OnTouchListener {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var lastTapTime = 0L

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN) {
                Log.d("TOP_DEBUG", "topBar.onTouch DOWN x=${event.x} y=${event.y} rawX=${event.rawX} rawY=${event.rawY} w=${v.width} h=${v.height} l=${v.left} t=${v.top} r=${v.right} b=${v.bottom}")
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val lp = binding.root.layoutParams as WindowManager.LayoutParams
                    startX = lp.x
                    startY = lp.y
                    startRawX = event.rawX
                    startRawY = event.rawY
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                    if (dragging) {
                        val lp = binding.root.layoutParams as WindowManager.LayoutParams
                        lp.x = (startX + dx).toInt()
                        lp.y = (startY + dy).toInt()
                        Instances.windowManager.updateViewLayout(binding.root, lp)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < DOUBLE_TAP_DELTA) {
                            lastTapTime = 0
                            transitionTo(WindowState.FULLSCREEN)
                        } else {
                            lastTapTime = now
                            toggleQuickToolbar()
                        }
                    }
                    moveToTopIfNeed(event)
                    return true
                }
            }
            return false
        }
    }

    private val bottomBarDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            sendBackKey()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (velocityY < -FLING_THRESHOLD) {
                close()
            } else if (velocityY > FLING_THRESHOLD) {
                transitionTo(WindowState.FULLSCREEN)
            }
            return true
        }
    })

    private fun cornerResizeListener(isLeft: Boolean) = object : View.OnTouchListener {
        private var beginX = 0f
        private var beginY = 0f
        private var beginWidth = 0
        private var beginHeight = 0
        private var targetWidth = 0
        private var targetHeight = 0

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    beginX = event.rawX
                    beginY = event.rawY
                    beginWidth = surfaceView.width
                    beginHeight = surfaceView.height
                    targetWidth = beginWidth
                    targetHeight = beginHeight
                    isResize = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - beginX).toInt()
                    val dy = (event.rawY - beginY).toInt()
                    targetWidth = if (isLeft) beginWidth - dx else beginWidth + dx
                    targetHeight = beginHeight + dy
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    isResize = true
                    surfaceView.updateLayoutParams {
                        if (targetWidth > minWidth) width = targetWidth
                        if (targetHeight > minHeight) height = targetHeight
                    }
                    moveToTopIfNeed(event)
                    return true
                }
            }
            return false
        }
    }

    private fun toggleQuickToolbar() {
        if (binding.quickToolbar.visibility == View.VISIBLE) {
            hideQuickToolbar()
        } else {
            binding.quickToolbar.visibility = View.VISIBLE
            quickToolbarHideJob?.cancel()
            quickToolbarHideJob = CoroutineScope(Dispatchers.Main).launch {
                delay(2000)
                hideQuickToolbar()
            }
        }
    }

    private fun hideQuickToolbar() {
        binding.quickToolbar.visibility = View.GONE
    }

    private fun sendBackKey() {
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
        val newEvent = MotionEvent.obtain(event)
        newEvent.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
        Instances.inputManager.injectInputEvent(newEvent, 0)
        newEvent.recycle()
    }

    inner class SurfaceOnTouchListener : View.OnTouchListener {
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN) {
                Log.d("TOP_DEBUG", "surface.onTouch DOWN x=${event.x} y=${event.y} v=${v.javaClass.simpleName}")
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

    // --- TextureView.SurfaceTextureListener ---

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        Log.d("SIZE_DEBUG", "onSurfaceTextureAvailable callback=${width}x$height textureView=${binding.viewTexture.width}x${binding.viewTexture.height} card=${binding.cvBackground.width}x${binding.cvBackground.height}")
        Log.d("SIZE_DEBUG", "density context=${context.resources.displayMetrics.density} system=${Resources.getSystem().displayMetrics.density}")
        newDpi = calculateDpi(width, height, calculateScreenInches(width, height)) - config.reduceDPI
        virtualDisplay.resize(width, height, newDpi)
        surface.setDefaultBufferSize(width, height)
        Log.d("SIZE_DEBUG", "after resize: vd=${virtualDisplay.display.width}x${virtualDisplay.display.height} dpi=${newDpi}")
        halfWidth = width % 2
        halfHeight = height % 2
        virtualDisplay.surface = Surface(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (isResize) {
            newDpi = calculateDpi(width, height, calculateScreenInches(width, height)) - config.reduceDPI
            virtualDisplay.resize(width, height, newDpi)
            surface.setDefaultBufferSize(width, height)
            halfWidth = width % 2
            halfHeight = height % 2
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    // --- SurfaceHolder.Callback ---

    override fun surfaceCreated(holder: SurfaceHolder) {
        virtualDisplay.surface = holder.surface
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        newDpi = calculateDpi(width, height, calculateScreenInches(width, height)) - config.reduceDPI
        virtualDisplay.resize(width, height, newDpi)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        virtualDisplay.surface = null
    }

    inner class RotationWatcher : IRotationWatcher.Stub() {
        override fun onRotationChanged(rotation: Int) {
            runMain {
                if (!rotateLock) rotate(rotation)
            }
        }
    }

    fun rotate(rotation: Int) {
        if (rotation == 1 || rotation == 3) {
            val t = halfHeight
            halfHeight = halfWidth
            halfWidth = t
            val surfaceWidth = surfaceView.width
            val surfaceHeight = surfaceView.height
            surfaceView.updateLayoutParams {
                width = surfaceHeight
                height = surfaceWidth
            }
        }
    }

    private fun Int.ctxDp() =
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
