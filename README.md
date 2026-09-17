# Mobile Auto Stock Synchronize

Ứng dụng Android Kotlin + Jetpack Compose cho luồng planning đã thống nhất.
Backend: https://github.com/mclean04/auto-stock-synchronize

## Hiện có

- DNSE account, Lệnh (planning upcoming/history), Thông báo, Cài đặt và Quản trị cho admin.
- Lệnh dùng `/v1/planning/upcoming` và `/v1/planning/history`, hiển thị nguyên danh sách backend.
- Đồng bộ nằm trong Cài đặt, chung block với toggle lịch định kỳ.
- Credential Manager → Firebase Auth; App Check Play Integrity cho mỗi request backend.
- Xác nhận quyền qua API trước khi bật đồng bộ; không nhúng token automation vào APK.
- Khóa DNSE và payload Room mã hóa bằng Android Keystore; tắt backup và screenshot.
- DNSE chỉ GET: lịch sử 30 ngày, lệnh trong ngày NORMAL/STOP, khớp lệnh, tiền và vị thế.
- Upload orders/executions/positions/balances theo lô <=100 bản ghi; phí được gửi khi DNSE cung cấp.
- Giữ nguyên batch ID/payload khi thử lại và chỉ ACK sau khi backend xác nhận commit.
- WorkManager mỗi 6 giờ khi có mạng, nút đồng bộ ngay, thử lại Sheet, đăng ký FCM.
- Thông báo luôn đọc trạng thái hiện tại khi mở. App không đặt/sửa/hủy lệnh.

## Build

Mở thư mục repo này bằng Android Studio và dùng JDK 25 đi kèm Studio.
Project mẫu giữ compile/target SDK 37 và Gradle 9.6.0.

    .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

APK: app/build/outputs/apk/debug/app-debug.apk

Các public identifier Firebase được giữ trong mobile.properties cục bộ (đã gitignore).
Không điền DNSE key, automation token hoặc service-account credential vào file cấu hình build.
Khóa DNSE chỉ nhập trên thiết bị sau khi đăng nhập.

## Trạng thái tích hợp thật

Firebase Android app, SHA-1/SHA-256 và Play Integrity đã được đăng ký. Backend production
xác minh đồng thời Firebase ID token, email owner và X-Firebase-AppCheck; automation và
Scheduler giữ cơ chế xác thực riêng. Google Sign-In provider vẫn cần owner bật thủ công
trong Firebase Console vì Google không cho tạo/sửa OAuth client bằng API.

Ứng dụng đã mapping và upload orders, executions, positions và balances. Trường fee_vnd
được gửi khi phản hồi DNSE có fee/feeAmount/tradingFee/commission; nếu nguồn không cung cấp
thì gửi null, không tự ước tính phí.
Cấu hình giá mặc định là VND theo ví dụ API 2026-07-23; chỉ đổi nếu hợp đồng nguồn yêu cầu.
Sandbox không được upload vào backend production.

Xem [đối chiếu API và yêu cầu backend](docs/API-COVERAGE.md).
Room instrumented test nằm trong OutboxPersistenceTest; cần emulator/thiết bị để chạy.

## App Check cho bản debug

Cả debug và release đều dùng Play Integrity; không dùng debug token. SHA-256 là dấu
vân tay chứng chỉ ký APK (bản debug dùng `~/.android/debug.keystore`), không phải mã máy.
Đăng ký chứng chỉ trong Firebase App Check. Với APK chỉ phân phối ngoài Google Play,
cấu hình theo hướng dẫn Firebase: không yêu cầu PLAY_RECOGNIZED hoặc LICENSED,
và yêu cầu Device integrity. Đây là cấu hình cloud riêng, thay đổi provider trong APK
không tự thay đổi các điều kiện này. Thiết bị phải vượt qua Play Integrity.

FCM vẫn dùng Firebase project trong `mobile.properties`; bản debug không tự chuyển
sang môi trường thông báo khác. Backend cần chấp nhận App Check và quyền tài khoản
trước khi ứng dụng đăng ký thiết bị nhận thông báo.

### Ngôn ngữ Android

- `app/src/main/res/values/strings.xml`: toàn bộ chuỗi hiển thị mặc định tiếng Việt.
- `app/src/main/res/values-en/strings.xml`: bản dịch tiếng Anh với cùng key/tham số.
- Compose dùng `stringResource`; lỗi, worker và notification dùng `AppText` đọc cùng
  Android resources. Nội dung/tên trường nhận từ backend và DNSE không bị dịch.
- App theo ngôn ngữ Android; Android 13+ hỗ trợ chọn Việt/Anh riêng cho ứng dụng
  qua cài đặt ngôn ngữ ứng dụng, khai báo trong `xml/locales_config.xml`.
  Ngôn ngữ chưa hỗ trợ dùng tiếng Việt mặc định.
- Khi thêm chuỗi: thêm cùng key vào hai file và giữ đúng các tham số `%1$s`, `%2$s`.
  `LocalizationTest` kiểm tra bản dịch/tham số; `LanguageResourcesTest` kiểm tra
  lựa chọn Việt/Anh và fallback bằng resource Android thật.
