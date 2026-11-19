# Deploy production (VPS + Docker Compose + GitHub Actions)

## Luồng CI/CD

```
push main ──► ci (test backend/frontend, dependency scan)
                 │ success
                 ▼
             deploy: build 2 image ──► push GHCR ──► SSH vào VPS ──► deploy.sh <git-sha>
                                                           pull → kafka-init → up --wait
                                                           không healthy → tự rollback tag cũ
```

- Image: `ghcr.io/<owner>/<repo>/backend:<sha>` và `.../frontend:<sha>` (kèm tag `latest`).
- Frontend (nginx) phục vụ SPA và proxy `/console/api/*`, `/api/*` sang backend, nên console và API cùng một origin.
- Không chạy thêm hạ tầng nào: dùng lại các container đang chạy trên VPS qua network `vinspeed-network`:

  | Service có sẵn       | Dùng cho                                   | Cấu hình                              |
  |----------------------|--------------------------------------------|---------------------------------------|
  | `vinspeed-postgres`  | DB `pnh` (Flyway tự migrate khi khởi động) | `compose.yaml` + `app.env`            |
  | `vinspeed-redis`     | rate limit / cache (có mật khẩu)           | `PNH_REDIS_PASSWORD` trong `app.env`  |
  | `vinspeed-kafka`     | topic `pnh.notification.*` (tạo bởi `kafka-init`) | `compose.yaml`                 |
  | `vinspeed-mailpit`   | SMTP cho alert + channel Email (test)      | `app.env`, channel config trong console |
  | `vinspeed-wiremock`  | mock SMS / FCM provider                    | channel config: `http://vinspeed-wiremock:8080` |

  Frontend chỉ nằm trong network riêng `pnh-internal` (gọi backend qua alias `pnh-backend`) nên không đụng tên với app khác trên network chung.
- Rollback thủ công: Actions → **deploy** → *Run workflow* → nhập `tag` = SHA cũ (không build lại).

## 1. Chuẩn bị VPS (làm một lần)

```bash
# User deploy (đã có docker + compose v2.20+), thuộc group docker
sudo adduser --disabled-password deploy && sudo usermod -aG docker deploy
# Thư mục deploy nằm trong folder vinspeed (cạnh compose.yml của hạ tầng), ví dụ /home/deploy/vinspeed/pnp
# → đặt đúng đường dẫn tuyệt đối này vào GitHub variable DEPLOY_DIR
mkdir -p <vinspeed>/pnp   # user deploy phải có quyền ghi

# Tạo DB + user cho PNH trong Postgres dùng chung
# (Postgres có sẵn — chỉ tạo thêm DB/user riêng cho PNH)
docker exec -it vinspeed-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "CREATE USER pnh WITH PASSWORD '<mật-khẩu>';" \
  -c "CREATE DATABASE pnh OWNER pnh;"
```

Sinh SSH key riêng cho GitHub Actions (trên máy cá nhân):

```bash
ssh-keygen -t ed25519 -f pnh_deploy -N '' -C github-actions-pnh
ssh-copy-id -i pnh_deploy.pub deploy@<vps>
ssh-keyscan -p 22 <vps>      # → giá trị cho secret VPS_KNOWN_HOSTS
```

Tạo file cấu hình trên VPS (workflow chỉ copy `compose.yaml`, `deploy.sh` và `vault-env.sh` vào `$DEPLOY_DIR` mỗi lần deploy, nhưng **không bao giờ** đụng tới `.env` / `app.env`):

```bash
cd <vinspeed>/pnp
cp env.example .env              # IMAGE_REGISTRY, FRONTEND_PORT
cp app.env.example app.env && chmod 600 app.env
# điền PNH_DB_PASSWORD, PNH_REDIS_PASSWORD (= REDIS_PASSWORD của infra),
# PNH_MASTER_KEY (openssl rand -base64 32 — nhớ backup), PNH_CONSOLE_BASE_URL
```

> Dùng Vault (mục 4) thì `app.env` được sinh tự động mỗi lần deploy — chỉ cần tạo tay lần đầu.
> `env.example` / `app.env.example` chỉ là mẫu trong repo: tạo `.env` / `app.env` trên VPS một lần theo mẫu (workflow không copy chúng).

## 2. Nginx trên host (TLS)

