# PLAN_DIAG_2 — Soi event dispatch nội bộ (chưa có lỗi tĩnh xác định)

Phạm vi: chỉ lập instrumentation chẩn đoán. Không đổi hành vi, ID, bounds/layout, listener, z-order, window flags, thresholds hoặc VirtualDisplay. Bao gồm ghi nguồn/kích thước của mọi lần commit resize để tìm anomaly `830x1399`.

## 1. Kết quả đối chiếu chính xác source hiện tại

### AppWindow.kt

- `init` inflate `WindowAppBinding`, gọi `doInit()` khi thành công: dòng 117–125.
- `doInit()` add root ở dòng 162; ngay sau đó gắn listeners: topHandle dòng 164, bottomBar 165, resizeBottomLeft 166, resizeBottomRight 167. Search toàn repo không thấy nơi khác ghi đè listener của topHandle/bottomBar.
- `topHandleListener` (dòng 438–479) gọi `topHandleDetector.onTouchEvent(event)`; listener ACTION_DOWN trả `true` (449–457). Detector `onDown()` dòng 424 trả `true`.
- `bottomBarListener` (dòng 481–505) ACTION_DOWN trả `true` (484–490); mọi action còn lại cũng trả true.
- `cornerResizeListener` được gắn trong cùng `doInit()`; listener resize phải được giữ nguyên để làm đối chứng.
- `SurfaceOnTouchListener` (609–618) luôn gọi `forwardMotionEvent` rồi trả `true`, nhưng hiện không log riêng. Chỉ thêm `SURFACE_TOUCH`, không đổi body.
- Không có touch listener/intercept trên `root`, `windowColumn`, `contentFrame`; `doInit()` không đặt `OnItemTouchListener` hay touch delegate lên chúng.

### window_app.xml

- Root là `MaterialCardView` `root`; child duy nhất là `LinearLayout` `windowColumn` (vertical).
- `windowColumn` children theo thứ tự: `contentFrame`, rồi `bottomBar`.
- `contentFrame` children: `viewTexture`, `topHandle`, `topQuickMenu`, `resizeBottomLeft`, `resizeBottomRight`.
- `topHandle` clickable=true. `ImageView` con không khai báo clickable/focusable, không listener.
- `bottomBar` clickable=true; pill `View` con không clickable, không listener.
- `resizeBottomRight` clickable=true; cùng cha `contentFrame` với topHandle. Vì resize hoạt động trong khi topHandle không hoạt động, khác biệt hiện cần phân biệt bằng dispatch trace, không phải suy đoán.
- BottomBar nằm ngoài contentFrame và ngoài TextureView; TextureView không thể giải thích riêng lỗi bottomBar.
- Z-order đã được gọi rõ ràng trong AppWindow.kt:168–172 cho topHandle, topQuickMenu, hai resize handle, bottomBar. Không thêm bringToFront lần nữa trong bản diagnostic.
- Bounds/shown/enabled/clickable/global visible rect và InputDispatcher window đã được xác nhận trong `reYAMF_TOUCH_FINDINGS.txt`; view listeners không log cho thao tác chạm đã thử. Đây là bằng chứng không đủ để quy lỗi cho một child cụ thể.

Kết luận: source tĩnh không có điểm khác biệt đủ để giải thích tại sao resizeBottomRight nhận DOWN còn topHandle và bottomBar thì không. Chọn `PLAN_DIAG_2`, không tạo fix phỏng đoán.

## 2. Trace dispatch tạm thời — phải log cả IN và OUT

Chỉ thêm các lớp instrumentation dưới đây vào `AppWindow.kt` (cùng package). Thêm import `android.util.AttributeSet`. Giữ nguyên constructor 2 tham số mà XML inflation dùng.

