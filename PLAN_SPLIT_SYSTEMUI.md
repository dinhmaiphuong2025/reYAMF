# PLAN_SPLIT_SYSTEMUI.md

## Mục tiêu và hiện trạng đã xác nhận

Kế hoạch này chỉ đọc mã nguồn tại `16ba629`; không sửa implementation. Mục tiêu là để `system_server` giữ toàn bộ quyền display/task/input, còn `com.android.systemui` sở hữu cửa sổ hiển thị và gesture. APK/module vẫn là một APK; ranh giới là process/LSPosed scope, không phải tách module Gradle.

Hiện trạng quan trọng:
- `HookSystem` chỉ chạy cho package `android`; tại `ActivityManagerService.systemReady` gọi `YAMFManager.systemReady()` (`app/src/main/java/com/mja/reyamf/xposed/hook/HookSystem.kt`).
- `YAMFManager` đang giữ cả Binder app API lẫn tạo `AppWindow` bằng `systemUiContext` và context wrapper (`xposed/services/YAMFManager.kt`, `Instances.kt`).
- `UserService` đẩy Binder `YAMFManager` sang `com.mja.reyamf` qua UID observer → `ServiceProvider.call()`; `YAMFManagerProxy` giữ binder và theo dõi death (`UserService.kt`, `ServiceProvider.kt`, `YAMFManagerProxy.kt`). Đây là cơ chế cấp Binder hiện có cần tái sử dụng, không đăng ký một service name toàn hệ thống mới.
- `AppWindow.kt` hiện trộn WindowManager/View/TextureView/UI/gesture với VirtualDisplay, task, input, IME và listener. `window_app.xml` là layout Material3 hiện hành.

## 1. Phân ranh giới tiến trình và file

### `system_server` — quyền đặc quyền, không inflate UI

Tiếp tục hook tại `HookSystem` trong scope `android`. Giữ `YAMFManager` là `IYAMFManager.Stub` chạy trong system_server, nhưng chuyển phần lõi cửa sổ thành coordinator/backend không tham chiếu `AppWindow`, View, binding hay Material:

- `xposed/services/YAMFManager.kt`: giữ API đang dùng bởi app chính; chuyển `createWindow` thành yêu cầu tạo UI tới callback SystemUI; tiếp nhận các lệnh display/input/task từ SystemUI và kiểm tra `clientId`/quyền sở hữu display.
- Thêm backend/registry nội bộ (tách file theo convention hiện có): quản lý `VirtualDisplay`, `Surface` phía server, display→client, task, `ITaskStackListener`, `IRotationWatcher`/`DisplayManager.DisplayListener`, và dọn dẹp theo client. Khởi tạo sau `Instances.init()` trong `systemReady`.
- `xposed/utils/Instances.kt` và `Utils.kt`: giữ các binder/API platform chỉ dùng phía system_server; tập trung `startActivityAsUser`, chuyển task, đóng task, InputManager injection và `setDisplayImePolicy` tại đây/backend. Không truyền `Instances` qua AIDL.
- `AppWindow.kt` và `WindowState`: không còn được gọi/tải để tạo cửa sổ trong system_server. System_server không giữ WindowManager overlay hay broadcast receiver của UI.

### `com.android.systemui` — toàn bộ UI và gesture

- `HookSystem` (được khai báo trong `app/src/main/assets/xposed_init`) chỉ hiện xử lý package `android`; mở rộng entrypoint này với nhánh `com.android.systemui` tách biệt: khởi tạo client Binder một lần cho process và đăng ký callback, không chạy setup system-server trong nhánh UI. `com.android.systemui` phải được thêm vào `xposed_scope` để nhánh đó được nạp.
- Chuyển `AppWindow.kt`, `WindowState` và các lớp gesture/window UI (bao gồm xử lý `window_app.xml`) sang execution trong SystemUI. Giữ nguyên layout và hành vi Phase 1; bỏ mọi truy cập trực tiếp `Instances.displayManager`, `Instances.inputManager`, task/window hidden APIs và `YAMFManager` singleton. Thay bằng client `IYAMFManager`.
- SystemUI tạo/giữ `TextureView`, `Surface(surfaceTexture)`, `WindowManager.LayoutParams`, listener cử chỉ, animation và state UI; request display qua Binder sau khi Surface có kích thước hợp lệ.
- Dùng Context lấy riêng từ APK `com.mja.reyamf` để inflate module resources (xem mục 4), nhưng lấy `WindowManager`/window token/context display configuration phù hợp với SystemUI. Không dùng context system_server hiện tại `Instances.systemUiContext`.

