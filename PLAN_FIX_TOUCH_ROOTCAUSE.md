# PLAN_FIX_TOUCH_ROOTCAUSE — Sửa dứt điểm touch topHandle/bottomBar (+ hardening resize commit)

Trạng thái: **đã tìm ra lỗi gốc bằng bằng chứng runtime**, không phải giả thuyết. Kèm hardening cho race commit kích thước (mục 7 của nhật ký trước) và dọn toàn bộ instrumentation. Tài liệu này bổ sung cho `PLAN_FIX_2.md` (kế hoạch thu log của model senior), không thay thế nội dung file đó.

## 1. Lỗi gốc (đã chứng minh)

`AppWindow` khởi tạo listener trong `init { … doInit() }` (dòng 118–126), nhưng hai thuộc tính listener được **khai báo sau** khối `init`:

- `private val topHandleListener = object : View.OnTouchListener { … }` — dòng 460
- `private val bottomBarListener = object : View.OnTouchListener { … }` — dòng 503

Kotlin khởi tạo thuộc tính theo thứ tự khai báo trong file. Vì `init` đứng trước hai khai báo này, lúc `doInit()` chạy thì `topHandleListener` và `bottomBarListener` vẫn là `null`. Do đó hai dòng:

```kotlin
binding.topHandle.setOnTouchListener(topHandleListener)   // = null
binding.bottomBar.setOnTouchListener(bottomBarListener)   // = null
```

thực chất là `setOnTouchListener(null)` → view **không có listener**; chỉ còn `clickable=true` nên `View.onTouchEvent` tự trả `true`. Đó là lý do trace thấy `handled=true` mà `CONTROL_TOUCH` không hề xuất hiện.

Đối chứng khớp 100% với dữ liệu thu được:

| View | Cách tạo listener | Listener có hiệu lực? |
|---|---|---|
| `viewTexture` | property khai báo dòng 81–82 (trước `init`) | Có — `SURFACE_TOUCH node=TouchTraceTextureView action=0` xuất hiện |
| `resizeBottomLeft/Right` | `cornerResizeListener(isLeft = …)` là **hàm** (dòng 529), gọi trong `doInit()` | Có — kéo góc resize đổi kích thước cửa sổ |
| `topHandle` | property khai báo dòng 460 (sau `init`) | **Không** — chỉ có `TOUCH_TRACE … handled=true`, không có `CONTROL_TOUCH` |
| `bottomBar` | property khai báo dòng 503 (sau `init`) | **Không** — cùng hiện tượng |

Bằng chứng trace cho một cú chạm tâm 3 chấm (720,1099) trên APK 1748b92:

```
IN  node=TouchTraceCardView(id=root) action=0 local=490.0,42.0 ...
IN  node=TouchTraceFrameLayout(id=topHandle) action=0 local=112.0,42.0 bounds=378,0,602,84 clickable=true
IN  node=TouchTraceImageView(id=NO_ID) action=0 ... handled=false
OUT node=TouchTraceFrameLayout(id=topHandle) action=0 ... handled=true
OUT node=TouchTraceFrameLayout(id=contentFrame) action=0 ... handled=true
```

Dispatch tới đúng `topHandle`, trả handled=true, nhưng **không một dòng `CONTROL_TOUCH`** → OnTouchListener chưa từng được gọi → listener null. Không phải z-order, không phải TextureView, không phải window flags.

## 2. Fix A — khởi tạo listener an toàn thứ tự (bắt buộc)

Đổi hai property sang `by lazy` để chúng được tạo tại thời điểm `doInit()` gọi tới, không phụ thuộc thứ tự khai báo. Thay **nguyên khối** dòng 460–501 bằng:

```kotlin
    private val topHandleListener: View.OnTouchListener by lazy {
        object : View.OnTouchListener {
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
    }
```

Thay **nguyên khối** dòng 503–527 bằng:

```kotlin
    private val bottomBarListener: View.OnTouchListener by lazy {
        object : View.OnTouchListener {
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
    }
```

Không thay đổi thân xử lý, ngưỡng, hay thứ tự gọi trong `doInit()`. `broadcastReceiver`, `topHandleDetector`, `cornerResizeListener` giữ nguyên (detector chỉ dùng lúc touch nên thứ tự khai báo không ảnh hưởng).

Kiểm chứng tĩnh sau khi sửa: hai dòng `setOnTouchListener(topHandleListener)` / `(bottomBarListener)` vẫn nằm trong `doInit()`, và `by lazy` bảo đảm giá trị khác null.

## 3. Fix B — chặn commit kích thước cũ trong `commitVirtualDisplaySize()`

Hiện tượng `applied vd=830x1399` **không tái hiện** trên bản 1748b92: kéo góc cho ra commit đúng và khớp:

```
commit source=layout-listener req=882x1195 texture=882x1195 root=882x1251 params=882x1251 vd=980x1274
apply  req=882x1195 texture=882x1195 root=882x1251 params=882x1251 vd=980x1274 state=ready
resized req=882x1195 -> vd=882x1195 dpi=471
commit source=fallback-120ms req=882x1195 ... vd=882x1195
```

Nhưng code hiện tại vẫn có một race thật: `postDelayed(120ms)` không bị huỷ khi có lần commit mới, nên nếu người dùng bắt đầu kéo lần tiếp trong vòng 120ms, runnable cũ sẽ chạy giữa lúc kéo và commit **kích thước trung gian** (`texture.width/height` tại thời điểm đó) → đúng dạng số lẻ như 830×1399. Đây là hardening cho race đã thấy trong code, không phải kết luận về nguyên nhân của dòng log cũ.

Thêm hai field vào `AppWindow` (đặt cạnh các field trạng thái hiện có, ví dụ sau `private var pendingHeight = 0`):

```kotlin
    private var resizeCommitRunnable: Runnable? = null
    private var resizeGestureActive = false
```

Trong `cornerResizeListener`, thêm cờ:

- `ACTION_DOWN`: đặt `resizeGestureActive = true` trước `return true`.
- `ACTION_UP`: đặt `resizeGestureActive = false` **trước** dòng `commitVirtualDisplaySize()`.
- `ACTION_CANCEL`: đặt `resizeGestureActive = false` trước khi khôi phục params.

Thay nguyên hàm `commitVirtualDisplaySize()` (dòng 402–441) bằng:

```kotlin
    private fun commitVirtualDisplaySize() {
        val texture = binding.viewTexture
        resizeCommitRunnable?.let { texture.removeCallbacks(it) }
        resizeCommitRunnable = null

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
                if (resizeGestureActive) return
                texture.removeOnLayoutChangeListener(this)
                resizeCommitRunnable?.let { texture.removeCallbacks(it) }
                resizeCommitRunnable = null
                applyVirtualDisplaySize(right - left, bottom - top)
            }
        }

        val fallback = object : Runnable {
            override fun run() {
                texture.removeOnLayoutChangeListener(listener)
                resizeCommitRunnable = null
                if (!resizeGestureActive && texture.width > 0 && texture.height > 0) {
                    applyVirtualDisplaySize(texture.width, texture.height)
                }
            }
        }

        texture.addOnLayoutChangeListener(listener)
        texture.requestLayout()
        resizeCommitRunnable = fallback
        texture.postDelayed(fallback, RESIZE_COMMIT_FALLBACK_MS)
    }
```

Không đổi `RESIZE_COMMIT_FALLBACK_MS = 120L`, không đổi `applyVirtualDisplaySize()`, không gọi resize VirtualDisplay trong ACTION_MOVE.

## 4. Dọn instrumentation (bắt buộc, cùng lần sửa)