```kotlin
private fun touchTraceName(view: View): String {
    val idName = if (view.id == View.NO_ID) "NO_ID" else runCatching {
        view.resources.getResourceEntryName(view.id)
    }.getOrDefault(view.id.toString())
    return "${view.javaClass.simpleName}(id=$idName)"
}

private fun traceDispatch(view: View, event: MotionEvent, phase: String, handled: Boolean? = null) {
    Log.d(
        "TOUCH_TRACE",
        "$phase node=${touchTraceName(view)} action=${event.actionMasked} " +
            "local=${event.x},${event.y} raw=${event.rawX},${event.rawY} " +
            "bounds=${view.left},${view.top},${view.right},${view.bottom} " +
            "shown=${view.isShown} clickable=${view.isClickable}" +
            (handled?.let { " handled=$it" } ?: "")
    )
}

class TouchTraceCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle
) : com.google.android.material.card.MaterialCardView(context, attrs, defStyleAttr) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        traceDispatch(this, event, "IN")
        val handled = super.dispatchTouchEvent(event)
        traceDispatch(this, event, "OUT", handled)
        return handled
    }
}

class TouchTraceLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : android.widget.LinearLayout(context, attrs) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        traceDispatch(this, event, "IN")
        val handled = super.dispatchTouchEvent(event)
        traceDispatch(this, event, "OUT", handled)
        return handled
    }
}

class TouchTraceFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : android.widget.FrameLayout(context, attrs) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        traceDispatch(this, event, "IN")
        val handled = super.dispatchTouchEvent(event)
        traceDispatch(this, event, "OUT", handled)
        return handled
    }
}

class TouchTraceTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        traceDispatch(this, event, "IN")
        val handled = super.dispatchTouchEvent(event)
        traceDispatch(this, event, "OUT", handled)
        return handled
    }
}

class TouchTraceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        traceDispatch(this, event, "IN")
        val handled = super.dispatchTouchEvent(event)
        traceDispatch(this, event, "OUT", handled)
        return handled
    }
}

class TouchTraceImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : android.widget.ImageView(context, attrs) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        traceDispatch(this, event, "IN")
        val handled = super.dispatchTouchEvent(event)
        traceDispatch(this, event, "OUT", handled)
        return handled
    }
}
```

Nếu custom MaterialCardView constructor báo compile error, đọc đúng lỗi và API của Material version có trong `app/build.gradle.kts`; không phỏng đoán style attribute. Không thêm subclass ngoài các nodes liệt kê.

Trong `SurfaceOnTouchListener.onTouch()` (609–618), thêm đầu hàm:

```kotlin
Log.d(
    "SURFACE_TOUCH",
    "node=${touchTraceName(v)} action=${event.actionMasked} " +
        "local=${event.x},${event.y} raw=${event.rawX},${event.rawY}"
)
```

Không đổi phần forward event hoặc return value.

## 3. XML: chỉ thay class tag, bảo toàn toàn bộ hierarchy

Trong `app/src/main/res/layout/window_app.xml`, giữ nguyên mọi ID, thuộc tính, kích thước, thứ tự child và cấu trúc. Chỉ đổi class:

- Root `MaterialCardView` → `com.mja.reyamf.xposed.ui.window.TouchTraceCardView`.
- `LinearLayout` id `windowColumn` → `TouchTraceLinearLayout`.
- `FrameLayout` id `contentFrame`, `topHandle`, `bottomBar` → `TouchTraceFrameLayout`.
- `TextureView` id `viewTexture` → `TouchTraceTextureView`.
- `ImageView` trong topHandle → `TouchTraceImageView`.
- Pill View trong bottomBar và hai resize handle View → `TouchTraceView`.
- Không đổi `topQuickMenu` hoặc ba TextView button.

Subclass-имя XML phải FQCN: `com.mja.reyamf.xposed.ui.window.TouchTrace...`; nested name/ID không đổi. Không thêm listener lên cha vì listener cha có thể làm thay đổi dispatch và che mất consumer thật.

Mỗi subclass ghi IN → gọi `super.dispatchTouchEvent(event)` → ghi OUT handled=... → trả đúng kết quả super. Trace này cho biết child được hit-test/gọi tới và liệu nhánh có xử lý DOWN. `SURFACE_TOUCH` xác định listener TextureView.

