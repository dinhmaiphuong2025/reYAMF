# PLAN — PHASE 1: NORMAL + FULLSCREEN

Phạm vi: triển khai lại cửa sổ nổi cho hai trạng thái `NORMAL` và `FULLSCREEN`. Không triển khai hành vi `MINI`, `EDGE_HIDE` hoặc Sidebar trong phase này. Đây là tài liệu thực thi; không thay đổi các file nguồn khi chỉ đang lập kế hoạch.

## 1. Hiện trạng & Phạm vi dọn dẹp

### File và điểm đang gây xung đột

1. `app/src/main/java/com/mja/reyamf/xposed/ui/window/AppWindow.kt`
   - `doInit()` (khoảng dòng 128–229): khởi tạo `WindowManager.LayoutParams` bằng `WRAP_CONTENT`; layout mới yêu cầu kích thước pixel xác định. Khởi tạo VirtualDisplay hiện dùng `config.defaultWindowWidth.ctxDp()` / `defaultWindowHeight.ctxDp()` thay vì kích thước TextureView đã đo thực tế. Dời khởi tạo VirtualDisplay đến sau khi TextureView được đo; tạo và resize từ đúng `binding.viewTexture.width` × `binding.viewTexture.height`.
   - Nhánh `config.surfaceView`, `surfaceView`, `SurfaceHolder.Callback`, `surfaceCreated()`, `surfaceChanged()`, `surfaceDestroyed()`: phase này chỉ dùng TextureView. Bỏ nhánh SurfaceView và các callback/import chỉ phục vụ SurfaceView trong AppWindow; không để binding hoặc listener cũ tham chiếu view đã bỏ khỏi layout. Không sửa Config/Setting để làm việc đó.
   - `topBarListener`, `binding.topBar`, `toggleQuickToolbar()`, `hideQuickToolbar()`, `quickToolbarHideJob`: thay listener/UI cũ bằng `topHandle` + `topQuickMenu`; timeout phải là 2500 ms. Không giữ listener cũ song song.
   - `bottomBarDetector` / `GestureDetector`: thay bằng listener đo `dy` từ `ACTION_DOWN` đến `ACTION_UP`; fling theo vận tốc hiện tại không đáp ứng ngưỡng khoảng cách bắt buộc.
   - `cornerResizeListener()`: hiện chỉ đổi kích thước view khi ACTION_UP, không cập nhật `WindowManager.LayoutParams` trong ACTION_MOVE. Thay bằng resize cửa sổ qua LayoutParams; không resize VirtualDisplay trong ACTION_MOVE.
   - `onSurfaceTextureSizeChanged()`: hiện gọi `virtualDisplay.resize()` và `setDefaultBufferSize()` mỗi lần kích thước đổi. Bỏ resize ở callback này; đây là đường gọi gây resize khi layout đang được kéo. Việc resize cuối chỉ được kích hoạt từ ACTION_UP của listener góc resize.
   - `onSurfaceTextureAvailable()`: giữ vai trò gắn SurfaceTexture và khởi tạo VirtualDisplay lần đầu, nhưng dùng đúng kích thước callback/TextureView thực tế; không tạo display với kích thước cấu hình dp trước khi đo view.
   - `rotate()`: hiện đổi kích thước view trực tiếp và có thể kích hoạt resize ngoài thao tác ACTION_UP. Không dùng logic đổi kích thước này trong phase 1. Không xóa các phần lifecycle/task listener không liên quan nếu vẫn còn được dùng.
   - `transitionTo()`, `close()`, `onDestroy()`, `getTopRootTask()`, `moveToTopIfNeed()`, `sendBackKey()`, `forwardMotionEvent()`: giữ và nối lại với UI mới, chỉ sửa khi cần để bảo đảm đúng hành vi được mô tả ở mục 3.
2. `app/src/main/res/layout/window_app.xml`
   - Layout hiện là `ConstraintLayout` + `LinearLayout` ba hàng, top bar 40dp, content bao `wrap_content`, toolbar cũ, vùng resize 44dp và bottom touch bar 32dp. Thay toàn bộ layout bằng mẫu ở mục 2. Không giữ các ID cũ `topBar`, `quickToolbar`, `bottomLeftResize`, `bottomRightResize`, `bottomBarTouch`.
