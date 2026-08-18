#!/usr/bin/env bash

# 一键构建并部署活动引擎前后端。
# --full 会先执行 Maven 全模块打包（含 Vue），再构建镜像并启动 nginx 与全部基础设施。

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/deploy/docker-compose.yml"
TIMEOUT_SECONDS=300
RETRY_COUNT=3
CORE_ONLY=false
NO_CACHE=false
PULL_IMAGES=false
SKIP_BUILD=false
FULL_DEPLOY=false
FRONTEND_ONLY=false
DRY_RUN=false
DEPLOY_STARTED=false
PROVISION_AUTH=false

COMPOSE_CMD=()
CORE_SERVICES=(mysql xxl-job-admin console decision gateway)
OBSERVABILITY_SERVICES=(prometheus grafana)

if [[ -t 1 ]]; then
  COLOR_BLUE=$'\033[34m'
  COLOR_GREEN=$'\033[32m'
  COLOR_YELLOW=$'\033[33m'
  COLOR_RED=$'\033[31m'
  COLOR_RESET=$'\033[0m'
else
  COLOR_BLUE=''
  COLOR_GREEN=''
  COLOR_YELLOW=''
  COLOR_RED=''
  COLOR_RESET=''
fi

info() { printf '%s[INFO]%s %s\n' "${COLOR_BLUE}" "${COLOR_RESET}" "$*"; }
success() { printf '%s[ OK ]%s %s\n' "${COLOR_GREEN}" "${COLOR_RESET}" "$*"; }
warn() { printf '%s[WARN]%s %s\n' "${COLOR_YELLOW}" "${COLOR_RESET}" "$*"; }
fail() { printf '%s[FAIL]%s %s\n' "${COLOR_RED}" "${COLOR_RESET}" "$*" >&2; }
die() { fail "$*"; exit 1; }

usage() {
  cat <<'EOF'
用法：./deploy.sh [选项]

默认行为：
  1. 校验 Docker 与 Compose 配置
  2. 构建前端、console 后端和 decision 后端镜像
  3. 启动 MySQL、前后端网关、Prometheus 和 Grafana
  4. 等待核心服务健康，并验证前端页面可访问

选项：
  --full            先 Maven 全模块打包（含前端和测试），再构建并启动全部服务
  --frontend-only   只重建并发布 Vue + nginx，不重启后端和基础设施
  --core-only       只部署 MySQL、console、decision 和 gateway
  --no-cache        不使用 Docker 构建缓存
  --pull            更新基础镜像和第三方服务镜像后再部署
  --skip-build      使用本地已有镜像启动，跳过源码构建（适合离线恢复）
  --timeout <秒>    健康检查超时时间，默认 300 秒
  --retries <次数>  拉取或构建失败时的重试次数，默认 3 次
  --dry-run         只校验环境并打印部署计划，不构建或启动容器
  --provision-auth  部署前幂等配置本机 Casdoor 的 acme/beta SPA 应用、8095 回调和测试用户
  -h, --help        显示帮助

示例：
  ./deploy.sh
  ./deploy.sh --full
  ./deploy.sh --frontend-only
  ./deploy.sh --provision-auth
  ./deploy.sh --pull
  ./deploy.sh --core-only --no-cache --timeout 600
EOF
}

