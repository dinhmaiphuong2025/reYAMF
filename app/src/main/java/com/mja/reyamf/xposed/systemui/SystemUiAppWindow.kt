package com.mja.reyamf.xposed.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.mja.reyamf.common.gson
import com.mja.reyamf.common.model.Config
import com.mja.reyamf.common.onException
import com.mja.reyamf.databinding.WindowAppBinding
import com.mja.reyamf.xposed.IYAMFManager
import com.mja.reyamf.xposed.ui.window.WindowState
import com.mja.reyamf.xposed.utils.animateAlpha
import com.qauxv.ui.CommonContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Cửa sổ chạy trong process com.android.systemui (PLAN_STAGE3A).
 *
 * Khác AppWindow cũ: không dùng Instances/YAMFManager/hidden API. Mọi thao tác
 * display/surface/task/input đi qua IYAMFManager (client AIDL của SystemUI).
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class SystemUiAppWindow(
    val context: Context,
    private val client: IYAMFManager,
    private val clientId: Long,
    private val flags: Int,
    private val taskIdProvider: () -> Int,
    private val onClosed: (displayId: Int) -> Unit
) : TextureView.SurfaceTextureListener {

    companion object {
        const val TAG = "reYAMF_SystemUiAppWindow"
        private const val QUICK_MENU_TIMEOUT = 2500L
        private const val BOTTOM_SWIPE_UP_CLOSE_PX = 60f
        private const val BOTTOM_SWIPE_DOWN_FULLSCREEN_PX = 80f
        private const val BOTTOM_TAP_BACK_PX = 15f
        private const val RESIZE_COMMIT_FALLBACK_MS = 120L
    }

    lateinit var binding: WindowAppBinding
    private val wm = context.getSystemService(WindowManager::class.java)
    private lateinit var config: Config
    private var virtualDisplay: VirtualDisplay? = null

    var displayId = -1
        private set
    var rotateLock = false

    private var vdWidth = 0
    private var vdHeight = 0
    private var pendingWidth = 0
    private var pendingHeight = 0
    private var resizeCommitRunnable: Runnable? = null
    private var resizeGestureActive = false
    private var newDpi = 0
    private var orientation = 0
    private var params = WindowManager.LayoutParams()
    private var destroyed = false
    private var createdCallbackSent = false
    private var onDisplayCreated: ((Int) -> Unit)? = null

    private var state = WindowState.NORMAL
    private var quickMenuHideJob: Job? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minWidth = 120.ctxDp()
    private val minHeight = 160.ctxDp()

    private val surfaceOnTouchListener = SurfaceOnTouchListener()
    private val surfaceOnGenericMotionListener = SurfaceOnGenericMotionListener()

    fun setup(onDisplayCreated: (Int) -> Unit = {}) {
        this.onDisplayCreated = onDisplayCreated
        runCatching {
            binding = WindowAppBinding.inflate(LayoutInflater.from(themedContext()))
        }.onException { e ->
            Log.e(TAG, "inflate window_app failed", e)
        }.onSuccess {
            doInit()
        }
    }

    /** Context có Resources của module (tránh trùng package id 0x7f với SystemUI). */
    private fun themedContext(): Context {
        val moduleContext = context.createPackageContext(
            com.mja.reyamf.BuildConfig.APPLICATION_ID,
            Context.CONTEXT_IGNORE_SECURITY
        )
        return CommonContextWrapper(moduleContext, com.mja.reyamf.R.style.Base_Theme_Reyamf)
    }

    private fun loadConfig() {
        config = runCatching {
            gson.fromJson(client.getConfigJson(), Config::class.java)
        }.getOrNull() ?: run {
            Log.w(TAG, "CONFIG_FAIL: fallback to defaults")
            Config()
        }
    }

    private fun doInit() {
        loadConfig()
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

        wm.addView(binding.root, params)

        binding.topHandle.setOnTouchListener(createTopHandleListener())
        binding.bottomBar.setOnTouchListener(createBottomBarListener())
        binding.resizeBottomLeft.setOnTouchListener(cornerResizeListener(isLeft = true))
        binding.resizeBottomRight.setOnTouchListener(cornerResizeListener(isLeft = false))
        binding.topHandle.bringToFront()
        binding.topQuickMenu.bringToFront()
        binding.resizeBottomLeft.bringToFront()
        binding.resizeBottomRight.bringToFront()
        binding.bottomBar.bringToFront()

        binding.btnFullscreen.setOnClickListener { transitionTo(WindowState.FULLSCREEN) }
        binding.btnMini.setOnClickListener {
            Log.d(TAG, "mini mode is not implemented yet")
        }
        binding.btnClose.setOnClickListener { close() }

        binding.viewTexture.setOnTouchListener(surfaceOnTouchListener)
        binding.viewTexture.setOnGenericMotionListener(surfaceOnGenericMotionListener)
        binding.viewTexture.surfaceTextureListener = this

        binding.root.post {
            animateAlpha(binding.root, 0f, 1f)
            Log.d("SIZE_DEBUG", "post: root w=${binding.root.width} h=${binding.root.height}")
            Log.d("SIZE_DEBUG", "post: topHandle w=${binding.topHandle.width} h=${binding.topHandle.height} l=${binding.topHandle.left} t=${binding.topHandle.top} r=${binding.topHandle.right} b=${binding.topHandle.bottom}")
            Log.d("SIZE_DEBUG", "post: bottomBar w=${binding.bottomBar.width} h=${binding.bottomBar.height} l=${binding.bottomBar.left} t=${binding.bottomBar.top} r=${binding.bottomBar.right} b=${binding.bottomBar.bottom}")
            Log.d("SIZE_DEBUG", "post: texture w=${binding.viewTexture.width} h=${binding.viewTexture.height}")
            Log.d("SIZE_DEBUG", "post: resizeBR l=${binding.resizeBottomRight.left} t=${binding.resizeBottomRight.top} r=${binding.resizeBottomRight.right} b=${binding.resizeBottomRight.bottom}")
            Log.d("SIZE_DEBUG", "post: resizeBL l=${binding.resizeBottomLeft.left} t=${binding.resizeBottomLeft.top} r=${binding.resizeBottomLeft.right} b=${binding.resizeBottomLeft.bottom}")
            val lp = currentParams()
            Log.d(
                "SIZE_DEBUG",
                "post: params x=${lp.x} y=${lp.y} w=${lp.width} h=${lp.height} gravity=${lp.gravity} flags=${lp.flags} attached=${binding.root.isAttachedToWindow}"
            )
            fun viewState(v: View): String {
                val r = Rect()
                val visible = v.getGlobalVisibleRect(r)
                return "shown=${v.isShown} enabled=${v.isEnabled} clickable=${v.isClickable} visibleRect=$visible:$r"
            }
            Log.d("CONTROL_TOUCH", "post: topHandle ${viewState(binding.topHandle)}")
            Log.d("CONTROL_TOUCH", "post: bottomBar ${viewState(binding.bottomBar)}")
        }
    }

    // --- State machine ---

    private fun transitionTo(newState: WindowState) {
        if (destroyed || state == newState) return
        state = newState
        when (newState) {
            WindowState.NORMAL -> Unit
            WindowState.FULLSCREEN -> {
                val taskId = taskIdProvider()
                if (displayId < 0) {
                    Log.w(TAG, "fullscreen: no display taskId=$taskId displayId=$displayId")
                    state = WindowState.NORMAL
                    return
                }
                // taskId <= 0: backend tự lấy task đang hiển thị trên display này.
                runCatching { client.moveTaskToDefaultDisplay(clientId, taskId) }
                    .onFailure { Log.e(TAG, "moveTaskToDefaultDisplay failed taskId=$taskId", it) }
                onDestroy()
            }
            WindowState.MINI, WindowState.EDGE_HIDE -> {
                Log.d(TAG, "transition to $newState not implemented yet")
                state = WindowState.NORMAL
            }
        }
    }

    private fun close() {
        if (state == WindowState.FULLSCREEN) return
        onDestroy()
    }

    fun onDestroy() {
        if (destroyed) return
        destroyed = true
        cancelPendingResizeCommit()
        quickMenuHideJob?.cancel()
        quickMenuHideJob = null
        if (displayId >= 0) {
            runCatching { virtualDisplay?.release() }
            virtualDisplay = null
            runCatching { client.releaseWindow(clientId, displayId) }
            onClosed(displayId)
        }
        runCatching { wm.removeView(binding.root) }
    }

    private fun moveToTop() {
        runCatching {
            wm.removeView(binding.root)
            wm.addView(binding.root, binding.root.layoutParams)
        }.onFailure { Log.w(TAG, "moveToTop failed", it) }
    }

    private fun moveToTopIfNeed(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_UP) moveToTop()
    }

    private fun currentParams() = binding.root.layoutParams as WindowManager.LayoutParams

    // --- VirtualDisplay lifecycle (qua AIDL) ---

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (destroyed) return
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

        if (displayId < 0) {
            // Phải tạo VirtualDisplay TẠI SystemUI: Surface thuộc uid này, tạo hộ từ
            // system_server sẽ bị SecurityException "packageName must match the owner uid".
            // SECURE (1<<2) yêu cầu CAPTURE_SECURE_VIDEO_OUTPUT — chỉ system_server có.
            // Tạo trong SystemUI phải bỏ bit này, giữ nguyên các bit còn lại của config.flags.
            val vdFlags = flags and android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE.inv()
            val vd = runCatching {
                val dm = context.getSystemService(DisplayManager::class.java)
                    ?: error("no DisplayManager")
                dm.createVirtualDisplay(
                    "yamf${System.currentTimeMillis()}",
                    w,
                    h,
                    newDpi,
                    Surface(surface),
                    vdFlags
                )
            }.getOrElse { t ->
                Log.e(TAG, "createVirtualDisplay threw", t)
                null
            }
            if (vd == null) {
                Log.e(TAG, "createVirtualDisplay failed ${w}x$h dpi=$newDpi")
                return
            }
            virtualDisplay = vd
            displayId = vd.display.displayId
            vdWidth = w
            vdHeight = h
            Log.d("SIZE_DEBUG", "created vd=${vd.display.width}x${vd.display.height} dpi=$newDpi flags=$vdFlags (cfg=$flags) displayId=$displayId")
            val policy = if (config.showImeInWindow) 1 else 0
            runCatching { client.setDisplayImePolicy(clientId, displayId, policy) }
                .onFailure { Log.w(TAG, "setDisplayImePolicy failed displayId=$displayId", it) }
            if (!createdCallbackSent) {
                createdCallbackSent = true
                onDisplayCreated?.invoke(displayId)
            }
        } else {
            runCatching { virtualDisplay?.surface = Surface(surface) }
                .onFailure { Log.e(TAG, "reattach surface failed displayId=$displayId", it) }
            if (w != vdWidth || h != vdHeight) {
                runCatching { virtualDisplay?.resize(w, h, newDpi) }
                    .onFailure { Log.e(TAG, "resizeVirtualDisplay failed displayId=$displayId", it) }
                vdWidth = w
                vdHeight = h
            }
            Log.d("SIZE_DEBUG", "reattached vd=${w}x$h dpi=$newDpi displayId=$displayId")
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
        runCatching { virtualDisplay?.surface = null }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    private fun applyVirtualDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || destroyed || displayId < 0) return
        val texture = binding.viewTexture.surfaceTexture
        if (texture == null) {
            pendingWidth = width
            pendingHeight = height
            return
        }
        newDpi = calculateDpi(width, height, calculateScreenInches(width, height)) - config.reduceDPI
        texture.setDefaultBufferSize(width, height)
        if (width == vdWidth && height == vdHeight) return
        runCatching { virtualDisplay?.resize(width, height, newDpi) }
            .onFailure { Log.e(TAG, "resizeVirtualDisplay failed displayId=$displayId", it) }
        vdWidth = width
        vdHeight = height
        Log.d("SIZE_DEBUG", "applied vd=${width}x$height dpi=$newDpi displayId=$displayId")
    }

    private fun cancelPendingResizeCommit() {
        resizeCommitRunnable?.let { binding.viewTexture.removeCallbacks(it) }
        resizeCommitRunnable = null
    }

    private fun commitVirtualDisplaySize() {
        cancelPendingResizeCommit()
        val texture = binding.viewTexture
        val commit = object : Runnable {
            override fun run() {
                if (resizeCommitRunnable !== this) return
                resizeCommitRunnable = null
                Log.d("CONTROL_TOUCH", "resize commitRun destroyed=$destroyed active=$resizeGestureActive texture=${texture.width}x${texture.height}")
                if (destroyed || resizeGestureActive || texture.width <= 0 || texture.height <= 0) return
                applyVirtualDisplaySize(texture.width, texture.height)
            }
        }
        resizeCommitRunnable = commit
        texture.postDelayed(commit, RESIZE_COMMIT_FALLBACK_MS)
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

    private fun createTopHandleListener(): View.OnTouchListener = object : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startWindowX = 0
        private var startWindowY = 0
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            Log.d("CONTROL_TOUCH", "topHandle action=${event.actionMasked} x=${event.x} y=${event.y} rawX=${event.rawX} rawY=${event.rawY}")
            topHandleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val lp = currentParams()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startWindowX = lp.x
                    startWindowY = lp.y
                    dragging = false
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
                        wm.updateViewLayout(binding.root, lp)
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

    private fun createBottomBarListener(): View.OnTouchListener = object : View.OnTouchListener {
        private var downRawY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            Log.d("CONTROL_TOUCH", "bottomBar action=${event.actionMasked} x=${event.x} y=${event.y} rawX=${event.rawX} rawY=${event.rawY}")
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
            Log.d("CONTROL_TOUCH", "resize(isLeft=$isLeft) action=${event.actionMasked} rawX=${event.rawX} rawY=${event.rawY}")
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
                    resizeGestureActive = true
                    cancelPendingResizeCommit()
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
                    wm.updateViewLayout(binding.root, lp)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val lp = currentParams()
                    lp.width = targetWidth
                    lp.height = targetHeight
                    if (isLeft) lp.x = targetWindowX
                    wm.updateViewLayout(binding.root, lp)
                    Log.d("CONTROL_TOUCH", "resize commit lp=${lp.width}x${lp.height} root=${binding.root.width}x${binding.root.height} texture=${binding.viewTexture.width}x${binding.viewTexture.height}")
                    resizeGestureActive = false
                    commitVirtualDisplaySize()
                    moveToTopIfNeed(event)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    val lp = currentParams()
                    lp.width = startWidth
                    lp.height = startHeight
                    lp.x = startWindowX
                    wm.updateViewLayout(binding.root, lp)
                    resizeGestureActive = false
                    commitVirtualDisplaySize()
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
        runCatching { client.injectBackKey(clientId, displayId) }
            .onFailure { Log.e(TAG, "injectBackKey failed displayId=$displayId", it) }
    }

    fun forwardMotionEvent(event: MotionEvent) {
        if (displayId < 0) return
        runCatching { client.injectMotionEvent(clientId, displayId, event) }
            .onFailure { Log.e(TAG, "injectMotionEvent failed displayId=$displayId", it) }
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
