#!/usr/bin/env bash
# 活动容量基准跑法。刻意放在 Maven 源码根之外（同 examples/aviator/），因为它引 QLExpress——
# 生产四个模块都不该有这个依赖，也就不该让它进 reactor 的 pom。
#
#   ./examples/capacity/run.sh                # 默认规模 10 50 100 200 500 1000 2000 5000
#   ./examples/capacity/run.sh 10 100 1000    # 只跑指定规模
#   TIERS=20 ./examples/capacity/run.sh       # 改每活动档位数
#   DISTINCT_TIERS=true ./examples/capacity/run.sh   # 每活动档位边界各不相同（打掉 Drools 的 alpha 节点复用）
#
# 环境要求：JDK 21 + 能连 Maven 仓库（首次要拉 QLExpress 3.3.4）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${ROOT}/examples/capacity/.work"
QL_VERSION="${QL_VERSION:-3.3.4}"
TIERS="${TIERS:-10}"
SAMPLES="${SAMPLES:-300}"
HEAP="${HEAP:-4g}"
META="${META:-1g}"

mkdir -p "${OUT}"

echo "==> 1/4 编译 activity-common（基准直接复用它的生产类：DRL 生成器 / 条件树求值器 / 权益算术）"
"${ROOT}/mvnw" -q -pl activity-common -am install -DskipTests

echo "==> 2/4 拉 QLExpress ${QL_VERSION} 并解析 activity-common 的运行时 classpath"
"${ROOT}/mvnw" -q dependency:get -Dartifact="com.alibaba:QLExpress:${QL_VERSION}"
"${ROOT}/mvnw" -q -pl activity-common dependency:build-classpath \
  -Dmdep.outputFile="${OUT}/cp.txt" -Dmdep.includeScope=runtime

QL_JAR="$(find "${HOME}/.m2/repository/com/alibaba/QLExpress/${QL_VERSION}" -name '*.jar' | head -1)"
if [[ -z "${QL_JAR}" ]]; then
  echo "找不到 QLExpress jar，检查 ~/.m2 与网络" >&2
  exit 1
fi
CP="${ROOT}/activity-common/target/classes:${QL_JAR}:$(cat "${OUT}/cp.txt")"

echo "==> 3/4 编译基准"
javac -nowarn -cp "${CP}" -d "${OUT}/classes" "${ROOT}/examples/capacity/CapacityBench.java"

echo "==> 4/4 跑基准（堆 ${HEAP} / Metaspace ${META}）"
exec java -Xmx"${HEAP}" -XX:+UseG1GC -XX:MaxMetaspaceSize="${META}" \
  -Dbench.tiers="${TIERS}" -Dbench.samples="${SAMPLES}" \
  -Dbench.distinctTiers="${DISTINCT_TIERS:-false}" \
  -cp "${OUT}/classes:${CP}" CapacityBench "$@"