### Dùng chung (Binder contract / model)

- Giữ package AIDL `com.mja.reyamf.xposed`; thêm callback AIDL. Cả system_server và SystemUI được LSPosed inject cùng APK nên dùng chung stub AIDL/models.
- `Config`, `StartCmd`, `AppInfo`, các API app/sidebar hiện có tiếp tục tồn tại. Không chuyển giao quyền đặc quyền vào app chính.
- `arrays.xml`: thêm đúng `com.android.systemui` vào `xposed_scope`; giữ `android`, launcher và permission-controller scopes hiện có. Đây mới là scope được khai báo trong APK; người dùng cũng phải bật scope `com.android.systemui` trong LSPosed Manager sau khi cài/cập nhật module.

## 2. Hợp đồng AIDL

Cập nhật `app/src/main/aidl/com/mja/reyamf/xposed/IYAMFManager.aidl`, giữ nguyên các method app hiện tại và bổ sung nhóm SystemUI. Thêm `ISystemUiCallback.aidl`. Repo hiện cấu hình `compileSdk = 36`, `targetSdk = 36` trong `app/build.gradle.kts`; kế hoạch này giữ nguyên hai giá trị đó, không yêu cầu nâng Android Gradle Plugin hay cài SDK platform 37 chỉ vì thiết bị thử nghiệm chạy API 37.

### Bước 0 bắt buộc: kiểm chứng AIDL trên toolchain repo

Trước khi triển khai bất kỳ method/callback nào, dùng đúng Gradle wrapper, AGP và `compileSdk = 36` của repo để chạy một probe AIDL tối thiểu trong workspace tạm: import `android.view.Surface` và `android.view.MotionEvent`, truyền chúng qua method AIDL, đồng thời compile callback `oneway`. Xác nhận AIDL generation và compile Kotlin/Java client/server đều qua; ghi lại lệnh và kết quả trong thay đổi implementation. Không suy ra khả năng compile chỉ từ việc các kiểu framework implement `Parcelable`.

Nếu probe thất bại, dừng trước khi triển khai hợp đồng này và thiết kế lại từng kiểu có vấn đề. Event có thể dùng một DTO AIDL/parcelable riêng chứa các trường MotionEvent cần thiết; phải bảo toàn pointer properties/coords, event time, action, source, buttons/meta state, history nếu cần, thứ tự và display targeting. `Surface` không thể thay bằng `ParcelFileDescriptor` một cách tương đương: chỉ chọn phương án khác nếu nó vẫn chuyển được một graphics `Surface` producer endpoint mà VirtualDisplay chấp nhận và đã được kiểm chứng trên thiết bị. Không tiếp tục triển khai API giả định cho đến khi cả Surface và event transport có quyết định compile/runtime rõ ràng.

### Bước 0b bắt buộc: spike quyền sở hữu overlay trong SystemUI

Song song với probe AIDL, chạy một spike nhỏ độc lập trong process `com.android.systemui` trước khi chuyển AppWindow: sau khi có application Context, lấy WindowManager từ context display-aware của SystemUI; tạo một `View` trống; gọi `addView()` với `TYPE_APPLICATION_OVERLAY`, width/height pixel dương rõ ràng và flags phù hợp cho cửa sổ không focus; xác nhận view thực sự attach/hiện trên màn hình; sau đó gọi `removeView()` trong `finally`. Ghi log process/UID, context nguồn, window type/flags và exception đầy đủ. Không tính việc build thành công hay gọi `addView()` không ném lỗi là pass nếu window không attach/hiện.

Nếu `addView()` bị từ chối hoặc view không hiển thị, xác định nguyên nhân cụ thể (permission/AppOps, policy/token, context/window service hoặc ROM) bằng spike này. Đây là gate kiến trúc: không refactor AppWindow sang SystemUI cho đến khi overlay add/remove được chứng minh; nếu không thể cấp quyền an toàn cho SystemUI thì phải thiết kế lại ownership/window host, không né lỗi bằng cách lén giữ overlay trong system_server.

### AIDL contract (chỉ sau khi bước 0 và 0b qua)

