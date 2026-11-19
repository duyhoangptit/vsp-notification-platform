# Vault deploy secrets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Khi deploy, lấy config/secret backend PNH từ Vault (KV) và sinh `app.env`, không sửa app Spring.

**Architecture:** Script riêng `deploy/prod/vault-env.sh` login AppRole qua HTTP API (curl + jq), đọc KV,
kiểm tra, ghi `app.env` nguyên tử. `deploy.sh` gọi nó trước khi đổi `IMAGE_TAG`; thiếu file cấu hình Vault thì
dùng `app.env` sẵn có. Workflow chỉ thêm `vault-env.sh` vào danh sách scp.

**Tech Stack:** bash, curl, jq, Vault 2.2.2 (container `vault`, `http://127.0.0.1:8200`, mount `kv/`), Docker Compose.

**Spec:** `docs/superpowers/specs/2026-11-19-vault-deploy-secrets-design.md`

## Global Constraints

- Không sửa code/config Spring (`backend/**`).
- Cấu hình Vault ở `/etc/pnh/vault.env` (override bằng env `PNH_VAULT_ENV`), chứa `VAULT_ADDR`, `VAULT_ROLE_ID`,
  `VAULT_SECRET_ID`, `VAULT_KV_PATH` (vd. `kv/pnh/prod`). File phải thuộc root, mode `600`.
- Key bắt buộc khác rỗng: `PNH_DB_PASSWORD`, `PNH_REDIS_PASSWORD`, `PNH_MASTER_KEY`.
- Hỗ trợ cả KV v1 và v2 tại mount `kv/` (chưa biết version).
- Mọi lỗi Vault ⇒ thoát ≠ 0 **trước** `set_tag`; `app.env` cũ giữ nguyên.
- Không đưa secret lên dòng lệnh (`ps`): payload login và header token truyền qua stdin/file, không qua argv.

## Review Focus

- Giá trị chứa `=`, `$`, `#`, dấu cách, quote ⇒ ghi nguyên văn `KEY=value`; Compose `env_file` đọc đúng nguyên văn.
- Giá trị chứa xuống dòng hoặc key không khớp `^[A-Z_][A-Z0-9_]*$` ⇒ lỗi, không ghi file.
- Vault sealed (HTTP 503) / không kết nối được / 403 ⇒ thông báo rõ, exit 1, `app.env` không đổi.
- Lỗi giữa chừng ⇒ không còn file tạm, token đã revoke (trap EXIT).
- `vault.env` mode khác 600 ⇒ từ chối chạy.

---

### Task 1: `vault-env.sh` — sinh app.env từ Vault

**Files:**
- Create: `deploy/prod/vault-env.sh`
- Test: `deploy/prod/tests/vault-env.test.sh`

**Interfaces:**
- Produces: `vault-env.sh <vault-env-file> <output-file>` — exit 0 và ghi `<output-file>` (mode 600) khi thành công;
  exit 1 kèm thông báo `vault-env: <lý do>` trên stderr khi lỗi, `<output-file>` không đổi.

- [ ] **Step 1: Viết test** `deploy/prod/tests/vault-env.test.sh`: khởi động `hashicorp/vault:latest` dev mode
  (`-dev -dev-root-token-id=root`, port ngẫu nhiên 127.0.0.1), bật approle, mount `kv1/` (v1) và `kv2/` (v2),
  policy đọc, tạo role/secret_id; dọn container khi EXIT. Các case (mỗi case in `PASS <tên>`/`FAIL <tên>`, exit ≠0 nếu có FAIL):
  - `v2_ok`: KV v2 có 3 key bắt buộc + `PNH_X='a=b $c #d "e"'` ⇒ exit 0, file chứa đúng dòng `PNH_X=a=b $c #d "e"`, mode 600.
  - `v1_ok`: như trên với KV v1.
  - `missing_required`: thiếu `PNH_MASTER_KEY` ⇒ exit 1, output file cũ không đổi (so sha256).
  - `newline_value`: giá trị có `\n` ⇒ exit 1, file không đổi.
  - `bad_key`: key `pnh-lower` ⇒ exit 1.
  - `unreachable`: `VAULT_ADDR=http://127.0.0.1:1` ⇒ exit 1, stderr chứa `cannot reach`.
  - `bad_secret_id`: secret_id sai ⇒ exit 1, stderr chứa `login failed`.
  - `perm_644`: vault.env mode 644 ⇒ exit 1, stderr chứa `must be mode 600`.
  - `no_tmp_left`: sau mọi case không còn `<output>.tmp*`.
  - `token_revoked`: case thành công ⇒ `sys/auth/token/accessors` (root) không còn accessor của role (hoặc lookup token trả 403).
