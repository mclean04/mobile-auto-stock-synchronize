# Mobile Auto Stock Synchronize

Ứng dụng Android Kotlin + Jetpack Compose cho luồng planning đã thống nhất.
Backend: https://github.com/mclean04/auto-stock-synchronize

## Hiện có

- Tổng quan, planning, thông báo, lịch sử lệnh, chi tiết lệnh/đợt gửi, phân trang.
- Credential Manager → Firebase Auth; App Check Play Integrity cho mỗi request backend.
- Xác nhận quyền qua API trước khi bật đồng bộ; không nhúng token automation vào APK.
- Khóa DNSE và payload Room mã hóa bằng Android Keystore; tắt backup và screenshot.
- DNSE chỉ GET: lịch sử 30 ngày, lệnh trong ngày NORMAL/STOP, tiền và vị thế trên máy.
- Hàng đợi upload <=100 lệnh/đợt, giữ batch ID/payload khi thử lại, chỉ ACK sau commit.
- WorkManager mỗi 6 giờ khi có mạng, nút đồng bộ ngay, thử lại Sheet, đăng ký FCM.
- Thông báo luôn đọc trạng thái hiện tại khi mở. App không đặt/sửa/hủy lệnh.

## Build

Mở thư mục repo này bằng Android Studio và dùng JDK 25 đi kèm Studio.
Project mẫu giữ compile/target SDK 37 và Gradle 9.6.0.

    .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

APK: app/build/outputs/apk/debug/app-debug.apk

Cấu hình Firebase chưa được cung cấp: APK vẫn build và mở được, nhưng nút đăng nhập sẽ
giải thích cần cấu hình. Copy mobile.properties.example thành mobile.properties, điền
các public identifier của Android Firebase app và Web OAuth client. Không điền DNSE key,
automation token hoặc service-account credential vào file cấu hình build.
Khóa DNSE chỉ nhập trên thiết bị sau khi đăng nhập.

## Trạng thái tích hợp thật

Backend đã có API nghiệp vụ nhưng phiên bản được kiểm tra đang tắt mobile Google login,
chưa xác thực Firebase ID token/App Check. Vì vậy chưa thể xác nhận login/upload production.
Cần backend hoàn thiện xác thực mobile và cấu hình Firebase/Play Integrity cho đúng package
và certificate. Không dùng automation token để vượt qua phần này.

Dữ liệu orders đã có mapping; tiền/vị thế được lưu mã hóa local. Chưa upload tự động
executions/positions/balances, chưa đối soát phí, chưa kiểm thử trực tiếp DNSE trên điện thoại.
Cấu hình giá mặc định là VND theo ví dụ API 2026-07-23; chỉ đổi nếu hợp đồng nguồn yêu cầu.
Sandbox không được upload vào backend production.

Xem [đối chiếu API và yêu cầu backend](docs/API-COVERAGE.md).
Room instrumented test nằm trong OutboxPersistenceTest; cần emulator/thiết bị để chạy.
