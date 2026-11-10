<context>
Tôi đang xây dựng và muốn nâng cấp toàn diện UI/UX cho hệ thống **Notification Platform (Nền tảng quản trị thông báo đa kênh)** hỗ trợ: Email, SMS, Push Notification, Zalo ZNS, Webhook.
- **Tech stack:** ReactJS + **TypeScript (Strict mode)** + **Mantine UI v8** (`@mantine/core`, `@mantine/hooks`, `@mantine/spotlight`, `@mantine/charts`, `@mantine/code-highlight`).
- **Người dùng mục tiêu:**
  1. **Admin / Operations:** Soạn thảo template, cấu hình Provider (SendGrid, AWS SES, Firebase, Zalo...), giám sát tỷ lệ gửi thành công/thất bại.
  2. **Developers:** Quản lý API Keys, test bắn thông báo thử nghiệm, tra cứu log và debug JSON payload/webhook lỗi.
</context>

<objective>
Hãy sử dụng skill **frontend-design** để đề xuất giải pháp cải tiến trải nghiệm người dùng (UX) và giao diện (UI) mang phong cách **"Modern Control Center"** (mật độ dữ liệu cao nhưng thoáng mắt, phân cấp thông tin rõ ràng, hỗ trợ tốt cả Light & Dark mode).
Yêu cầu:
- Tuân thủ **100% cú pháp, API và best practices của Mantine v8** (CSS Variables gốc của Mantine, `createTheme`, `AppShell`, `Drawer`, `Timeline`, `CodeHighlight`, `Spotlight`, `Tabs`, `Badge`). Tránh các cú pháp cũ của Mantine v6/v7.
- Tất cả code mẫu phải viết bằng **TypeScript chuẩn** (có định nghĩa `interface`/`type` rõ ràng, không dùng `any`).
</objective>

<core_screens_to_improve>
Hãy tập trung giải quyết 4 bài toán UI/UX trọng tâm sau:

1. **Template Editor & Multi-Channel Live Preview:**
   - **Layout Split-pane:** Cột trái là trình soạn thảo + danh sách biến động (`{{user_name}}`, `{{order_id}}`, `{{otp}}`) dạng clickable pills để chèn nhanh.
   - **Device & Channel Frame:** Cột phải mô phỏng trực quan giao diện nhận tin thực tế (Email Client, iOS/Android Push Banner, SMS Bubble, Zalo ZNS Template) kèm nút chuyển đổi nhanh **Light/Dark mode** riêng cho khung preview.

2. **Delivery Logs & Real-time Inspector (Tối ưu cho Debug):**
   - Bộ lọc nhanh kết hợp Search theo `Notification ID` / `Recipient`, MultiSelect cho `Channel` và `Status` (`Queued`, `Sent`, `Delivered`, `Bounced`, `Failed`).
   - Khi click vào một dòng log, mở **Mantine Drawer** bên phải chia làm 2 phần:
     - **Delivery Journey (Mantine Timeline):** Hiển thị từng mốc thời gian kèm độ trễ (latency) giữa các bước: *API Received -> Queue -> Provider Accepted -> Delivered/Failed*.
     - **Payload & Provider Response (Mantine CodeHighlight):** Hiển thị JSON request và response lỗi từ nhà cung cấp, có nút Copy và nút **"Replay / Retry Notification"** cho phép gửi lại ngay lập tức.

3. **Routing & Fallback Flow (Cấu hình kênh dự phòng):**
   - Thiết kế giao diện trực quan hóa quy tắc gửi tuần tự (ví dụ: *Gửi Push -> Chờ 30s nếu chưa đọc/lỗi -> Fallback sang Zalo ZNS -> Fallback sang SMS*).

4. **Global Command Palette (`@mantine/spotlight`):**
   - Cho phép nhấn `Cmd/Ctrl + K` ở bất kỳ đâu để tìm nhanh Template, dán nhanh `Notification ID` để mở thẳng Drawer chi tiết log, hoặc chuyển nhanh sang màn hình cấu hình Provider.
</core_screens_to_improve>

<deliverables>
Hãy trình bày câu trả lời theo 4 phần rõ ràng:
1. **Mantine v8 Theme Configuration (`theme.ts`):** Code TypeScript cấu hình `createTheme` chuẩn Mantine v8 (bảng màu custom tinh tế, font sans-serif hiện đại kết hợp font monospace cho ID/JSON, default radius và shadow).
2. **Layout & UX Architecture:** Cách tổ chức `AppShell` (Header, Navbar có thể thu gọn, khu vực làm việc chính) để giảm thao tác click cho Admin/Dev.
3. **Component Redesign Breakdown:** Đề xuất chi tiết cải tiến UX cho từng màn hình ở trên (đặc biệt là cách xử lý trạng thái lỗi và dữ liệu lớn).
4. **Production-Ready TSX Code (Mantine v8):** Viết code mẫu hoàn chỉnh, sạch sẽ (kèm TypeScript interfaces) cho component **`DeliveryLogDrawer.tsx`** (sử dụng `Drawer`, `Timeline`, `CodeHighlight`, `Badge`, `Group`, `Stack` chuẩn Mantine v8) để tôi có thể tích hợp ngay vào dự án.
</deliverables>