while (($# > 0)); do
  case "$1" in
    --full)
      FULL_DEPLOY=true
      shift
      ;;
    --frontend-only)
      FRONTEND_ONLY=true
      shift
      ;;
    --core-only)
      CORE_ONLY=true
      shift
      ;;
    --no-cache)
      NO_CACHE=true
      shift
      ;;
    --pull)
      PULL_IMAGES=true
      shift
      ;;
    --skip-build)
      SKIP_BUILD=true
      shift
      ;;
    --dry-run)
      DRY_RUN=true
      shift
      ;;
    --provision-auth)
      PROVISION_AUTH=true
      shift
      ;;
    --timeout)
      (($# >= 2)) || die "--timeout 后必须提供秒数"
      [[ "$2" =~ ^[1-9][0-9]*$ ]] || die "--timeout 必须是大于 0 的整数"
      TIMEOUT_SECONDS="$2"
      shift 2
      ;;
    --retries)
      (($# >= 2)) || die "--retries 后必须提供次数"
      [[ "$2" =~ ^[1-9][0-9]*$ ]] || die "--retries 必须是大于 0 的整数"
      RETRY_COUNT="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "未知选项：$1（使用 --help 查看帮助）"
      ;;
  esac
done

if [[ "${SKIP_BUILD}" == true && "${NO_CACHE}" == true ]]; then
  die "--skip-build 与 --no-cache 不能同时使用"
fi
if [[ "${FULL_DEPLOY}" == true && "${CORE_ONLY}" == true ]]; then
  die "--full 会启动全部服务，不能与 --core-only 同时使用"
fi
if [[ "${FULL_DEPLOY}" == true && "${SKIP_BUILD}" == true ]]; then
  die "--full 必须构建最新镜像，不能与 --skip-build 同时使用"
fi
if [[ "${FRONTEND_ONLY}" == true && "${FULL_DEPLOY}" == true ]]; then
  die "--frontend-only 不能与 --full 同时使用"
fi
if [[ "${FRONTEND_ONLY}" == true && "${CORE_ONLY}" == true ]]; then
  die "--frontend-only 不能与 --core-only 同时使用"
fi
if [[ "${FRONTEND_ONLY}" == true && "${SKIP_BUILD}" == true ]]; then
  die "--frontend-only 必须构建最新前端镜像，不能与 --skip-build 同时使用"
fi

command -v docker >/dev/null 2>&1 || die "未找到 docker，请先安装 Docker Desktop 或 Docker Engine"
docker info >/dev/null 2>&1 || die "Docker 服务未运行，请先启动 Docker"

if docker compose version >/dev/null 2>&1; then
  COMPOSE_CMD=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE_CMD=(docker-compose)
else
  die "未找到 Docker Compose，请安装 docker compose 插件"
fi

[[ -f "${COMPOSE_FILE}" ]] || die "Compose 文件不存在：${COMPOSE_FILE}"

compose() {
  "${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" "$@"
}

print_command() {
  printf '  '
  printf '%q ' "$@"
  printf '\n'
}

show_failure_context() {
  [[ "${DEPLOY_STARTED}" == true ]] || return 0
  fail "部署未完成，以下是容器状态和最近日志："
  compose ps || true
  compose logs --no-color --tail=120 "${CORE_SERVICES[@]}" || true
}

on_error() {
  local exit_code=$?
  trap - ERR
  show_failure_context
  exit "${exit_code}"
}
trap on_error ERR

container_state() {
  local service="$1"
  local container_id
  container_id="$(compose ps -q "${service}" 2>/dev/null || true)"
  if [[ -z "${container_id}" ]]; then
    printf 'missing'
    return 0
  fi
  docker inspect --format '{{.State.Status}}' "${container_id}" 2>/dev/null || printf 'unknown'
}

wait_for_mysql() {
  local started=${SECONDS}
  local container_id state health
  info "等待 MySQL 健康（最多 ${TIMEOUT_SECONDS} 秒）…"

  while ((SECONDS - started < TIMEOUT_SECONDS)); do
    container_id="$(compose ps -q mysql 2>/dev/null || true)"
    if [[ -n "${container_id}" ]]; then
      state="$(docker inspect --format '{{.State.Status}}' "${container_id}" 2>/dev/null || true)"
      health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "${container_id}" 2>/dev/null || true)"
      if [[ "${health}" == healthy ]]; then
        success "MySQL 已健康"
        return 0
      fi
      if [[ "${state}" == exited || "${state}" == dead ]]; then
        fail "MySQL 容器已退出"
        return 1
      fi
    fi
    sleep 2
  done

  fail "等待 MySQL 健康超时"
  return 1
}

guard_mysql_data_volume() {
  local container_id volume_name compose_volume_label
  container_id="$(compose ps -q mysql 2>/dev/null || true)"
  [[ -n "${container_id}" ]] || return 0

  volume_name="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/mysql"}}{{.Name}}{{end}}{{end}}' \
    "${container_id}" 2>/dev/null || true)"
  [[ -n "${volume_name}" ]] || die "现有 MySQL 的 /var/lib/mysql 不是 Docker 卷，拒绝自动重建"
  compose_volume_label="$(docker volume inspect --format '{{index .Labels "com.docker.compose.volume"}}' \
    "${volume_name}" 2>/dev/null || true)"
  if [[ "${compose_volume_label}" != "mysql-data" ]]; then
    die "现有 MySQL 使用旧匿名卷 ${volume_name}。为避免重建时丢库，需先备份并迁移到 mysql-data 命名卷；本次未自动执行危险迁移"
  fi
}

