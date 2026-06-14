#!/bin/bash
# =============================================================================
# verify.sh - Custom OTel Extension 메트릭 수집 검증 스크립트
#
# 사용법: ./verify.sh
# 사전 조건: docker compose 환경이 기동된 상태여야 합니다.
# =============================================================================

set -euo pipefail

PETCLINIC_URL="http://localhost:8080"
PROMETHEUS_URL="http://localhost:9090"
METRIC_NAME="demo_requests_total"
MAX_WAIT=120
INTERVAL=5

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }

# -------------------------------------------------------------------------
# 1. Petclinic 기동 대기
# -------------------------------------------------------------------------
log_info "Petclinic 기동 대기 중..."
elapsed=0
until curl -sf "${PETCLINIC_URL}/actuator/health" > /dev/null 2>&1; do
  if [ $elapsed -ge $MAX_WAIT ]; then
    log_error "Petclinic이 ${MAX_WAIT}초 안에 기동되지 않았습니다."
    exit 1
  fi
  sleep $INTERVAL
  elapsed=$((elapsed + INTERVAL))
  log_warn "대기 중... (${elapsed}s/${MAX_WAIT}s)"
done
log_info "Petclinic 정상 기동 확인!"

# -------------------------------------------------------------------------
# 2. 트래픽 생성
# -------------------------------------------------------------------------
log_info "Petclinic에 트래픽 생성 중..."

ENDPOINTS=(
  "/"
  "/owners/find"
  "/owners?lastName="
  "/vets.html"
)

for endpoint in "${ENDPOINTS[@]}"; do
  for i in $(seq 1 3); do
    curl -sf "${PETCLINIC_URL}${endpoint}" > /dev/null 2>&1 || true
  done
  log_info "  → ${endpoint} (3회 요청)"
done

log_info "트래픽 생성 완료. 메트릭 수집 대기 (30초)..."
sleep 30

# -------------------------------------------------------------------------
# 3. Prometheus 에서 커스텀 메트릭 조회
# -------------------------------------------------------------------------
log_info "Prometheus에서 '${METRIC_NAME}' 메트릭 조회 중..."

QUERY_URL="${PROMETHEUS_URL}/api/v1/query?query=${METRIC_NAME}"
RESPONSE=$(curl -sf "${QUERY_URL}" 2>&1)

if echo "${RESPONSE}" | grep -q '"result":\[\]'; then
  log_error "메트릭 '${METRIC_NAME}'이 Prometheus에서 발견되지 않았습니다."
  log_error "응답: ${RESPONSE}"
  exit 1
fi

if echo "${RESPONSE}" | grep -q '"resultType":"vector"'; then
  log_info "✅ 커스텀 메트릭 '${METRIC_NAME}'이 Prometheus에서 정상 수집되었습니다!"
  echo ""
  log_info "=== 메트릭 상세 ==="
  echo "${RESPONSE}" | python3 -m json.tool 2>/dev/null || echo "${RESPONSE}"
  echo ""
  log_info "=== 검증 성공 ==="
  exit 0
else
  log_warn "예상과 다른 응답을 받았습니다:"
  echo "${RESPONSE}" | python3 -m json.tool 2>/dev/null || echo "${RESPONSE}"
  exit 1
fi