- [ ] **Step 2: Chạy test, xác nhận FAIL** — `bash deploy/prod/tests/vault-env.test.sh` ⇒ FAIL vì chưa có script.
- [ ] **Step 3: Viết `vault-env.sh`** (`set -euo pipefail`, trap EXIT: xoá tmp + revoke token):
  kiểm tra `jq`/`curl` có sẵn; mode `vault.env` = 600 (`stat -c %a`, fallback `stat -f %Lp` cho macOS);
  source file; `GET $VAULT_ADDR/v1/sys/health` — 503 ⇒ `vault is sealed`, lỗi kết nối ⇒ `cannot reach $VAULT_ADDR`;
  login `POST /v1/auth/approle/login` (body qua `--data @-`); đọc KV: thử `GET /v1/<mount>/data/<path>` (v2, lấy
  `.data.data`), 404/403 thì `GET /v1/<mount>/<path>` (v1, lấy `.data`) — `<mount>` = phần trước `/` đầu tiên của
  `VAULT_KV_PATH`; token gửi qua `-H @<file tạm 600>`; jq xuất `KEY=value` theo thứ tự key; validate key/value;
  ghi `mktemp "<output>.tmp.XXXX"`, chmod 600, `mv` đè; revoke `POST /v1/auth/token/revoke-self`.
- [ ] **Step 4: Chạy test, xác nhận PASS** — `bash deploy/prod/tests/vault-env.test.sh` ⇒ toàn `PASS`;
  `shellcheck deploy/prod/vault-env.sh deploy/prod/tests/vault-env.test.sh` ⇒ không cảnh báo.
- [ ] **Step 5: Commit** — `git add deploy/prod/vault-env.sh deploy/prod/tests/vault-env.test.sh && git commit -m "feat(deploy): render app.env from Vault KV"`

### Task 2: Nối vào deploy.sh và workflow

**Files:**
- Modify: `deploy/prod/deploy.sh` (trước dòng `PREV_TAG=...`)
- Modify: `.github/workflows/deploy.yml` (bước "Upload compose files": thêm `deploy/prod/vault-env.sh` vào scp, `chmod +x vault-env.sh` ở bước SSH)

**Interfaces:**
- Consumes: `vault-env.sh <vault-env-file> <output-file>` (Task 1).

- [ ] **Step 1: Sửa deploy.sh**: `VAULT_ENV="${PNH_VAULT_ENV:-/etc/pnh/vault.env}"`; nếu file tồn tại ⇒
  `echo "==> loading app.env from Vault"; ./vault-env.sh "$VAULT_ENV" app.env || { echo "!! Vault: deploy aborted, nothing changed"; exit 1; }`;
  không có ⇒ `echo "::warning:: $VAULT_ENV not found — using existing app.env"`. Kiểm tra `[[ -f app.env ]]` dời xuống sau bước này.
- [ ] **Step 2: Sửa deploy.yml** như mục Files.
- [ ] **Step 3: Verify** — `bash -n deploy/prod/deploy.sh && shellcheck deploy/prod/deploy.sh` sạch;
  `grep -n vault-env .github/workflows/deploy.yml` thấy cả scp và chmod.
- [ ] **Step 4: Commit** — `git commit -am "feat(deploy): load secrets from Vault before deploying"`

### Task 3: Tài liệu setup Vault

**Files:**
- Modify: `deploy/prod/README.md` (mục mới "Secrets từ Vault")
- Modify: `deploy/prod/app.env.example` (dòng đầu: file được sinh từ Vault khi có `/etc/pnh/vault.env`)
- Modify: `docs/superpowers/specs/2026-11-19-vault-deploy-secrets-design.md` (dòng CI: thêm vault-env.sh vào scp)

- [ ] **Step 1: Viết mục README** gồm lệnh chạy được nguyên văn (không placeholder dạng `<...>` trong lệnh, dùng biến shell):
  `apt install -y jq`; kiểm tra version mount (`vault secrets list -detailed | grep '^kv/'`);
  `vault auth enable approle` (bỏ qua nếu đã bật); policy `pnh-deploy` read cả `kv/data/pnh/prod` và `kv/pnh/prod`;
  `vault write auth/approle/role/pnh-deploy token_policies=pnh-deploy token_ttl=5m token_max_ttl=10m secret_id_ttl=0` (không bind CIDR: request từ host qua port-mapping Docker tới Vault mang IP gateway bridge, không phải 127.0.0.1);
  nạp secret từ `app.env` hiện có (`vault kv put kv/pnh/prod` đọc từng dòng, không in giá trị);
  tạo `/etc/pnh/vault.env` mode 600; chạy thử `./vault-env.sh /etc/pnh/vault.env /tmp/check.env && diff <(sort app.env) <(sort /tmp/check.env)`;
  đổi secret (`vault kv patch` + deploy lại / chạy lại workflow); xử lý khi sealed; rotate `secret_id`.
- [ ] **Step 2: Verify** — chạy các lệnh vault của README trên Vault dev container của test (Task 1) ⇒ thành công.
- [ ] **Step 3: Commit** — `git commit -am "docs(deploy): Vault setup for deploy-time secrets"`
