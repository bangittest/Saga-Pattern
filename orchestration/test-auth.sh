#!/usr/bin/env bash
# Tests edge auth at the gateway: RS256 verification, BCrypt login, RBAC, refresh tokens.
# Usage: bash test-auth.sh   (stack must be up, incl. auth-service + api-gateway)
set -u

GW=http://localhost:8000

pass=0; fail=0
ok()  { echo "  PASS: $1"; pass=$((pass+1)); }
bad() { echo "  FAIL: $1"; fail=$((fail+1)); }
code(){ curl -s -o /dev/null -w '%{http_code}' "$@"; }
# extract first JSON string value for a key
field(){ grep -o "\"$1\":\"[^\"]*\"" | head -1 | sed "s/\"$1\":\"\([^\"]*\)\"/\1/"; }
login(){ curl -s -X POST "$GW/auth/login" -H "Content-Type: application/json" -d "$1"; }
ORDER='{"customerId":"c1","productId":"P1","quantity":1,"amount":20}'

echo "Waiting for gateway + auth..."
for i in $(seq 1 40); do [ "$(code $GW/auth/login -X POST -H 'Content-Type: application/json' -d '{}')" != "000" ] && break; sleep 3; done

echo "== 1) no token -> 401 =="
[ "$(code -X POST $GW/orchestrator/orders -H 'Content-Type: application/json' -d "$ORDER")" = "401" ] \
  && ok "request without token rejected (401)" || bad "expected 401"

echo "== 2) bad credentials (BCrypt) -> 401 =="
[ "$(code -X POST $GW/auth/login -H 'Content-Type: application/json' -d '{"username":"user","password":"wrong"}')" = "401" ] \
  && ok "wrong password rejected (401)" || bad "expected 401"

echo "== 3) login USER, place order -> 200 =="
resp=$(login '{"username":"user","password":"password"}')
ut=$(echo "$resp" | field accessToken)
[ -n "$ut" ] && ok "USER login returned an access token" || bad "no access token"
[ "$(code -X POST $GW/orchestrator/orders -H "Authorization: Bearer $ut" -H 'Content-Type: application/json' -d "$ORDER")" = "200" ] \
  && ok "USER can place order (200)" || bad "USER place order != 200"

echo "== 4) USER hits admin-only route -> 403 =="
[ "$(code $GW/order-service/orders -H "Authorization: Bearer $ut")" = "403" ] \
  && ok "USER blocked from /order-service (403)" || bad "expected 403 for USER"

echo "== 5) login ADMIN, hit admin route -> 200 =="
at=$(login '{"username":"admin","password":"admin123"}' | field accessToken)
[ "$(code $GW/order-service/orders -H "Authorization: Bearer $at")" = "200" ] \
  && ok "ADMIN can read /order-service (200)" || bad "ADMIN /order-service != 200"

echo "== 6) tampered token -> 401 (RS256 signature check) =="
[ "$(code $GW/order-service/orders -H "Authorization: Bearer ${at}tampered")" = "401" ] \
  && ok "tampered token rejected (401)" || bad "expected 401 for bad token"

echo "== 7) refresh token flow =="
rt=$(echo "$resp" | field refreshToken)
[ -n "$rt" ] && ok "login also returned a refresh token" || bad "no refresh token"
# refresh token must NOT be accepted as a bearer access credential
[ "$(code -X POST $GW/orchestrator/orders -H "Authorization: Bearer $rt" -H 'Content-Type: application/json' -d "$ORDER")" = "401" ] \
  && ok "refresh token rejected as access token (401)" || bad "refresh token wrongly accepted"
# exchange refresh token for a fresh access token, which must work
nt=$(curl -s -X POST "$GW/auth/refresh" -H "Content-Type: application/json" -d "{\"refreshToken\":\"$rt\"}" | field accessToken)
[ -n "$nt" ] && ok "/auth/refresh issued a new access token" || bad "refresh did not return a token"
[ "$(code -X POST $GW/orchestrator/orders -H "Authorization: Bearer $nt" -H 'Content-Type: application/json' -d "$ORDER")" = "200" ] \
  && ok "refreshed access token works (200)" || bad "refreshed token != 200"

echo
echo "RESULT: $pass passed, $fail failed"
[ "$fail" -eq 0 ]