# 自托管字体授权说明

本目录下两个 woff2 均为 **拉丁 / 数字子集**（`unicode-range` 见 `src/shared/styles/tokens.css`
的 `@font-face`），中文字形不在其中，由系统栈承担。

| 文件 | 字体 | 版本来源 | 授权 | 体积 |
|---|---|---|---|---|
| `inter-latin-var.woff2` | Inter（可变，wght 100–900） | Google Fonts CSS2 API 的 `latin` 子集 | SIL Open Font License 1.1 | 48,432 B |
| `jetbrains-mono-latin-var.woff2` | JetBrains Mono（可变，wght 100–800） | 同上 | SIL Open Font License 1.1 | 31,340 B |

SIL OFL 1.1 允许自由使用、修改与再分发（含随软件打包），条件是保留授权声明、
且不得单独售卖字体本身。本文件即为该声明。

- Inter: <https://github.com/rsms/inter> · <https://openfontlicense.org/>
- JetBrains Mono: <https://github.com/JetBrains/JetBrainsMono>

## 为什么是子集、为什么不放 public/

- **只要拉丁**：CJK web font 即使子集化也是 3–10MB 级，对内网部署的 B 端控制台不划算。
  代价是英文数字走 Inter、汉字走苹方/雅黑的混排割裂，已知并接受。
- **必须放 `src/assets/`**：`public/` 下的文件落到 `/ui/fonts/`，命中
  `deploy/nginx.conf` 的 `location /ui/ { Cache-Control: no-cache }`，每次导航都重下；
  `src/assets/` 由 Vite 指纹化到 `/ui/assets/`，命中 `location ^~ /ui/assets/` 的 immutable 长缓存。

## 更新字体

```bash
UA='Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120 Safari/537.36'
# 取 latin 子集的 woff2 直链（CSS 里带 /* latin */ 注释的那一段）
curl -s "https://fonts.googleapis.com/css2?family=Inter:wght@100..900&display=swap" -H "User-Agent: $UA"
curl -s "https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;700&display=swap" -H "User-Agent: $UA"
```
更新后需同步本文件里的体积数字，并复核 `FINAL_PLAN.md` §10.4 的 100KB 预算。
