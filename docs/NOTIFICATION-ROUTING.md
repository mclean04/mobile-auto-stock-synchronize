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
Token rotation cập nhật token_hash; logout luôn gỡ thiết bị khỏi backend.
Bản debug giữ token FCM của installation để tái sử dụng khi test trên Firebase Console;
bản release vẫn xóa token trên Android. Đăng nhập lại đăng ký thiết bị/token hiện tại
với UID đang đăng nhập. Token FCM này không phải debug token của Firebase App Check.
Firebase SDK vẫn có thể tự đổi token; không cần nhập lại trên Console chỉ vì đăng xuất
debug, nhưng đổi thiết bị hoặc token thực sự thay đổi thì dùng token hiện tại.
Thiết bị legacy chưa gắn UID bị loại khỏi lượt gửi cho đến khi đăng ký lại hợp lệ.

Payload FCM là data-only, chứa target_uid của phiên nhận và event_id, không chứa lệnh mua/bán,
số tài khoản hay lý do giao dịch. Android kiểm tra target_uid trùng UID hiện tại, rồi dùng
WorkManager tải sự kiện qua backend, lưu local, hiển thị thông báo và gửi RECEIVED.
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

## Android: danh sách và điều hướng

- Tab Thông báo quan sát Room theo UID, hiển thị nội dung đã lưu; không có nút Cập nhật.
- Khi có push planning, worker tải sự kiện cùng tất cả các trang danh sách và upsert
  từng event trong cache mã hóa. Trùng event không tạo thêm hàng. Mở app cũng tự đồng bộ
  để bù push bị bỏ lỡ. Khi mất mạng, công việc chờ mạng và danh sách local vẫn hiển thị.
- Bấm notification hệ thống mở màn hình danh sách thông báo (ngoài thanh điều hướng dưới); bấm sự kiện trong danh sách đọc cache,
  đánh dấu mở trên máy và gửi OPENED qua WorkManager. Nếu chưa có cache thì tải chi tiết.
- Nội dung local là lần đồng bộ gần nhất, không phải xác nhận đặt/hủy lệnh. Worker OPENED
  vẫn tải lại trạng thái hiện tại qua backend. Giao diện dùng nội dung, thời gian, trạng thái
  thay cho event ID, revision ID và JSON kỹ thuật.
- Notification từ Firebase Console ở foreground được lưu local và hiển thị bằng icon
  riêng, rồi yêu cầu đồng bộ danh sách backend. Ở background, FCM tự hiển thị notification;
  bấm mở màn hình danh sách thông báo (ngoài thanh điều hướng dưới). Console không cung cấp API lịch sử và không gọi onMessageReceived
  ở background, nên không thể lưu đầy đủ nội dung Console vào DB bằng luồng này. Dùng
  push data-only của backend cho lịch sử planning đồng nhất ở cả foreground/background.

### Kiểm tra foreground và token Firebase Console

Firebase Console giữ “Recently Used” token thủ công; token có thể đổi dù vẫn giữ phiên
Google. Khi test, dùng Cài đặt → Sao chép token FCM hiện tại và chọn token đó trên Console.
Backend tiếp tục đăng ký/cập nhật token tự động theo UID khi khôi phục phiên/onNewToken.

Console foreground hiển thị thông báo hệ thống ngay trong onMessageReceived; worker
lưu Room và làm mới inbox. Sau khi lưu, app hiển thị banner có tiêu đề/nội dung và nút
Xem nội dung khi Activity đang hoạt động. Mức kênh/âm thanh do người dùng quản lý;
không tạo kênh mới để vượt qua lựa chọn tắt thông báo. Event backend được hiển thị
sau khi tải và xác minh nội dung, trước khi làm mới tất cả trang thông báo; lỗi làm
mới danh sách không ngăn hiển thị event đã tải được.
