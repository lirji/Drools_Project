#!/usr/bin/env bash
# casdoor-m2m-verify.sh — P0-3 真 Casdoor M2M 决策身份端到端冒烟（重建版）
# -----------------------------------------------------------------------------
# 目的：证 P0-3「每租户独立 client_credentials 应用 + 唯一 secret（非共享派生）」在真 Casdoor 成立，
#       且 app(:8099, auth 档) 用真 token 做到跨租户读隔离（acme 建活动 → beta 看不到）。
# 三段（可分阶段跑）：
#   ① PROVISION 造两个独立 M2M 应用（activity-acme-cid / activity-beta-cid，各自唯一 secret，grant=client_credentials）
#   ② MINT     用 client_credentials 各铸真 token；并证「acme 的 client_id 换不出用 beta secret 的 token」（secret 不可伪造）
#   ③ SMOKE    打运行中的 app：无 token/垃圾 token → 401；acme 建活动 → beta 列表看不到、详情越权 fail-closed；信封≠租户 → 403
# 用法（app 需已以 auth 档起在 :8099，见脚本尾部启动命令）：
#   bash scratchpad/casdoor-m2m-verify.sh
#   PROVISION_ONLY=1 bash scratchpad/casdoor-m2m-verify.sh   # 只造应用
#   SMOKE_ONLY=1     bash scratchpad/casdoor-m2m-verify.sh   # 应用已存在，只铸 token + 冒烟
# 前置：Casdoor 在 localhost:8000（容器 authz-casdoor + authz-postgres）；jq/curl/docker 可用。
# 幂等：应用已存在则跳过创建；secret 为 dev 固定值（本机 dev Casdoor，非生产）。
set -uo pipefail

CASDOOR="${CASDOOR_URL:-http://localhost:8000}"
APP="${APP_URL:-http://localhost:8099}"
BUILTIN_CID="${BUILTIN_CID:-ea46d9a8033b0be2d8ed}"
ADMIN="${CASDOOR_ADMIN:-admin}"; ADMIN_PW="${CASDOOR_ADMIN_PW:-123}"

ACME_CID="activity-acme-cid"; ACME_SEC="${ACME_SECRET:-activity-acme-secret-000000000001}"
BETA_CID="activity-beta-cid"; BETA_SEC="${BETA_SECRET:-activity-beta-secret-000000000002}"
# 非家族 aud（不匹配 activity-{tenant}-cid 模板）→ app 应 401（家族外拒）
ALIEN_CID="decision-alien-cid"; ALIEN_SEC="${ALIEN_SECRET:-decision-alien-secret-00000000003}"

command -v jq >/dev/null   || { echo "需要 jq"; exit 1; }
command -v curl >/dev/null || { echo "需要 curl"; exit 1; }

pass=0; fail=0
ok(){ echo "  ✅ $1"; pass=$((pass+1)); }
no(){ echo "  ❌ $1"; fail=$((fail+1)); }
hr(){ echo "────────────────────────────────────────────────────────"; }