mysql_root_scalar() {
  local query="$1"
  compose exec -T mysql sh -ec \
    'exec mysql --default-character-set=utf8mb4 -uroot -p"${MYSQL_ROOT_PASSWORD:?}" -Nse "$1"' \
    mysql-root "${query}" 2>/dev/null
}

initialize_xxl_job_schema() {
  local sql_file="${SCRIPT_DIR}/deploy/mysql-init/02-xxl-job.sql"
  local table_count column_count
  [[ -f "${sql_file}" ]] || die "缺少 XXL-JOB 初始化脚本：${sql_file}"
  info "幂等初始化并校验 XXL-JOB 3.4.2 调度库…"
  compose exec -T mysql sh -ec \
    'exec mysql --default-character-set=utf8mb4 -uroot -p"${MYSQL_ROOT_PASSWORD:?}"' \
    < "${sql_file}"

  table_count="$(mysql_root_scalar "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='xxl_job' AND table_name IN ('xxl_job_group','xxl_job_registry','xxl_job_info','xxl_job_logglue','xxl_job_log','xxl_job_log_report','xxl_job_lock','xxl_job_user')")"
  column_count="$(mysql_root_scalar "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='xxl_job' AND table_name IN ('xxl_job_group','xxl_job_registry','xxl_job_info','xxl_job_logglue','xxl_job_log','xxl_job_log_report','xxl_job_lock','xxl_job_user')")"
  [[ "${table_count}" == "8" && "${column_count}" == "70" ]] \
    || die "xxl_job 结构不是预期的 3.4.2 Schema（tables=${table_count}, columns=${column_count}）；请先备份并执行官方版本迁移"
  success "XXL-JOB 调度库已就绪（8 张表 / 70 列）"
}

wait_for_xxl_admin() {
  local started=${SECONDS}
  local state
  info "等待 XXL-JOB Admin 健康（最多 ${TIMEOUT_SECONDS} 秒）…"
  while ((SECONDS - started < TIMEOUT_SECONDS)); do
    state="$(container_state xxl-job-admin)"
    if [[ "${state}" == exited || "${state}" == dead ]]; then
      fail "XXL-JOB Admin 容器已退出"
      return 1
    fi
    if [[ "${state}" == running ]] \
      && compose exec -T xxl-job-admin curl -fsS http://localhost:8080/actuator/health \
        >/dev/null 2>&1; then
      success "XXL-JOB Admin 已健康"
      return 0
    fi
    sleep 2
  done
  fail "等待 XXL-JOB Admin 健康超时"
  return 1
}

wait_for_xxl_job_ready() {
  local started=${SECONDS}
  local registry_count success_count
  info "等待 XXL 执行器注册并完成一次活动生命周期任务（最多 ${TIMEOUT_SECONDS} 秒）…"
  while ((SECONDS - started < TIMEOUT_SECONDS)); do
    registry_count="$(mysql_root_scalar "SELECT COUNT(*) FROM xxl_job.xxl_job_registry WHERE registry_group='EXECUTOR' AND registry_key='activity-console-executor'" || true)"
    success_count="$(mysql_root_scalar "SELECT COUNT(*) FROM xxl_job.xxl_job_log WHERE executor_handler='activityLifecycleSweep' AND trigger_code=200 AND handle_code=200" || true)"
    if [[ "${registry_count:-0}" -gt 0 && "${success_count:-0}" -gt 0 ]]; then
      success "XXL 执行器已注册，活动生命周期任务已成功执行"
      return 0
    fi
    sleep 2
  done
  fail "XXL 执行器未注册或任务没有成功执行"
  return 1
}

wait_for_service_http() {
  local service="$1"
  local url="$2"
  local label="$3"
  local started=${SECONDS}
  local state
  info "等待 ${label} 健康（最多 ${TIMEOUT_SECONDS} 秒）…"

  while ((SECONDS - started < TIMEOUT_SECONDS)); do
    state="$(container_state "${service}")"
    if [[ "${state}" == exited || "${state}" == dead ]]; then
      fail "${label} 容器已退出"
      return 1
    fi
    if [[ "${state}" == running ]] && compose exec -T "${service}" curl -fsS --max-time 4 "${url}" >/dev/null 2>&1; then
      success "${label} 已健康"
      return 0
    fi
    sleep 2
  done

  fail "等待 ${label} 健康超时"
  return 1
}

