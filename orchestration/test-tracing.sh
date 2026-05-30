#!/usr/bin/env bash
# Verifies distributed tracing: one order request produces ONE trace that spans the
# gateway, orchestrator and all three downstream services, visible in Zipkin.
# Usage: bash test-tracing.sh   (stack up, incl. zipkin)
set -u

GW=http://localhost:8000
ZIPKIN=http://localhost:9411

pass=0; fail=0
ok()  { echo "  PASS: $1"; pass=$((pass+1)); }
bad() { echo "  FAIL: $1"; fail=$((fail+1)); }
field(){ grep -o "\"$1\":\"[^\"]*\"" | head -1 | sed "s/\"$1\":\"\([^\"]*\)\"/\1/"; }

echo "Waiting for gateway + zipkin..."
for i in $(seq 1 40); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' $ZIPKIN/health 2>/dev/null)" = "200" ] \
    && [ "$(curl -s -o /dev/null -w '%{http_code}' -X POST $GW/auth/login -H 'Content-Type: application/json' -d '{}')" != "000" ] && break
  sleep 3
done

echo "Placing an order through the gateway to generate a trace..."
tok=$(curl -s -X POST $GW/auth/login -H "Content-Type: application/json" \
  -d '{"username":"user","password":"password"}' | field accessToken)
curl -s -X POST $GW/orchestrator/orders -H "Authorization: Bearer $tok" \
  -H "Content-Type: application/json" \
  -d '{"customerId":"trace","productId":"P1","quantity":1,"amount":20}' >/dev/null

echo "Waiting for spans to flush to Zipkin (8s)..."; sleep 8

echo "== services that reported spans to Zipkin =="
services=$(curl -s $ZIPKIN/api/v2/services)
echo "  $services"
for s in api-gateway orchestrator-service order-service payment-service inventory-service; do
  echo "$services" | grep -q "\"$s\"" && ok "Zipkin received spans from $s" || bad "no spans from $s"
done

echo "== one trace spans multiple services =="
trace=$(curl -s "$ZIPKIN/api/v2/traces?serviceName=orchestrator-service&limit=1")
for s in order-service payment-service inventory-service; do
  echo "$trace" | grep -q "$s" && ok "orchestrator trace includes $s" || bad "trace missing $s"
done

echo
echo "RESULT: $pass passed, $fail failed"
echo "Open the Zipkin UI at $ZIPKIN to see the timeline."
[ "$fail" -eq 0 ]
