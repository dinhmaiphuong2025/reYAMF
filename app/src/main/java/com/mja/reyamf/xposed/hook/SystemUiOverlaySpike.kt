package com.mja.reyamf.xposed.hook

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * TEMPORARY SPIKE (PLAN_SPLIT_SYSTEMUI bước 0b) — xóa sau khi có kết quả.
 *
 * Trả lời đúng một câu hỏi: process com.android.systemui có add/remove được
 * TYPE_APPLICATION_OVERLAY bằng WindowManager lấy từ display-aware context của chính nó không,
 * với đúng bộ flags Phase 1 đang dùng?
 *
 * Không ném exception KHÔNG được coi là pass: phải thấy attached=true và shown=true trong log.
 */
object SystemUiOverlaySpike {
    private const val TAG = "SPIKEOVERLAY"

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.SystemUIApplication",
                lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Context ?: return
                        runSpike(app)
                    }
                }
            )
            Log.d(TAG, "hook installed uid=${Process.myUid()} pid=${Process.myPid()}")
        }.onFailure {
            Log.e(TAG, "hook install FAILED: ${it.javaClass.name}: ${it.message}", it)
        }
    }

    private fun runSpike(app: Context) {
        Handler(Looper.getMainLooper()).post {
            var view: View? = null
            var wm: WindowManager? = null
            try {
                val dm = app.getSystemService(DisplayManager::class.java)
                val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
                val displayContext = if (display != null) app.createDisplayContext(display) else app
                wm = displayContext.getSystemService(WindowManager::class.java)

                val v = View(displayContext)
                v.setBackgroundColor(Color.argb(160, 255, 0, 0))
                val lp = WindowManager.LayoutParams(
                    300,
                    200,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 40
                    y = 240
                }

                wm.addView(v, lp)
                view = v
                Log.d(TAG, "addView returned, uid=${Process.myUid()} pid=${Process.myPid()} wm=$wm")

                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching {
                        Log.d(
                            TAG,
                            "RESULT attached=${v.isAttachedToWindow} shown=${v.isShown} " +
                                "w=${v.width} h=${v.height} visibleRect=${v.getGlobalVisibleRect(android.graphics.Rect())}"
                        )
                    }.onFailure { Log.e(TAG, "state read failed", it) }
                    runCatching { wm?.removeView(v) }
                        .onFailure { Log.e(TAG, "removeView failed", it) }
                        .onSuccess { Log.d(TAG, "removeView done") }
                }, 1500)
            } catch (t: Throwable) {
                Log.e(TAG, "addView FAILED: ${t.javaClass.name}: ${t.message}", t)
                runCatching { view?.let { wm?.removeView(it) } }
            }
        }
    }
}