```aidl
long registerSystemUiClient(ISystemUiCallback callback);
void unregisterSystemUiClient(long clientId);

int createVirtualDisplay(long clientId, in Surface surface,
                         int width, int height, int densityDpi, int flags);
void setVirtualDisplaySurface(long clientId, int displayId, in Surface surface);
void clearVirtualDisplaySurface(long clientId, int displayId);
void resizeVirtualDisplay(long clientId, int displayId,
                          int width, int height, int densityDpi);
void releaseWindow(long clientId, int displayId);

void startActivityOnDisplay(long clientId, int displayId,
                            String componentName, int userId);
void moveTaskToDisplay(long clientId, int taskId, int displayId);
void moveTaskToDefaultDisplay(long clientId, int taskId);
void closeTask(long clientId, int taskId);
void launchStartCommand(long clientId, int displayId,
                        String componentName, int userId, int taskId);
void setDisplayImePolicy(long clientId, int displayId, int policy);
oneway void injectMotionEvent(long clientId, int displayId, in MotionEvent event);
oneway void injectBackKey(long clientId, int displayId);
```

Import `android.view.Surface` và `android.view.MotionEvent` trong AIDL. Không bắt đầu triển khai hợp đồng này trước khi probe bước 0 pass.

Callback từ system_server tới SystemUI, tất cả `oneway` để callback không block system_server:

```aidl
oneway interface ISystemUiCallback {
    void onCreateWindow(long requestId, String componentName, int userId, int taskId);
    void onTaskStateChanged(int displayId, int taskId, boolean visible);
    void onTaskClosed(int displayId, int taskId);
    void onDisplayError(long requestId, int displayId, int errorCode, String message);
}
```

`taskId == 0` và `userId == -1` biểu thị giá trị không có; component rỗng/null là không yêu cầu start activity. `launchStartCommand` mang đủ ba trường của `StartCmd` (`componentName`, `userId`, `taskId`) và backend áp dụng `Config.windowfy` cùng logic `StartCmd.startAuto` hiện tại để chọn move/start/fallback. API app cũ như `createWindow()`/`createWindowUserspace(AppInfo)` vẫn được gọi từ APK; system_server gửi `onCreateWindow` đến SystemUI client đã đăng ký. Trả lỗi rõ ràng/log nếu SystemUI chưa đăng ký, không âm thầm tạo UI trong system_server.

Quy tắc sở hữu và quyền: mỗi `clientId` gắn với đúng Binder callback đã đăng ký; mỗi `displayId` thuộc một client. Ngoài kiểm tra client sở hữu display, backend phải xác thực Binder caller UID/package là `com.android.systemui` cho mọi method đặc quyền; `clientId` không phải credential và không đủ để cấp quyền. Giữ API app hiện có riêng, không cho app chính gọi trực tiếp các method lõi. `createVirtualDisplay` nhận `flags` để giữ hợp đồng config nhưng backend chỉ áp dụng giá trị cấu hình đã xác thực; không tự OR cờ khác.

### Surface và MotionEvent: vòng đời / độ trễ

- SystemUI chỉ gọi `createVirtualDisplay` sau `onSurfaceTextureAvailable` với `width/height > 0`; `Surface(surfaceTexture)` tạo wrapper phía client và được giữ tham chiếu đến khi detach/release. Khi TextureView bị destroy, gọi `clearVirtualDisplaySurface(clientId, displayId)`, rồi `Surface.release()` ở SystemUI. Không release wrapper ngay sau khi truyền binder.
- Binder giải mã thành Surface wrapper riêng trong system_server. Backend giữ reference hiện hành, gọi `VirtualDisplay.setSurface`; khi thay Surface, clear hoặc release display, detach Surface khỏi VD trước rồi release wrapper server-side. Release mỗi wrapper đúng một lần; release VD khi đóng window/client chết. Không gọi `SurfaceTexture.release()` từ system_server.
- Resize gọi một API đồng bộ ngắn để thứ tự commit/layout rõ ràng; chỉ sau ACTION_UP phía SystemUI. Không truyền Surface mới cho mỗi MOVE.
- `MotionEvent` được gửi `oneway`: tránh block luồng UI mỗi frame. Binder giữ thứ tự transaction của cùng callback Binder; system_server xử lý tuần tự trong handler/queue riêng, clone/chuẩn hóa event ở phía nhận, đặt displayId, gọi `IInputManager.injectInputEvent(event, 0)`, rồi recycle event clone sau khi lời gọi hoàn tất. Không giữ event Binder-parcelled qua callback. Không coalesce/drop ACTION_DOWN/UP/CANCEL; nếu sau này cần tối ưu MOVE thì chỉ sau đo đạc và phải bảo toàn gesture.
- `KEYCODE_BACK`: gửi `oneway injectBackKey`; system_server tạo DOWN rồi UP với displayId đích và inject theo thứ tự. InputManager vẫn chỉ ở system_server.