3. `app/src/main/java/com/mja/reyamf/xposed/ui/window/WindowState.kt`
   - Enum đã có `NORMAL`, `FULLSCREEN`, `MINI`, `EDGE_HIDE`. Giữ enum và thứ tự/giá trị tên hiện tại để tránh thay đổi ngoài phạm vi; phase này chỉ hiện thực NORMAL/FULLSCREEN. MINI chỉ hiển thị toast stub; EDGE_HIDE không được gắn vào nút nào.
4. Không xóa/xây lại các lớp dùng chung ngoài phạm vi. `Instances` ở `app/src/main/java/com/mja/reyamf/xposed/utils/Instances.kt` cung cấp `windowManager`, `displayManager`, `inputManager`, `activityTaskManager`, `iWindowManager`; `YAMFManager.createWindow()` khởi tạo AppWindow và chờ callback `onVirtualDisplayCreated(displayId)` trước khi bắt đầu app. Giữ hợp đồng callback đó.

### Các lõi VirtualDisplay/Input bắt buộc giữ

- `AppWindow.doInit()` tiếp tục tạo overlay, đăng ký task/rotation/lifecycle phù hợp và gắn listener, nhưng không được gọi `createVirtualDisplay()` bằng kích thước cấu hình chưa đo.
- Giữ `Instances.displayManager.createVirtualDisplay(...)` với `flags` truyền vào AppWindow. `app/src/main/java/com/mja/reyamf/common/model/Config.kt` mặc định `flags = 1164`; không đổi flags. Kiểm tra giá trị cấu hình đang chạy cũng là 1164, vì `YAMFManager.updateConfig()` nạp cấu hình lưu trước đó và có thể ghi đè giá trị mặc định.
- Khởi tạo và mọi resize dùng cùng một nguồn pixel: kích thước đo được của `binding.viewTexture`. Khi SurfaceTexture sẵn sàng, gọi `surfaceTexture.setDefaultBufferSize(width, height)` và gắn `Surface(surfaceTexture)` vào VirtualDisplay. Khi resize hoàn tất, tiếp tục gọi `virtualDisplay.resize(width, height, dpi)` và `setDefaultBufferSize(width, height)` đúng một lần cho kích thước cuối.
- Giữ touch forwarding: `forwardMotionEvent(event)` đặt displayId qua `invokeMethod("setDisplayId", ...)`, gọi `Instances.inputManager.injectInputEvent(newEvent, 0)` rồi recycle event. Giữ `sendBackKey()` tạo ACTION_DOWN/ACTION_UP của `KeyEvent.KEYCODE_BACK`, đặt displayId rồi inject bằng `Instances.inputManager.injectInputEvent(event, 0)`.
- Sửa lỗi DPI hiện hữu khi nối lại: `newDpi` hiện bị trừ `config.reduceDPI` lúc tính và lại bị trừ lần nữa khi `createVirtualDisplay()`. Tính DPI từ đúng width/height thực tế rồi trừ `reduceDPI` đúng một lần; cùng giá trị đó truyền vào create/resize.

## 2. Resource & Layout hoàn chỉnh

Thay toàn bộ `app/src/main/res/layout/window_app.xml` bằng XML sau. Dự án đã có Material dependency; `MaterialCardView` là CardView của Material, với FrameLayout nội dung bên trong. Thứ tự các child trong FrameLayout là bắt buộc: TextureView trước, toàn bộ view điều khiển sau.

