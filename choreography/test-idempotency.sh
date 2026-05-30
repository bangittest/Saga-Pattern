#!/usr/bin/env bash
# Proves the idempotent consumer: replaying an already-processed event into Kafka
# must NOT double-charge / double-reserve. Run after the stack is up.
# Usage: bash test-idempotency.sh
set -u

ORDER=http://localhost:8091
PAY=http://localhost:8092
INV=http://localhost:8093
PG=choreography-postgres-1
KAFKA=choreography-kafka-1

pass=0; fail=0
ok()  { echo "  PASS: $1"; pass=$((pass+1)); }
bad() { echo "  FAIL: $1"; fail=$((fail+1)); }
paycount() { curl -s "$PAY/payments" | grep -o "\"orderId\":$1" | wc -l | tr -d ' '; }
stock()    { curl -s "$INV/inventory/products" | grep -o "\"id\":\"$1\",\"stock\":[0-9]*" | grep -o '[0-9]*$'; }

echo "Placing a fresh happy order..."
id=$(curl -s -X POST "$ORDER/orders" -H "Content-Type: application/json" \
  -d '{"customerId":"idem","productId":"P1","quantity":1,"amount":30}' | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
for _ in $(seq 1 20); do
  curl -s "$ORDER/orders" | grep -o "{\"id\":$id,[^}]*}" | grep -q CONFIRMED && break; sleep 1
done

pay_before=$(paycount "$id"); stock_before=$(stock P1)
echo "order #$id confirmed. payments=$pay_before, P1 stock=$stock_before"

echo "Replaying the ORDER_CREATED event (same eventId) into Kafka..."
payload=$(docker exec "$PG" psql -U postgres -d orderdb -tAc \
  "select payload from outbox where event_type='ORDER_CREATED' and aggregate_id=$id limit 1")
# MSYS_NO_PATHCONV stops Git-Bash from mangling the /opt/... path inside the container.
echo "$payload" | MSYS_NO_PATHCONV=1 docker exec -i "$KAFKA" /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic saga-events
echo "replayed; waiting for consumers..."; sleep 5

pay_after=$(paycount "$id"); stock_after=$(stock P1)
echo "after replay: payments=$pay_after, P1 stock=$stock_after"

[ "$pay_before" = "$pay_after" ]   && ok "payment NOT charged twice ($pay_after)" || bad "double charge: $pay_before -> $pay_after"
[ "$stock_before" = "$stock_after" ] && ok "stock NOT reduced twice ($stock_after)" || bad "double reserve: $stock_before -> $stock_after"

echo
echo "RESULT: $pass passed, $fail failed"
[ "$fail" -eq 0 ]