wait_for_gateway() {
  local started=${SECONDS}
  local state
  info "等待网关和前端页面可访问（最多 ${TIMEOUT_SECONDS} 秒）…"

  while ((SECONDS - started < TIMEOUT_SECONDS)); do
    state="$(container_state gateway)"
    if [[ "${state}" == exited || "${state}" == dead ]]; then
      fail "gateway 容器已退出"
      return 1
    fi
    # Alpine 的 localhost 可能优先解析为 ::1，而 nginx 当前只监听 IPv4，固定使用 loopback IPv4。
    if [[ "${state}" == running ]] \
      && compose exec -T gateway wget -q -O /dev/null http://127.0.0.1/actuator/health >/dev/null 2>&1 \
      && compose exec -T gateway wget -q -O /dev/null http://127.0.0.1/ui/console >/dev/null 2>&1; then
      success "网关与前端页面已就绪"
      return 0
    fi
    sleep 2
  done

  fail "等待网关或前端页面就绪超时"
  return 1
}

wait_for_frontend() {
  local started=${SECONDS}
  local state
  info "等待前端 nginx 与 SPA 页面可访问（最多 ${TIMEOUT_SECONDS} 秒）…"

  while ((SECONDS - started < TIMEOUT_SECONDS)); do
    state="$(container_state gateway)"
    if [[ "${state}" == exited || "${state}" == dead ]]; then
      fail "前端 nginx 容器已退出"
      return 1
    fi
    if [[ "${state}" == running ]] \
      && compose exec -T gateway wget -q -O /dev/null http://127.0.0.1/nginx-health >/dev/null 2>&1 \
      && compose exec -T gateway wget -q -O /dev/null http://127.0.0.1/ui/console >/dev/null 2>&1; then
      success "前端 nginx 与 SPA 页面已就绪"
      return 0
    fi
    sleep 2
  done

  fail "等待前端 nginx 或 SPA 页面就绪超时"
  return 1
}

published_url() {
  local service="$1"
  local container_port="$2"
  local path="${3:-}"
  local binding port
  binding="$(compose port "${service}" "${container_port}" 2>/dev/null | tail -n 1 || true)"
  port="${binding##*:}"
  if [[ "${port}" =~ ^[0-9]+$ ]]; then
    printf 'http://localhost:%s%s' "${port}" "${path}"
  fi
}

print_build_plan() {
  local services=("$@")
  if [[ "${NO_CACHE}" == true && "${PULL_IMAGES}" == true ]]; then
    print_command "${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" build --no-cache --pull "${services[@]}"
  elif [[ "${NO_CACHE}" == true ]]; then
    print_command "${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" build --no-cache "${services[@]}"
  elif [[ "${PULL_IMAGES}" == true ]]; then
    print_command "${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" build --pull "${services[@]}"
  else
    print_command "${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" build "${services[@]}"
  fi
}

build_images() {
  local services=("$@")
  if [[ "${NO_CACHE}" == true && "${PULL_IMAGES}" == true ]]; then
    compose build --no-cache --pull "${services[@]}"
  elif [[ "${NO_CACHE}" == true ]]; then
    compose build --no-cache "${services[@]}"
  elif [[ "${PULL_IMAGES}" == true ]]; then
    compose build --pull "${services[@]}"
  else
    compose build "${services[@]}"
  fi
}

pull_images() {
  compose pull "${PULL_SERVICES[@]}"
}

run_with_retry() {
  local label="$1"
  shift
  local attempt=1
  local delay_seconds

  while ((attempt <= RETRY_COUNT)); do
    if "$@"; then
      return 0
    fi
    if ((attempt == RETRY_COUNT)); then
      fail "${label}失败，已尝试 ${RETRY_COUNT} 次"
      return 1
    fi
    delay_seconds=$((attempt * 5))
    warn "${label}失败，${delay_seconds} 秒后重试（${attempt}/${RETRY_COUNT}）…"
    sleep "${delay_seconds}"
    attempt=$((attempt + 1))
  done
}

