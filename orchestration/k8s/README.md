# Orchestration trên Kubernetes

Deploy stack orchestration lên Kubernetes (test với **Docker Desktop Kubernetes**), dùng lại
image đã build bằng docker-compose (`imagePullPolicy: Never`, không cần registry).

## So với docker-compose

| docker-compose | Kubernetes |
|---|---|
| `service` | **Deployment** (quản lý Pod + replica) |
| `ports` nội bộ | **Service** (ClusterIP) — DNS theo tên service |
| `ports` ra host | **Service type: NodePort** (gateway `:30080`, zipkin `:30411`) |
| `environment` | `env` trong container spec |
| `depends_on` | không có — Pod tự retry; app đã chịu được thứ tự khởi động |
| volume init | **ConfigMap** mount vào `/docker-entrypoint-initdb.d` |

Tất cả nằm trong namespace **`saga`**. Tên Service trùng hostname app dùng (`postgres`,
`order-service`, …) nên các biến `*_URL` giữ nguyên như compose.

## Yêu cầu

1. Bật **Docker Desktop → Settings → Kubernetes → Enable Kubernetes → Apply**.
2. Image đã build sẵn: `cd orchestration && docker compose build` (nếu chưa có).
   Docker Desktop k8s dùng chung image store với docker nên `imagePullPolicy: Never` thấy được.

## Deploy

```bash
cd orchestration/k8s
bash deploy.sh
# hoặc thủ công:
kubectl apply -f .
kubectl -n saga rollout status deploy/api-gateway --timeout=180s
```

## Dùng thử

```bash
# Public entry = NodePort của gateway
TOKEN=$(curl -s -X POST localhost:30080/auth/login -H "Content-Type: application/json" \
  -d '{"username":"user","password":"password"}' | grep -o '"accessToken":"[^"]*"' | sed 's/.*:"//;s/"//')

curl -X POST localhost:30080/orchestrator/orders -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"customerId":"c1","productId":"P1","quantity":1,"amount":20}'

# Zipkin UI: http://localhost:30411
```

## Quan sát

```bash
kubectl -n saga get pods
kubectl -n saga get svc
kubectl -n saga logs deploy/orchestrator-service
```

## Dọn

```bash
kubectl delete namespace saga
```

## Ghi chú production (demo cố ý đơn giản)
- Postgres dùng `emptyDir` (mất dữ liệu khi Pod restart) → production dùng **StatefulSet + PVC**.
- Mật khẩu DB để thẳng trong `env` → production dùng **Secret** (+ external secrets).
- Chưa có `resources` requests/limits, HPA, NetworkPolicy, hay Ingress (đang dùng NodePort).
- Image `:latest` + `Never` chỉ hợp môi trường local → production push lên **registry** và pin tag.
