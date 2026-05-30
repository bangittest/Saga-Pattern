# Microservices & Distributed Transactions — Demo

Hai cách xử lý transaction trải nhiều service (mỗi service một DB riêng), cùng một bài toán
**đặt hàng**: `Order → Payment → Inventory`. Không có ACID xuyên service; tính nhất quán đạt
được bằng **Saga** + **compensating transaction**.

| | `orchestration/` | `choreography/` |
|---|---|---|
| Điều phối | 1 **orchestrator** gọi REST tuần tự | Không có trung tâm — service tự nghe **event Kafka** |
| Giao tiếp | REST đồng bộ | Event bất đồng bộ (Kafka, topic `saga-events`) |
| Logic saga | Tập trung 1 chỗ (`SagaService`) | Phân tán trong từng listener |
| Ưu điểm | Dễ đọc/đổi/debug, thấy rõ toàn cảnh | Lỏng lẻo (loose coupling), service độc lập, dễ mở rộng |
| Nhược điểm | Orchestrator là điểm phụ thuộc trung tâm | Khó lần theo luồng, dễ "event spaghetti" |
| Hạ tầng | Postgres ×1 (3 DB) | Postgres ×1 (3 DB) + Kafka (KRaft) |

## Monolith vs Microservices (tóm tắt)

- **Monolith**: 1 deploy, 1 DB ⇒ transaction là **1 ACID** của DB lo hết — đơn giản.
- **Microservices**: nhiều DB ⇒ **không có ACID xuyên service** ⇒ phải dùng Saga + bù trừ,
  chấp nhận **eventual consistency**. Đổi lại được scale/độc lập triển khai từng service.

## Chạy

```bash
# Cách 1 — Orchestration (REST). Không cần Kafka.
cd orchestration && docker compose up --build
#   POST http://localhost:8080/orders   (xem orchestration/README.md)

# Cách 2 — Choreography (Kafka).
cd choreography && docker compose up --build
#   POST http://localhost:8091/orders   (xem choreography/README.md)
```

Cả hai có thể chạy song song (cổng host đã tách: orchestration 808x/5433, choreography 809x/5434).

## Khái niệm chính (áp dụng cho cả hai)

1. **Database per service** — service không đụng DB của nhau.
2. **Saga** — chuỗi local transaction; mỗi bước có một **compensating transaction** để hoàn tác.
3. **Compensation** chạy theo **thứ tự ngược** và nên **idempotent**.
4. **Eventual consistency** thay cho ACID toàn cục.
5. **Outbox pattern** (đã implement ở `choreography/`): ghi DB + phát event nguyên tử qua bảng
   `outbox` + relay → vá lỗ hổng dual-write, đảm bảo at-least-once.
6. **API Gateway** (`orchestration/api-gateway`, port 8000): một cổng vào route tới mọi service.

## Đã có sẵn

| Tính năng | Ở đâu |
|---|---|
| Saga Orchestration (REST) | `orchestration/` |
| Saga Choreography (Kafka) | `choreography/` |
| Transactional Outbox | `choreography/*/Outbox.java` |
| Idempotent consumer (Inbox) | `choreography/*/Inbox.java` (dedup theo `eventId`) |
| Retry | orchestration: `SagaService.withRetry` (4xx không retry); choreography: `DefaultErrorHandler` |
| API Gateway (Spring Cloud Gateway) | `orchestration/api-gateway/`, port **8000** |
| Auth: JWT **RS256** + BCrypt + refresh token + RBAC | `orchestration/auth-service/` + `api-gateway/AuthGatewayFilter.java` |
| Dead Letter Queue (DLQ) | `choreography/*` → topic `saga-events.DLT` |
| Distributed Tracing (Zipkin) | `orchestration/` (Micrometer + Brave), UI `:9411` |
| Test e2e tự động | `orchestration/`: `test-saga.sh` (12), `test-auth.sh` (11), `test-tracing.sh` (8); `choreography/`: `test-saga.sh` (7), `test-idempotency.sh` (2), `test-dlq.sh` (3) |

Mỗi thư mục con có README riêng kèm các lệnh `curl` demo happy-path và các đường lỗi
(để **xem compensation** chạy: hoàn tiền, trả kho, hủy đơn).