`app/src/main/res/layout/window_app.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<com.google.android.material.card.MaterialCardView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:clipChildren="true"
    android:clipToPadding="true"
    android:elevation="8dp"
    app:cardBackgroundColor="?attr/colorSurface"
    app:cardCornerRadius="18dp"
    app:cardElevation="0dp"
    app:strokeWidth="0dp">

    <FrameLayout
        android:id="@+id/contentFrame"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:clipChildren="true"
        android:clipToPadding="true">

        <!-- BOTTOMMOST CHILD: full-bleed VirtualDisplay surface. -->
        <TextureView
            android:id="@+id/viewTexture"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:visibility="visible" />

        <!-- All touch controls MUST remain after TextureView in child order. -->
        <FrameLayout
            android:id="@+id/topHandle"
            android:layout_width="64dp"
            android:layout_height="24dp"
            android:layout_gravity="top|center_horizontal"
            android:clickable="true"
            android:contentDescription="Window controls and drag handle"
            android:focusable="false">

            <ImageView
                android:layout_width="24dp"
                android:layout_height="8dp"
                android:layout_gravity="center"
                android:contentDescription="@null"
                android:src="@drawable/ic_three_dots" />
        </FrameLayout>

        <LinearLayout
            android:id="@+id/topQuickMenu"
            android:layout_width="wrap_content"
            android:layout_height="36dp"
            android:layout_gravity="top|center_horizontal"
            android:background="@drawable/bg_quick_menu_pill"
            android:gravity="center"
            android:orientation="horizontal"
            android:visibility="gone">

            <TextView
                android:id="@+id/btnFullscreen"
                android:layout_width="wrap_content"
                android:layout_height="match_parent"
                android:background="?android:attr/selectableItemBackground"
                android:clickable="true"
                android:focusable="true"
                android:gravity="center"
                android:minWidth="72dp"
                android:paddingHorizontal="10dp"
                android:text="Fullscreen"
                android:textColor="?attr/colorOnSurface"
                android:textSize="12sp" />

            <TextView
                android:id="@+id/btnMini"
                android:layout_width="wrap_content"
                android:layout_height="match_parent"
                android:background="?android:attr/selectableItemBackground"
                android:clickable="true"
                android:focusable="true"
                android:gravity="center"
                android:minWidth="56dp"
                android:paddingHorizontal="10dp"
                android:text="Mini"
                android:textColor="?attr/colorOnSurface"
                android:textSize="12sp" />

            <TextView
                android:id="@+id/btnClose"
                android:layout_width="wrap_content"
                android:layout_height="match_parent"
                android:background="?android:attr/selectableItemBackground"
                android:clickable="true"
                android:focusable="true"
                android:gravity="center"
                android:minWidth="56dp"
                android:paddingHorizontal="10dp"
                android:text="Close"
                android:textColor="?attr/colorOnSurface"
                android:textSize="12sp" />
        </LinearLayout>

        <View
            android:id="@+id/resizeBottomLeft"
            android:layout_width="32dp"
            android:layout_height="32dp"
            android:layout_gravity="bottom|start"
            android:background="@android:color/transparent"
            android:clickable="true"
            android:contentDescription="@string/resize_desc"
            android:focusable="false" />

        <View
            android:id="@+id/resizeBottomRight"
            android:layout_width="32dp"
            android:layout_height="32dp"
            android:layout_gravity="bottom|end"
            android:background="@android:color/transparent"
            android:clickable="true"
            android:contentDescription="@string/resize_desc"
            android:focusable="false" />

        <FrameLayout
            android:id="@+id/bottomBar"
            android:layout_width="96dp"
            android:layout_height="20dp"
            android:layout_gravity="bottom|center_horizontal"
            android:background="@android:color/transparent"
            android:clickable="true"
            android:focusable="false">

            <View
                android:layout_width="48dp"
                android:layout_height="4dp"
                android:layout_gravity="center"
                android:background="@drawable/bg_pill_handle" />
        </FrameLayout>
    </FrameLayout>
</com.google.android.material.card.MaterialCardView>
```

Thêm ba drawable sau vào `app/src/main/res/drawable/`:

`ic_three_dots.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="8dp"
    android:viewportWidth="24"
    android:viewportHeight="8">
    <path
        android:fillColor="?attr/colorOnSurface"
        android:pathData="M4,4m-1.5,0a1.5,1.5 0,1 1,3 0a1.5,1.5 0,1 1,-3 0 M10.5,4m-1.5,0a1.5,1.5 0,1 1,3 0a1.5,1.5 0,1 1,-3 0 M17,4m-1.5,0a1.5,1.5 0,1 1,3 0a1.5,1.5 0,1 1,-3 0" />
</vector>
```

`bg_pill_handle.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#CCFFFFFF" />
    <corners android:radius="2dp" />
</shape>
```

