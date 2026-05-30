#!/usr/bin/env bash
# End-to-end saga tests for the ORCHESTRATION stack.
# Usage: bash test-saga.sh            (assumes the stack is already up)
#        bash test-saga.sh --reset    (wipe DBs + restart first, for a clean run)
set -u

BASE=http://localhost:8080      # orchestrator
ORDER=http://localhost:8081
PAY=http://localhost:8082
INV=http://localhost:8083

pass=0; fail=0
ok()   { echo "  PASS: $1"; pass=$((pass+1)); }
bad()  { echo "  FAIL: $1"; fail=$((fail+1)); }
post() { curl -s -X POST "$1" -H "Content-Type: application/json" -d "$2"; }
stock(){ curl -s "$INV/inventory/products" | grep -o "\"id\":\"$1\",\"stock\":[0-9]*" | grep -o '[0-9]*$'; }
has()  { echo "$1" | grep -q "$2" && ok "$3" || bad "$3 :: got: $1"; }

if [ "${1:-}" = "--reset" ]; then
  echo "Resetting stack..."; docker compose down -v >/dev/null 2>&1; docker compose up -d >/dev/null 2>&1
fi

echo "Waiting for all services to be ready..."
ready() { [ "$(curl -s -o /dev/null -w '%{http_code}' "$1" 2>/dev/null)" = "200" ]; }
for i in $(seq 1 50); do
  if ready "$ORDER/orders" && ready "$PAY/payments" && ready "$INV/inventory/products" \
     && [ "$(curl -s -o /dev/null -w '%{http_code}' $BASE/ 2>/dev/null)" != "000" ]; then
    echo "all services ready"; break
  fi
  sleep 3
done
sleep 2

echo "== 1) happy path =="
r=$(post "$BASE/orders" '{"customerId":"t","productId":"P1","quantity":2,"amount":50}')
has "$r" '"status":"CONFIRMED"' "order confirmed"
[ "$(stock P1)" = "8" ] && ok "P1 stock 10->8" || bad "P1 stock != 8 (got $(stock P1))"

echo "== 2) fail at payment -> inventory released =="
r=$(post "$BASE/orders" '{"customerId":"t","productId":"P1","quantity":3,"amount":99,"failAt":"payment"}')
has "$r" '"status":"CANCELLED"' "order cancelled"
has "$r" 'inventory released'  "compensation: inventory released"
[ "$(stock P1)" = "8" ] && ok "P1 stock back to 8" || bad "P1 stock != 8 (got $(stock P1))"

echo "== 3) fail at inventory -> nothing charged =="
r=$(post "$BASE/orders" '{"customerId":"t","productId":"P1","quantity":2,"amount":50,"failAt":"inventory"}')
has "$r" '"status":"CANCELLED"' "order cancelled"

echo "== 4) fail at confirm -> payment refunded =="
r=$(post "$BASE/orders" '{"customerId":"t","productId":"P1","quantity":1,"amount":25,"failAt":"confirm"}')
has "$r" '"status":"CANCELLED"'        "order cancelled"
has "$r" 'payment refunded'            "compensation: payment refunded"
has "$r" 'inventory released'          "compensation: inventory released"
[ "$(stock P1)" = "8" ] && ok "P1 stock still 8" || bad "P1 stock != 8 (got $(stock P1))"
has "$(curl -s $PAY/payments)" '"status":"REFUNDED"' "a payment is REFUNDED"

echo "== 5) out of stock (natural failure) =="
r=$(post "$BASE/orders" '{"customerId":"t","productId":"P2","quantity":999,"amount":10}')
has "$r" '"status":"CANCELLED"' "order cancelled (insufficient stock)"

echo
echo "RESULT: $pass passed, $fail failed"
[ "$fail" -eq 0 ]