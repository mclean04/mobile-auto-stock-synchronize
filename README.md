# Mobile Auto Stock Synchronize

Ứng dụng Android Kotlin + Jetpack Compose cho luồng planning đã thống nhất.
Backend: https://github.com/mclean04/auto-stock-synchronize

## Hiện có

- Thanh điều hướng dưới: Lệnh (planning upcoming/history), Cài đặt và Quản trị cho admin. Nút chuông ở góc trên bên phải mở màn hình danh sách thông báo riêng; nút quay lại trở về tab trước đó. Bấm thông báo FCM vẫn mở màn hình thông báo và nội dung tương ứng.
- Thông tin DNSE account nằm trong Cài đặt, dưới nút Đồng bộ DNSE ngay.
- Lệnh dùng `/v1/planning/upcoming` và `/v1/planning/history`, hiển thị nguyên danh sách backend.
- Đồng bộ nằm trong Cài đặt, chung block với toggle lịch định kỳ.
- Credential Manager → Firebase Auth; App Check Play Integrity cho mỗi request backend.
- Xác nhận quyền qua API trước khi bật đồng bộ; không nhúng token automation vào APK.
- Khóa DNSE và payload Room mã hóa bằng Android Keystore; tắt backup và screenshot.
- DNSE GET phục vụ đồng bộ lịch sử, lệnh, khớp lệnh, tiền và vị thế; app cũng hỗ trợ đặt/huỷ lệnh LO có xác nhận rõ ràng.
- Upload orders/executions/positions/balances theo lô <=100 bản ghi; phí được gửi khi DNSE cung cấp.
- Giữ nguyên batch ID/payload khi thử lại và chỉ ACK sau khi backend xác nhận commit.
- WorkManager mỗi 6 giờ khi có mạng, nút đồng bộ ngay, thử lại Sheet, đăng ký FCM.
- Thông báo luôn đọc trạng thái hiện tại khi mở và không tự giao dịch. Trong Lệnh → Đang đợi, người dùng có thể đặt lệnh cổ phiếu LO thủ công qua DNSE sau khi chọn tiểu khoản/gói giao dịch, nhập giá VND, xác minh OTP và xác nhận. Chưa có nút sửa/hủy lệnh.

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
Sau khi DNSE xác nhận đặt lệnh, app gọi `POST /v1/orders/placed`. Payload ghi rõ
`environment=production` hoặc `environment=sandbox`; backend ghi vào bộ sưu tập và Sheet
tương ứng. Yêu cầu được lưu mã hoá trước khi gửi và giữ nguyên `request_id` khi thử lại,
tránh tạo bản ghi Planning trùng nếu backend đã commit nhưng phản hồi bị gián đoạn.

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

### Đặt lệnh planning thủ công

API trực tiếp DNSE: `GET /accounts`, `GET /accounts/{accountNo}/loan-packages?marketType=STOCK&symbol=...`, `POST /registration/send-email-otp`, `POST /registration/trading-token`, `POST /accounts/{accountNo}/orders?marketType=STOCK&orderCategory=NORMAL`.

Nút Đặt lệnh gửi ngay sau xác nhận cuối cùng, không tạo lệnh chờ đến ngày planning. Giá phải là VND đầy đủ (25950 nghĩa là 25.950 đồng/cổ phiếu); không dùng hệ số giá của chức năng đọc/đồng bộ. Chỉ tiểu khoản `dealAccount=true`, cổ phiếu, LO, lô lẻ 1–99 hoặc bội số của 100. DNSE kiểm tra giá hợp lệ, sức mua, chứng khoán khả dụng và các quy tắc giao dịch. Gói giao dịch phải do người dùng chọn từ dữ liệu DNSE; app không tự chọn gói vay.

Smart OTP/email OTP phải khớp phương thức đăng ký DNSE. Token chỉ giữ trong bộ nhớ của hộp thoại, cần xác minh lại nếu quá 5 phút trước khi gửi; không lưu OTP/token hay log HTTP giao dịch. Môi trường theo cài đặt DNSE hiện hành; Production đặt lệnh thật, Sandbox thử nghiệm. Luồng kiểm tra không gửi lệnh thực tế.

App kiểm tra lại planning, phiên đăng nhập, cấu hình DNSE, tiểu khoản và gói trước khi gửi. Room mã hóa lưu dấu gửi theo tài khoản Google/môi trường/ngày dự kiến/dòng sheet trước POST. Yêu cầu đã tiếp nhận hoặc không xác định kết quả không được gửi lại trên thiết bị, kể cả khởi động lại; không tự retry/redirect. HTTP 400/401/403/404/422 là từ chối và có thể xác minh OTP rồi xác nhận lại. Trạng thái chưa xác định cần kiểm tra DNSE/EntradeX; dấu gửi chỉ bảo vệ trên thiết bị này, không chống lệnh tạo từ thiết bị/app khác. Việc xóa dữ liệu hoặc đăng xuất/xóa dữ liệu sẽ xóa dấu gửi. Nhận `id` từ DNSE là tiếp nhận yêu cầu, chưa phải xác nhận khớp; Đồng bộ ngay cập nhật giao dịch thực tế lên backend. Không tự đánh dấu planning đã khớp hay ghi thực thi vào sheet.