# 解码 JWT payload 的几个 claim（无签名校验，仅观察）
jwt_claims(){ printf '%s' "$1" | cut -d. -f2 | tr '_-' '/+' \
  | { b=$(cat); p=$(( (4-${#b}%4)%4 )); printf '%s%*s' "${b}" "${p}" '' | tr ' ' '='; } \
  | base64 -d 2>/dev/null | jq -c '{iss,aud,sub,owner}' 2>/dev/null; }

# ── admin token（built-in app secret 不入库，从 Postgres 现查）──
BSEC=$(docker exec authz-postgres psql -U authz -d spicedb -tAc \
  "select client_secret from application where client_id='${BUILTIN_CID}'" 2>/dev/null | tr -d '[:space:]')
AT=$(curl -s -X POST "${CASDOOR}/api/login/oauth/access_token" -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&username=${ADMIN}&password=${ADMIN_PW}&client_id=${BUILTIN_CID}&client_secret=${BSEC}&scope=openid" \
  | jq -r '.access_token // empty')
[ -n "${AT}" ] || { echo "拿不到 Casdoor admin token（检查 admin/123 + built-in app 凭据）"; exit 1; }

capi(){ curl -s -X POST "${CASDOOR}/api/$1" -H "Authorization: Bearer ${AT}" -H "Content-Type: application/json" -d "$2"; }

# ══════════════ ① PROVISION：每租户独立 M2M 应用 ══════════════
ensure_app(){
  local cid="$1" sec="$2" org="$3"
  local exist; exist=$(curl -s "${CASDOOR}/api/get-application?id=admin/${cid}" -H "Authorization: Bearer ${AT}" | jq -r '.data.name // empty')
  if [ -n "${exist}" ]; then echo "  · app ${cid} 已存在，跳过创建（幂等）"; return; fi
  echo "  · 创建 M2M app ${cid}（org=${org}, grant=client_credentials, 独立 secret）"
  capi add-application "{\"owner\":\"admin\",\"name\":\"${cid}\",\"displayName\":\"${cid}\",\"organization\":\"${org}\",\"cert\":\"cert-built-in\",\"tokenFormat\":\"JWT\",\"expireInHours\":24,\"refreshExpireInHours\":24,\"enablePassword\":false,\"enableSignUp\":false,\"clientId\":\"${cid}\",\"clientSecret\":\"${sec}\",\"grantTypes\":[\"client_credentials\"],\"redirectUris\":[],\"signinMethods\":[],\"providers\":[]}" \
    | jq -c '{status,msg}'
}

if [ "${SMOKE_ONLY:-0}" != "1" ]; then
  hr; echo "① PROVISION：造两个独立 client_credentials 应用（+ 一个非家族 alien）"
  ensure_app "${ACME_CID}" "${ACME_SEC}" "acme"
  ensure_app "${BETA_CID}" "${BETA_SEC}" "beta"
  ensure_app "${ALIEN_CID}" "${ALIEN_SEC}" "recsys"
  [ "${PROVISION_ONLY:-0}" = "1" ] && { echo "PROVISION_ONLY=1，止于造应用。"; exit 0; }
fi

# ══════════════ ② MINT：client_credentials 铸真 token + 不可伪造证明 ══════════════
mint(){ # cid secret → token
  curl -s -X POST "${CASDOOR}/api/login/oauth/access_token" -H "Content-Type: application/x-www-form-urlencoded" \
    -d "grant_type=client_credentials&client_id=$1&client_secret=$2&scope=openid" | jq -r '.access_token // empty'
}
hr; echo "② MINT：client_credentials 铸真 token"
ACME_TOK=$(mint "${ACME_CID}" "${ACME_SEC}")
BETA_TOK=$(mint "${BETA_CID}" "${BETA_SEC}")
ALIEN_TOK=$(mint "${ALIEN_CID}" "${ALIEN_SEC}")
[ -n "${ACME_TOK}" ] && ok "acme token 铸成: $(jwt_claims "${ACME_TOK}")" || no "acme token 铸造失败（检查 app 是否开 client_credentials）"
[ -n "${BETA_TOK}" ] && ok "beta token 铸成: $(jwt_claims "${BETA_TOK}")" || no "beta token 铸造失败"
[ -n "${ALIEN_TOK}" ] && ok "alien(非家族) token 铸成: $(jwt_claims "${ALIEN_TOK}")" || echo "  · alien token 未铸（可选，跳过家族外测试）"

# 不可伪造：acme 的 client_id 配 beta 的 secret → 换不出 token
FORGE=$(mint "${ACME_CID}" "${BETA_SEC}")
[ -z "${FORGE}" ] && ok "不可伪造：acme client_id + beta secret 换不出 token（每应用独立 secret）" \
                  || no "⚠️ 安全漏洞：acme client_id 竟能用 beta secret 换出 token！"

[ -n "${ACME_TOK}" ] && [ -n "${BETA_TOK}" ] || { echo "缺 token，无法冒烟"; echo "pass=${pass} fail=${fail}"; exit 1; }

# ══════════════ ③ SMOKE：打运行中的 app（:8099, auth 档）══════════════
hr; echo "③ SMOKE：真 token 打 ${APP}"

# app 可达性
if ! curl -s -o /dev/null -m 3 "${APP}/actuator/health" 2>/dev/null; then
  echo "  ⚠️ ${APP} 不可达。请先以 auth 档启动 app（见脚本尾部命令），再跑 SMOKE_ONLY=1。"
  echo "pass=${pass} fail=${fail}"; exit 1
fi

code(){ echo "$1" | tail -1; }             # 取 http_code（-w 写在末行）
body(){ echo "$1" | sed '$d'; }            # 去掉末行 http_code 剩 body

req(){ # METHOD PATH TOKEN [BODY] [EXTRA_HEADER]
  local m="$1" p="$2" tok="$3" data="${4:-}" xh="${5:-}"
  local args=(-s -w $'\n%{http_code}' -X "$m" "${APP}${p}")
  [ -n "$tok" ] && args+=(-H "Authorization: Bearer ${tok}")
  [ -n "$xh" ]  && args+=(-H "$xh")
  [ -n "$data" ] && args+=(-H "Content-Type: application/json" -d "$data")
  curl "${args[@]}"
}

# 3.1 无 token → 401
C=$(code "$(req GET /activity-marketing/list '')"); [ "$C" = "401" ] && ok "无 token → 401" || no "无 token 期望 401，实得 $C"
# 3.2 垃圾 token → 401
C=$(code "$(req GET /activity-marketing/list 'garbage.token.value')"); [ "$C" = "401" ] && ok "垃圾 token → 401" || no "垃圾 token 期望 401，实得 $C"
# 3.3 非家族 aud token → 401（家族外拒）
if [ -n "${ALIEN_TOK}" ]; then
  C=$(code "$(req GET /activity-marketing/list "${ALIEN_TOK}")"); [ "$C" = "401" ] && ok "非家族 aud(${ALIEN_CID}) → 401（家族外拒）" || no "非家族 aud 期望 401，实得 $C"
fi

# 3.4 acme 建活动 → 200
NOW=$(( $(date +%s) * 1000 )); AGO=$(( NOW - 3600000 )); LATER=$(( NOW + 604800000 ))
CREATE_BODY=$(cat <<JSON
{"requestId":null,"activityId":null,"activityName":"P0-3冒烟-acme","bizLine":"mall","activityType":1,
 "activityRule":"P0-3冒烟-acme","activityStartTime":${AGO},"activityEndTime":${LATER},
 "activityAreaType":1,"districtIds":null,"priority":1,"inventory":100,
 "redPackageTakeType":1,"redPackageAmount":50,"redPackageAmountUnit":"元","redPackageRangeAmount":null,
 "discountStrategy":"MAX","eligibilityConditionTree":null,
 "spuBindings":[{"storeId":1,"spuId":900001}],"poolRefs":null,"gifts":null}
JSON
)
R=$(req POST /activity-marketing/create "${ACME_TOK}" "${CREATE_BODY}")
C=$(code "$R"); ACT_ID=$(body "$R" | jq -r '.activityId // empty' 2>/dev/null)
[ "$C" = "200" ] && [ -n "${ACT_ID}" ] && ok "acme 建活动 → 200 (activityId=${ACT_ID})" || no "acme 建活动期望 200+id，实得 $C / id=${ACT_ID}"

# 3.5 acme 列表能看到自己建的活动
R=$(req GET /activity-marketing/list "${ACME_TOK}"); ACME_SEES=$(body "$R" | jq -r --arg id "${ACT_ID}" '[.[]?|select(.activityId==$id)]|length' 2>/dev/null)
[ "${ACME_SEES}" = "1" ] && ok "acme 列表能看到自己的活动" || no "acme 列表看不到自己的活动（count=${ACME_SEES}）"

# 3.6 beta 列表看不到 acme 的活动（跨租户读隔离——P0-3 核心证明）
R=$(req GET /activity-marketing/list "${BETA_TOK}"); BETA_SEES=$(body "$R" | jq -r --arg id "${ACT_ID}" '[.[]?|select(.activityId==$id)]|length' 2>/dev/null)
[ "${BETA_SEES}" = "0" ] && ok "beta 列表看不到 acme 的活动（跨租户读隔离 ✔）" || no "❗ 隔离失败：beta 看到了 acme 的活动（count=${BETA_SEES}）"

# 3.7 beta 取 acme 的 activityId 详情 → 越权 fail-closed（400/404，非 200）
if [ -n "${ACT_ID}" ]; then
  C=$(code "$(req GET "/activity-marketing/${ACT_ID}" "${BETA_TOK}")")
  { [ "$C" = "400" ] || [ "$C" = "404" ]; } && ok "beta 取 acme 详情 → ${C}（越权 fail-closed ✔）" || no "beta 取 acme 详情期望 400/404，实得 $C"
fi

# 3.8 信封 X-Tenant-Id=beta 但用 acme token → 403（信封≠token 租户）
C=$(code "$(req GET /activity-marketing/list "${ACME_TOK}" '' 'X-Tenant-Id: beta')")
[ "$C" = "403" ] && ok "信封 X-Tenant-Id=beta + acme token → 403（信封≠租户拒）" || no "信封≠租户期望 403，实得 $C"

hr
echo "结果：pass=${pass}  fail=${fail}"
[ "${fail}" = "0" ] && echo "🎉 P0-3 真 Casdoor 端到端冒烟全绿" || echo "⚠️ 有失败项，见上"
exit $(( fail > 0 ? 1 : 0 ))

# ─────────────────────────────────────────────────────────────────────────────
# 以 auth 档启动 app（另一个终端 / 已由 Claude 起在 :8099）：
#   ./mvnw spring-boot:run -Dspring-boot.run.profiles=h2 \
#     -Dspring-boot.run.arguments="--server.port=8099 --activity.tenant.dev-default-enabled=false --activity.tenant.auth.enabled=true"
# ─────────────────────────────────────────────────────────────────────────────