## 4. Trace kích thước VD commit (log-only)

Không thay đổi `commitVirtualDisplaySize()` hoặc `applyVirtualDisplaySize()` behavior.

Trong `commitVirtualDisplaySize()` (`AppWindow.kt:392–419`), trước `applyVirtualDisplaySize(...)` ở cả OnLayoutChangeListener và fallback postDelayed, thêm `Log.d("SIZE_COMMIT_TRACE", ...)` có nhãn nguồn `layout-listener`/`fallback-120ms` và in:
- size sắp truyền (`right-left × bottom-top` hoặc `texture.width × texture.height`);
- `binding.viewTexture.width/height`, `binding.root.width/height`;
- `currentParams().width/height`, `vdWidth/vdHeight`, `pendingWidth/pendingHeight`, `displayId`.

Trong `applyVirtualDisplaySize(width,height)` (`AppWindow.kt:374–390`), log ở đầu: requested size, TextureView actual size, root size, current params, vdWidth/vdHeight, pending size, displayId. Sau `vd.resize()` log requested và giá trị `vdWidth/vdHeight` được lưu.

Không debounce, không cancel callback, không generation guard, không đổi thời gian fallback 120ms trong bản chẩn đoán.

## 5. Chạy đúng một vòng chẩn đoán

Build ở repo root:

```bash
./gradlew assembleDebug
```

Trên APK diagnostic, mở đúng một cửa sổ. Trước mỗi thao tác riêng biệt:

```bash
adb logcat -c
adb logcat -b all -v time -s TOUCH_TRACE:V SURFACE_TOUCH:V CONTROL_TOUCH:V SIZE_COMMIT_TRACE:V SIZE_DEBUG:V
```

Thử lần lượt: (a) tap tâm topHandle, (b) tap tâm bottomBar, (c) tap vùng texture, (d) resize một góc rồi nhả. Lưu toàn bộ logs cho từng thao tác.

Đọc kết quả:
- Root IN và leaf/branch traces: theo thứ tự IN/OUT, xác định node sâu nhất được dispatch và node trả handled=true.
- Có TextureView IN + SURFACE_TOUCH ở đúng tọa độ topHandle: TextureView thật sự nhận event; mới lập sửa z-order/hit region có căn cứ.
- Child ImageView/pill View được dispatch và trả handled=true: child nhận event; đối chiếu `isClickable`/listener thực tế trước khi sửa.
- Control node được dispatch nhưng `CONTROL_TOUCH` không ghi: kiểm tra trace OUT, listener gắn đúng instance hay không và view/class binding; không thay return true.
- Root không có IN: đối chiếu frame/toạ độ/display cùng thời điểm; không dựa vào dumpsys snapshot cũ.
- Với resize: mỗi commit phải có nhãn nguồn và requested/current dimensions. So với params và TextureView tại chính thời điểm callback; chỉ kết luận callback stale/race nếu log chứng minh requested size không còn khớp. Không sửa anomaly bằng suy đoán.

## 6. Cấm và hoàn tất

- CẤM sửa clickability, return true, GestureDetector, listener, bounds, order, z/elevation, flags hoặc cử chỉ trong bản diagnostic.
- CẤM chỉ log root: phải trace từng custom node được yêu cầu và SURFACE_TOUCH.
- CẤM đổi resize behavior/fallback trước khi có SIZE_COMMIT_TRACE.
- Build đạt khi XML inflate và `assembleDebug` thành công. Chẩn đoán đạt khi mỗi lần DOWN có trace IN/OUT đủ để xác định node được hit/handled, và resize commit có source + requested/current sizes.
- Sau khi chốt nguyên nhân, plan sửa tiếp theo phải xóa toàn bộ TouchTrace classes, XML custom tags, SURFACE_TOUCH/TOUCH_TRACE/SIZE_COMMIT_TRACE instrumentation.
