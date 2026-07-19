#!/usr/bin/env bash
# casdoor-spa-provision.sh — 前端 OIDC（authorization_code+PKCE）的 Casdoor SPA 应用 + 测试用户 provision
# -----------------------------------------------------------------------------
# 目的：按 52-frontend-oidc-login-design.md §1 建「每租户一个 SPA 公有应用」+ 每租户一个测试用户，
#       供浏览器登录（authorize+PKCE → 回调换 token → Bearer 打 /activity-marketing/**）。
# 与 M2M（casdoor-m2m-verify.sh）的区别：
#   - grant = authorization_code + refresh_token（M2M 是 client_credentials）
#   - 有 redirectUris（回调地址）+ Password 登录方式（浏览器人登录）
#   - 浏览器端**不放 client_secret**（公有客户端），PKCE(S256) 防授权码截获；
#     Casdoor 应用必有 secret 字段，但前端永不下发/引用它。
#   - client_id 命名 activity-{tenant}-web-cid，会被模板 activity-{tenant}-cid 误反解成
#     租户 "acme-web"，故 **必须**在 application.yml 的 client-tenant-map 显式映射（map 优先于模板）。
# 用法：bash scratchpad/casdoor-spa-provision.sh
# 前置：Casdoor 在 localhost:8000（容器 authz-casdoor + authz-postgres）；jq/curl/docker 可用。
# 幂等：应用/用户已存在则跳过创建；密码为 dev 固定值（本机 dev Casdoor，非生产）。
set -uo pipefail

CASDOOR="${CASDOOR_URL:-http://localhost:8000}"
BUILTIN_CID="${BUILTIN_CID:-ea46d9a8033b0be2d8ed}"
ADMIN="${CASDOOR_ADMIN:-admin}"; ADMIN_PW="${CASDOOR_ADMIN_PW:-123}"

# 回调地址 = auth 档 demo 首页（activity.js 在页面加载时检测 ?code= 完成换 token）
REDIRECT="${REDIRECT_URI:-http://localhost:8099/index.html}"

ACME_WEB_CID="activity-acme-web-cid"
BETA_WEB_CID="activity-beta-web-cid"
# 用户名带 act- 前缀：不碰 auth-platform 等其它项目在同一 dev Casdoor 里的既有用户（如 acme/alice）
ACME_USER="act-alice"; ACME_USER_PW="${ACME_USER_PW:-act-alice-dev-pass-01}"
BETA_USER="act-bob";   BETA_USER_PW="${BETA_USER_PW:-act-bob-dev-pass-02}"

command -v jq >/dev/null   || { echo "需要 jq"; exit 1; }
command -v curl >/dev/null || { echo "需要 curl"; exit 1; }

pass=0; fail=0
ok(){ echo "  ✅ $1"; pass=$((pass+1)); }
no(){ echo "  ❌ $1"; fail=$((fail+1)); }
hr(){ echo "────────────────────────────────────────────────────────"; }

# ── admin token（built-in app secret 不入库，从 Postgres 现查；token 仅本进程内用）──
BSEC=$(docker exec authz-postgres psql -U authz -d spicedb -tAc \
  "select client_secret from application where client_id='${BUILTIN_CID}'" 2>/dev/null | tr -d '[:space:]')
AT=$(curl -s -X POST "${CASDOOR}/api/login/oauth/access_token" -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&username=${ADMIN}&password=${ADMIN_PW}&client_id=${BUILTIN_CID}&client_secret=${BSEC}&scope=openid" \
  | jq -r '.access_token // empty')
[ -n "${AT}" ] || { echo "拿不到 Casdoor admin token（检查 admin/123 + built-in app 凭据）"; exit 1; }

capi(){ curl -s -X POST "${CASDOOR}/api/$1" -H "Authorization: Bearer ${AT}" -H "Content-Type: application/json" -d "$2"; }

# ══════════════ ① SPA 公有应用（每租户一个，authorization_code+PKCE）══════════════
ensure_spa_app(){
  local cid="$1" org="$2"
  local exist; exist=$(curl -s "${CASDOOR}/api/get-application?id=admin/${cid}" -H "Authorization: Bearer ${AT}" | jq -r '.data.name // empty')
  if [ -n "${exist}" ]; then ok "app ${cid} 已存在（幂等跳过）"; return; fi
  # clientSecret 仍是 dev 固定值（Casdoor 必填字段），但公有客户端流程（PKCE）不用它、前端不下发。
  local st; st=$(capi add-application "{\"owner\":\"admin\",\"name\":\"${cid}\",\"displayName\":\"活动控制台(${org})\",\"organization\":\"${org}\",\"cert\":\"cert-built-in\",\"tokenFormat\":\"JWT\",\"expireInHours\":1,\"refreshExpireInHours\":24,\"enablePassword\":true,\"enableSignUp\":false,\"clientId\":\"${cid}\",\"clientSecret\":\"${cid}-secret-dev-only\",\"grantTypes\":[\"authorization_code\",\"refresh_token\"],\"redirectUris\":[\"${REDIRECT}\"],\"signinMethods\":[{\"name\":\"Password\",\"displayName\":\"Password\",\"rule\":\"All\"}],\"providers\":[]}" | jq -r '.status')
  [ "${st}" = "ok" ] && ok "创建 SPA app ${cid}（org=${org}, grant=authorization_code+refresh, redirect=${REDIRECT}）" \
                     || no "创建 SPA app ${cid} 失败（status=${st}）"
}

# ══════════════ ② 测试用户（每租户一个，浏览器密码登录）══════════════
ensure_user(){
  local org="$1" name="$2" pw="$3" app="$4"
  local exist; exist=$(curl -s "${CASDOOR}/api/get-user?id=${org}/${name}" -H "Authorization: Bearer ${AT}" | jq -r '.data.name // empty')
  if [ -n "${exist}" ]; then ok "user ${org}/${name} 已存在（幂等跳过）"; return; fi
  local st; st=$(capi add-user "{\"owner\":\"${org}\",\"name\":\"${name}\",\"displayName\":\"${name}\",\"password\":\"${pw}\",\"email\":\"${name}@${org}.dev\",\"phone\":\"\",\"type\":\"normal-user\",\"signupApplication\":\"${app}\"}" | jq -r '.status')
  [ "${st}" = "ok" ] && ok "创建 user ${org}/${name}（密码=dev 固定值）" || no "创建 user ${org}/${name} 失败（status=${st}）"
}

hr; echo "① SPA 公有应用（authorization_code+PKCE，每租户一个）"
ensure_spa_app "${ACME_WEB_CID}" "acme"
ensure_spa_app "${BETA_WEB_CID}" "beta"

hr; echo "② 测试用户（浏览器密码登录）"
ensure_user "acme" "${ACME_USER}" "${ACME_USER_PW}" "${ACME_WEB_CID}"
ensure_user "beta" "${BETA_USER}" "${BETA_USER_PW}" "${BETA_WEB_CID}"

hr
echo "结果：pass=${pass}  fail=${fail}"
echo "登录账号（dev）：acme → ${ACME_USER}/${ACME_USER_PW}；beta → ${BETA_USER}/${BETA_USER_PW}"
echo "下一步：application.yml 的 client-tenant-map 需含："
echo "  ${ACME_WEB_CID}: acme"
echo "  ${BETA_WEB_CID}: beta"
exit $(( fail > 0 ? 1 : 0 ))