`bg_quick_menu_pill.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#CC202124" />
    <corners android:radius="18dp" />
</shape>
```

Implementation notes:
- `topHandle` is exactly 64×24dp and displays only the three dots. Do not add a title or a long drag bar.
- `topQuickMenu` is wrap-content×36dp, initially GONE, and contains only the three named actions. If project string-resource conventions require localization, move the three literal labels into `strings.xml` and use those resources; do not change their order or behavior.
- The bottom touch target is exactly 96×20dp; its visible pill is exactly 48×4dp. Neither the handle nor the bar is a full-width strip.
- `MaterialCardView` clips the TextureView at the 18dp corners. Do not inset TextureView to make room for controls; controls overlay it.

## 3. Thiết kế chi tiết `AppWindow.kt` & `WindowState.kt`

### State và biến

Giữ `WindowState.kt` enum hiện tại: `NORMAL`, `MINI`, `EDGE_HIDE`, `FULLSCREEN`. Trong phase 1:

- `state: WindowState = WindowState.NORMAL`: nguồn sự thật duy nhất cho trạng thái cửa sổ.
- `params: WindowManager.LayoutParams`: width/height luôn là pixel dương, không bao giờ là `WRAP_CONTENT`.
- `virtualDisplay: VirtualDisplay?` hoặc `lateinit` chỉ sau khi đã tạo; tránh dùng trước `onSurfaceTextureAvailable`.
- `displayId = -1` đến khi VirtualDisplay được tạo; sau đó giữ đúng displayId cho inject và lifecycle hiện tại.
- `newDpi`: DPI tính từ kích thước pixel thực tế, `calculateDpi(width, height, calculateScreenInches(width, height)) - config.reduceDPI`, chỉ trừ một lần.
- Thông tin thao tác top: `downRawX`, `downRawY`, `startWindowX`, `startWindowY`, `dragging`, `lastTap`/GestureDetector.
- Thông tin resize: `startRawX`, `startRawY`, `startWidth`, `startHeight`, `startWindowX`, `targetWidth`, `targetHeight`, `targetWindowX`, và `isLeft`.
- `quickToolbarHideJob` có thể đổi tên thành `quickMenuHideJob`; cancel job khi mở lại/đóng cửa sổ. Delay đúng 2500ms.
- Min window size tiếp tục dùng ngưỡng hiện có (`minWidth` 120dp, `minHeight` 160dp) chuyển sang pixel bằng cùng `context.resources.displayMetrics`.

### Khởi tạo LayoutParams và VirtualDisplay

1. Chọn duy nhất `binding.viewTexture`; đặt VISIBLE trước khi add view. `window_app.xml` không khai báo SurfaceView.
2. Chuyển `config.defaultWindowWidth`/`defaultWindowHeight` từ dp sang pixel bằng `context.resources.displayMetrics` một lần. Dùng các giá trị pixel đó cho cả `params.width`/`params.height` và layout root; không gán WRAP_CONTENT.
3. Dùng `gravity = Gravity.TOP or Gravity.START` để `params.x` là mép trái của cửa sổ; đặt x/y khởi đầu ở vị trí giữa vùng hiển thị nếu còn đủ chỗ. Không dùng `Gravity.CENTER` vì resize góc trái cần tăng `params.x` theo raw dx.
4. Giữ các window flags hiện tại cần thiết cho overlay (NO_FOCUSABLE, NOT_TOUCH_MODAL, HARDWARE_ACCELERATED, ALT_FOCUSABLE_IM) trừ khi build/test chứng minh có xung đột. Thêm view bằng `Instances.windowManager.addView(binding.root, params)`.
5. Chờ đo layout (`binding.viewTexture.post { ... }`) hoặc khởi tạo trong `onSurfaceTextureAvailable`; đọc `binding.viewTexture.width`/`height`, kiểm tra cả hai > 0, rồi mới tạo VirtualDisplay. Không dùng giá trị dp hoặc `params` như nguồn thay thế cho kích thước surface thực tế.
6. Gọi `setDefaultBufferSize(width, height)` trên SurfaceTexture và tạo VirtualDisplay bằng cùng `width`, `height`, `newDpi`, `flags` truyền vào constructor. Gắn `Surface(surfaceTexture)`, lấy `displayId`, gọi `setDisplayImePolicy(...)` hiện có, đăng ký listener hiện có và chỉ sau đó gọi `onVirtualDisplayCreated(displayId)` đúng một lần. Đảm bảo xử lý trường hợp callback surface đến trước/sau khi view post; không tạo hai VirtualDisplay.
7. Giữ `YAMFManager.createWindow()` tương thích: callback hiện được dùng để `addWindow(displayId)` và `startCmd?.startAuto(displayId)` (`YAMFManager.kt` quanh dòng 145). Không đổi chữ ký constructor/callback trong kế hoạch này.

