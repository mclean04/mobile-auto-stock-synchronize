# Kiểm thử thông báo QA trên Android

Luồng này chỉ có trong bản `debug`. Khi chưa cài cấu hình QA, ứng dụng tiếp tục dùng
backend và FCM production như hiện tại. Khi đã cài cấu hình QA, mọi thao tác thông báo
của phiên QA bị khóa vào đúng UID, device ID, namespace và proxy cục bộ
`http://127.0.0.1:18766`; cấu hình sai sẽ dừng luồng thay vì chuyển sang production.

FCM data message QA chỉ được chấp nhận khi có đúng schema sau:

```json
{
  "schema_version": "1",
  "event_id": "<uuid>",
  "plan_id": "test-dnse-daily-YYYYMMDD-HHMM",
  "version": "1",
  "type": "PLANNING_REVIEW_REQUIRED",
  "target_uid": "<firebase-uid>",
  "test_marker": "DEMO_ONLY",
  "notification_namespace": "<qa-source>:<positive-version>"
}
```

Kế hoạch tháng dùng tiền tố `test-dnse-monthly-YYYYMMDD-HHMM`. Push không được chứa URL,
token, tài khoản DNSE, lý do giao dịch hoặc hành động đặt/hủy lệnh. App luôn tải nội dung
chuẩn từ QA API trước khi hiển thị. Thông báo TEST có tiền tố `[TEST]` và không có hành
động giao dịch. RECEIVED và OPENED được gửi bất đồng bộ về cùng endpoint QA.

## Chuẩn bị thiết bị

Chỉ dùng Samsung SM-X730 đã đăng nhập đúng tài khoản Firebase và đã được backend duyệt.
Lấy metadata không nhạy cảm trước:

```bash
python3 tools/configure-qa-notification.py metadata --transport-id <adb-transport-id>
```

Lệnh sẽ cho biết model, quyền thông báo, UID và device ID nếu phiên đăng nhập đã sẵn sàng.
Nếu báo `QA Firebase user is not signed in`, hãy đăng nhập trong app trước. Công cụ không
in FCM token hoặc bearer.

Sau khi QA proxy được phát hành và chạy ở máy phát triển, nối cổng cho thiết bị:

```bash
adb reverse tcp:18766 tcp:18766
```

Tạo file JSON cục bộ với đúng bốn trường sau và đặt quyền `0600`. Không commit file này:

```json
{
  "target_uid": "<firebase-uid>",
  "target_device_id": "<uuid-cua-thiet-bi>",
  "notification_namespace": "<qa-source>:<positive-version>",
  "bearer": "<qa-bearer>"
}
```

## Cài và đăng ký

```bash
chmod 600 /duong-dan/qa-notification.json
python3 tools/configure-qa-notification.py install --config /duong-dan/qa-notification.json --transport-id <adb-transport-id>
python3 tools/configure-qa-notification.py register --transport-id <adb-transport-id>
```

`install` truyền cấu hình thẳng vào instrumented helper, lưu bằng Android Keystore/Vault
trong vùng private của app rồi xóa bản rõ tạm. `register` mới thực hiện đăng ký thiết bị
với QA API. Các bước này phải chạy riêng để việc cài cấu hình không vô tình gọi mạng.

Xóa cấu hình sau khi kiểm thử:

```bash
python3 tools/configure-qa-notification.py clear --transport-id <adb-transport-id>
```

Sau khi xóa, bản debug trở lại luồng production thông thường. Không ghi FCM token, bearer,
API key DNSE hay nội dung xác thực vào log, ảnh chụp hoặc tài liệu bằng chứng.

## Phạm vi xác minh cục bộ

- Unit test kiểm tra allowlist, định tuyến daily/monthly, chống đổi UID/namespace/marker,
  không fallback QA sang production và thông báo TEST không thể thao tác.
- Instrumented isolation test dùng transport giả, không gọi mạng, để xác minh cấu hình
  mã hóa chỉ áp dụng cho đúng UID/device.
- Việc gửi FCM thật, chạy worker theo lịch, khởi tạo proxy và xác nhận RECEIVED/OPENED chỉ
  thực hiện khi QA backend, target UID và thiết bị đã sẵn sàng.
