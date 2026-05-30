# Saga — Orchestration demo (Spring Boot + Docker)

Demo một **distributed transaction** trải trên 3 service, mỗi service có **DB riêng**, dùng
**Saga theo kiểu Orchestration**: một `orchestrator-service` điều phối tuần tự và chạy
**compensating transaction** (hành động bù trừ) khi có bước thất bại.

```
client ──▶ orchestrator-service (8080)
                │  1. tạo đơn (PENDING)        ──▶ order-service (8081)     ──▶ orderdb
                │  2. giữ kho                  ──▶ inventory-service (8083) ──▶ inventorydb
                │  3. trừ tiền                 ──▶ payment-service (8082)   ──▶ paymentdb
                │  4. xác nhận đơn (CONFIRMED) ──▶ order-service
                └─ nếu lỗi ⇒ bù trừ ngược: refund tiền → trả kho → hủy đơn (CANCELLED)
```

## Chạy

```bash
cd orchestration
docker compose up --build      # lần đầu hơi lâu (tải Maven deps)
```

Service đã sẵn sàng khi log có dòng `Started ... Application`. Kho khởi tạo: `P1=10`, `P2=5`.

> Reset về trạng thái sạch (xóa DB, seed lại kho): `docker compose down -v && docker compose up --build`

## Demo

### 1) Happy path — đơn thành công
```bash
curl -s -X POST localhost:8080/orders -H "Content-Type: application/json" \
  -d '{"customerId":"c1","productId":"P1","quantity":2,"amount":50}'
```
Kết quả: `status: CONFIRMED`, log đủ 4 bước. Kiểm chứng 3 DB:
```bash
curl -s localhost:8081/orders             # đơn ở CONFIRMED
curl -s localhost:8082/payments           # payment COMPLETED
curl -s localhost:8083/inventory/products # P1 còn 8
```

### 2) Lỗi ở bước thanh toán ⇒ xem bù trừ
```bash
curl -s -X POST localhost:8080/orders -H "Content-Type: application/json" \
  -d '{"customerId":"c1","productId":"P1","quantity":2,"amount":50,"failAt":"payment"}'
```
Kết quả: `status: CANCELLED`. Vì kho đã giữ trước rồi nên log sẽ có
`COMPENSATION: inventory released` → kho **P1 quay lại 8** (không bị hụt), đơn `CANCELLED`.

### 2b) Lỗi ở bước xác nhận (sau khi đã trừ tiền) ⇒ xem REFUND
```bash
curl -s -X POST localhost:8080/orders -H "Content-Type: application/json" \
  -d '{"customerId":"c1","productId":"P1","quantity":1,"amount":25,"failAt":"confirm"}'
```
Kết quả: `status: CANCELLED`. Đây là kịch bản duy nhất chạy **cả 2 compensation**:
`payment refunded` (payment → REFUNDED) **và** `inventory released`. Đây là điểm "xem sao"
rõ nhất — tiền được hoàn, kho được trả, đơn bị hủy.

### 3) Lỗi ở bước giữ kho ⇒ không có gì để bù tiền
```bash
curl -s -X POST localhost:8080/orders -H "Content-Type: application/json" \
  -d '{"customerId":"c1","productId":"P1","quantity":2,"amount":50,"failAt":"inventory"}'
```
Kết quả: `status: CANCELLED`, đơn bị hủy, không trừ tiền, không trừ kho.

### 4) Hết hàng (lỗi tự nhiên, không cần failAt)
```bash
curl -s -X POST localhost:8080/orders -H "Content-Type: application/json" \
  -d '{"customerId":"c1","productId":"P2","quantity":999,"amount":50}'
```

## API Gateway (cổng vào duy nhất, port 8000)

`api-gateway` (Spring Cloud Gateway) route tới mọi service qua một cổng, ẩn cổng nội bộ:
```bash
curl -X POST localhost:8000/orchestrator/orders -H "Content-Type: application/json" \
  -d '{"customerId":"c1","productId":"P1","quantity":1,"amount":20}'   # -> orchestrator
curl localhost:8000/order-service/orders          # -> order-service
curl localhost:8000/payment-service/payments      # -> payment-service
curl localhost:8000/inventory-service/inventory/products  # -> inventory-service
```
Quy tắc: `/<service>/**` → service tương ứng, filter `StripPrefix=1` bỏ segment đầu.

