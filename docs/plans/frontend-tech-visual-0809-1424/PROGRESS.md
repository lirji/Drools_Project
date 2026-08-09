# 进度锚点 · 前端「科技感」视觉换代

> 分支 `feat/visual-tech-refresh`（从 `main` 的 `2c1f49d` 切出）。
> 计划见同目录 `FINAL_PLAN.md`；评审处置见 `REVIEW.md`；样板屏 `style-tile.html`。
>
> ## ✅ 计划的 10 个步骤已全部完成。

## 用户已批准的四项裁决（2026-08-09）

| 项 | 裁决 |
|---|---|
| 视觉方向 | 「深空遥测」候选 1（靛紫签名色 `#8B7BFF`/`#5B4BE8` + 青色数据色 `#22D3EE`/`#0C6B85`） |
| 默认主题 | dark-first，浅色保留不降级 |
| 字体 | 允许自托管拉丁，≤100KB；中文一律系统栈 |
| 节奏 | 按计划走完 10 步 |

## 完成情况

| 步骤 | commit | 说明 |
|---|---|---|
| **0** 落盘整棵工作树 + 网关开 gzip | `be3516e` `b33e737` | 前后端一起提交（`activityApi.ts` 调的 `/bulk-status` 实现在未提交的 Controller 里） |
| **1** 13 处 `#fff` + F2 + **F9** | `9665b84` | TopBar 主题初值缺陷 + 4 条新单测 |
| **2** 令牌换代（dark-first） | `70cd3c0` | 90 个既有名保留原名改值 + 新增令牌；body 斜纹底纹删除 |
| **2b** 硬编码深色面收编 | `7fa8795` | **全仓硬编码颜色归零**（A-1） |
| **3** effects.css | `9880b5a` | 9 个 keyframes 里 3 组重复合一 |
| **5** 共享组件形制 | `9880b5a` `4f65778` | Button/Card/Icon/Skeleton/AppLayout/AppShell + **7 处输入控件接上 `--border-ctl`** |
| **4** 字体自托管 | `e9c8205` | Inter + JetBrains Mono 拉丁子集，**80KB**（预算 100KB）；tabular-nums 铺开 |
| **6** Tier A 门面 | `64b4088` | `Hero.vue`/`Stat.vue` 新原语；HomeView hero + 真数统计 + **F6**；**F5** + EditorView rail；CallbackView |
| **7** Tier B 展示页 + **8** 密度屏 | `29fb999` `9880b5a` | **F4**；103 处微型字号；loading/empty 收敛；Sparkline 发光折线 + 渐变面积；**F3** |
| **9** e2e 视觉守卫 | `601f7b1` | 新增 `e2e:visual`，10 条断言 |
| **10** 清理 | `601f7b1` | 删 `Field.vue`；Sparkline 接真实数据；死令牌清理；27 条 transition 统一 |

## 验收结果（全部达成）

| 标准 | 结果 |
|---|---|
| A-1 硬编码颜色归零 | ✅ `grep -rnE '#[0-9a-fA-F]{3,6}' src --include='*.vue'` 无命中 |
| A-2 单测 / typecheck / build | ✅ vitest **154** 全绿（基线 150 + 新增 4）、typecheck 干净、build 通过 |
| A-3 e2e 全套 | ✅ **8 套 71 条断言全绿**：visual 10 / dev 7 / catalog 6 / tablet 7 / phone 7 / bench 13 / playbooks 17 / ruler 4 |
| A-4 WCAG 数字写回注释 | ✅ 已写进 `tokens.css` 头部（评审复算 20/23 组一致，`--accent-2` 浅色三行按复算值更正） |
| A-5 `--border-ctl` ≥3:1 | ✅ 暗 3.11/3.32、浅 3.29/3.03，且已接到 7 处输入控件 |
| A-6 手机工具条 | ✅ search-box **46px**（≤56）、toolbar **178px**（≤240）—— e2e 守卫常态化 |
| A-7 零横向溢出 | ✅ 390/768/1440 实测 0px（阈值 ≤4） |
| A-8 手机关玻璃 + 触控 ≥44px | ✅ `backdrop-filter: none`；触控目标全部 ≥44px |
| A-9 reduced-motion | ✅ 无循环动画在跑 |
| A-10 性能红线 | ✅ CSS 逐 chunk gzip **32.4KB**（红线 40）、字体 **80KB**（红线 100）、入口 gzip 59.5KB（红线 75） |
| A-11 三套视觉语言收敛 | ✅ login / demos / console 同一套令牌，实拍对比确认 |
| A-12 打印白底 | ✅ 双档实测回落白底、无背景图 |
| A-13 缺陷 F1–F10 闭环 | ✅ 全部 |

