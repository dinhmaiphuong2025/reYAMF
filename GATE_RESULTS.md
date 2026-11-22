# GATE_RESULTS — PLAN_SPLIT_SYSTEMUI bước 0 / 0b

Ngày 2026-11-22. Commit triển khai gate: `67d9462` (trên nền `16ba629`).

## Bước 0 — AIDL với `Surface` / `MotionEvent`: PASS (mức compile)

Đã thêm probe tạm vào repo, dùng đúng toolchain repo (`compileSdk = 36`, AGP 8.9.3, Kotlin 2.0.21):

- `app/src/main/aidl/com/mja/reyamf/xposed/IProbeAidl.aidl`
  - `int probeCreate(in Surface surface, int width, int height, int densityDpi, int flags)`
  - `void probeResize(int displayId, int width, int height, int densityDpi)`
  - `oneway void probeInject(in MotionEvent event, int displayId)`
  - `void probeRegister(IProbeCallback callback)`
- `app/src/main/aidl/com/mja/reyamf/xposed/IProbeCallback.aidl` — `oneway interface` với 1 method.
- `app/src/main/java/com/mja/reyamf/xposed/services/ProbeAidlImpl.kt` — implement `IProbeAidl.Stub()`, gọi `Surface.hashCode()`, `event.recycle()`, callback `onProbeEvent`.

Lệnh kiểm chứng (CI, vì container không có JDK/SDK):

```bash
git push origin main      # commit 67d9462
gh run watch 45027120561 -R dinhmaiphuong2025/reYAMF --exit-status
gh run view 45027120561 -R dinhmaiphuong2025/reYAMF --json conclusion -q .conclusion
```

Kết quả: `conclusion=success`, job `Build debug APK` chạy 1m27s, `:app:assembleDebug` thành công.
Nghĩa là AIDL generator nhận `android.view.Surface` và `android.view.MotionEvent` làm tham số `in`, callback `oneway` generate được, và Kotlin compile với stub vừa sinh.

Chưa chứng minh: marshal runtime qua Binder thật (cần thiết bị). Bước này chỉ chốt khả năng compile của contract.

## Bước 0b — spike overlay trong SystemUI: ĐÃ CÓ CODE, CHƯA CÓ KẾT QUẢ RUNTIME

Đã thêm:

- `app/src/main/java/com/mja/reyamf/xposed/hook/SystemUiOverlaySpike.kt` — hook `com.android.systemui.SystemUIApplication.onCreate`, lấy `createDisplayContext(DEFAULT_DISPLAY)`, `WindowManager.addView` với `TYPE_APPLICATION_OVERLAY`, 300×200px, đúng bộ flags Phase 1 (`LAYOUT_NO_LIMITS | NOT_FOCUSABLE | NOT_TOUCH_MODAL | HARDWARE_ACCELERATED | ALT_FOCUSABLE_IM`), chờ 1500ms, log `isAttachedToWindow/isShown/width/height/getGlobalVisibleRect`, rồi `removeView` trong `finally`-style guard. Mọi lỗi log kèm exception.
- `HookSystem.handleLoadPackage`: nhánh đầu tiên cho `com.android.systemui` chỉ gọi spike rồi `return` (không chạy setup system_server).
- `app/src/main/res/values/arrays.xml`: thêm `com.android.systemui` vào `xposed_scope`.

APK của gate: `/storage/emulated/0/Download/reYAMF-gate0-debug.apk`
sha256 `ff657e6f1adc59d03b47475edb636eb5d137ae2f01fa92baf2923b64c8157277`

### Cách lấy kết quả (cần làm trên máy)

1. `adb uninstall com.mja.reyamf` rồi `adb install reYAMF-gate0-debug.apk` (chữ ký CI đổi mỗi run).
2. Mở LSPosed Manager → module reYAMF → bật thêm scope `com.android.systemui` (giữ `android`).
3. Full reboot (soft reboot treo logo trên ROM này).
4. `adb logcat -c && adb logcat -s SPIKEOVERLAY`

Đọc kết quả:
- PASS: có `addView returned` + `RESULT attached=true shown=true` và thấy ô đỏ 300×200 ở góc trên-trái màn hình ~1.5s.
- FAIL: có `addView FAILED: <Exception>` (thường `BadTokenException`/`SecurityException`/AppOps) → dừng, thiết kế lại ownership theo mục 0b của plan, không chuyển AppWindow.

## Trạng thái sau gate

- Chưa refactor `AppWindow` sang SystemUI; chưa đổi `IYAMFManager.aidl`; `AppWindow` vẫn chạy trong system_server như `16ba629`.
- Sau khi 0b pass: xóa 3 file probe (`IProbeAidl.aidl`, `IProbeCallback.aidl`, `ProbeAidlImpl.kt`), xóa `SystemUiOverlaySpike.kt` + nhánh trong `HookSystem`, rồi mới triển khai contract thật.
- adb hiện không kết nối từ container (`adb devices` rỗng, `172.28.0.1:5555` timeout) nên phiên này không tự lấy được kết quả 0b.
