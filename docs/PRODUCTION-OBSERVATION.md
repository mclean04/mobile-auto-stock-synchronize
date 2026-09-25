# Android production observation

Kênh này ghi JSONL có schema cố định để BA/QA đối chiếu các lượt daily/monthly thật. Nó
không thay thế debug Logcat P1 hiện có và không thay đổi `FLAG_SECURE`, luồng DNSE hay
quyết định giao dịch của người dùng.

## Lưu trữ và giới hạn

- App-private path: `noBackupFilesDir/production-observation`.
- Tên file cố định: `observation-0.jsonl` đang ghi và tối đa ba archive.
- Mỗi file tối đa 256 KiB; tổng tối đa xấp xỉ 1 MiB.
- Retention 14 ngày, được prune khi có lần ghi hoặc export kế tiếp.
- Directory/file chỉ cho app UID đọc/ghi và nằm ngoài Android backup.
- Không tự upload, không gọi Backend/Cloud Run và không tạo lịch/worker mới.

Mỗi record tối đa 4 KiB, có UTC và thời gian hiển thị Asia/Ho_Chi_Minh, component,
action, stage, result, duration và error code đã lọc. Correlation dùng các ID đã có:
run/event/plan/action/request/broker-order, version và source ID/generation. Giá trị thiếu
là `UNKNOWN`; giá trị sai định dạng hoặc có dấu hiệu credential bị loại/redact. Schema
không có field tự do cho header, body, tài khoản, symbol, số lượng, giá, OTP hoặc key.

Các chặng được phân biệt rõ:

- Planning refresh request, server response, cache commit và source change.
- Preflight, broker request, broker acknowledgement và backend placed-order update.
- `BROKER_ACKNOWLEDGED` chỉ là broker nhận lệnh; ngay sau đó `BROKER_FILLED` được ghi
  `NOT_OBSERVED`. Chỉ planning refresh sau này có state `PARTIALLY_FILLED/FILLED` mới ghi
  fill là `OBSERVED`.
- FCM received, canonical notification fetch, displayed, user opened và receipt accepted.
- Cancel state chỉ được ghi khi planning refresh quan sát transition cancel. Mobile hiện
  không có production broker-cancel mutation; không thêm chức năng này trong thay đổi log.

Mọi lỗi ghi file bị nuốt tại boundary của logger; nó không crash/chặn action và không gọi
lại block nghiệp vụ. Chi phí mỗi record là một append đồng bộ tối đa 4 KiB cùng rotation
cục bộ khi cần; không có network overhead.

## Export chủ động

Trên debug build đã được USB-debugging authorize, BA/QA chạy:

```bash
python3 tools/export-production-observation.py \
  --transport-id <adb-transport-id> \
  --output /private/tmp/android-production-observation-<timestamp>
```

Tool dùng `run-as` đọc đúng bốn tên file cố định, kiểm tra schema trước khi ghi ra host,
tạo directory `0700`, file/manifest `0600`, không in nội dung log và không xóa nguồn.
Đây là thao tác thủ công; không có quyền Drive/IAM hoặc auto-upload. Artifact export có
correlation IDs nên chỉ BA/QA được ủy quyền trên máy phát triển đọc và phải xử lý như dữ
liệu nội bộ. Release build không hỗ trợ `run-as`; nếu chuyển sang APK release cần một
export flow được PO review riêng.

## Chuyển routing QA về production trước observation

Thiết bị Samsung hiện giữ encrypted QA notification config từ lượt registration. Theo
`PlanningRepository`, chỉ cần config còn hiện diện thì notification/registerPush sẽ đi QA
hoặc fail closed; app không fallback production. Proxy QA đã đóng, vì vậy production
notification observation chưa được phép bắt đầu với state này.

Existing clear operator:

```bash
python3 tools/configure-qa-notification.py clear --transport-id <adb-transport-id>
```

Lệnh chỉ xóa config QA local. Nó không revoke QA device record và không tự đăng ký lại
production. Không chạy cho đến khi PO release transition và bàn giao đúng Samsung/user 0.

Checklist sau khi được release:

1. Giữ evidence QA registration hiện có; xác nhận QA job vẫn không chạy.
2. Nếu cần revoke QA device, dùng endpoint/operator path được Backend/PO duyệt. Không dùng
   logout vì logout xóa phiên và DNSE data.
3. Chạy helper `clear` một lần; metadata phải cho thấy
   `qa_notification_configured=false` và `qa_notification_isolation_enabled=false`.
4. Mở app/verify session bình thường để `registerPush()` đăng ký token với production
   backend. Xác minh production device registration bằng readback được Backend cung cấp;
   không in token.
5. Chỉ sau readback production mới quan sát daily/monthly thật.

Rollback về QA cần config artifact/bearer đã duyệt, QA proxy sẵn sàng, install + register
và readback exact target. Không tự suy ra credential, không clear data và không đổi tài
khoản. Việc chuyển routing này chưa được thực hiện trong thay đổi code hiện tại.