Hợp đồng đối chiếu [SDK chính thức DNSE](https://github.com/dnse-tech/openapi-sdk/blob/main/python/dnse/api/client.py) và [hướng dẫn đặt lệnh](https://developers.dnse.com.vn/docs/guide/trading-api/trading_order/). DNSE cũng hỗ trợ `DELETE /accounts/{accountNo}/orders/{orderId}` với `trading-token`; màn hình hủy lệnh nằm ngoài thay đổi này.

### So sánh số dư với planning

Danh sách Đang đợi/Lịch sử dùng thẻ thông tin hai cột, màu xanh cho planning đặt lệnh, đỏ cho hành động/trạng thái hủy được khai báo rõ. Lịch sử không có nút đặt lệnh. Lệnh mua chỉ có nút khi số dư tiền khả dụng (`stock.availableCash` → `cash_vnd`) đã đồng bộ của ít nhất một tiểu khoản giao dịch đủ ngân sách; không cộng số dư giữa tiểu khoản, không dùng sức mua margin. Ngân sách là mức lớn nhất giữa giá × số lượng + phí dự phòng, giá trị kế hoạch + phí dự phòng, và tổng chi ngân sách. Thiếu số dư hoặc giá trị hợp lệ thì khóa lệnh mua. Các lệnh được so sánh riêng, không coi kết quả này là đủ tiền để đặt toàn bộ danh sách cùng lúc.

Nạp tiền trong DNSE rồi chọn Cài đặt → Đồng bộ ngay để cập nhật và mở lại nút. Dữ liệu số dư gắn với cấu hình key/môi trường/đơn vị giá đã đồng bộ; thay đổi cấu hình cần đồng bộ lại. Hộp thoại chỉ cho chọn tiểu khoản đủ tiền; kiểm tra lại ngân sách theo giá/số lượng thực nhập (phí dự phòng được tăng theo tỷ lệ nếu giá trị tăng). Trước POST, phải đủ tiền cả ở snapshot đã đồng bộ và số dư DNSE đọc trực tiếp mới nhất. Lệnh bán không yêu cầu số dư tiền; DNSE vẫn kiểm tra lượng cổ phiếu khả dụng và các quy tắc giao dịch.

### Bộ khóa DNSE theo môi trường

Production và Sandbox lưu riêng API key, secret và đơn vị giá trong Vault mã hóa, theo tài khoản Google. Chọn chip môi trường sẽ chọn bộ khóa tương ứng ngay; không lấy khóa của môi trường còn lại nếu chưa có. Khi có khóa, hàng hiển thị trạng thái Đang dùng khóa Production nền xanh đậm (không bấm được), hoặc nút Dùng khóa Sandbox; nút Xóa khóa [môi trường] có nền đỏ. Xóa chỉ bộ khóa đang chọn; khi chưa có khóa, hiện hai input và Lưu key. Bộ khóa cũ được chuyển nguyên vẹn sang môi trường đã lưu, không nhân bản và không làm thay đổi fingerprint số dư đã đồng bộ. Đăng xuất/xóa dữ liệu vẫn xóa cả hai bộ khóa của tài khoản. Số dư chỉ hiển thị nếu khớp cấu hình môi trường/key đã đồng bộ; đổi cấu hình cần đồng bộ lại.

### Giao diện quản trị

Quản trị chia thành Tổng quan (quyền admin, số dữ liệu đã tải, trạng thái ghi Sheet), Tài khoản (chọn tài khoản, nhóm số dư/lệnh/cổ phiếu nắm giữ/khớp lệnh) và Kế hoạch (đọc lại planning, cập nhật dữ liệu giao dịch ra Sheet, danh sách kế hoạch đã lưu). Dữ liệu hiển thị bằng nhãn tiếng Việt/Anh và ô thông tin hai cột; ưu tiên số tiền, mã cổ phiếu, số lượng, trạng thái, thời gian. UID và mã tham chiếu chỉ là thông tin phụ. Số đếm/phân nhóm áp dụng cho các bản ghi đã tải, phân trang vẫn giữ nguyên. Các thao tác dùng API hiện có, đọc lại kế hoạch vẫn yêu cầu xác nhận. Thông báo xem bằng nút chuông.

### Cache kế hoạch trên máy

Đang đợi và Lịch sử đọc Room mã hóa theo tài khoản Google trước khi xác minh backend hoàn tất, nếu phiên đã được xác minh trước đó. Đã có cache (kể cả danh sách rỗng) thì mở app/đưa app về foreground/chuyển tab không tải lại API planning. Thiếu cache thì tải một lần và lưu. Backend trả 404 được lưu thành trạng thái chưa có kế hoạch để không gọi lặp mỗi lần mở; lỗi mạng không thay cache đang có.

Mỗi tab có nút Làm mới kế hoạch; bấm từ bất kỳ tab nào cũng GET upcoming và history, lưu cả hai và cập nhật thời điểm lưu. Không tự lọc/chuyển kế hoạch giữa Đang đợi/Lịch sử theo ngày trên máy; danh sách giữ kết quả lần tải gần nhất cho tới khi làm mới. Quản trị → Làm mới và nhập planning là thao tác chủ động cập nhật cả phiên bản chung và hai danh sách. Trước khi đặt lệnh DNSE, app vẫn bắt buộc lấy upcoming mới nhất để kiểm tra tính hợp lệ; không dùng cache để bỏ qua kiểm tra trước giao dịch. Trở lại foreground chỉ khôi phục cache, không gọi refreshAll; xác minh backend và đăng ký FCM khi đăng nhập/khởi động vẫn giữ nguyên, dùng lại response syncStatus để tránh gọi trùng.

### Bố cục theo kích thước cửa sổ

- Điện thoại và cửa sổ hẹp: một cột, thanh điều hướng dưới.
- Tablet dọc: nội dung căn giữa (tối đa 840 dp), thanh điều hướng dưới gọn, danh sách lệnh/thông báo/quản trị tự chia cột khi mỗi thẻ đủ rộng.
- Tablet ngang (cửa sổ từ 900 dp, chiều cao từ 480 dp): điều hướng bên trái; Cài đặt chia hai vùng cuộn độc lập (tài khoản/kết nối/khóa DNSE và đồng bộ/dữ liệu DNSE/hàng đợi). Nội dung tối đa 1440 dp; các danh sách tự chia cột, thẻ tối thiểu 340 dp.
- Bố cục dựa trên cửa sổ app nên thu gọn khi dùng chia đôi màn hình. Xoay màn hình không tự gọi lại API kế hoạch.

Navigation dùng SVG Lucide (clipboard-list, settings, shield-user), chuyển thành Android VectorDrawable; SVG gốc và giấy phép nằm trong `docs/icons/lucide`. Cả bottom navigation và navigation rail dùng cùng bộ icon.

### Kiểm thử DNSE Sandbox

Cài đặt → Kết nối DNSE → Sandbox: lưu bộ API Key/API Secret riêng. Nút **Kiểm thử đặt / huỷ lệnh Sandbox** luôn dùng host `sb-openapi.dnse.com.vn` và slot khóa Sandbox, không phụ thuộc môi trường đang chọn và không dùng khóa Production.

Chọn tiểu khoản, mã, số lượng, giá VND, tải số dư/gói giao dịch và chọn gói. Trước POST, app gọi balances/loan-packages/ppse và lấy trading token bằng OTP mô phỏng `666666`. Nút đặt lệnh có bước xem lại các thông số. Sau đó dùng nút đọc trạng thái hoặc huỷ chính lệnh thử đã tạo. HTTP status và JSON phản hồi từng bước xuất hiện trong màn hình kiểm thử, không ghi log; token, khóa và OTP được che. HTTP lỗi vẫn giữ code/message của DNSE để chẩn đoán.

Journal mã hóa trong Room theo UID và fingerprint khóa Sandbox giữ order ID qua lần mở lại. Kết quả POST chưa xác định chặn gửi lại; không tự retry. Chỉ bắt đầu bài thử mới khi lệnh cũ ở trạng thái kết thúc hoặc POST bị từ chối rõ ràng. Sandbox tự mô phỏng khớp lệnh nên huỷ có thể trả lỗi nếu lệnh đã khớp; kết quả huỷ không được suy ra chỉ từ HTTP 200, phải đọc trạng thái.

Tuỳ chọn **Huỷ ngay sau khi đặt** dùng cùng trading token để gọi DELETE ngay sau POST thành công. Trước DELETE, journal được lưu là `CANCEL_UNKNOWN`; app không tự gửi lại khi mất kết nối. Sau phản hồi huỷ, app đọc lại chi tiết lệnh và lưu trạng thái terminal. Cách này tăng cơ hội kiểm thử được nhánh huỷ trước khi vòng đời Sandbox tự chuyển lệnh sang `Filled`, nhưng máy chủ vẫn có quyền từ chối nếu mô phỏng đã hoàn tất.

Tài liệu: https://developers.dnse.com.vn/docs/guide/sandbox/ và https://developers.dnse.com.vn/docs/dnse/cancel-order/ . Chưa có khóa Sandbox thì chỉ kiểm thử giao thức bằng mock, chưa thể kết luận kết quả từ máy chủ DNSE.