### Listener topHandle: kéo, tap, double-tap

Gắn listener chỉ cho `binding.topHandle`. Dùng `event.rawX`/`event.rawY`.

- ACTION_DOWN: lưu raw tọa độ, `params.x/y` ban đầu, đặt `dragging=false`; bắt gesture (`GestureDetector.onDown()` trả true).
- ACTION_MOVE: nếu độ lệch raw vượt `ViewConfiguration.scaledTouchSlop`, đánh dấu dragging. Khi dragging, gán `params.x = startWindowX + dx`, `params.y = startWindowY + dy` rồi gọi `Instances.windowManager.updateViewLayout(binding.root, params)`. Không dùng local `event.x/y` để định vị cửa sổ. Sau khi đã kéo, gesture không được phát sinh tap.
- Tap đơn: chỉ xử lý qua `onSingleTapConfirmed` (hoặc cơ chế tương đương chờ hết cửa sổ nhận diện double-tap), gọi `showQuickMenu()`. Mở menu và đặt job tự ẩn 2500ms.
- Double-tap trong cửa sổ hiện tại (300ms như `DOUBLE_TAP_DELTA` hiện có): cancel tap đơn/menu job nếu cần và gọi `transitionTo(WindowState.FULLSCREEN)` đúng một lần.
- ACTION_UP: gọi `moveToTopIfNeed(event)` như hiện tại nếu không phá gesture; luôn kết thúc tracking. Kéo không được mở menu.

### topQuickMenu và touch TextureView

- `btnFullscreen`: gọi `transitionTo(WindowState.FULLSCREEN)`.
- `btnMini`: chỉ gọi `TipUtil.showToast(...)` với thông báo chưa hỗ trợ; không đổi state sang MINI, không resize, không tạo chế độ thu nhỏ.
- `btnClose`: gọi `close()`.
- Mở menu: `visibility = View.VISIBLE`, hủy job cũ, launch delay 2500ms rồi đặt GONE. Khi đóng cửa sổ phải cancel job.
- Khi người dùng chạm TextureView, ẩn menu ngay (ít nhất ở ACTION_DOWN), nhưng vẫn forward toàn bộ MotionEvent qua `forwardMotionEvent(event)` để app đích nhận touch. Ẩn menu không được làm mất event đầu tiên hoặc chặn chuỗi touch.
- Gắn `setOnGenericMotionListener` như hiện tại và tiếp tục forward qua `forwardMotionEvent()`.

### Listener resize hai góc

Tạo một listener dùng chung `cornerResizeListener(isLeft: Boolean)` cho `resizeBottomLeft` và `resizeBottomRight`.