## 3. Hook, kết nối Binder và DeathRecipient

### Kết nối theo cơ chế hiện có

Không dựa vào `ServiceManager.addService` tên tùy chọn (có thể bị SELinux service_contexts từ chối), cũng không phụ thuộc app đã được người dùng mở tay.

1. Giữ `UserService` UID observer trong system_server theo dõi UID `com.mja.reyamf`. Khi app process được đánh thức bởi handshake SystemUI, observer hiện có lấy provider external và đưa Binder `YAMFManager` qua `Bundle("binder")` như hiện tại.
2. Nâng `ServiceProvider.call()` thành handshake theo method riêng: nhánh inject hiện tại chỉ nhận caller `android`; caller `com.android.systemui` đã xác thực UID/package được trả Binder đang lưu ở `YAMFManagerProxy.asBinder()`. Trả Binder nếu đã nhận; nếu chưa sẵn sàng trả trạng thái not-ready để retry. Phân biệt lỗi quyền với not-ready.
3. Đảm bảo app process được khởi động ngay cả khi chưa có ai mở UI app: SystemUI gọi `ContentResolver.call()` tới provider `com.mja.reyamf.ServiceProvider` từ worker thread. Provider/process start làm UID observer kích hoạt; sau đó SystemUI retry handshake đến khi Binder có mặt. Không giả định chỉ gọi provider sẽ đánh thức một UID observer chưa hề đăng ký; `UserService.register()` phải được cài đặt ngay khi system_server setup để đường này bootstrap được.
4. Khi Binder nhận được, SystemUI tạo `IYAMFManager.Stub.asInterface`, gắn `linkToDeath` ở client, gọi `registerSystemUiClient(callback)` và giữ `clientId`. Sau Binder death/`RemoteException`, xóa clientId/displayId cũ, reconnect provider và đăng ký callback Binder mới. Chỉ báo kết nối hoàn tất sau clientId hợp lệ; một state machine/worker duy nhất tránh đua reconnect.
5. Provider exported hiện có (`AndroidManifest.xml`) không được biến thành API mở: xác thực UID thực (`Binder.getCallingUid()`) cùng calling package qua PackageManager, chỉ cho phép Android/SystemUI và từ chối app khác. Giữ relay Binder cũ cho caller `android` không đổi.

### Đăng ký callback và dọn dẹp khi SystemUI chết

- `registerSystemUiClient(callback)` trong system_server tạo record client, gọi `callback.asBinder().linkToDeath(deathRecipient, 0)` trước khi trả id; nếu link thất bại thì registration thất bại. Registration lặp lại cùng Binder phải idempotent hoặc trả client id hiện có; Binder callback cũ không được dùng lại sau death.
- Record client chứa callback Binder/death recipient, display IDs, listener registrations, Surface đang giữ và window/task metadata. `linkToDeath` là bắt buộc, kể cả khi SystemUI có gọi unregister.
- `binderDied`: chuyển cleanup sang handler tuần tự của backend, đánh dấu client chết trước khi nhận thêm callback; detach Surface khỏi mọi VirtualDisplay, release mọi VirtualDisplay của client đó, xóa mapping display/task, unregister `TaskStackListener`/`RotationWatcher`/`DisplayListener` đã đăng ký, rồi xóa death recipient/record. Mỗi bước best-effort để một lỗi không ngăn cleanup còn lại. Cleanup phải idempotent khi cạnh tranh với `releaseWindow`.
- Listener đăng ký một lần theo nhu cầu, với reference count hoặc record sở hữu rõ ràng. Sau khi đóng task/cửa sổ cuối cùng thì unregister ngay; khi client chết thì xóa mọi listener của client, không giữ listener suốt vòng đời system_server.
- `onTaskRemovalStarted`/display removal trong system_server cập nhật callback (`onTaskClosed` / `onTaskStateChanged`) và dọn cửa sổ liên quan. Callback lỗi được xem là client chết và kích hoạt cleanup. SystemUI gọi `onDestroy` tối đa một lần; cleanup phía server vẫn là authoritative.

### Phân công triển khai

- `HookSystem`: nạp hook SystemUI trong scope riêng; setup `Instances`, `YAMFManager.systemReady()` và `UserService` chỉ trong nhánh `android`; nhánh SystemUI chỉ khởi động connector.
- `YAMFManager`: giữ Binder endpoint. Chuyển `createWindow(StartCmd?)` thành `callback.onCreateWindow`; backend xử lý create/resize/release/task/input/IME. Không giữ đường dự phòng tạo `AppWindow` trong system_server.
- Đăng ký `TaskStackListener`/`DisplayListener` sau khi tạo VD, theo dõi theo display/client và unregister khi close, task removal, display removal hoặc callback death.