run_maven_package() {
  local console_jar decision_jar
  [[ -x "${SCRIPT_DIR}/mvnw" ]] || die "未找到可执行的 Maven Wrapper：${SCRIPT_DIR}/mvnw"
  command -v java >/dev/null 2>&1 || die "未找到 Java，请安装 JDK 21"

  info "执行 Maven 全模块打包（包含测试与 Vue 前端）…"
  (
    cd "${SCRIPT_DIR}"
    ./mvnw -Pfrontend clean package
  )

  console_jar="$(find "${SCRIPT_DIR}/activity-console/target" -maxdepth 1 -type f -name 'activity-console-*.jar' ! -name '*.original' -print -quit)"
  decision_jar="$(find "${SCRIPT_DIR}/activity-decision/target" -maxdepth 1 -type f -name 'activity-decision-*.jar' ! -name '*.original' -print -quit)"
  [[ -n "${console_jar}" ]] || die "Maven 成功结束，但未找到 activity-console JAR"
  [[ -n "${decision_jar}" ]] || die "Maven 成功结束，但未找到 activity-decision JAR"
  [[ -f "${SCRIPT_DIR}/activity-console/target/classes/static/ui/index.html" ]] \
    || die "Maven 成功结束，但 console 产物中缺少 Vue 前端页面"
  success "Maven 打包完成：后端 JAR 与前端静态资源均已生成"
}

build_frontend_assets() {
  command -v npm >/dev/null 2>&1 || die "未找到 npm，前端独立部署需要 Node.js 22 与 npm"
  info "安装前端依赖并构建 Vue 生产资源…"
  npm --prefix "${SCRIPT_DIR}/frontend" ci --prefer-offline --no-audit --no-fund
  npm --prefix "${SCRIPT_DIR}/frontend" run build
  [[ -f "${SCRIPT_DIR}/frontend/dist/index.html" ]] || die "前端构建结束，但未找到 frontend/dist/index.html"
  success "Vue 生产资源已生成"
}

info "校验 Docker Compose 配置…"
compose config >/dev/null
success "Compose 配置有效"

UP_SERVICES=("${CORE_SERVICES[@]}")
if [[ "${CORE_ONLY}" == false ]]; then
  UP_SERVICES+=("${OBSERVABILITY_SERVICES[@]}")
fi

if [[ "${DRY_RUN}" == true ]]; then
  info "Dry-run：将执行以下部署命令"
  if [[ "${PROVISION_AUTH}" == true ]]; then
    print_command bash "${SCRIPT_DIR}/scratchpad/casdoor-spa-provision.sh"
  fi
  if [[ "${FULL_DEPLOY}" == true ]]; then
    print_command "${SCRIPT_DIR}/mvnw" -Pfrontend clean package
  elif [[ "${FRONTEND_ONLY}" == false && "${SKIP_BUILD}" == false ]]; then
    print_command npm --prefix "${SCRIPT_DIR}/frontend" ci --prefer-offline --no-audit --no-fund
    print_command npm --prefix "${SCRIPT_DIR}/frontend" run build
  fi
  if [[ "${PULL_IMAGES}" == true && "${FRONTEND_ONLY}" == false ]]; then
    PULL_SERVICES=(mysql xxl-job-admin)
    if [[ "${CORE_ONLY}" == false ]]; then
      PULL_SERVICES+=("${OBSERVABILITY_SERVICES[@]}")
    fi
    print_command "${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" pull "${PULL_SERVICES[@]}"
  fi
  if [[ "${FRONTEND_ONLY}" == true ]]; then
    print_command npm --prefix "${SCRIPT_DIR}/frontend" ci --prefer-offline --no-audit --no-fund
    print_command npm --prefix "${SCRIPT_DIR}/frontend" run build
    print_build_plan gateway
    print_command "${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" up -d --no-deps --force-recreate gateway
  elif [[ "${SKIP_BUILD}" == false ]]; then
    print_build_plan console decision gateway
    print_command "${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" up -d mysql
    printf '  初始化并校验 %q（XXL-JOB 3.4.2：8 张表 / 70 列）\n' \
      "${SCRIPT_DIR}/deploy/mysql-init/02-xxl-job.sql"
    print_command "${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" up -d --remove-orphans "${UP_SERVICES[@]}"
  else
    info "将跳过源码构建，使用本地 activity-console、activity-decision 与 activity-frontend 镜像"
    print_command "${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" up -d mysql
    printf '  初始化并校验 %q（XXL-JOB 3.4.2：8 张表 / 70 列）\n' \
      "${SCRIPT_DIR}/deploy/mysql-init/02-xxl-job.sql"
    print_command "${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" up -d --remove-orphans "${UP_SERVICES[@]}"
  fi
  success "Dry-run 完成，未修改任何容器"
  exit 0
fi