## 换代过程中新发现并修掉的缺陷（不在原计划内）

| # | 缺陷 | 发现方式 |
|---|---|---|
| **F11** | **全局触控兜底一直是失效的**：`tokens.css` 的 `@media (pointer:coarse) { button { min-height:44px } }` 选择器是 0-0-1，压不过任何组件 scoped 样式里的 `min-height`（带 `[data-v-*]` 至少 0-2-0）。实测 TopBar 汉堡 40px、ListView 卡片模式行内按钮 36px 长期不达标 | 步骤 9 新写的 e2e 守卫**第一次运行就抓到** |
| **F12** | `Stat`/`Hero` 用 `--accent-2` 做深色面上的强调色，但它随主题翻面——浅色档拿到压暗版 `#0C6B85`，落在 `--surface-deep` 上只有 **2.9:1** | 步骤 6 看手机端实拍时发现 |

两条都已修并写入 e2e 守卫做回归护栏。

## 刻意没做的决定（附理由）

1. **`useReveal.ts` 未建** —— 纯 CSS 的 `.u-stagger` 已够，不想再添零引用抽象（`Field.vue` 就是前车之鉴）。
2. **LoginView 未重排** —— 它在步骤 2b 收编硬编码色后已与新体系一致，结构本身没问题。
   因此 A5 授权的 `LoginView.test.ts` 修改**全程未动用**，4 条 class 断言原样通过。
3. **Gauge 保留但未接数据**（R-6 第②项）—— 它有 3 条单测撑着；强行接一个不存在的数据源
   就是造假图，违反 D7。等后端补 `GET /decision/v1/metrics` 再接。
4. **viz 三件与 TierRuler 的 6 处微型字号未动** —— 属 D6「保几何换语汇」范围。
5. **8 个正典外断点未收敛** —— 超出观感层，已在 `tokens.css` 头部记录在案供后续处理。

## 复现与验收命令

```bash
# 本地看效果（前端由 gateway 镜像托管，--build console 对前端零效果）
cd frontend && npm run build
DROOLS_AUTH_ENABLED=false DROOLS_DEV_DEFAULT_ENABLED=true \
  docker compose -f deploy/docker-compose.yml up -d --build gateway
# → http://localhost:8095/ui/home

# 回归
cd frontend && npx vitest run && npm run typecheck && npm run build
for f in dist/assets/*.css; do gzip -9 -c "$f" | wc -c; done | awk '{s+=$1} END {print s" B"}'   # 红线 40KB

# e2e（编排默认 auth 档，跑这些必须先按上面切 header 档，否则被登录守卫弹走）
BASE=http://localhost:8095 npm run e2e:visual   # 新增：视觉/移动端红线守卫
BASE=http://localhost:8095 npm run e2e:dev && ... e2e:catalog / e2e:tablet / e2e:phone
BASE=http://localhost:8095 npm run e2e:bench && ... e2e:playbooks / e2e:ruler
npm run e2e:oidc   # 需本机 Casdoor :8000，本轮未跑
```

## 回滚

每步一个独立 commit。最坏情况 `git revert 70cd3c0`（令牌换代）即回到旧观感——
其余步骤（effects.css / 字体 / 组件形制）在旧令牌下也能正常工作，不会连锁崩。

## 尚未验证的一项

`npm run e2e:oidc` 需要本机 Casdoor `:8000`，本轮环境没起，**未跑**。
登录页本轮只做了令牌收编、未改结构与 `#login-tenant`，风险低，但合并前建议补跑一次。