## 4. Chuyển AppWindow sang SystemUI; Resources và Theme

### Thay đổi API ở AppWindow

- Chuyển các imports và truy cập trực tiếp `DisplayManager`, `VirtualDisplay`, `ActivityTaskManager`, `IWindowManager`, `Instances`, `YAMFManager` ra khỏi `AppWindow`. Tiêm Binder client/adapter và `clientId`; mọi thao tác đặc quyền gọi AIDL.
- `onSurfaceTextureAvailable`: giữ guard size/destroyed; tạo Surface phía SystemUI, gọi `createVirtualDisplay(clientId, surface, w, h, dpi, flags=1164)`, nhận displayId. Backend áp dụng `setDisplayImePolicy` từ config hiện tại trước khi start task; không expose policy tùy ý cho client.
- `onSurfaceTextureDestroyed`: gọi `clearVirtualDisplaySurface` phía server; release Surface client đúng lúc; VD chỉ release khi close/task close/SystemUI chết, không release ngầm khi TextureView tạm thời recreate.
- `applyVirtualDisplaySize`: chỉ được gọi sau commit ACTION_UP; setDefaultBufferSize trên SurfaceTexture local, gọi resize AIDL một lần khi kích thước thực khác kích thước đã commit. `onSurfaceTextureSizeChanged` tiếp tục không resize.
- `sendBackKey` và `forwardMotionEvent` chỉ gọi AIDL; event clone/recycle ở mỗi phía đúng ownership. Task close/fullscreen gọi method AIDL; fullscreen là `moveTaskToDefaultDisplay(taskId)` rồi cleanup window, đóng là close task/window theo đúng hành vi cũ.
- `DisplayListener` và `TaskStackListener` thuộc system_server. SystemUI nhận callback trạng thái và cập nhật/dọn UI, không tự đăng ký listener privileged.

### Context, APK Resources, Material theme

`CommonContextWrapper.createAppCompatContext(systemUiContext.createContext())` hiện được gọi từ system_server (`YAMFManager.kt`); không sao chép nguyên lời gọi vào SystemUI vì ở đó không có `Instances.systemUiContext` của system_server.

Phương án mặc định trong SystemUI:
1. Trong nhánh hook `com.android.systemui`, khởi tạo EzXHelper và cài hook vào `com.android.systemui.SystemUIApplication.onCreate`; chỉ sau `onCreate` mới lấy application Context. Tạo display-aware context cho `Display.DEFAULT_DISPLAY` bằng `createDisplayContext()`, dùng context đó làm base cho cả WindowManager và density/metrics cửa sổ.
2. Tạo trực tiếp `CommonContextWrapper(displayContext, R.style.Base_Theme_Reyamf)` — không gọi factory `createAppCompatContext()`, vì factory có thể nhận diện theme của SystemUI là AppCompat và trả về base mà chưa cài module asset path. Constructor hiện có gọi `EzXHelperInit.addModuleAssetPath(getResources())`, áp theme reYAMF, và cung cấp classloader module đã patch.
3. Dùng wrapper này cho `LayoutInflater.from(themeContext)`/`WindowAppBinding.inflate(...)`. Xác nhận `MaterialCardView` được nạp từ dependency Material của module; `R.layout`, `R.id`, `R.drawable`, `R.string`, `?attr/colorSurface`/`colorOnSurface` resolve từ APK `com.mja.reyamf`. Không dùng package context của SystemUI để lookup resource id của reYAMF.
4. Giữ WindowManager gắn với display-aware SystemUI context (hoặc lấy service từ wrapper nếu xác nhận cùng window context); không dùng resource-only package context để add overlay. Dùng một nguồn density duy nhất cho dp→px, kích thước TextureView/LayoutParams và tính DPI.
5. Nếu `addModuleAssetPath()`/classloader không hoạt động trong SystemUI ở API/ROM đích, đó là blocker cần giải quyết trước chuyển UI: thử `createPackageContext("com.mja.reyamf", Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)` làm resource base, nhưng vẫn phải giữ display-aware SystemUI context cho WindowManager. Không fallback âm thầm sang resources SystemUI.
6. Smoke test bắt buộc trên thiết bị trong process `com.android.systemui`: inflate layout, kiểm tra MaterialCardView/theme attr/dp, xác nhận không có `Resources.NotFoundException`, `InflateException` hay lỗi Material theme. `CommonContextWrapper` đang dùng trong system_server chưa tự chứng minh việc thêm module assets hoạt động trong SystemUI.

