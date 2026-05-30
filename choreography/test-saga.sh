#!/usr/bin/env bash
# End-to-end saga tests for the CHOREOGRAPHY stack (event-driven, async).
# Because processing is asynchronous (Kafka + outbox relay), we POST then POLL
# the order status until it reaches the expected terminal state.
# Usage: bash test-saga.sh            (assumes the stack is already up)
#        bash test-saga.sh --reset    (wipe DBs + restart first)
set -u

ORDER=http://localhost:8091
PAY=http://localhost:8092
INV=http://localhost:8093

pass=0; fail=0
ok()   { echo "  PASS: $1"; pass=$((pass+1)); }
bad()  { echo "  FAIL: $1"; fail=$((fail+1)); }
post() { curl -s -X POST "$ORDER/orders" -H "Content-Type: application/json" -d "$1"; }
oid()  { echo "$1" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*'; }
stock(){ curl -s "$INV/inventory/products" | grep -o "\"id\":\"$1\",\"stock\":[0-9]*" | grep -o '[0-9]*$'; }
has()  { echo "$1" | grep -q "$2" && ok "$3" || bad "$3 :: got: $1"; }

# wait_status <orderId> <expectedStatus> <maxSeconds>
wait_status() {
  for _ in $(seq 1 "$3"); do
    obj=$(curl -s "$ORDER/orders" | grep -o "{\"id\":$1,[^}]*}")
    echo "$obj" | grep -q "\"status\":\"$2\"" && { ok "order #$1 -> $2"; return 0; }
    sleep 1
  done
  bad "order #$1 never reached $2 (last: $(curl -s "$ORDER/orders" | grep -o "{\"id\":$1,[^}]*}"))"
  return 1
}

if [ "${1:-}" = "--reset" ]; then
  echo "Resetting stack..."; docker compose down -v >/dev/null 2>&1; docker compose up -d >/dev/null 2>&1
fi

echo "Waiting for order-service + kafka consumers..."
for i in $(seq 1 50); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' $ORDER/orders 2>/dev/null)" = "200" ] && break; sleep 3
done
sleep 8   # let consumer groups join

echo "== 1) happy path (async) =="
id=$(oid "$(post '{"customerId":"t","productId":"P1","quantity":2,"amount":50}')")
wait_status "$id" CONFIRMED 20
[ "$(stock P1)" = "8" ] && ok "P1 stock 10->8" || bad "P1 stock != 8 (got $(stock P1))"

echo "== 2) fail at payment -> cancelled, nothing charged =="
id=$(oid "$(post '{"customerId":"t","productId":"P1","quantity":3,"amount":99,"failAt":"payment"}')")
wait_status "$id" CANCELLED 20
[ "$(stock P1)" = "8" ] && ok "P1 stock unchanged (8)" || bad "P1 stock changed (got $(stock P1))"

echo "== 3) fail at inventory AFTER charge -> refund chain =="
id=$(oid "$(post '{"customerId":"t","productId":"P1","quantity":2,"amount":77,"failAt":"inventory"}')")
wait_status "$id" CANCELLED 20
[ "$(stock P1)" = "8" ] && ok "P1 stock returned to 8" || bad "P1 stock != 8 (got $(stock P1))"
# the payment for THIS order must end up REFUNDED (distributed compensation via Kafka)
obj=$(curl -s "$PAY/payments" | grep -o "{\"id\":[0-9]*,\"orderId\":$id,[^}]*}")
has "$obj" '"status":"REFUNDED"' "payment for order #$id is REFUNDED"

echo
echo "RESULT: $pass passed, $fail failed"
[ "$fail" -eq 0 ]