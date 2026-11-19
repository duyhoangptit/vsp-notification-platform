# Secret từ Vault lúc deploy (không sửa app)

## Mục đích
Vault trên VPS là nguồn duy nhất của config/secret backend PNH. Đổi mật khẩu = sửa trong Vault rồi deploy lại;
không còn sửa tay `app.env`. App Spring không đổi một dòng nào.

## Goals / Non-goals
- Goal: `deploy.sh` lấy secret từ Vault, sinh `app.env`, chạy compose như cũ (rollback giữ nguyên).
- Goal: Vault sealed/không truy cập được ⇒ deploy dừng **trước khi** đổi gì, bản đang chạy không bị ảnh hưởng.
- Goal: VPS reboot không cần Vault (container giữ env đã có).
- Non-goal: tự rotate secret không cần deploy; giấu secret khỏi root trên VPS (`docker inspect` vẫn thấy).
- Anti-goal: app phụ thuộc Vault lúc khởi động; Vault phải mở ra Internet.

## Approaches
- **A (chọn):** `deploy.sh` trên VPS login AppRole → đọc KV → ghi `app.env`. Ít thay đổi nhất, Vault chỉ cần nội bộ.
- B: GitHub Actions đọc Vault (JWT/OIDC) rồi đẩy qua SSH — cần mở Vault ra Internet, secret đi qua runner.
- C: Vault Agent sidecar + entrypoint — sửa image, app lại phụ thuộc Vault mỗi lần start.

## Thiết kế
- **Dữ liệu:** KV v2, path `<mount>/vinspeed/notification`. Mỗi key đặt **đúng tên biến môi trường** (`PNH_DB_PASSWORD`,
  `PNH_REDIS_PASSWORD`, `PNH_MASTER_KEY`, `PNH_MASTER_KEY_ID`, `PNH_CONSOLE_BASE_URL`, ...) — toàn bộ nội dung
  `app.env` hiện tại chuyển vào đây, để Vault là nguồn duy nhất.
- **Quyền:** policy `pnh-deploy` chỉ `read` trên `<mount>/data/vinspeed/notification`. AppRole `pnh-deploy`: token_ttl 5m,
  secret_id không hết hạn. Thông tin đăng nhập ở `/etc/pnh/vault.env` (thuộc user deploy SSH vào — VPS_USER, chmod 600):
  `VAULT_ADDR`, `VAULT_ROLE_ID`, `VAULT_SECRET_ID`, `VAULT_KV_PATH`.
- **deploy.sh:** nếu có `/etc/pnh/vault.env` → login AppRole (curl + jq) → đọc secret → kiểm tra các key bắt buộc
  (`PNH_DB_PASSWORD`, `PNH_REDIS_PASSWORD`, `PNH_MASTER_KEY`) khác rỗng → ghi `app.env.tmp` (600) → `mv` thành
  `app.env` → revoke token. Mọi lỗi (sealed, 403, thiếu key, thiếu jq) ⇒ thoát trước `set_tag`.
  Không có `/etc/pnh/vault.env` ⇒ dùng `app.env` sẵn có + cảnh báo (để chuyển đổi dần, không gãy deploy hiện tại).
- **Giá trị** có ký tự đặc biệt: ghi `KEY='value'` (`'` → `\'`) vì Compose `env_file` mặc định cắt ở ` #`,
  thay `$VAR`, bỏ nháy; trong nháy đơn giá trị được giữ nguyên văn. Xuống dòng, `\` cuối giá trị, `\'` ⇒ báo lỗi.
- **Tài liệu:** `deploy/prod/README.md` thêm mục setup Vault (enable approle, policy, role, nạp secret, tạo
  `/etc/pnh/vault.env`), cách đổi secret, xử lý khi sealed.
- **CI:** `deploy.yml` chỉ thêm `vault-env.sh` vào bước scp/chmod (script tách riêng để test được).

## Để builder quyết
- Tên/format log, cách gọi API Vault cụ thể, cách test script (shellcheck + chạy thử với Vault dev container).
