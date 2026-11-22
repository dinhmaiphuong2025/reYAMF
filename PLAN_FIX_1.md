# PLAN_FIX_1 — Touch controls + bottom gesture strip

Scope: chỉ sửa `AppWindow.kt` và `window_app.xml` cho hai lỗi đã báo. Không thay đổi VirtualDisplay, flags, tên ID hay logic các cử chỉ.

## 1. Chẩn đoán touch từ code hiện tại

- `AppWindow.kt:428–439`: listener `topHandle` đã gọi `topHandleDetector.onTouchEvent(event)`; `GestureDetector.SimpleOnGestureListener.onDown()` tại dòng 407 trả `true`; nhánh `ACTION_DOWN` cũng trả `true`. Không phải lỗi thiếu `return true`.
- `AppWindow.kt:463–480`: listener `bottomBar` trả `true` ở `ACTION_DOWN`, `ACTION_UP` và mặc định các action khác. Không phải lỗi thiếu `return true`.
- Các window flags tại `AppWindow.kt:138–143` không chứa `FLAG_NOT_TOUCHABLE`; không có căn cứ kết luận flags đang chủ động chặn touch.
- Trong `window_app.xml`, `TextureView` khai báo trước các control và `bottomBar` là child cuối của FrameLayout; theo thứ tự khai báo, TextureView không nên che các control. `topHandle.bringToFront()` tại `AppWindow.kt:167` nâng topHandle lên trên cả menu nếu chúng chồng lấn, nhưng không giải thích việc chính topHandle không nhận touch. Vì vậy không được “sửa” bằng cách đổi return value vốn đã đúng hoặc tuyên bố có listener thiếu `onDown`.
- Lỗi cấu trúc xác định được: `bottomBar` hiện là overlay ở đáy `contentFrame` (XML dòng 120–134), cùng vùng với TextureView, nên chiếm diện tích điều hướng app. Đồng thời cần chốt Z-order của các control sau khi add root để tránh phụ thuộc thứ tự runtime.

## 2. Sửa `window_app.xml`: tách bottomBar khỏi nội dung app

Giữ root MaterialCardView và các ID hiện tại. Thay cấu trúc con bên trong root thành `LinearLayout` dọc như sau:

```xml
<LinearLayout
    android:id="@+id/windowColumn"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="?attr/colorSurface">

    <FrameLayout
        android:id="@+id/contentFrame"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:clipChildren="true"
        android:clipToPadding="true">

        <!-- Giữ nguyên TextureView và 4 control, theo đúng thứ tự này. -->
        <!-- 1) viewTexture: match_parent x match_parent, visible -->
        <!-- 2) topHandle -->
        <!-- 3) topQuickMenu -->
        <!-- 4) resizeBottomLeft -->
        <!-- 5) resizeBottomRight -->
    </FrameLayout>

    <FrameLayout
        android:id="@+id/bottomBar"
        android:layout_width="match_parent"
        android:layout_height="16dp"
        android:background="?attr/colorSurface"
        android:clickable="true"
        android:focusable="false">

        <View
            android:layout_width="48dp"
            android:layout_height="4dp"
            android:layout_gravity="center"
            android:background="@drawable/bg_pill_handle" />
    </FrameLayout>
</LinearLayout>
```

Hướng dẫn áp dụng:
- Di chuyển nguyên `contentFrame` đang có vào LinearLayout; không đổi ID/thuộc tính/kích thước của TextureView, topHandle, topQuickMenu và hai resize handle.
- Xóa `bottomBar` khỏi `contentFrame`; đặt nó làm sibling ngay sau `contentFrame` trong LinearLayout. Không để bất kỳ view bottom gesture nào phủ TextureView.
- `bottomBar` cao 16dp, nền cùng `?attr/colorSurface` với card; pill vẫn 48×4dp, được căn giữa (còn 6dp đệm mỗi phía). Vùng chạm rộng toàn hàng để dễ bắt vuốt/tap; không đổi ngưỡng cử chỉ trong Kotlin.

## 3. Chốt touch dispatch trong `AppWindow.kt`

Không thay đổi listener hiện có: các nhánh ACTION_DOWN đã trả true. Trong `doInit()`, ngay sau `Instances.windowManager.addView(binding.root, params)` và sau khi gắn listener, đặt thứ tự nổi tường minh cho controls; không đưa TextureView lên trước controls:

```kotlin
binding.topHandle.bringToFront()
binding.topQuickMenu.bringToFront()
binding.resizeBottomLeft.bringToFront()
binding.resizeBottomRight.bringToFront()
binding.bottomBar.bringToFront()
```

Chỉ gọi năm dòng trên; tuyệt đối không gọi `viewTexture.bringToFront()`. `bottomBar` là sibling dưới `contentFrame`, ngoài vùng chạm của nội dung app. Thứ tự topHandle rồi topQuickMenu bảo đảm menu là lớp trên khi đang hiện.

Để xác định touch dispatch sau sửa, thêm log tạm thời ngay đầu mỗi listener trước `when`:

```kotlin
Log.d("CONTROL_TOUCH", "topHandle action=${event.actionMasked} x=${event.x} y=${event.y}")
Log.d("CONTROL_TOUCH", "bottomBar action=${event.actionMasked} x=${event.x} y=${event.y}")
```

Đồng thời log bounds `left/top/right/bottom` của hai view sau layout. Nếu log DOWN không xuất hiện, kiểm tra bounds/Z-order của view và thứ tự `bringToFront`; không đổi return true hoặc touch slop để che lỗi. Sau khi xác minh trên thiết bị, có thể bỏ log tạm.

Không sửa thresholds hoặc nhánh xử lý: `bottomBarListener` hiện đã dùng `dy < -60px` để đóng, `dy > 80px` để fullscreen, `abs(dy) < 15px` để gửi Back.

## 4. Build và nghiệm thu

Chạy từ repo root:

```bash
./gradlew assembleDebug
```

Checklist:
- [ ] Layout build được; root CardView vẫn giữ nguyên.
- [ ] `contentFrame` là child đầu của LinearLayout dọc, `height=0dp`, `weight=1`; TextureView và 4 control giữ nguyên IDs/thứ tự bên trong.
- [ ] `bottomBar` là sibling ngay sau contentFrame, không còn bên trong contentFrame; cao 16dp, match_parent width, nền colorSurface, pill 48×4dp căn giữa.
- [ ] Log ghi ACTION_DOWN ở topHandle và bottomBar khi chạm đúng vùng; cả hai thao tác nhận cử chỉ, hai góc resize vẫn hoạt động.
- [ ] Vuốt lên đóng, vuốt xuống fullscreen, tap gửi Back theo ngưỡng hiện có; vùng điều hướng của app bên trong không còn bị bottomBar phủ.