```nginx
server {
    listen 443 ssl http2;
    server_name notification.example.com;
    # ssl_certificate ... (certbot)

    location / {
        proxy_pass http://127.0.0.1:8180;     # FRONTEND_PORT
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

`/actuator` không được public; Prometheus (`vinspeed-prometheus`) scrape được qua network chung:

```yaml
# vinspeed/docker/prometheus/prometheus.yml
  - job_name: pnh
    metrics_path: /actuator/prometheus
    static_configs: [{ targets: ['pnh-backend-1:8080'] }]
```

WireMock của infra mount `vinspeed/wiremock/`; copy stub của dự án vào đó nếu muốn dùng mock SMS/FCM:
`scp deploy/local/wiremock/mappings/*.json <vps>:<thư-mục-vinspeed>/wiremock/mappings/` rồi `docker restart vinspeed-wiremock`.

## 3. GitHub repo settings

`NVD_API_KEY` (dùng cho job `dependency-scan` của `ci`) phải là **Repository secret** (Settings → Secrets and variables → Actions → *Repository secrets*), không đặt trong environment.

**Settings → Environments → `vinspeed-uat`** (có thể bật *Required reviewers* để duyệt trước khi deploy):

| Loại     | Tên                | Giá trị                                         |
|----------|--------------------|-------------------------------------------------|
| Secret   | `VPS_HOST`         | IP / domain VPS                                 |
| Secret   | `VPS_USER`         | `deploy`                                        |
| Secret   | `VPS_SSH_KEY`      | nội dung private key `pnh_deploy`               |
| Secret   | `VPS_KNOWN_HOSTS`  | output `ssh-keyscan` (khuyến nghị)              |
| Secret   | `VPS_PORT`         | tùy chọn, mặc định 22                           |
| Secret/Variable | `DEPLOY_DIR`       | đường dẫn tuyệt đối, vd `/home/deploy/vinspeed/pnp` (mặc định `/opt/pnh`) |
| Variable | `APP_URL`          | tùy chọn, link hiển thị trên trang Actions      |

VPS pull image bằng `GITHUB_TOKEN` của chính lần chạy workflow (login → pull → logout), không cần PAT.

## 4. Secrets từ Vault (khuyến nghị)

Khi có file `/etc/pnh/vault.env`, mỗi lần deploy `deploy.sh` chạy `vault-env.sh`: login AppRole vào Vault của VPS,
đọc `kv/vinspeed/notification`, **sinh lại `app.env`** (mỗi key Vault = một biến môi trường), rồi mới `docker compose up`.
App không đổi gì. Không có file đó thì `app.env` viết tay được dùng như cũ (kèm cảnh báo).

- Vault sealed / không truy cập được / thiếu key ⇒ deploy **dừng trước khi đổi bất cứ gì**, bản đang chạy giữ nguyên.
- Giá trị mới từ Vault làm backend không healthy ⇒ `app.env` trước đó (`app.env.prev`) được khôi phục và chạy lại
  (kể cả khi deploy lại cùng tag). Vault vẫn giữ giá trị mới: sửa lại trong Vault rồi deploy lại.
- `/etc/pnh/vault.env` có nhưng user deploy không đọc được ⇒ deploy **báo lỗi**, không lặng lẽ dùng `app.env` cũ.
- VPS reboot không cần Vault: container khởi động lại với env đã có.
- Key bắt buộc: `PNH_DB_PASSWORD`, `PNH_REDIS_PASSWORD`, `PNH_MASTER_KEY`.
- Giữ cả giá trị không bí mật (`PNH_CONSOLE_BASE_URL`, SMTP...) trong Vault để có một nguồn duy nhất.

> Thứ tự: merge + deploy một lần trước (để `vault-env.sh` có mặt trong `$DEPLOY_DIR`), rồi mới setup bên dưới.

### 4.1 Setup một lần (trên VPS, copy nguyên văn)

Chạy bằng **đúng user mà GitHub Actions SSH vào** (`VPS_USER`), để file cấu hình thuộc user đó.

```bash
sudo apt install -y jq
DEPLOY_DIR=/opt/vinspeed/pnp             # ← sửa thành giá trị DEPLOY_DIR của bạn (thư mục có app.env hiện tại)
cd "$DEPLOY_DIR" || echo "!! sai DEPLOY_DIR"
umask 077                                # mọi file tạm bên dưới chỉ user này đọc được

# Root token chỉ nằm trong biến môi trường của shell này (không lưu file, không hiện trên màn hình)
read -rs -p "Vault root token: " VAULT_TOKEN; echo; export VAULT_TOKEN
v() { docker exec -i -e VAULT_TOKEN -e VAULT_ADDR=http://127.0.0.1:8200 vault vault "$@"; }

# KV version của mount kv/: cột Options "map[version:2]" = v2, trống = v1
v secrets list -detailed | awk 'NR==1 || $1=="kv/"' | cut -c1-200

# AppRole + policy chỉ đọc đúng 1 path (cả dạng v1 lẫn v2)
v auth list | grep -q '^approle/' || v auth enable approle
printf 'path "kv/data/vinspeed/notification" { capabilities = ["read"] }\npath "kv/vinspeed/notification" { capabilities = ["read"] }\n' \
  | v policy write pnh-deploy -
v write auth/approle/role/pnh-deploy token_policies=pnh-deploy token_ttl=5m token_max_ttl=10m secret_id_ttl=0

# Path đã có dữ liệu? (`kv put` bên dưới GHI ĐÈ toàn bộ path). Có key rồi thì BỎ QUA bước nạp,
# chỉ cần mỗi key đặt đúng tên biến môi trường (PNH_DB_PASSWORD, ...) — xem danh sách key:
v kv get -format=json kv/vinspeed/notification 2>/dev/null | jq -r '(.data.data // .data) | keys[]' || echo "(path trống)"

# Nạp toàn bộ app.env hiện tại vào Vault (không in giá trị), rồi xem danh sách key.
# Lấy giá trị đúng như container đang nhận (Compose đã bỏ nháy, comment...), chỉ các key có trong app.env.
KEYS=$(grep -oE '^[A-Za-z_][A-Za-z0-9_]*=' app.env | tr -d = | jq -Rsc 'split("\n") | map(select(. != ""))')
docker compose config --format json \
  | jq --argjson keys "$KEYS" '.services.backend.environment
      | with_entries(select(.key as $k | $keys | index($k)))
      | map_values((. // "") | gsub("\\$\\$"; "$"))' \
  | v kv put kv/vinspeed/notification -
v kv get -format=json kv/vinspeed/notification | jq -r '(.data.data // .data) | keys[]'

# File đăng nhập cho deploy.sh — thuộc user mà GitHub Actions SSH vào (VPS_USER), mode 600
ROLE_ID=$(v read -field=role_id auth/approle/role/pnh-deploy/role-id)
SECRET_ID=$(v write -f -field=secret_id auth/approle/role/pnh-deploy/secret-id)
sudo install -d -m 700 -o "$(id -un)" -g "$(id -gn)" /etc/pnh
( umask 077; printf 'VAULT_ADDR=http://127.0.0.1:8200\nVAULT_ROLE_ID=%s\nVAULT_SECRET_ID=%s\nVAULT_KV_PATH=kv/vinspeed/notification\n' \
    "$ROLE_ID" "$SECRET_ID" > /etc/pnh/vault.env )
unset ROLE_ID SECRET_ID

# Chạy thử: env backend nhận được từ file Vault sinh ra phải giống hệt env hiện tại
./vault-env.sh /etc/pnh/vault.env /tmp/check.env
backend_env() { docker compose --env-file .env config --format json | jq -S '.services.backend.environment'; }
backend_env > /tmp/env.now
cp app.env /tmp/app.env.bak && cp /tmp/check.env app.env && backend_env > /tmp/env.vault; cp /tmp/app.env.bak app.env
diff /tmp/env.now /tmp/env.vault > /dev/null && echo "GIỐNG NHAU" || echo "KHÁC — kiểm tra lại key trong Vault"
rm -f /tmp/check.env /tmp/env.now /tmp/env.vault /tmp/app.env.bak
unset VAULT_TOKEN
```

Lần deploy kế tiếp log sẽ có `==> loading app.env from Vault` và `vault-env: wrote N keys ...`.
Giá trị trong Vault là giá trị thật, **không** kèm nháy: `vault-env.sh` tự ghi dạng `KEY='value'` để Compose giữ
nguyên `#`, `$`, dấu cách, `"`. Không hỗ trợ (deploy báo lỗi): dấu nháy đơn `'`, xuống dòng, `\` ở cuối giá trị.

### 4.2 Đổi một secret

```bash
read -rs -p "Vault root token: " VAULT_TOKEN; echo; export VAULT_TOKEN
v() { docker exec -i -e VAULT_TOKEN -e VAULT_ADDR=http://127.0.0.1:8200 vault vault "$@"; }

# KV v2: giá trị đọc từ stdin (không lộ trên dòng lệnh / history)
read -rs -p "Giá trị mới: " NEW; echo
printf '%s' "$NEW" | v kv patch kv/vinspeed/notification PNH_DB_PASSWORD=-
unset NEW VAULT_TOKEN
# KV v1 không có patch: dùng `v kv put kv/vinspeed/notification -` với JSON đầy đủ như mục 4.1
```

Sau đó deploy lại để áp dụng: Actions → **deploy** → *Run workflow* với `tag` = SHA đang chạy
(hoặc trên VPS: `./deploy.sh "$(sed -n 's/^IMAGE_TAG=//p' .env)"`).

### 4.3 Sự cố

| Log deploy | Nguyên nhân / xử lý |
|---|---|
| `vault is sealed` | Unseal Vault (UI hoặc `docker exec -it vault vault operator unseal` ×3), chạy lại deploy |
| `cannot reach http://127.0.0.1:8200` | Container `vault` không chạy: `docker ps \| grep vault` |
| `login failed (HTTP 400)` | `secret_id` sai/đã huỷ → tạo lại (mục 4.4) |
| `no secret readable at kv/vinspeed/notification` | Chưa nạp secret, đã xoá, hoặc policy sai → `v kv get kv/vinspeed/notification` bằng root token |
| `required key ... is missing or empty` | Thêm key đó vào Vault (mục 4.2) |
| `must be mode 600` | `chmod 600 /etc/pnh/vault.env` |
| `cannot access /etc/pnh/vault.env as ...` | File/thư mục thuộc user khác: `sudo chown -R <VPS_USER>: /etc/pnh` |
| `value contains a single quote` | Đổi giá trị đó trong Vault (mục 4.2) sang giá trị không có `'` |
| `restoring the previous app.env` | Giá trị mới trong Vault sai (vd. mật khẩu DB) → sửa trong Vault, deploy lại |
| `jq is required` | `apt install -y jq` |

### 4.4 Đổi `secret_id` (khi nghi lộ)

Tạo `secret_id` mới, ghi vào `/etc/pnh/vault.env`, rồi **huỷ mọi `secret_id` khác** của role (kể cả cái bị lộ):

```bash
read -rs -p "Vault root token: " VAULT_TOKEN; echo; export VAULT_TOKEN
v() { docker exec -i -e VAULT_TOKEN -e VAULT_ADDR=http://127.0.0.1:8200 vault vault "$@"; }
umask 077

NEW_JSON=$(v write -f -format=json auth/approle/role/pnh-deploy/secret-id)
KEEP=$(jq -r .data.secret_id_accessor <<<"$NEW_JSON")
# ghi file mới bằng builtin printf (secret_id không lên dòng lệnh của tiến trình nào)
{ grep -v '^VAULT_SECRET_ID=' /etc/pnh/vault.env; printf 'VAULT_SECRET_ID=%s\n' "$(jq -r .data.secret_id <<<"$NEW_JSON")"; } \
  > /etc/pnh/vault.env.new && mv /etc/pnh/vault.env.new /etc/pnh/vault.env
unset NEW_JSON

for acc in $(v list -format=json auth/approle/role/pnh-deploy/secret-id | jq -r '.[]'); do
  [[ "$acc" == "$KEEP" ]] || v write auth/approle/role/pnh-deploy/secret-id-accessor/destroy secret_id_accessor="$acc"
done
v list auth/approle/role/pnh-deploy/secret-id      # chỉ còn 1 accessor = $KEEP
unset VAULT_TOKEN KEEP
```

Kiểm tra (phải in `wrote N keys`): `T=$(mktemp) && ./vault-env.sh /etc/pnh/vault.env "$T"; rm -f "$T"`

> Vault quản lý tập trung và kiểm soát ai đọc secret, nhưng **không** giấu secret khỏi root trên VPS
> (`app.env` và `docker inspect` vẫn thấy giá trị).

## 5. Vận hành

```bash
cd <vinspeed>/pnp
docker compose ps
docker compose logs -f backend
./deploy.sh <sha>                 # deploy/rollback thủ công (cần docker login ghcr.io nếu package private)
docker compose run --rm backend --spring.profiles.active=cli <command>   # provisioning CLI
```
