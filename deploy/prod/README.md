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

Tạo file cấu hình trên VPS (workflow sẽ copy `compose.yaml`, `deploy.sh`, `*.example` vào `$DEPLOY_DIR` mỗi lần deploy, nhưng **không bao giờ** đụng tới `.env` / `app.env`):

```bash
cd <vinspeed>/pnp
cp env.example .env              # IMAGE_REGISTRY, FRONTEND_PORT
cp app.env.example app.env && chmod 600 app.env
# điền PNH_DB_PASSWORD, PNH_REDIS_PASSWORD (= REDIS_PASSWORD của infra),
# PNH_MASTER_KEY (openssl rand -base64 32 — nhớ backup), PNH_CONSOLE_BASE_URL
```

> Lần deploy đầu tiên, nếu chưa có `env.example` trên VPS, hãy chạy workflow một lần (bước upload chạy trước bước deploy), rồi tạo `.env`/`app.env` và chạy lại.

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

## 4. Vận hành

```bash
cd <vinspeed>/pnp
docker compose ps
docker compose logs -f backend
./deploy.sh <sha>                 # deploy/rollback thủ công (cần docker login ghcr.io nếu package private)
docker compose run --rm backend --spring.profiles.active=cli <command>   # provisioning CLI
```
