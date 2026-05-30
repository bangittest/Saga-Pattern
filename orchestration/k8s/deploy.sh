#!/usr/bin/env bash
# Deploy the orchestration stack to the current kube context and wait for it to be ready.
# Usage: bash deploy.sh
set -eu

echo "== applying manifests to namespace 'saga' =="
kubectl apply -f .

echo "== waiting for deployments to roll out =="
for d in postgres zipkin order-service payment-service inventory-service \
         orchestrator-service auth-service api-gateway; do
  kubectl -n saga rollout status "deploy/$d" --timeout=180s
done

echo
echo "== pods =="
kubectl -n saga get pods
echo
echo "Gateway:  http://localhost:30080   (e.g. POST /auth/login then /orchestrator/orders)"
echo "Zipkin :  http://localhost:30411"
