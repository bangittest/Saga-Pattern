# Saga — Choreography demo (Spring Boot + Kafka + Docker)

Cùng bài toán đặt hàng nhưng **không có orchestrator**. Mỗi service lắng nghe topic Kafka
`saga-events`, tự phản ứng và phát event tiếp theo. Tính nhất quán là **eventual**.

```
order ─ORDER_CREATED─▶ payment ─PAYMENT_COMPLETED─▶ inventory ─INVENTORY_RESERVED─▶ order=CONFIRMED
                              └─PAYMENT_FAILED──────────────────────────────────▶ order=CANCELLED
                       inventory ─INVENTORY_FAILED─▶ payment(refund) ─PAYMENT_REFUNDED─▶ order=CANCELLED
```

Mỗi service là một **consumer group** riêng (`order`, `payment`, `inventory`) đọc chung 1 topic
và lọc theo `type` của event.

## Chạy

```bash
cd choreography
docker compose up --build      # có Kafka (KRaft) nên khởi động lâu hơn orchestration
```

Cổng host: order **8091**, payment **8092**, inventory **8093**, postgres **5434**.
Kho khởi tạo: `P1=10`, `P2=5`.

> Reset về trạng thái sạch (xóa DB, seed lại kho): `docker compose down -v && docker compose up --build`

## Demo

Vì xử lý **bất đồng bộ**, POST trả về ngay với `status: PENDING`. Đợi ~1s rồi GET để xem kết quả cuối.

### 1) Happy path
```bash
curl -s -X POST localhost:8091/orders -H "Content-Type: application/json" \
  -d '{"customerId":"c1","productId":"P1","quantity":2,"amount":50}'
sleep 2
curl -s localhost:8091/orders             # đơn -> CONFIRMED
curl -s localhost:8093/inventory/products # P1 -> 8
```

### 2) Lỗi thanh toán (PAYMENT_FAILED → CANCELLED, chưa trừ tiền/kho)
```bash
curl -s -X POST localhost:8091/orders -H "Content-Type: application/json" \
  -d '{"customerId":"c1","productId":"P1","quantity":2,"amount":50,"failAt":"payment"}'
sleep 2; curl -s localhost:8091/orders
```

### 3) Lỗi giữ kho SAU khi đã thu tiền ⇒ xem REFUND bù trừ
```bash
curl -s -X POST localhost:8091/orders -H "Content-Type: application/json" \
  -d '{"customerId":"c1","productId":"P1","quantity":2,"amount":77,"failAt":"inventory"}'
sleep 2
curl -s localhost:8092/payments           # payment của đơn này -> REFUNDED
curl -s localhost:8091/orders             # đơn -> CANCELLED
```
Đây là chuỗi compensation choreography: `INVENTORY_FAILED` → payment tự refund →
`PAYMENT_REFUNDED` → order tự hủy. Không service nào "ra lệnh" cho service khác.

## Outbox Pattern (đã implement)

Mỗi service **không** gọi `kafka.send` trực tiếp. Thay vào đó ghi event vào bảng **`outbox`**
trong **cùng transaction DB** với thay đổi nghiệp vụ (xem `Outbox.java`). Một `OutboxRelay`
(`@Scheduled` mỗi 1s) đọc các dòng chưa gửi, đẩy lên Kafka rồi đánh dấu `sent=true`.

Điều này vá **lỗ hổng dual-write**: nếu service chết sau khi commit DB nhưng trước khi gửi
Kafka, event vẫn nằm trong outbox và sẽ được relay gửi lại (đảm bảo **at-least-once**).
Đánh đổi: consumer cần **idempotent** vì có thể nhận trùng.

Kiểm chứng nhanh các event đang nằm trong outbox:
```bash
docker exec choreography-postgres-1 psql -U postgres -d orderdb -c \
  "select event_type, sent from outbox order by id;"
```

## Idempotency (Inbox) + Retry

- **Inbox** (`Inbox.java`): mỗi event mang `eventId` (UUID). Trước khi xử lý, consumer kiểm tra
  `eventId` trong bảng `processed_events` (cùng transaction); nếu đã có → **bỏ qua**. Nhờ vậy
  event giao trùng (at-least-once) **không** bị tính tiền/trừ kho 2 lần.
- **Retry**: mỗi consumer có `DefaultErrorHandler(FixedBackOff(1000ms, 3))` → khi handler ném lỗi,
  Kafka **thử lại 3 lần** cách nhau 1s. An toàn vì handler đã idempotent.

## Dead Letter Queue (DLQ)

Mỗi consumer có `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`: khi handler ném lỗi,
Kafka retry 3 lần; **hết retry** thì đẩy record sang topic **`saga-events.DLT`** thay vì kẹt
vô hạn ("poison message"). Đặt đơn với `"failAt":"poison"` để payment luôn ném lỗi → vào DLT.

```bash
# Xem các message đang nằm trong DLT
docker exec choreography-kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic saga-events.DLT --from-beginning --timeout-ms 5000
```

## Test tự động

```bash
bash test-saga.sh          # happy + refund chain (7 checks)
bash test-idempotency.sh   # replay event trùng -> KHÔNG double-charge (2 checks)
bash test-dlq.sh           # poison message -> saga-events.DLT, consumer phục hồi (3 checks)
```

## So với Orchestration
- Không có điểm điều phối trung tâm ⇒ loose coupling, dễ thêm service mới (chỉ cần nghe event).
- Đổi lại: luồng nằm rải rác, khó debug; production cần **Outbox pattern** để "ghi DB + phát event"
  không bị lệch khi service chết giữa chừng.