#!/bin/bash
# =============================================================================
# test-all-jdks.sh - JDK LTS 버전별 OTel Javaagent 메트릭 수집 통합 검증 스크립트
#
# 사용법:
#   ./docker/test-all-jdks.sh              # 모든 JDK LTS 버전 테스트
#   ./docker/test-all-jdks.sh 17           # 특정 JDK 버전만 테스트
#   ./docker/test-all-jdks.sh 8 11         # 여러 JDK 버전 지정
#
# 각 JDK 버전에 대해:
#   1. Docker Compose 환경 빌드 및 기동
#   2. 헬스체크 대기
#   3. 트래픽 생성 (커스텀 메트릭 트리거)
#   4. Prometheus 메트릭 검증
#   5. 결과 출력 및 정리
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.multi-jdk.yml"

# JDK LTS versions to test
ALL_JDK_VERSIONS=(8 11 17 21 25)

# If arguments are provided, use them as JDK versions
if [ $# -gt 0 ]; then
  JDK_VERSIONS=("$@")
else
  JDK_VERSIONS=("${ALL_JDK_VERSIONS[@]}")
fi

PETCLINIC_URL="http://localhost:8080"
PROMETHEUS_URL="http://localhost:9090"
MAX_WAIT=180
INTERVAL=5

# Metrics to verify
AGENT_METRIC="demo_requests_total"

# Micrometer metrics common to all JDK versions (both legacy and modern apps)
MICROMETER_METRICS_COMMON=(
  "petclinic_owners_search_count_total"
  "petclinic_visits_created_count_total"
)

# Micrometer metrics only available in modern petclinic (JDK 17+)
MICROMETER_METRICS_MODERN=(
  "petclinic_custom_requests_total"
)

# Helper: get Micrometer metrics list for a given JDK version
# Sets global CURRENT_MICROMETER_METRICS array (bash 3.2 compatible, no nameref)
get_micrometer_metrics() {
  local jdk_version=$1
  CURRENT_MICROMETER_METRICS=("${MICROMETER_METRICS_COMMON[@]}")
  if [ "$jdk_version" -ge 17 ]; then
    CURRENT_MICROMETER_METRICS+=("${MICROMETER_METRICS_MODERN[@]}")
  fi
}

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

log_info()    { echo -e "${GREEN}[INFO]${NC}    $*"; }
log_warn()    { echo -e "${YELLOW}[WARN]${NC}    $*"; }
log_error()   { echo -e "${RED}[ERROR]${NC}   $*"; }
log_header()  { echo -e "\n${BLUE}${BOLD}========================================${NC}"; echo -e "${BLUE}${BOLD}  $*${NC}"; echo -e "${BLUE}${BOLD}========================================${NC}"; }
log_section() { echo -e "\n${CYAN}--- $* ---${NC}"; }

# Results tracking
declare -a RESULTS=()

# -------------------------------------------------------------------------
# Helper: determine Dockerfile based on JDK version
# -------------------------------------------------------------------------
get_dockerfile() {
  local jdk_version=$1
  if [ "$jdk_version" -le 11 ]; then
    echo "docker/Dockerfile.petclinic-legacy"
  else
    echo "docker/Dockerfile.petclinic"
  fi
}

# -------------------------------------------------------------------------
# Helper: cleanup docker compose environment
# -------------------------------------------------------------------------
cleanup() {
  log_section "Docker 환경 정리 중..."
  cd "${PROJECT_ROOT}"
  JDK_VERSION="${CURRENT_JDK:-17}" \
  DOCKERFILE="$(get_dockerfile "${CURRENT_JDK:-17}")" \
  docker compose -f "${COMPOSE_FILE}" down --volumes --remove-orphans 2>/dev/null || true

  # Force delete any remaining petclinic containers to prevent port conflicts
  for ver in "${ALL_JDK_VERSIONS[@]}"; do
    docker rm -f "petclinic-jdk${ver}" 2>/dev/null || true
  done
}

# -------------------------------------------------------------------------
# Helper: wait for application health
# -------------------------------------------------------------------------
wait_for_health() {
  local elapsed=0
  log_info "애플리케이션 기동 대기 중..."

  until curl -sf "${PETCLINIC_URL}/actuator/health" > /dev/null 2>&1; do
    if [ $elapsed -ge $MAX_WAIT ]; then
      log_error "애플리케이션이 ${MAX_WAIT}초 안에 기동되지 않았습니다."
      return 1
    fi
    sleep $INTERVAL
    elapsed=$((elapsed + INTERVAL))
    log_warn "대기 중... (${elapsed}s/${MAX_WAIT}s)"
  done

  log_info "애플리케이션 정상 기동 확인!"
  return 0
}

# -------------------------------------------------------------------------
# Helper: generate traffic
# -------------------------------------------------------------------------
generate_traffic() {
  local jdk_version=$1
  log_section "트래픽 생성 (JDK ${jdk_version})"

  ENDPOINTS=(
    "/"
    "/owners/find"
    "/owners?lastName="
    "/vets.html"
    "/visits/create"
  )

  for endpoint in "${ENDPOINTS[@]}"; do
    for i in $(seq 1 5); do
      curl -sf "${PETCLINIC_URL}${endpoint}" > /dev/null 2>&1 || true
    done
    log_info "  → ${endpoint} (5회 요청)"
  done

  log_info "트래픽 생성 완료. 메트릭 전파 대기 (20초)..."
  sleep 20
}

# -------------------------------------------------------------------------
# Helper: verify a single metric in Prometheus
# -------------------------------------------------------------------------
verify_metric() {
  local metric_name=$1
  local metric_source=$2
  local max_retries=6
  local retry_interval=10
  local attempt=0

  log_info "  Prometheus에서 '${metric_name}' 조회 중... (${metric_source})"

  while [ $attempt -lt $max_retries ]; do
    local query_url="${PROMETHEUS_URL}/api/v1/query?query=${metric_name}"
    local response
    response=$(curl -sf "${query_url}" 2>&1) || {
      attempt=$((attempt + 1))
      if [ $attempt -lt $max_retries ]; then
        log_warn "  Prometheus 쿼리 실패, 재시도 (${attempt}/${max_retries})..."
        sleep $retry_interval
        continue
      fi
      log_error "  ✗ Prometheus 쿼리 실패: ${metric_name}"
      return 1
    }

    # Check for non-empty result
    if echo "${response}" | grep -q '"resultType":"vector"' && \
       ! echo "${response}" | grep -q '"result":\[\]'; then
      log_info "  ✓ '${metric_name}' 메트릭 수집 확인!"
      return 0
    fi

    attempt=$((attempt + 1))
    if [ $attempt -lt $max_retries ]; then
      log_warn "  '${metric_name}' 아직 미수집, 재시도 (${attempt}/${max_retries})..."
      sleep $retry_interval
    fi
  done

  log_error "  ✗ '${metric_name}' 메트릭이 ${max_retries}회 시도 후에도 발견되지 않았습니다."
  return 1
}

# -------------------------------------------------------------------------
# Main: test a single JDK version
# -------------------------------------------------------------------------
test_jdk_version() {
  local jdk_version=$1
  CURRENT_JDK=$jdk_version

  log_header "JDK ${jdk_version} 테스트 시작"

  local dockerfile
  dockerfile=$(get_dockerfile "$jdk_version")
  log_info "Dockerfile: ${dockerfile}"

  # 1. Cleanup any previous run
  cleanup

  # 2. Build and start
  log_section "Docker 환경 빌드 및 기동 (JDK ${jdk_version})"
  cd "${PROJECT_ROOT}"

  JDK_VERSION="${jdk_version}" \
  DOCKERFILE="${dockerfile}" \
  docker compose -f "${COMPOSE_FILE}" up --build -d

  # 3. Wait for health
  if ! wait_for_health; then
    log_error "JDK ${jdk_version}: 애플리케이션 기동 실패"
    RESULTS+=("JDK ${jdk_version}: ❌ FAIL (기동 실패)")
    cleanup
    return 1
  fi

  # 4. Generate traffic
  generate_traffic "$jdk_version"

  # 5. Verify metrics
  log_section "메트릭 검증 (JDK ${jdk_version})"
  local all_passed=true

  # 5a. Javaagent extension metric
  if ! verify_metric "${AGENT_METRIC}" "Javaagent Extension"; then
    all_passed=false
  fi

  # 5b. Micrometer custom metrics (JDK version-aware)
  get_micrometer_metrics "$jdk_version"

  for metric in "${CURRENT_MICROMETER_METRICS[@]}"; do
    if ! verify_metric "${metric}" "Micrometer (앱 정의)"; then
      all_passed=false
    fi
  done

  # 6. Record result
  if [ "${all_passed}" = true ]; then
    log_info "✅ JDK ${jdk_version}: 모든 메트릭 검증 성공!"
    RESULTS+=("JDK ${jdk_version}: ✅ PASS")
  else
    log_error "❌ JDK ${jdk_version}: 일부 메트릭 검증 실패"
    RESULTS+=("JDK ${jdk_version}: ❌ FAIL")
  fi

  # 7. Cleanup
  cleanup
}

# =============================================================================
# Main execution
# =============================================================================

log_header "OTel Javaagent 멀티 JDK 검증 시작"
log_info "대상 JDK 버전: ${JDK_VERSIONS[*]}"
log_info "검증 메트릭:"
log_info "  - ${AGENT_METRIC} (Javaagent Extension)"
for metric in "${MICROMETER_METRICS_COMMON[@]}"; do
  log_info "  - ${metric} (Micrometer 공통)"
done
for metric in "${MICROMETER_METRICS_MODERN[@]}"; do
  log_info "  - ${metric} (Micrometer JDK 17+ 전용)"
done

# Trap for cleanup on exit
trap cleanup EXIT

# Test each JDK version
for jdk_version in "${JDK_VERSIONS[@]}"; do
  test_jdk_version "$jdk_version" || true
done

# =============================================================================
# Summary
# =============================================================================
log_header "검증 결과 요약"
echo ""
for result in "${RESULTS[@]}"; do
  echo -e "  ${result}"
done
echo ""

# Check for failures
has_failure=false
for result in "${RESULTS[@]}"; do
  if echo "${result}" | grep -q "FAIL"; then
    has_failure=true
    break
  fi
done

if [ "${has_failure}" = true ]; then
  log_error "일부 JDK 버전에서 검증 실패가 발생했습니다."
  exit 1
else
  log_info "🎉 모든 JDK 버전에서 검증 성공!"
  exit 0
fi
