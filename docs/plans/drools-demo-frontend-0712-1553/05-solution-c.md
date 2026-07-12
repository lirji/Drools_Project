# 05 Solution C - 独立 Vite/React 前端

## 方案概述

在仓库新增 `frontend/` 子项目，使用 Vite + React + TypeScript 构建单页应用。开发时前端运行在独立端口，通过 Vite proxy 转发到 Spring Boot `8081`；生产构建可以独立部署，或将 dist 复制到 Spring Boot 静态资源目录。

这是交互能力最强的方案，适合后续把 demo 演进为规则实验台。

## 架构

```text
Dev:
Browser -> Vite dev server -> proxy /api or direct paths -> Spring Boot 8081

Prod option 1:
Browser -> static hosting -> Spring Boot API 8081/other

Prod option 2:
Browser -> Spring Boot static resources -> same-origin REST controllers
```

## 模块职责

- `frontend/package.json`
  - npm scripts：`dev`, `build`, `test`, `lint`
- `frontend/vite.config.ts`
  - dev proxy 到 `http://localhost:8081`
- `frontend/src/api/client.ts`
  - fetch 封装、错误处理、文本/JSON 响应解析。
- `frontend/src/demo/catalog.ts`
  - Step 元数据、端点、示例 payload、响应摘要配置。
- `frontend/src/components/*`
  - JSON 编辑器、响应查看器、审计时间线、TMS 对比、DRL 编辑器。
- `frontend/src/App.tsx`
  - 主布局和路由/标签状态。

## 核心流程

1. 后端以 H2 或 MySQL profile 启动在 8081。
2. 前端 `npm run dev` 启动。
3. Vite proxy 将 API 请求转发到 8081。
4. React 应用渲染 Step 目录、表单、JSON 编辑器和结果视图。
5. 构建时 `npm run build` 输出静态文件。

## 改动范围

新增：

- `frontend/`
- `frontend/package.json`
- `frontend/vite.config.ts`
- `frontend/src/**`
- `frontend/index.html`

可能修改：

- `README.md`：增加前后端双服务启动说明。
- `pom.xml`：如果要求 Maven 一键构建前端，需引入前端构建插件，待验证。
- `src/main/java/com/lrj/drools/config/CorsConfig.java`：如果不用 proxy 或同源部署，需新增 CORS 配置，待验证。

## 扩展性

优点：

- TypeScript 可约束请求/响应结构。
- React 生态适合复杂交互：
  - DRL 编辑器；
  - 审计轨迹时间线；
  - JSON diff；
  - 多 Step 对比；
  - 状态保持和收藏示例。
- 易做组件测试和 UI 回归测试。
- 后续如果加入 OpenAPI，可生成 API client。

限制：

- 引入 Node 工具链，当前仓库没有相关基础。
- 开发启动需要两个进程，学习门槛上升。
- 需要处理 dev proxy、CORS、生产构建归档。
- 依赖版本会随时间变化，维护成本高于静态方案。

## 实施成本

- 高。
- 需要建立前端工程、包管理、构建、测试。
- 需要维护前后端集成文档。
- 若最终要打入 Spring Boot jar，还要设计 dist 复制流程。

## 风险与约束

- 网络依赖：首次安装 npm 包需要联网；当前执行环境网络受限，本规划阶段不能验证依赖安装。
- CORS：如果前端和后端不同源，当前后端没有 CORS 配置。
- 构建链复杂度会遮蔽 Drools 学习重点。
- 如果没有 OpenAPI，TypeScript 类型仍需手写，和 Java record 字段可能漂移。

## 适用判断

适合后续把项目产品化为“规则工作台”或“教学实验室”的长期方向。不适合当前第一版，因为用户需求是给现有 demo 增加前端可视化，而不是引入完整前端工程体系。
