# deploy.ps1 - Automated local deployment of TinyKV on kind with NGINX Gateway Fabric v2.7.2
param (
    [string]$clusterName = "tinykv-cluster"
)

$ErrorActionPreference = "Stop"

Write-Host "==> Checking prerequisites..."
if (!(Get-Command kind -ErrorAction SilentlyContinue)) {
    Write-Error "kind CLI not found. Please install kind: https://kind.sigs.k8s.io/docs/user/quick-start/#installation"
}
if (!(Get-Command kubectl -ErrorAction SilentlyContinue)) {
    Write-Error "kubectl CLI not found. Please install kubectl."
}
if (!(Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "Docker not found. Please ensure Docker Desktop / daemon is running."
}

# 1. Create kind cluster if it doesn't already exist
$existingClusters = kind get clusters 2>$null
if ($existingClusters -contains $clusterName) {
    Write-Host "==> Cluster '$clusterName' already exists. Using existing cluster."
} else {
    Write-Host "==> Creating kind cluster '$clusterName' with NodePort mappings 80/443..."
    kind create cluster --config k8s/kind-config.yaml --name $clusterName
}

# 2. Install Gateway API Standard CRDs (v2.7.2 reference)
Write-Host "==> Installing Kubernetes Gateway API CRDs..."
kubectl kustomize "https://github.com/nginx/nginx-gateway-fabric/config/crd/gateway-api/standard?ref=v2.7.2" | kubectl apply -f -

# 3. Create namespace & install NGINX Gateway Fabric v2.7.2 CRDs
Write-Host "==> Creating nginx-gateway namespace..."
kubectl create namespace nginx-gateway --dry-run=client -o yaml | kubectl apply -f -

Write-Host "==> Installing NGINX Gateway Fabric CRDs (server-side apply)..."
kubectl apply --server-side -f https://raw.githubusercontent.com/nginx/nginx-gateway-fabric/v2.7.2/deploy/crds.yaml

# 4. Deploy NGINX Gateway Fabric controller
Write-Host "==> Deploying NGINX Gateway Fabric controller v2.7.2..."
kubectl apply -f https://raw.githubusercontent.com/nginx/nginx-gateway-fabric/v2.7.2/deploy/default/deploy.yaml

Write-Host "==> Waiting for NGINX Gateway Fabric controller to be ready..."
kubectl wait --namespace nginx-gateway --for=condition=ready pod --selector=app.kubernetes.io/name=nginx-gateway --timeout=120s

# 5. Configure NginxProxy to expose data plane via NodePort on kind
Write-Host "==> Configuring NginxProxy NodePort mapping..."
kubectl apply -f k8s/nginx-proxy-config.yaml

# 6. Build and load Docker image into kind
Write-Host "==> Building TinyKV container image..."
docker build -t tinykv:latest .

Write-Host "==> Loading TinyKV image into kind cluster..."
kind load docker-image tinykv:latest --name $clusterName

# 7. Deploy TinyKV StatefulSet, Service, Gateway, and HTTPRoute
Write-Host "==> Deploying TinyKV manifests..."
kubectl apply -f k8s/statefulset.yaml
kubectl apply -f k8s/gateway.yaml
kubectl apply -f k8s/httproute.yaml

Write-Host "==> Waiting for TinyKV pod to be ready..."
kubectl wait --namespace tinykv --for=condition=ready pod/tinykv-0 --timeout=120s

Write-Host "==> Waiting for Gateway to be programmed..."
kubectl wait --namespace tinykv --for=condition=Programmed gateway/tinykv-gateway --timeout=120s

# 8. Active end-to-end traffic verification through Gateway Fabric
Write-Host ""
Write-Host "==> Validating end-to-end traffic through NGINX Gateway Fabric at http://localhost..."

$attempts = 0
$maxAttempts = 30
$connected = $false
while ($attempts -lt $maxAttempts) {
    try {
        $res = Invoke-WebRequest -Uri "http://localhost/healthz" -UseBasicParsing -TimeoutSec 2 -ErrorAction SilentlyContinue
        if ($res.StatusCode -eq 200) {
            $connected = $true
            break
        }
    } catch {}
    $attempts++
    Start-Sleep -Seconds 2
}

if (-not $connected) {
    Write-Error "ERROR: Timed out waiting for Gateway to route traffic to http://localhost/healthz"
}

Write-Host "  [PASS] GET /healthz returned HTTP 200"

$readyRes = Invoke-WebRequest -Uri "http://localhost/readyz" -UseBasicParsing
if ($readyRes.StatusCode -ne 200) { Write-Error "GET /readyz failed" }
Write-Host "  [PASS] GET /readyz returned HTTP 200"

$putRes = Invoke-WebRequest -Uri "http://localhost/api/v1/keys/deploy_check" -Method Put -Body "NGINX Gateway Fabric v2.7.2 Verified" -UseBasicParsing
if ($putRes.StatusCode -ne 200) { Write-Error "PUT failed" }
Write-Host "  [PASS] PUT /api/v1/keys/deploy_check succeeded"

$getRes = Invoke-WebRequest -Uri "http://localhost/api/v1/keys/deploy_check" -UseBasicParsing
if ($getRes.Content -ne "NGINX Gateway Fabric v2.7.2 Verified") {
    Write-Error "Expected 'NGINX Gateway Fabric v2.7.2 Verified', got '$($getRes.Content)'"
}
Write-Host "  [PASS] GET /api/v1/keys/deploy_check verified correct value: '$($getRes.Content)'"

$statsRes = Invoke-WebRequest -Uri "http://localhost/api/v1/stats" -UseBasicParsing
if ($statsRes.StatusCode -ne 200) { Write-Error "GET /api/v1/stats failed" }
Write-Host "  [PASS] GET /api/v1/stats returned live metrics"

$delRes = Invoke-WebRequest -Uri "http://localhost/api/v1/keys/deploy_check" -Method Delete -UseBasicParsing
if ($delRes.StatusCode -ne 204) { Write-Error "DELETE failed" }
Write-Host "  [PASS] DELETE /api/v1/keys/deploy_check succeeded"

Write-Host ""
Write-Host "==============================================================="
Write-Host "  TINYKV SUCCESSFULLY DEPLOYED & VALIDATED ON GATEWAY FABRIC!  "
Write-Host "==============================================================="
Write-Host "Access TinyKV web console: http://localhost/"
Write-Host "API endpoints active at:   http://localhost/api/v1/"
Write-Host "==============================================================="