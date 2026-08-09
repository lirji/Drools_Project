# 进度锚点 · 前端「科技感」视觉换代

> 分支 `feat/visual-tech-refresh`（从 `main` 的 `2c1f49d` 切出）。
> 计划见同目录 `FINAL_PLAN.md`；评审处置见 `REVIEW.md`；样板屏 `style-tile.html`。
> **当前停在计划里的「⏸ 中途确认点」（步骤 5 之后），等用户看过实屏再决定是否继续。**

## 用户已批准的四项裁决（2026-08-09）

| 项 | 裁决 |
|---|---|
| 视觉方向 | 「深空遥测」候选 1（靛紫签名色 `#8B7BFF`/`#5B4BE8` + 青色数据色 `#22D3EE`/`#0C6B85`） |
| 默认主题 | dark-first，浅色保留不降级 |
| 字体 | 允许自托管拉丁，≤100KB / 3 个 woff2；中文一律系统栈 |
| 节奏 | 按计划走完 10 步 |

## 已完成

| 步骤 | commit | 状态 |
|---|---|---|
| **0** 落盘整棵工作树 + 网关开 gzip | `be3516e` `b33e737` | ✅ 前后端一起提交（`activityApi.ts` 调的 `/bulk-status` 实现在未提交的 Controller 里，只提前端会产出「接口不存在」的 commit） |
| **1** 13 处 `#fff` + F2 + F9 | `9665b84` | ✅ 含 TopBar 主题初值缺陷与 4 条新单测 |
| **2** 令牌换代（dark-first） | `70cd3c0` | ✅ 90 个既有名保留原名改值 + 新增 13 名 |
| **2b** 硬编码深色面收编 | `7fa8795` | ✅ 全仓硬编码颜色归零（A-1 达成） |
| **3** effects.css 收敛动效 | `9880b5a` | ✅ 3 组重复 keyframes 合一 |
| **5** 共享组件形制（部分） | `9880b5a` | ⚠️ Button/Card/Icon/Skeleton/AppLayout/AppShell 已改；**Hero.vue / Stat.vue / useReveal.ts 未建**，评审 Y5 点名的 9 个漏网组件未改 |
| **8** 的 F3（提前做） | `9880b5a` | ✅ 手机端搜索框 316×240 → 316×46 |

## 验收现状

| 项 | 结果 |
|---|---|
| A-1 硬编码颜色归零 | ✅ `grep -rnE '#[0-9a-fA-F]{3,6}' src --include='*.vue'` 无命中 |
| A-2 单测 / typecheck / build | ✅ vitest **154** 例全绿（基线 150 + 新增 4）、typecheck 干净、build 通过 |
| A-6 手机工具条 | ✅ search-box 46px（≤56）、toolbar 178px（≤240） |
| A-7 横向溢出 | ✅ 390px 实测 0（阈值 ≤4） |
| A-8 手机关玻璃 | ✅ 390px 实测 `backdrop-filter: none` |
| A-10 CSS 预算 | ✅ 逐 chunk gzip 求和 **30.7KB**（红线 40KB） |
| A-3 e2e 全套 | ⬜ **未跑**——步骤 6/7/8 改完页面后再跑两轮 |
| A-4 WCAG 数字写回注释 | ✅ 已写进 `tokens.css` 头部（评审复算 20/23 组一致，`--accent-2` 浅色三行已按复算值更正） |
| A-9 reduced-motion | ⬜ 未验（步骤 9 补断言） |
| A-12 打印白底 | ⬜ 未验（`@media print` 已写，未实测） |

## 待办（按依赖排序）

| 步骤 | 内容 | 阻塞 / 备注 |
|---|---|---|
| **4** 字体自托管 | Inter var latin + JetBrains Mono Regular/Bold 放 `src/assets/fonts/` + `@font-face` + mono/tabular-nums 铺开 | ⚠️ **需要拿到 3 个 woff2 子集文件**（本机无现成字体资源）。`--font-ui`/`--font-code` 的字族名已在 tokens.css 就位，字体文件到位即生效，不到位则自动 fallback 系统栈，**不会破相** |
| **5 余** | `Hero.vue` / `Stat.vue` / `useReveal.ts`；评审 Y5 的 9 个漏网组件（DemoNav / ConsoleShell / DemoShell / BulkBar / BulkConfirm / DynRowTable / condition-tree 三件） | |
| **6** Tier A 门面 | HomeView 加 hero + 修 F6（mergeRows/排序/`:key` 去重）、DemoHome、LoginView（+ 同步 `LoginView.test.ts` 全文，A5 已授权）、CallbackView 补 loading、修 F5（`.catalog-tools` 的 `top:0` 盖住顶栏） | |
| **7** Tier B 展示页 | DetailView（修 F4 缺 `v-else`）/ ValidateView / PlaybooksView / DemoPanel + viz 语汇 | |
| **8** Tier C 密度屏 | ListView / EditorView 余下部分（消灭 ≤10px 字号 80 处、EditorView rail sticky 偏移被顶栏遮 40px） | |
| **9** 补 e2e 断言 | A-6/A-8/A-9 目前只有我手跑的一次性脚本，没进仓库 | |
| **10** 清理 | 删 `Field.vue`（零引用且无测试）；Sparkline/Gauge 按 R-6 **默认走第②项**（保留组件 + 保留测试 + 本轮不接数据）；补断点偏离清单注释 | |

## 复现与验收命令

```bash
# 本地看效果（前端由 gateway 镜像托管，--build console 对前端零效果）
cd frontend && npm run build
DROOLS_AUTH_ENABLED=false DROOLS_DEV_DEFAULT_ENABLED=true \
  docker compose -f deploy/docker-compose.yml up -d --build gateway
# → http://localhost:8095/ui/console/activities

# 回归
cd frontend && npx vitest run && npm run typecheck && npm run build
for f in dist/assets/*.css; do gzip -9 -c "$f" | wc -c; done | awk '{s+=$1} END {print s" B"}'   # 红线 40KB

# e2e（编排默认 auth 档，跑 bench/playbooks/ruler 必须先切 header 档，否则被登录守卫弹走）
npm run e2e:dev && npm run e2e:catalog && npm run e2e:tablet && npm run e2e:phone
npm run e2e:bench && npm run e2e:playbooks && npm run e2e:ruler
```

## 回滚

每步一个独立 commit。最坏情况 `git revert 70cd3c0`（令牌换代）即回到旧观感——
其余步骤（effects.css / 组件形制）在旧令牌下也能正常工作，不会连锁崩。