- ACTION_DOWN: lưu `rawX/rawY`, kích thước cửa sổ hiện tại bằng `params.width/height` hoặc kích thước view đã đo, lưu `params.x`, khởi tạo target bằng kích thước hiện tại; trả true.
- ACTION_MOVE: tính `dx = rawX - startRawX`, `dy = rawY - startRawY`; bên phải `targetWidth = startWidth + dx`, bên trái `targetWidth = startWidth - dx`; cả hai `targetHeight = startHeight + dy`. Clamp theo minWidth/minHeight. Góc trái phải neo cạnh phải: khi đã clamp, `targetWindowX = startWindowX + startWidth - targetWidth`; góc phải giữ nguyên x.
- Trong mỗi ACTION_MOVE chỉ cập nhật `params.width`, `params.height`, và `params.x` với góc trái; gọi `Instances.windowManager.updateViewLayout(binding.root, params)`. Không gọi `virtualDisplay.resize()`, `setDefaultBufferSize()`, không gán kích thước cho một view con riêng lẻ.
- ACTION_UP: commit kích thước LayoutParams cuối. Sau khi layout cuối được áp dụng, đọc lại `binding.viewTexture.width/height` thực tế (post nếu cần); nếu khác kích thước VD hiện tại, tính DPI một lần, gọi `binding.viewTexture.surfaceTexture?.setDefaultBufferSize(width, height)` và `virtualDisplay.resize(width, height, newDpi)` một lần. Đây là đường duy nhất resize VirtualDisplay khi người dùng resize cửa sổ. Nếu surface chưa sẵn sàng, ghi nhận kích thước cuối và áp dụng khi surface sẵn sàng, không gọi bằng 0.
- ACTION_CANCEL: khôi phục kích thước/x ban đầu qua params + updateViewLayout; không resize VirtualDisplay.

### Listener bottomBar

Dùng listener ACTION_DOWN/MOVE/UP, lưu `downRawY`; `dy = event.rawY - downRawY`. Không dùng fling velocity.

- ACTION_UP với `dy < -60px`: gọi `close()`.
- ACTION_UP với `dy > 80px`: gọi `transitionTo(WindowState.FULLSCREEN)`.
- ACTION_UP với `abs(dy) < 15px`: gọi `sendBackKey()` để inject KEYCODE_BACK vào `displayId`.
- Các khoảng còn lại không thực hiện action. Đánh giá đúng theo pixel tọa độ MotionEvent, không đổi các ngưỡng sang dp.

### NORMAL, FULLSCREEN và cleanup

- `NORMAL`: cửa sổ overlay vẫn ở nguyên vị trí/kích thước; không có side effect khi gọi transition sang chính state hiện tại.
- `FULLSCREEN`: giữ semantics code hiện có: lấy task trên cùng qua `getTopRootTask()`, gọi `Instances.activityTaskManager.moveRootTaskToDisplay(taskId, 0)`, sau đó `onDestroy()`. Kiểm tra task null trước khi truy cập. Không triển khai trạng thái fullscreen bằng cách set overlay match-screen hay thay đổi VirtualDisplay flags.
- `MINI` / `EDGE_HIDE`: không thực hiện transition thực. MINI chỉ là toast từ nút stub; EDGE_HIDE không xuất hiện trong menu.
- `close()` giữ guard hiện có để không đóng nhầm khi state đã FULLSCREEN. `onDestroy()` giữ cleanup task listener, rotation watcher, broadcast receiver, `YAMFManager.removeWindow(displayId)`, `virtualDisplay.release()` và `Instances.windowManager.removeView(...)`; thêm guard chống cleanup lặp và cancel job menu. Không để surface callback tiếp tục resize sau release.

## 4. Quy tắc cấm cho Dev thực thi

1. CẤM `WindowManager.LayoutParams.WRAP_CONTENT` cho width hoặc height. Luôn truyền pixel cụ thể và dương; root và TextureView phải được layout theo cùng kích thước cuối.
2. CẤM gọi `VirtualDisplay.resize()` hoặc `SurfaceTexture.setDefaultBufferSize()` trong ACTION_MOVE của resize. Chỉ commit VirtualDisplay ở ACTION_UP (hoặc xử lý kích thước cuối đã pending khi SurfaceTexture vừa sẵn sàng).
3. CẤM đặt bất kỳ view điều khiển nào trước TextureView trong thứ tự child của FrameLayout. TextureView là child đầu tiên; handle/menu/resize/bottom bar khai báo sau.
4. CẤM dùng kích thước dp cấu hình làm kích thước VirtualDisplay/buffer sau khi TextureView đã layout. Luôn lấy width/height thực tế từ TextureView, cùng một nguồn density/context.
5. CẤM resize cả VirtualDisplay và TextureView trong callback `onSurfaceTextureSizeChanged()`; callback đó không được tạo đường resize lặp khi params đang đổi.
6. CẤM dùng SurfaceView song song hoặc giữ nhánh cũ `config.surfaceView` trong layout phase này. Không xóa Config/Setting field ngoài phạm vi; chỉ không dùng nhánh SurfaceView trong AppWindow phase 1.
7. CẤM đổi flags VirtualDisplay khỏi 1164, thêm `SHOULD_SHOW_SYSTEM_DECORATIONS`, hoặc truyền flags=0. Kiểm tra config runtime có thể được lưu bền; không kết luận chỉ từ default trong Config.kt.
8. CẤM trừ `config.reduceDPI` hai lần. Tính từ pixel thực tế, trừ một lần, truyền cùng DPI vào create/resize.
9. CẤM dùng fling velocity thay ngưỡng khoảng cách bottomBar; không đổi ngưỡng -60/80/15 px sang dp.
10. CẤM để topHandle double-tap cũng phát sinh hành động tap đơn; tap đơn chỉ xác nhận sau khi hết cửa sổ nhận diện double-tap.
11. CẤM để menu tự ẩn làm mất lần touch đầu tiên vào TextureView; menu ẩn và event vẫn được forward vào đúng displayId.
12. CẤM xóa callback `onVirtualDisplayCreated` hoặc thay đổi hợp đồng khởi chạy app trong YAMFManager.

