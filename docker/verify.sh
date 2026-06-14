#!/bin/bash
# =============================================================================
# verify.sh - Custom OTel Extension + Micrometer 메트릭 수집 검증 스크립트
#
# 사용법:
#   ./verify.sh                 # 기본 검증 (Javaagent + Micrometer 메트릭)
#   ./verify.sh --agent-only    # Javaagent Extension 메트릭만 검증
#
# 사전 조건: docker compose 환경이 기동된 상태여야 합니다.
# =============================================================================

set -euo pipefail

PETCLINIC_URL="http://localhost:8080"
PROMETHEUS_URL="http://localhost:9090"
MAX_WAIT=120
INTERVAL=5

# Javaagent extension metric
AGENT_METRIC="demo_requests_total"

# Micrometer custom metrics (defined in the application)
MICROMETER_METRICS=(
  "petclinic_owners_search_count_total"
  "petclinic_visits_created_count_total"
  "petclinic_custom_requests_total"
)

AGENT_ONLY=false
if [ "${1:-}" = "--agent-only" ]; then
  AGENT_ONLY=true
fi

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

log_info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }

# -------------------------------------------------------------------------
# 1. Petclinic 기동 대기
# -------------------------------------------------------------------------
log_info "애플리케이션 기동 대기 중..."
elapsed=0
until curl -sf "${PETCLINIC_URL}/actuator/health" > /dev/null 2>&1; do
  if [ $elapsed -ge $MAX_WAIT ]; then
    log_error "애플리케이션이 ${MAX_WAIT}초 안에 기동되지 않았습니다."
    exit 1
  fi
  sleep $INTERVAL
  elapsed=$((elapsed + INTERVAL))
  log_warn "대기 중... (${elapsed}s/${MAX_WAIT}s)"
done
log_info "애플리케이션 정상 기동 확인!"

# -------------------------------------------------------------------------
# 2. 트래픽 생성
# -------------------------------------------------------------------------
log_info "트래픽 생성 중..."

ENDPOINTS=(
  "/"
  "/owners/find"
  "/owners?lastName="
  "/vets.html"
)

for endpoint in "${ENDPOINTS[@]}"; do
  for i in $(seq 1 5); do
    curl -sf "${PETCLINIC_URL}${endpoint}" > /dev/null 2>&1 || true
  done
  log_info "  → ${endpoint} (5회 요청)"
done

# Legacy app의 visit 엔드포인트 시도 (실패해도 무시)
for i in $(seq 1 3); do
  curl -sf "${PETCLINIC_URL}/visits/create" > /dev/null 2>&1 || true
done

log_info "트래픽 생성 완료. 메트릭 전파 대기 (20초)..."
sleep 20

# -------------------------------------------------------------------------
# 3. 메트릭 검증 함수 (재시도 포함)
# -------------------------------------------------------------------------
verify_metric() {
  local metric_name=$1
  local label=$2
  local max_retries=6
  local retry_interval=10
  local attempt=0

  log_info "Prometheus에서 '${metric_name}' 메트릭 조회 중... [${label}]"

  while [ $attempt -lt $max_retries ]; do
    local query_url="${PROMETHEUS_URL}/api/v1/query?query=${metric_name}"
    local response
    response=$(curl -sf "${query_url}" 2>&1) || {
      attempt=$((attempt + 1))
      if [ $attempt -lt $max_retries ]; then
        log_warn "Prometheus 쿼리 실패, 재시도 (${attempt}/${max_retries})..."
        sleep $retry_interval
        continue
      fi
      log_error "Prometheus 쿼리 실패"
      return 1
    }

    # Check for non-empty vector result
    if echo "${response}" | grep -q '"resultType":"vector"' && \
       ! echo "${response}" | grep -q '"result":\[\]'; then
      log_info "✅ '${metric_name}' 메트릭 정상 수집 확인!"
      echo ""
      log_info "=== ${metric_name} 상세 ==="
      echo "${response}" | python3 -m json.tool 2>/dev/null || echo "${response}"
      echo ""
      return 0
    fi

    attempt=$((attempt + 1))
    if [ $attempt -lt $max_retries ]; then
      log_warn "'${metric_name}' 아직 미수집, 재시도 (${attempt}/${max_retries})..."
      sleep $retry_interval
    fi
  done

  log_error "메트릭 '${metric_name}'이 ${max_retries}회 시도 후에도 Prometheus에서 발견되지 않았습니다."
  return 1
}

# -------------------------------------------------------------------------
# 4. Javaagent Extension 메트릭 검증
# -------------------------------------------------------------------------
all_passed=true

if ! verify_metric "${AGENT_METRIC}" "Javaagent Extension"; then
  all_passed=false
fi

# -------------------------------------------------------------------------
# 5. Micrometer 커스텀 메트릭 검증
# -------------------------------------------------------------------------
if [ "${AGENT_ONLY}" = false ]; then
  echo -e "\n${CYAN}--- Micrometer 커스텀 메트릭 검증 ---${NC}"

  for metric in "${MICROMETER_METRICS[@]}"; do
    if ! verify_metric "${metric}" "Micrometer 커스텀"; then
      all_passed=false
    fi
  done
fi

# -------------------------------------------------------------------------
# 6. 최종 결과
# -------------------------------------------------------------------------
echo ""
if [ "${all_passed}" = true ]; then
  log_info "=== 🎉 모든 메트릭 검증 성공 ==="
  exit 0
else
  log_error "=== ❌ 일부 메트릭 검증 실패 ==="
  exit 1
fi
