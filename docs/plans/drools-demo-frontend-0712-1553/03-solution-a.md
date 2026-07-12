# 03 Solution A - Spring Boot 静态前端

## 方案概述

在 Spring Boot 应用内新增静态前端资源，放入 `src/main/resources/static/`，由 Spring Boot 默认静态资源机制托管。页面使用原生 HTML/CSS/JavaScript，直接调用同源 REST API。

这是对当前项目侵入最小的方案：不改后端业务类、不引入 Node 构建链、不新增模板引擎依赖。

## 架构

```text
Browser
  |
  | GET /index.html
  | POST /discount/calculate
  | POST /pipeline/audit
  | ...
  v
Spring Boot 8081
  |
  +-- static/index.html
  +-- static/assets/app.js
  +-- existing REST controllers
        |
        v
      existing services -> KieSession / KieBase / DB
```

## 模块职责

- `src/main/resources/static/index.html`
  - 页面骨架。
  - 左侧/顶部导航。
  - 演示面板容器。
- `src/main/resources/static/assets/styles.css`
  - 仪表盘布局、表单、JSON 编辑区、响应区、时间线、对比表样式。
- `src/main/resources/static/assets/examples.js`
  - 按 Step 维护示例 payload 和端点配置。
  - 来源应优先转写 `README.md` 中已有 curl 示例。
- `src/main/resources/static/assets/app.js`
  - 渲染导航和面板。
  - 处理示例加载、JSON 编辑、fetch 调用、错误显示。
  - 对关键响应做摘要渲染，例如 `discountReasons`、`recommendations`、`auditTrail`。

## 核心流程

1. 用户访问 `http://localhost:8081/` 或 `http://localhost:8081/index.html`。
2. 页面加载 `examples.js` 中的演示目录。
3. 用户选择一个 Step。
4. 前端把该 Step 的示例 JSON 填入编辑区。
5. 用户点击运行。
6. `app.js` 用 `fetch(endpoint, { method, headers, body })` 调用现有 REST API。
7. 前端显示：
   - HTTP 状态码；
   - 原始响应 JSON 或文本；
   - 关键字段摘要；
   - 错误信息。

## 覆盖范围

第一阶段建议覆盖：

- `/hello`
- `/discount/calculate`
- `/cart/checkout`
- `/risk/evaluate`
- `/pipeline/run`
- `/pipeline/audit`
- `/fraud/check`
- `/hot/upsert`
- `/hot/run/{name}`
- `/hot/list`
- `/loyalty/start`
- `/loyalty/{id}/purchase`
- `/loyalty/{id}`
- `/tms/compare`
- `/backward/contains`
- `/guard/runaway`
- `/guard/timeout`
- `/guard/canary`
- `/metrics/discount`
- `/dmn/price`
- `/campaign/create`
- `/campaign/{id}/check`
- `/campaign/{id}/end`
- `/campaign/list`

第二阶段补齐：

- `/decision/calculate`
- `/stateless/calculate`
- `/stateless/batch`
- `/scanner/deploy`
- `/scanner/run`
- `/scanner/poll/start`
- `/scanner/poll/stop`
- `/scanner/status`
- `/actuator/prometheus` 文本展示或链接。

## 改动范围

新增静态资源文件，不改 Java 业务代码：

- 新增 `src/main/resources/static/index.html`
- 新增 `src/main/resources/static/assets/app.js`
- 新增 `src/main/resources/static/assets/examples.js`
- 新增 `src/main/resources/static/assets/styles.css`
- 修改 `README.md` 增加前端访问说明

可选但不推荐第一版做：

- 新增 `src/main/resources/static/favicon.ico`

## 扩展性

优点：

- 新增 Step 只需在 `examples.js` 中加配置和可选摘要渲染函数。
- 同源请求无需 CORS。
- 与现有 Maven 构建兼容，Spring Boot 打包时自动包含静态资源。
- 不增加后端依赖，降低学习项目复杂度。

限制：

- 原生 JS 随功能增多会变得臃肿，需要保持模块化。
- 缺少 TypeScript 类型保护，请求体字段变更时靠测试发现。
- 大型交互如 DRL 编辑器、JSON schema 表单、可拖拽流程图不如 React/Vue 生态方便。

## 实施成本

- 低。
- 不需要新增构建工具。
- 不需要修改 `pom.xml`。
- 不需要解决 CORS。
- 主要成本在整理示例配置、摘要渲染、错误处理和基础 UI 质量。

## 风险与约束

- 前端示例配置可能与后端 record 字段漂移，需要测试覆盖。
- 未做表单级强校验时，用户可能提交 `items: null` 等后端未处理输入，导致 500；建议前端默认使用 JSON 编辑 + 基础 try/catch，不承诺所有异常友好。
- `/hot/*`、`/scanner/*`、`/campaign/*` 允许用户提交 DRL，学习环境可用，生产环境必须加鉴权；本方案不解决生产安全。
- `/actuator/prometheus` 是文本，通用 JSON 渲染器不能直接解析。

## 适用判断

适合当前任务。用户目标是“更好看到演示效果”，当前仓库后端 API 已完整，缺的是低成本交互层。方案 A 能最大程度复用现有接口，避免把任务扩大成前后端工程化改造。
