# Định tuyến thông báo riêng theo tài khoản

## Quyền và người nhận

- NotificationCommand.recipient_uid là Firebase UID chính xác của người nhận.
- Có recipient_uid: push chỉ đến thiết bị gắn UID đó; các UID khác không đọc được
  notification, notification plan hoặc ghi receipt cho sự kiện.
- Admin trananh1498@gmail.com được xem mọi sự kiện qua API/app. Quyền xem tất cả không
  tự nhân bản push cá nhân sang admin.
- Không có recipient_uid (kể cả sự kiện cũ): chỉ admin được xem và nhận push.
- Không suy ra người nhận từ email chủ project/Drive/ChatGPT hoặc từ số tài khoản DNSE.
- Mỗi plan_id giữ nguyên recipient_uid qua các phiên bản; dùng plan_id khác nếu đổi người nhận.

## Thiết bị và FCM

PUT /v1/devices/{device_id} lấy UID/role từ token Firebase đã xác minh, không từ body.
Mỗi UID tối đa 10 thiết bị; không cho chiếm device_id hoặc FCM token của UID khác.
Token rotation cập nhật token_hash; logout gỡ thiết bị và xóa FCM token trên Android.
Thiết bị legacy chưa gắn UID bị loại khỏi lượt gửi cho đến khi đăng ký lại hợp lệ.

Payload FCM là data-only, chứa target_uid của phiên nhận và event_id, không chứa lệnh mua/bán,
số tài khoản hay lý do giao dịch. Android kiểm tra target_uid trùng UID hiện tại, rồi dùng
WorkManager tải sự kiện qua backend và ghi RECEIVED trước khi hiển thị thông báo.
Backend tiếp tục kiểm tra quyền người nhận tại thời điểm đọc. OPENED cũng kiểm tra quyền
và quyền sở hữu thiết bị. Không đọc snapshot planning chung để mở thông báo cá nhân.
Thông báo hiển thị cần quyền notification của Android; hệ điều hành có thể trì hoãn công việc nền.

## Planning từ Sheet

NOTIFICATION_OUTBOX giữ nguyên A:V hiện có. Có thể thêm cột W với tiêu đề chính xác
Recipient UID. Backend chấp nhận cả header cũ và header có thêm cột này.
Điền Firebase UID từ danh sách nguồn trong màn hình Quản trị vào cột W để nhắm người nhận.
Bỏ trống W tương đương admin-only. Không sửa người nhận trên một phiên bản đã publish;
backend kiểm tra thay đổi đầu vào/recipient trước khi ghi acknowledgement.

Không tự sửa cấu trúc Sheet trong lần cập nhật code này.
SHEET_NOTIFICATIONS_LIVE_ENABLED là cờ gửi thật riêng; triển khai phân quyền không tự bật cờ.
BACKEND_SHEET_WRITES là cờ ghi dữ liệu DNSE vào planning và cũng không tự đổi.

## Kiểm thử

Firestore emulator: A chỉ đọc/nhận A; B chỉ đọc/nhận B; admin đọc cả A/B và nhận admin-only.
Kiểm tra device ownership, token reuse, rotation, revoke, receipt chéo tài khoản,
thiết bị legacy, API admin, idempotency sự kiện cũ và Recipient UID tùy chọn.
Không gửi dữ liệu giả DNSE hoặc thông báo thử vào production trong kiểm thử này.
