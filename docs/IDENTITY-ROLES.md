# Vai trò tài khoản và quyền Android

## Quy ước của chủ dự án

Android chấp nhận mọi tài khoản Google đã được Firebase xác minh. Mỗi tài khoản thường chỉ
được gửi dữ liệu và xem dữ liệu của chính mình. Không có quyền xem planning chung,
đăng ký nhận thông báo planning chung, quản lý thiết bị khác hay gọi API quản trị.

Backend/Google Cloud, Google Drive và ChatGPT là ba vai trò độc lập. Chủ dự án hiện
cho phép cả ba dùng trananh1498@gmail.com. Email Android không thay đổi project,
chủ namespace dữ liệu, service account, file Sheet đích hay tài khoản ChatGPT.

## Xác thực và lưu trữ

- Firebase ID token được kiểm tra chữ ký, issuer và project bằng Firebase Admin SDK.
  Email phải được xác minh và sign_in_provider phải là google.com.
- App Check vẫn bắt buộc theo cấu hình, và app ID phải khớp.
- UID lấy từ token đã xác minh chọn vùng dữ liệu:
  planning_owners/{owner-hash}/mobile_uploaders/{uid-hash}/...
  Không lấy UID chủ dữ liệu từ body hoặc tham số do client tự khai.
- Batch, order, cursor và mọi truy vấn mobile được giới hạn vào vùng UID đó.
  Hai UID có thể dùng cùng batch ID hoặc broker order ID mà không đọc/ghi vùng của nhau.
- OWNER_EMAIL chọn chủ namespace/hệ thống đích. Không phải danh sách email Android.
- google_sub là định danh Google cũ; firebase_uid ở tài liệu owner chỉ còn giúp nhận diện
  nguồn dữ liệu legacy khi projection, không ràng buộc tất cả người dùng mobile vào một UID.

## API

Android thường dùng /v1/sync/status, /v1/sync/batches (POST/GET/detail), /v1/orders (GET/detail).
POST /v1/sync/retry chỉ yêu cầu backend thử ghi lại dữ liệu đã lưu; client không chọn file,
range hoặc nội dung planning để chỉnh sửa.

API planning, Sheets reconcile quản trị, notifications, notification plans/receipts,
device registrations và preview từ chối phiên Firebase mobile thường với admin_required.
Automation token/legacy operator authentication và Scheduler OIDC giữ cơ chế riêng.

## Sheet chung và lịch sử cũ

Upload vẫn lưu vào Firestore cùng project. Backend tổng hợp các vùng UID và dữ liệu legacy,
dùng một lease chung để cập nhật đúng file Sheet đã cấu hình khi BACKEND_SHEET_WRITES=enabled.
Không cấp quyền Drive hoặc token của chủ hệ thống cho Android.

Nếu hai nguồn UID khác nhau trùng account/order ID, projection giữ trạng thái pending,
không ghi đè Sheet. Bản ghi legacy và bản gửi lại cùng UID được hợp nhất theo phiên bản nguồn.
Dữ liệu cũ không bị xóa; không tự phân phối dữ liệu legacy chung cho tài khoản mới.
Lần xác minh đầu tiên sau nâng cấp làm mới baseline DNSE để lần sync tiếp theo gửi snapshot
vào vùng riêng. Khóa DNSE và hàng đợi đang chờ vẫn được giữ.

Android thường không hiển thị planning/thông báo chung. Subscription FCM cũ được yêu cầu thu hồi
khi xác minh phiên; app bỏ qua push planning cũ. Thông báo theo từng người dùng chưa được triển khai.

## Các tài khoản không thay thế nhau

- Quản trị Cloud: tài khoản người vận hành và IAM.
- Backend chạy: runtime service account với quyền Firestore/FCM/Sheet được cấp riêng.
- Drive: quyền tài liệu; backend được chia sẻ file qua service account.
- Android: phiên Firebase của người gửi dữ liệu.
- ChatGPT: phiên ChatGPT và các connector/automation được cấp quyền riêng.
- FCM registration token: địa chỉ nhận thông báo, không phải token đăng nhập.
- App Check: xác minh app, không phải danh tính người dùng.
- DNSE API key/secret: chỉ xác thực với DNSE trên thiết bị.

Không ghi token, secret hoặc khóa vào log. Khi báo lỗi phải chỉ rõ bước bị lỗi.

## Admin Android

MOBILE_ADMIN_EMAIL mặc định là trananh1498@gmail.com. Sau khi Firebase ID token,
email_verified, Google sign-in provider và App Check đều hợp lệ, backend đối chiếu email
với cấu hình này và cấp role=admin. Không nhận role hoặc email do client tự khai.
Không yêu cầu đăng nhập đồng thời vào Drive/ChatGPT/Cloud Console để xác định role.
Quyền này thuộc ứng dụng planning, không cấp IAM quản trị Google Cloud hoặc phiên ChatGPT.

GET /v1/sync/status trả role để Android hiện mục Quản trị. Admin có quyền dùng API planning,
notifications, devices và preview; có thể nhập lại planning và yêu cầu ghi Sheet theo cờ hiện có.
GET /v1/admin/sources liệt kê các vùng Firebase UID và nguồn legacy, có phân trang.
GET /v1/admin/sources/{source}/records đọc mọi loại bản ghi nguồn, có phân trang.
API orders/batches/detail nhận query source chỉ cho admin; tài khoản thường bị chặn 403.
Upload của admin vẫn vào vùng UID của chính admin; không ghi nhầm vào nguồn đang xem.
Android cho admin chọn nguồn, xem mọi loại dữ liệu, planning và thông báo chung.
Subscription push chưa khôi phục; thông báo chung được đọc trực tiếp qua API trong màn hình quản trị.
BACKEND_SHEET_WRITES vẫn độc lập với role admin; thay đổi role không tự bật ghi Sheet.