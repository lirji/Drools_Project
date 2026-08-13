#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成本仓库落库用的行政区划字典 CSV（activity-console/src/main/resources/district/china-district.csv）。

**刻意放在 Maven 源码根之外**（与 examples/aviator、examples/capacity 同理）：一次性数据加工脚本，
不进 ./mvnw compile、不引任何 pom 依赖，只在上游发新数据时手工跑一次。

────────────────────────────────────────────────────────────────────────
数据源：两份，分工明确
────────────────────────────────────────────────────────────────────────
【结构权威】xihan123/gb2260  (CC0-1.0)   data/build/areas.csv
    口径：民政部 / GB-T 2260 行政区划沿革（上游是 cn/GB2260 + yescallop/areacodes 的加工产物）
    形态：6824 条**生命周期**记录，每条带 status(active/retired/变更) + start_year/end_year + new_code；
          本脚本只取 status=active 的 3212 条 = 34 省级 / 333 地市级 / 2845 区县级。
    实测：0 重复码、0 孤儿、100% 六位纯数字、parent_code 与代码前缀 100% 自洽、path 列与父链逐字吻合。

【展示增强】xiangyuecn/AreaCity-JsSpider-StatsGov  (MIT)
    release 2025.251231.260403 的 ok_data_level3.csv（2026-04-03 采集，民政部 2025-12-31 + 高德/腾讯合并）
    只按 6 位代码**左连接**补 简称 / 拼音 / 拼音首字母。它的结构不可信（见下），但拼音是这批数据里最全的。

────────────────────────────────────────────────────────────────────────
选型记录：为什么不是那两份"更常见"的
────────────────────────────────────────────────────────────────────────
✗ modood/Administrative-divisions-of-China（国家统计局 2023-06-30，最常被引用的那份）
    **它对重庆是错的**：2025-11-06 国务院批复撤销江北区(500105)、渝北区(500112)，设立两江新区(500157)，
    民政部同步废止那两个代码（重庆市人民政府 2025-11-07 公告 t20251107_15148513；
    重庆市民政局 2025-12-05 代码变更公告）。该数据集停更于 2023-06-30（README 明写不再更新），
    今天仍把这两个已撤销的区当作在册行政区发出来。它还缺 2020 年设立的西沙区/南沙区、
    2024 年新设的和康县/和安县。**"结构最干净"不等于"是对的"——它只是冻结在了三年前。**

✗ xiangyuecn（即上面那份展示增强）当结构权威
    它混了高德/腾讯地图口径：省直辖县级市被提到地市级、给港澳造了 ext_id 与父节点相同的合成子行、
    台湾 376 行市县区用的是地图厂商码而非 GB/T 2260。作展示补齐没问题，作结构权威会把这些私货带进主键。

☑ 本脚本选的这份，唯一真正的短板：数据覆盖到 **2025 年**为止，
    缺 2026 年新设的两个县级单位——653132 岑岭县（新疆喀什，2026-03-26）与 659013 草湖市（兵团，2026-04）。
    影响面极小（两个新设单位，无存量订单），且上游一旦补上，重跑本脚本即可。**不要手工往 CSV 里塞这两行**：
    那会让"这张表的每一行都能追到某个发文机关"这条性质失效，而这正是它唯一的可信来源。

层级模型的一处刻意选择
    区县级**不一定挂在地市级下面**：直辖市（东城区→北京市）、省直辖县级市（济源市→河南省）、
    兵团师市（石河子市→新疆）都是直接挂省级。所以 district_level 表示的是**行政级别**，
    与父子深度解耦；这类行的 city_code 为空。**不要为了"三级对齐"去合成"市辖区"占位行**——
    统计局口径里那些占位行（110100 市辖区 / 419000 省直辖县级行政区划）在民政部口径里根本不存在。

────────────────────────────────────────────────────────────────────────
用法
────────────────────────────────────────────────────────────────────────
    # 1. 结构权威
    curl -sSLO https://raw.githubusercontent.com/xihan123/gb2260/master/data/build/areas.csv
    # 2. 展示增强（macOS 自带的 tar 就能解 .7z）
    curl -sSLO https://github.com/xiangyuecn/AreaCity-JsSpider-StatsGov/releases/download/2025.251231.260403/ok_data_level3-4.csv.7z
    tar -xf ok_data_level3-4.csv.7z ok_data_level3.csv
    # 3. 生成（目标目录是 district/ 不是 data/——根 .gitignore 有一条无路径前缀的 data/，
    #    放那儿的数据集不会进版本库，本机却一切正常，极难察觉）
    python3 examples/district-data/build-district-csv.py areas.csv ok_data_level3.csv \
        activity-console/src/main/resources/district/china-district.csv