## 5. Checklist nghiệm thu & Lệnh build

### Build

Từ thư mục gốc repo:

```bash
./gradlew assembleDebug
```

Build phải kết thúc thành công; kiểm tra task `:app:assembleDebug` và APK trong `app/build/outputs/apk/debug/`.

### Kiểm tra code/layout sau triển khai

- [ ] `window_app.xml` parse/build thành công; CardView radius đúng 18dp, bên trong là FrameLayout.
- [ ] TextureView là child đầu tiên, match_parent × match_parent và visibility visible; không còn SurfaceView trong layout.
- [ ] `topHandle` 64×24dp chỉ hiện ba chấm; `topQuickMenu` wrap_content×36dp, mặc định GONE, có đúng Fullscreen/Mini/Close.
- [ ] Resize handles mỗi cái 32×32dp; bottomBar 96×20dp và pill nhìn thấy 48×4dp.
- [ ] Tất cả view điều khiển được khai báo sau TextureView và nhận touch.
- [ ] Không còn tham chiếu binding ID cũ `topBar`, `quickToolbar`, `bottomLeftResize`, `bottomRightResize`, `bottomBarTouch`; AppWindow chỉ dùng các ID mới.
- [ ] Tìm toàn bộ `virtualDisplay.resize` và `setDefaultBufferSize` trong AppWindow: không có call trong ACTION_MOVE hoặc `onSurfaceTextureSizeChanged()`; resize gesture chỉ commit sau ACTION_UP.
- [ ] Tìm `WRAP_CONTENT` trong cấu hình LayoutParams AppWindow: không dùng cho width/height.
- [ ] Xác nhận createVirtualDisplay, TextureView buffer và resize dùng cùng kích thước pixel thực tế; log width/height texture và VirtualDisplay bằng nhau.
- [ ] Xác nhận flags runtime là 1164; touch thường và KEYCODE_BACK đều được inject vào đúng displayId.
- [ ] Chạm TextureView ẩn menu nhưng app đích vẫn nhận touch; menu tự ẩn sau 2.5 giây.
- [ ] Kéo ba chấm di chuyển cửa sổ; tap đơn mở menu; double-tap chuyển app ra display 0/fullscreen.
- [ ] Resize từng góc: trong lúc kéo VirtualDisplay không resize; thả tay thì chỉ một commit cuối, góc trái giữ cạnh phải cố định.
- [ ] Bottom pill: vuốt lên hơn 60px đóng app; vuốt xuống hơn 80px fullscreen; tap dưới 15px gửi Back; khoảng cách khác không gây action.
- [ ] Nút Mini chỉ báo chưa hỗ trợ; không kích hoạt MINI/EDGE_HIDE/Sidebar.
- [ ] Đóng cửa sổ và fullscreen đều release VirtualDisplay, remove view, unregister listener/receiver đúng một lần; không có callback sau release.
- [ ] Test trên thiết bị: không có dải đen/xám trên/dưới, không có vệt đen dọc, không có tablet taskbar/navigation trong nội dung VirtualDisplay.
