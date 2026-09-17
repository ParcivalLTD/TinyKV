#!/usr/bin/env bash
set -euo pipefail

CLUSTER_NAME="${1:-tinykv-cluster}"

echo "==> Checking prerequisites..."
command -v kind >/dev/null 2>&1 || { echo "kind CLI not found. Please install kind: https://kind.sigs.k8s.io/"; exit 1; }
command -v kubectl >/dev/null 2>&1 || { echo "kubectl CLI not found. Please install kubectl."; exit 1; }
command -v docker >/dev/null 2>&1 || { echo "Docker not found. Please ensure Docker is running."; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "curl not found. Please install curl."; exit 1; }

# 1. Create kind cluster if it doesn't already exist
if kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
    echo "==> Cluster '${CLUSTER_NAME}' already exists. Using existing cluster."
else
    echo "==> Creating kind cluster '${CLUSTER_NAME}' with NodePort mappings 80/443..."
    kind create cluster --config k8s/kind-config.yaml --name "${CLUSTER_NAME}"
fi

# 2. Install Gateway API Standard CRDs (v2.7.2 reference)
echo "==> Installing Kubernetes Gateway API CRDs..."
kubectl kustomize "https://github.com/nginx/nginx-gateway-fabric/config/crd/gateway-api/standard?ref=v2.7.2" | kubectl apply -f -

# 3. Create namespace & install NGINX Gateway Fabric v2.7.2 CRDs
echo "==> Creating nginx-gateway namespace..."
kubectl create namespace nginx-gateway --dry-run=client -o yaml | kubectl apply -f -

echo "==> Installing NGINX Gateway Fabric CRDs (server-side apply)..."
kubectl apply --server-side -f https://raw.githubusercontent.com/nginx/nginx-gateway-fabric/v2.7.2/deploy/crds.yaml

# 4. Deploy NGINX Gateway Fabric controller
echo "==> Deploying NGINX Gateway Fabric controller v2.7.2..."
kubectl apply -f https://raw.githubusercontent.com/nginx/nginx-gateway-fabric/v2.7.2/deploy/default/deploy.yaml

echo "==> Waiting for NGINX Gateway Fabric controller to be ready..."
kubectl wait --namespace nginx-gateway --for=condition=ready pod --selector=app.kubernetes.io/name=nginx-gateway --timeout=120s

# 5. Configure NginxProxy to expose data plane via NodePort on kind
echo "==> Configuring NginxProxy NodePort mapping..."
kubectl apply -f k8s/nginx-proxy-config.yaml

# 6. Build and load Docker image into kind
echo "==> Building TinyKV container image..."
docker build -t tinykv:latest .

echo "==> Loading TinyKV image into kind cluster..."
kind load docker-image tinykv:latest --name "${CLUSTER_NAME}"

# 7. Deploy TinyKV StatefulSet, Service, Gateway, and HTTPRoute
echo "==> Deploying TinyKV manifests..."
kubectl apply -f k8s/statefulset.yaml
kubectl apply -f k8s/gateway.yaml
kubectl apply -f k8s/httproute.yaml

echo "==> Waiting for TinyKV pod to be ready..."
kubectl wait --namespace tinykv --for=condition=ready pod/tinykv-0 --timeout=120s

echo "==> Waiting for Gateway to be programmed..."
kubectl wait --namespace tinykv --for=condition=Programmed gateway/tinykv-gateway --timeout=120s

# 8. Active end-to-end traffic verification through Gateway Fabric
echo ""
echo "==> Validating end-to-end traffic through NGINX Gateway Fabric at http://localhost..."

ATTEMPTS=0
MAX_ATTEMPTS=30
until curl -sf http://localhost/healthz > /dev/null; do
    ATTEMPTS=$((ATTEMPTS + 1))
    if [ "$ATTEMPTS" -ge "$MAX_ATTEMPTS" ]; then
        echo "ERROR: Timed out waiting for Gateway to route traffic to http://localhost/healthz"
        exit 1
    fi
    sleep 2
done

echo "  [PASS] GET /healthz returned HTTP 200"

curl -sf http://localhost/readyz > /dev/null
echo "  [PASS] GET /readyz returned HTTP 200"

curl -sf -X PUT http://localhost/api/v1/keys/deploy_check -d 'NGINX Gateway Fabric v2.7.2 Verified' > /dev/null
echo "  [PASS] PUT /api/v1/keys/deploy_check succeeded"

VALUE=$(curl -sf http://localhost/api/v1/keys/deploy_check)
if [ "$VALUE" != "NGINX Gateway Fabric v2.7.2 Verified" ]; then
    echo "ERROR: Expected 'NGINX Gateway Fabric v2.7.2 Verified', got '$VALUE'"
    exit 1
fi
echo "  [PASS] GET /api/v1/keys/deploy_check verified correct value: '$VALUE'"

curl -sf http://localhost/api/v1/stats > /dev/null
echo "  [PASS] GET /api/v1/stats returned live metrics"

curl -sf -X DELETE http://localhost/api/v1/keys/deploy_check > /dev/null
echo "  [PASS] DELETE /api/v1/keys/deploy_check succeeded"

echo ""
echo "==============================================================="
echo "  TINYKV SUCCESSFULLY DEPLOYED & VALIDATED ON GATEWAY FABRIC!  "
echo "==============================================================="
echo "Access TinyKV web console: http://localhost/"
echo "API endpoints active at:   http://localhost/api/v1/"
echo "==============================================================="