"""
import csv
import sys
from pathlib import Path

LEVEL_OF = {"province": 1, "prefecture": 2, "county": 3}

COLUMNS = ["code", "name", "short_name", "district_level", "parent_code",
           "province_code", "city_code", "full_name", "pinyin", "pinyin_initial", "sort_no"]

# 换源自检：这几条是"数据是不是当期口径"的判据，不是随手挑的样本。
# 前三条必须在、后两条必须不在——2025-11 重庆那次调整同时提供了正反两个方向的判据，
# 只查"在不在"会漏掉"该没的还在"这一类陈旧。
MUST_EXIST = {"500157": "两江新区", "110101": "东城区", "440305": "南山区",
              "310115": "浦东新区", "460302": "西沙区"}
MUST_NOT_EXIST = {"500105": "江北区（2025-11 撤销，民政部废止该代码）",
                  "500112": "渝北区（2025-11 撤销，民政部废止该代码）"}


def load_structure(src: Path):
    """只取 status=active 的当期行政区划。"""
    rows = {}
    with src.open(encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            if r["status"] != "active":
                continue
            if r["code"] in rows:
                raise SystemExit(f"[FATAL] active 里出现重复代码 {r['code']}: {r}")
            rows[r["code"]] = r
    print(f"[structure] active {len(rows)} 行（全表含 retired/变更，已滤掉）")
    return rows


def load_enrichment(src: Path):
    """展示增强：6 位代码 → (简称, 拼音, 首字母)。上游 ext_id 是 12 位统计用区划代码。"""
    out = {}
    with src.open(encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            out.setdefault(r["ext_id"][:6], {
                "short": r["name"],
                "pinyin": r["pinyin"],
                "initial": r["pinyin_prefix"] if r["pinyin_prefix"].isalpha() else "",
            })
    return out


def build(struct, enrich):
    def chain(r):
        """自上而下的祖先链（含自己）。"""
        out, cur, guard = [], r, 0
        while cur is not None:
            out.append(cur)
            guard += 1
            if guard > 8:
                raise SystemExit(f"[FATAL] 祖先链成环: {r}")
            cur = struct.get(cur["parent_code"]) if cur["parent_code"] else None
        out.reverse()
        return out

    rows = []
    for sort_no, code in enumerate(sorted(struct), start=1):  # 代码有序即树的 DFS 序
        r = struct[code]
        ch = chain(r)
        level = LEVEL_OF[r["level"]]
        lv_of = {LEVEL_OF[c["level"]]: c["code"] for c in ch}
        e = enrich.get(code)
        rows.append({
            "code": code,
            "name": r["name"],
            # 上游没有这个码时退回全称——宁可不缩写，也不按后缀猜简称。
            "short_name": e["short"] if e else r["name"],
            "district_level": level,
            "parent_code": r["parent_code"],
            "province_code": lv_of[1],
            # 直辖市的区、省直辖县级市、兵团师市都直接挂省级，没有地市级祖先 → 留空。
            "city_code": lv_of.get(2, ""),
            "full_name": "/".join(c["name"] for c in ch),
            "pinyin": e["pinyin"] if e else "",
            "pinyin_initial": e["initial"] if e else "",
            "sort_no": sort_no,
            "_path": r["path"],
        })
    return rows


def verify(rows):
    by = {}
    for r in rows:
        assert len(r["code"]) == 6 and r["code"].isdigit(), r
        assert r["code"] not in by, f"重复 code: {r}"
        by[r["code"]] = r

    for r in rows:
        assert r["district_level"] in (1, 2, 3), r
        # 上游自己的 path 列与我们重算的父链必须逐字一致；不一致说明 parent_code 与 path 有一方是坏的。
        assert r["full_name"] == r["_path"], (r["code"], r["full_name"], r["_path"])
        if r["district_level"] == 1:
            assert r["parent_code"] == "" and r["province_code"] == r["code"] and r["city_code"] == "", r
        else:
            p = by[r["parent_code"]]
            # 只要求父级**更高**，不要求相邻：区县直接挂省级是合法形态（直辖市/省直辖县级市/兵团）。
            assert p["district_level"] < r["district_level"], (r, p)
            assert r["province_code"] == p["province_code"], (r, p)
            assert r["code"][:2] == r["province_code"][:2], r
        if r["district_level"] == 2:
            assert r["city_code"] == r["code"], r
        if r["district_level"] == 3 and r["city_code"]:
            assert r["city_code"] == r["parent_code"], r
        for f in ("name", "short_name", "full_name"):
            assert r[f].strip(), (f, r)
        # 落库侧的 CSV 解析器是 split(",", -1)，不认引号转义——这条断言是那边能这么写的前提。
        for f, v in r.items():
            assert not any(ch in str(v) for ch in (",", '"', "\n", "\r")), (f, r)

    for code, name in MUST_EXIST.items():
        assert code in by and by[code]["name"] == name, f"换源自检失败：{code} 应为 {name}，实得 {by.get(code)}"
    for code, why in MUST_NOT_EXIST.items():
        assert code not in by, f"换源自检失败：{code} 不该存在——{why}；这份数据是陈旧口径"

    lv = {n: sum(1 for r in rows if r["district_level"] == n) for n in (1, 2, 3)}
    cov = sum(1 for r in rows if r["pinyin"])
    hang = sum(1 for r in rows if r["district_level"] == 3 and not r["city_code"])
    print(f"[ok] {len(rows)} 行；省级 {lv[1]} / 地市级 {lv[2]} / 区县级 {lv[3]}"
          f"（其中 {hang} 个区县直挂省级：直辖市 + 省直辖县级市 + 兵团师市）")
    print(f"[ok] 拼音覆盖 {cov} 行 ({cov * 100 // len(rows)}%)；"
          f"列宽上限 name={max(len(r['name']) for r in rows)} "
          f"full_name={max(len(r['full_name']) for r in rows)} "
          f"pinyin={max(len(r['pinyin']) for r in rows)}")
    print(f"[ok] 换源自检通过：{'/'.join(MUST_EXIST)} 在；{'/'.join(MUST_NOT_EXIST)} 不在")
    return by


if __name__ == "__main__":
    struct_csv, enrich_csv, dst = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    rows = build(load_structure(struct_csv), load_enrichment(enrich_csv))
    verify(rows)
    dst.parent.mkdir(parents=True, exist_ok=True)
    with dst.open("w", encoding="utf-8", newline="\n") as f:
        w = csv.DictWriter(f, fieldnames=COLUMNS, lineterminator="\n", extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)
    print(f"[out] {dst} ({dst.stat().st_size} bytes)")