Giữ cấu trúc layout: MaterialCardView → vertical LinearLayout → contentFrame (TextureView là child đầu tiên; handle/menu/corners sau đó) → bottomBar là sibling row riêng. `?attr/colorSurface`, dp và `MaterialCardView` của `window_app.xml` không được thay bằng theme/style khác để né lỗi resource.

## 5. Bất biến và checklist nghiệm thu

### Bất biến Phase 1 (không được phá)

- Flags VirtualDisplay là `1164` (không thêm `SHOULD_SHOW_SYSTEM_DECORATIONS`, không OR cờ khác). Giữ cấu hình giá trị hiện tại khi truyền qua Binder.
- `WindowManager.LayoutParams.width/height` luôn là pixel dương rõ ràng; cấm `WRAP_CONTENT` cho kích thước window.
- Resize MOVE chỉ update LayoutParams; chỉ commit `VirtualDisplay.resize` và buffer size sau `ACTION_UP`/commit resize (không resize trong MOVE, `onSurfaceTextureSizeChanged` hay rotation callback).
- Listener top/bottom tiếp tục tạo bằng `createTopHandleListener()`/`createBottomBarListener()` khi khởi tạo; không dùng `by lazy` khai báo sau `init` gây listener null. Giữ z-order, thứ tự TextureView/controls, bottomBar là sibling row, destroyed guard và teardown idempotent.
- Giữ logic DPI trừ `reduceDPI` đúng một lần và dùng cùng metrics source. Không tạo VirtualDisplay thứ hai khi SurfaceTexture recreate; reattach Surface cho display hiện tại.
- Thêm `com.android.systemui` vào `xposed_scope`, không xóa scopes hiện tại. Chỉ SystemUI nhận UI hook; không để UI inflate trong system_server hoặc app chính.

### Nghiệm thu implementation

1. Hoàn thành probe AIDL bước 0 và spike overlay bước 0b song song, dùng đúng toolchain repo (`compileSdk = 36`); cả hai phải pass trước khi refactor. Nếu AIDL fail thì chốt contract thay thế; nếu overlay fail thì dừng và thiết kế lại ownership/window host. Không có gate nâng SDK 37.
2. Build/cài module; vào LSPosed Manager bật thủ công cả scope `android` và `com.android.systemui` (xác nhận scope SystemUI đã enable sau cập nhật APK), sau đó full reboot để LSPosed nạp hook mới trong cả hai process. Không coi chỉ thêm package vào `xposed_scope` là đã kích hoạt hook. Xác nhận Binder handshake, callback registration, mở cửa sổ và app launch vào đúng display.
3. Đo kiểm: flags=1164; VD dimension/dpi khớp; chuyển task fullscreen về `Display.DEFAULT_DISPLAY`; đóng task; IME policy; back/motion đến đúng display; mọi input event theo thứ tự.
4. Kéo resize liên tục: trong MOVE chỉ LayoutParams thay đổi; đúng một commit VD resize sau ACTION_UP; test cancel không resize sai. Kiểm chứng bất biến bằng review/test hiện có hoặc kiểm tra thủ công; repo hiện không có `scripts/verify_window_app.py` hay thư mục `scripts/`, vì vậy không phụ thuộc script đó trừ khi nó được thêm thành deliverable riêng.
5. Test cleanup chủ động (close window, task tự đóng, display removed) rồi kiểm tra không còn VD/listener/task mapping mồ côi. Test nhiều cửa sổ để xác nhận ownership và listener cleanup không ảnh hưởng client khác.
6. Với cửa sổ đang mở, chạy `killall com.android.systemui`; xác nhận system_server nhận DeathRecipient, release mọi VD/Surface và unregister listeners, không treo/crash system_server. SystemUI tự khởi động; connector phải lấy Binder lại qua handshake provider, đăng ký callback mới, mở cửa sổ mới và thao tác gesture/input/resize được mà không reboot. Lặp kill/reconnect vài lần; xác nhận số VD/listener không tăng.
7. Kiểm tra logcat/SystemUI/system_server không có `Resources.NotFoundException`, `InflateException`, lỗi Material theme, `DeadObjectException` không được xử lý hoặc lỗi linkToDeath; xem dumpsys display/activity/window để xác nhận không còn display/client cũ.