1. `window_app.xml`: trả toàn bộ class tag về chuẩn — root `com.google.android.material.card.MaterialCardView`, `windowColumn`/`topQuickMenu` = `LinearLayout`, `contentFrame`/`topHandle`/`bottomBar` = `FrameLayout`, `viewTexture` = `TextureView`, ImageView con = `ImageView`, pill + 2 resize handle = `View`. Giữ nguyên mọi ID, thuộc tính, kích thước, thứ tự child.
2. `AppWindow.kt`: xoá toàn bộ phần cuối file từ comment `// --- DIAGNOSTIC ONLY (PLAN_DIAG_2)` tới hết file, gồm `touchTraceName`, `traceDispatch`, và 6 class `TouchTrace*`.
3. Xoá log `SURFACE_TOUCH` trong `SurfaceOnTouchListener.onTouch` và mọi `Log.d("SIZE_COMMIT_TRACE", …)` trong `applyVirtualDisplaySize`, `commitVirtualDisplaySize`, `onSurfaceTextureAvailable`.
4. Giữ lại `CONTROL_TOUCH` và `SIZE_DEBUG` để nghiệm thu trên máy. Xoá `TOP_DEBUG` nếu còn.

## 5. Cấm

1. CẤM "sửa" bằng `bringToFront()` lần nữa — z-order không phải nguyên nhân; đã có đủ 5 lệnh trong `doInit()`.
2. CẤM đổi `clickable`, thêm `requestDisallowInterceptTouchEvent`, hay đổi cấu trúc `TextureView`/`bottomBar` — dispatch đã đúng.
3. CẤM chuyển sang `setOnClickListener` hoặc bỏ `GestureDetector` (phải giữ double-tap); cấm đổi ngưỡng `-60px / 80px / 15px / 2500ms`.
4. CẤM gọi `virtualDisplay.resize()`/`setDefaultBufferSize()` trong ACTION_MOVE.
5. CẤM đổi `Config.flags` khỏi 1164 hoặc đổi layout ngoài việc hoàn nguyên class tag ở mục 4.

## 6. Build & nghiệm thu

```bash
./gradlew assembleDebug
```

Trên máy (adb):

```bash
adb -s emulator-5554 uninstall com.mja.reyamf      # chữ ký CI khác bản cũ
adb -s emulator-5554 install app/build/outputs/apk/debug/app-debug.apk
# reboot đầy đủ để system_server nạp dex mới
adb logcat -c && adb logcat -v time -s CONTROL_TOUCH:V SIZE_DEBUG:V
```

Checklist:
- [ ] Chạm tâm 3 chấm → log `CONTROL_TOUCH topHandle action=0` xuất hiện, menu Fullscreen/Mini/Close hiện, tự ẩn sau 2.5s.
- [ ] Tap 1 lần trên 3 chấm không kéo cửa sổ; kéo 3 chấm di chuyển cửa sổ theo rawX/rawY.
- [ ] Double-tap 3 chấm → app ra display 0, cửa sổ đóng, không còn log sau đó.
- [ ] Chạm thanh pill đáy → `CONTROL_TOUCH bottomBar action=0`; tap < 15px gửi Back vào displayId (app bên trong lùi 1 bước).
- [ ] Vuốt lên > 60px đóng cửa sổ; vuốt xuống > 80px fullscreen; khoảng cách khác không gây action.
- [ ] Kéo 2 góc resize vẫn chạy; `onSurfaceTextureSizeChanged ... (no vd resize)` chỉ xuất hiện khi đang kéo.
- [ ] `grep -rn "TouchTrace\|SURFACE_TOUCH\|SIZE_COMMIT_TRACE\|TOUCH_TRACE" app/src` trả về rỗng.
- [ ] `window_app.xml` không còn class `com.mja.reyamf.xposed.ui.window.*`; vẫn đủ `topHandle` 64×24dp, `bottomBar` 16dp ngoài `contentFrame`, TextureView là child đầu tiên.
