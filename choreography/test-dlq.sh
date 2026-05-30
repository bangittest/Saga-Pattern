#!/usr/bin/env bash
# Proves the Dead Letter Queue: a "poison" message that always throws is retried,
# then routed to saga-events.DLT instead of blocking the consumer forever.
# Usage: bash test-dlq.sh   (stack up)
set -u

ORDER=http://localhost:8091
PAY=http://localhost:8092
KAFKA=choreography-kafka-1

pass=0; fail=0
ok()  { echo "  PASS: $1"; pass=$((pass+1)); }
bad() { echo "  FAIL: $1"; fail=$((fail+1)); }

echo "Placing a POISON order (payment handler will always throw)..."
resp=$(curl -s -X POST "$ORDER/orders" -H "Content-Type: application/json" \
  -d '{"customerId":"dlq","productId":"P1","quantity":1,"amount":10,"failAt":"poison"}')
id=$(echo "$resp" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
echo "order #$id created. Waiting for retries to exhaust + DLT publish (10s)..."
sleep 10

echo "== reading saga-events.DLT =="
# Read ALL current DLT messages (until timeout) and look for THIS run's poison order.
# MSYS_NO_PATHCONV stops Git-Bash from mangling the /opt/... path inside the container.
dlt=$(MSYS_NO_PATHCONV=1 docker exec "$KAFKA" /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic saga-events.DLT \
  --from-beginning --timeout-ms 6000 2>/dev/null)
echo "DLT now contains $(echo "$dlt" | grep -c failAt) message(s)"

echo "$dlt" | grep -q "\"orderId\":$id,\"customerId\":\"dlq\"" && ok "poison order #$id landed in saga-events.DLT" \
  || bad "poison order #$id NOT found in DLT"
echo "$dlt" | grep -q '"failAt":"poison"' && ok "DLT payload preserved (failAt=poison)" \
  || bad "DLT payload unexpected"

# the consumer must have RECOVERED (not stuck): a following good order still processes
echo "== consumer not stuck: a normal order after the poison still confirms =="
gid=$(curl -s -X POST "$ORDER/orders" -H "Content-Type: application/json" \
  -d '{"customerId":"after","productId":"P1","quantity":1,"amount":10}' | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
good=""
for _ in $(seq 1 20); do
  curl -s "$ORDER/orders" | grep -o "{\"id\":$gid,[^}]*}" | grep -q CONFIRMED && { good=1; break; }; sleep 1
done
[ -n "$good" ] && ok "consumer recovered; later order #$gid CONFIRMED" || bad "consumer appears stuck"

echo
echo "RESULT: $pass passed, $fail failed"
[ "$fail" -eq 0 ]