## Retry (lỗi tạm thời)

`SagaService.withRetry(...)` bọc mọi lời gọi downstream: lỗi **5xx / mất kết nối** được **thử lại
3 lần** (backoff tăng dần); còn lỗi **4xx** (nghiệp vụ: thanh toán bị từ chối, hết hàng) thì **không
retry** mà compensate ngay. Các compensation cũng được retry vì chúng *phải* thành công.

## Auth (JWT + RBAC tại Gateway)

`auth-service` cấp **JWT** khi login; `api-gateway` kiểm token cho mọi request (trừ `/auth`),
và chặn theo `role`. User demo: `user/password` (USER), `admin/admin123` (ADMIN).

```bash
# 1) Lấy token
TOKEN=$(curl -s -X POST localhost:8000/auth/login -H "Content-Type: application/json" \
  -d '{"username":"user","password":"password"}' | grep -o '"token":"[^"]*"' | sed 's/"token":"//;s/"//')

# 2) Gọi có token -> 200
curl -X POST localhost:8000/orchestrator/orders -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"customerId":"c1","productId":"P1","quantity":1,"amount":20}'

# 3) Không token -> 401 ; USER vào route admin (/order-service) -> 403
curl -i localhost:8000/order-service/orders -H "Authorization: Bearer $TOKEN"
```
- **Authentication**: token thiếu/hỏng/hết hạn → **401** (`AuthGatewayFilter`).
- **Authorization (RBAC)**: `/order-service`, `/payment-service`, `/inventory-service` chỉ cho **ADMIN`;
  USER → **403**. Gateway gắn `X-User`/`X-Role` xuống downstream (stateless — service không gọi lại auth).

### Nâng cấp đã có
- **RS256 (bất đối xứng)**: auth-service tự sinh cặp khoá RSA, ký bằng **private key**; gateway tải
  **public key** (`/auth/public-key`) để verify → gateway **không thể giả mạo** token. (`JwtKeys`, `PublicKeyProvider`)
- **BCrypt**: mật khẩu lưu dạng **hash** (`BCryptPasswordEncoder`), không plaintext.
- **Refresh token**: login trả `accessToken` (5 phút) + `refreshToken` (7 ngày).
  `POST /auth/refresh` đổi refresh token lấy access token mới. Refresh token **không** dùng được làm bearer.

```bash
# refresh: lấy access token mới mà không cần đăng nhập lại
RT=$(curl -s -X POST localhost:8000/auth/login -H "Content-Type: application/json" \
  -d '{"username":"user","password":"password"}' | grep -o '"refreshToken":"[^"]*"' | sed 's/.*:"//;s/"//')
curl -X POST localhost:8000/auth/refresh -H "Content-Type: application/json" -d "{\"refreshToken\":\"$RT\"}"
```

## Distributed Tracing (Zipkin)

Mỗi service gửi span về **Zipkin** (Micrometer Tracing + Brave). Một request đặt hàng tạo **một
trace** xuyên `gateway → orchestrator → order/inventory/payment`. Orchestrator dùng `RestClient`
build từ `RestClient.Builder` để **truyền trace context** xuống downstream.

```bash
# Đặt 1 đơn rồi mở UI: http://localhost:9411  (Run query -> xem timeline các span)
```

## Test tự động

```bash
bash test-saga.sh --reset   # 12 checks: saga + compensation
bash test-auth.sh           # 11 checks: RS256 + BCrypt + RBAC + refresh token
bash test-tracing.sh        # 8 checks: 1 order -> 1 trace xuyên 5 service (Zipkin)
```

## Điểm cốt lõi
- **Không có ACID xuyên service.** Mỗi bước là 1 local transaction trên DB của chính nó.
- Tính nhất quán đạt được bằng **eventual consistency** + **compensation**, không phải rollback DB.
- Compensation phải **idempotent** và chạy theo **thứ tự ngược**.
- Orchestration: logic tập trung 1 chỗ (`SagaService`) ⇒ dễ đọc, dễ debug; đổi lại orchestrator
  là điểm phụ thuộc trung tâm.

## Bước tiếp theo (đề cập, chưa làm trong demo này)
- **Outbox pattern**: đảm bảo "ghi DB" và "phát event" là nguyên tử (cho choreography qua Kafka).
- Phiên bản **Choreography** (các service tự trao đổi qua Kafka) — xem thư mục `../choreography`.