if [[ "${PROVISION_AUTH}" == true ]]; then
  [[ -f "${SCRIPT_DIR}/scratchpad/casdoor-spa-provision.sh" ]] \
    || die "Casdoor provision 脚本不存在：${SCRIPT_DIR}/scratchpad/casdoor-spa-provision.sh"
  command -v jq >/dev/null 2>&1 || die "--provision-auth 需要 jq"
  command -v curl >/dev/null 2>&1 || die "--provision-auth 需要 curl"
  info "幂等配置本机 Casdoor SPA 应用、8095 callback 与测试用户…"
  bash "${SCRIPT_DIR}/scratchpad/casdoor-spa-provision.sh"
  success "Casdoor 本地认证资源已就绪"
fi

if [[ "${FRONTEND_ONLY}" == true ]]; then
  build_frontend_assets
  info "只构建 Vue 前端与 nginx 镜像…"
  run_with_retry "构建前端 nginx 镜像" build_images gateway
  info "只重建前端 nginx 容器，后端与基础设施保持运行…"
  DEPLOY_STARTED=true
  compose up -d --no-deps --force-recreate gateway
  wait_for_frontend
  compose ps gateway
  GATEWAY_URL="$(published_url gateway 80 /ui/console)"
  success "前端独立部署完成"
  if [[ -n "${GATEWAY_URL}" ]]; then
    printf '  控制台：%s\n' "${GATEWAY_URL}"
  fi
  exit 0
fi

guard_mysql_data_volume

if [[ "${FULL_DEPLOY}" == true ]]; then
  run_maven_package
elif [[ "${SKIP_BUILD}" == false ]]; then
  build_frontend_assets
fi

if [[ "${PULL_IMAGES}" == true ]]; then
  PULL_SERVICES=(mysql xxl-job-admin)
  if [[ "${CORE_ONLY}" == false ]]; then
    PULL_SERVICES+=("${OBSERVABILITY_SERVICES[@]}")
  fi
  info "更新第三方服务镜像…"
  run_with_retry "拉取第三方镜像" pull_images
fi

if [[ "${SKIP_BUILD}" == false ]]; then
  info "构建前端与后端镜像…"
  run_with_retry "构建前后端镜像" build_images console decision gateway
else
  docker image inspect activity-console:latest >/dev/null 2>&1 || die "本地缺少 activity-console:latest，无法跳过构建"
  docker image inspect activity-decision:latest >/dev/null 2>&1 || die "本地缺少 activity-decision:latest，无法跳过构建"
  docker image inspect activity-frontend:latest >/dev/null 2>&1 || die "本地缺少 activity-frontend:latest，无法跳过构建"
  warn "已跳过源码构建，正在使用本地已有前后端镜像"
fi

info "先启动 MySQL 并准备调度库"
DEPLOY_STARTED=true
compose up -d mysql
wait_for_mysql
initialize_xxl_job_schema

info "启动服务：${UP_SERVICES[*]}"
compose up -d --remove-orphans "${UP_SERVICES[@]}"

wait_for_mysql
wait_for_xxl_admin
wait_for_service_http console http://localhost:8080/actuator/health "console 后端"
wait_for_service_http decision http://localhost:8080/actuator/health "decision 后端"
wait_for_gateway
wait_for_xxl_job_ready

compose ps

GATEWAY_URL="$(published_url gateway 80 /ui/console)"
PROMETHEUS_URL=''
GRAFANA_URL=''
if [[ "${CORE_ONLY}" == false ]]; then
  PROMETHEUS_URL="$(published_url prometheus 9090)"
  GRAFANA_URL="$(published_url grafana 3000)"
fi

success "前后端 Docker 部署完成"
if [[ -n "${GATEWAY_URL}" ]]; then
  printf '  控制台：%s\n' "${GATEWAY_URL}"
fi
if [[ -n "${PROMETHEUS_URL}" ]]; then
  printf '  Prometheus：%s\n' "${PROMETHEUS_URL}"
fi
if [[ -n "${GRAFANA_URL}" ]]; then
  printf '  Grafana：%s（默认管理员密码：admin）\n' "${GRAFANA_URL}"
fi
XXL_ADMIN_URL="$(published_url xxl-job-admin 8080 /)"
if [[ -n "${XXL_ADMIN_URL}" ]]; then
  printf '  XXL-JOB Admin：%s\n' "${XXL_ADMIN_URL}"
fi
