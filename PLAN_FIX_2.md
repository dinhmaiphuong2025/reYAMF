# PLAN_FIX_2 — Chẩn đoán touch theo log a8d8751

Phạm vi: chỉ tạo bằng chứng để xử lý lỗi touch `topHandle`/`bottomBar`; không đổi VirtualDisplay, flags, IDs, ngưỡng cử chỉ hoặc layout đang có cho đến khi xác định được điểm rơi của MotionEvent.

## 1. Kết luận từ code và log đã cung cấp

Các số đo **không cho thấy lỗi kích thước/bounds**:

- `viewTexture=980×1274`; `root=980×1330`; phần chênh 56px đúng bằng `bottomBar=16dp` ở density 3.5 (16×3.5=56). `bottomBar` bounds `[0,1274..980,1330]` nằm đúng trong root và ngoài vùng TextureView.
- `topHandle=224×84px`, đúng `64×24dp` ở density 3.5; bounds `x=378..602, y=0..84`, nằm trong content `980×1274`, không bị cắt bởi biên root/content.
- `AppWindow.kt:471–476` đã log `CONTROL_TOUCH` và trả `true` ở ACTION_DOWN của bottomBar. `AppWindow.kt:407` `onDown()` trả true; `AppWindow.kt:428–439` log `CONTROL_TOUCH` và trả true ở ACTION_DOWN của topHandle. Vì vậy không sửa return value/listener để “thêm true”.
- `AppWindow.kt:167–171` đã gọi bringToFront cho topHandle, topQuickMenu, hai resize handle, bottomBar; không thêm lại cùng workaround. `window_app.xml` đã đặt TextureView trước các control và bottomBar ở ngoài contentFrame.
- Trích đoạn log gửi chỉ có `SIZE_DEBUG`, không có dòng `CONTROL_TOUCH`. Nếu đây là toàn bộ log sau khi chạm các vùng thì sự kiện chưa vào listener; nếu chỉ là trích đoạn thì chưa có bằng chứng touch thất bại. Hiện chưa đủ dữ kiện để kết luận nguyên nhân chính xác như overlay hệ thống, vùng touchable cửa sổ hay event không chạm đúng bounds.

## 2. Lấy bằng chứng sạch trên đúng APK

Cài/xác nhận đang chạy APK build từ commit `a8d8751`; mở một cửa sổ mới. Xóa log trước thao tác, rồi chạm riêng vào tâm 3 chấm và tâm pill đáy (không kéo trước):

```bash
adb logcat -c
adb logcat -v time -s CONTROL_TOUCH:V SIZE_DEBUG:V
```

Sau mỗi chạm, lưu toàn bộ output, không chỉ các dòng SIZE_DEBUG. Kỳ vọng mỗi listener ghi `action=0` khi DOWN; khi nhấc tay ghi action UP tương ứng. Sau đó thử kéo mỗi vùng một lần.

Đối chiếu log bounds với tọa độ chạm màn hình (rawX/rawY). Nếu chưa có raw tọa độ, thêm log tạm `rawX/rawY` vào đúng hai dòng `CONTROL_TOUCH` hiện có; không sửa nhánh xử lý cử chỉ.

## 3. Chẩn đoán theo kết quả (không phỏng đoán trước)

1. Nếu xuất hiện `CONTROL_TOUCH action=0` và `action=1`: touch dispatch tới listener. Khi đó xử lý là gesture/visual target; đối chiếu raw coordinates, dy và kết quả gesture. Không thay `return true` hay kích thước trước khi thấy mismatch cụ thể.
2. Nếu tag CONTROL_TOUCH vắng nhưng log sạch xác nhận đã chạm đúng vị trí và APK đúng commit: ghi thêm `isShown`, `isEnabled`, `isClickable`, `getGlobalVisibleRect()` cho `topHandle`/`bottomBar` trong callback `binding.root.post` hiện có (`AppWindow.kt:189–194`). Ghi thêm params cửa sổ (x/y/width/height/gravity/flags) và kiểm tra touchable window/input region bằng `adb shell dumpsys window` và `adb shell dumpsys input`.
3. Nếu `getGlobalVisibleRect()` không phủ điểm raw chạm hoặc view hidden/disabled: sửa nguyên nhân bounds/visibility cụ thể ở `window_app.xml`/khởi tạo AppWindow; giữ nguyên các ID và cấu trúc hiện tại nếu không cần thay đổi để khắc phục số đo sai.
4. Nếu bounds phủ điểm, view visible/enabled/clickable, root params đúng nhưng listener vẫn không log: xác định cửa sổ/overlay nào nhận vùng input từ dumpsys trước. Chỉ sửa Z-order/window flags sau khi dumpsys chứng minh xung đột; tuyệt đối không thêm `FLAG_NOT_TOUCHABLE`. Kiểm tra cả việc có cửa sổ khác phủ hai vùng, vì resize handles vẫn nhận touch không loại trừ overlay chỉ phủ giữa/đầu cửa sổ.
5. Nếu một vùng có log, vùng kia không: xử lý riêng vùng thiếu log; không thay listener của vùng đã hoạt động.

## 4. Giới hạn thay đổi và build

- Chưa thay đổi code trong bước chẩn đoán. Sau khi có output đầy đủ và root cause, sửa tối thiểu đúng nhánh đã chứng minh; ghi rõ file/function và bỏ log tạm sau khi xác minh.
- Giữ `bottomBar` sibling sau `contentFrame`, 16dp cao, nền `?attr/colorSurface`; giữ TextureView và 4 control theo thứ tự hiện có trong contentFrame.
- Giữ các ngưỡng `dy < -60px`, `dy > 80px`, `abs(dy) < 15px`; giữ `GestureDetector.onDown() = true` và ACTION_DOWN listeners trả true.
- Không resize VirtualDisplay trong ACTION_MOVE; không đổi flags 1164, không đưa TextureView lên trước controls, không đổi IDs.
- Sau khi áp dụng sửa đã có bằng chứng, chạy từ repo root:

```bash
./gradlew assembleDebug
```

Checklist: layout build được; hai tag touch chứng minh DOWN/UP đến đúng vùng; resize hai góc còn chạy; bottom swipe up/down/tap vẫn thực hiện đúng ngưỡng; thanh đáy tiếp tục nằm ngoài TextureView.
