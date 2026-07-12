# 04 Solution B - Spring MVC + Thymeleaf 服务端渲染

## 方案概述

引入 `spring-boot-starter-thymeleaf`，新增页面 Controller 和模板。页面由服务端渲染，表单提交到页面 Controller，再由页面 Controller 调用现有 Service 或 REST Controller 逻辑并渲染结果。

该方案把“演示页面”做成 Spring MVC 页面，适合希望保持纯 Java 技术栈、不写太多前端状态逻辑的场景。

## 架构

```text
Browser
  |
  | GET /demo
  | POST /demo/discount
  v
DemoPageController
  |
  | calls existing services
  v
DiscountService / PipelineService / CampaignService / ...
  |
  v
Thymeleaf model -> HTML
```

也可以让 Thymeleaf 页面继续使用 fetch 调 REST，但那会和方案 A 接近。为了保持方案差异，本方案定义为“服务端表单 + 服务端渲染结果”。

## 模块职责

- `pom.xml`
  - 新增 `spring-boot-starter-thymeleaf`。
- `src/main/java/com/lrj/drools/controller/DemoPageController.java`
  - `GET /demo` 渲染首页。
  - `POST /demo/discount` 等页面提交入口。
  - 构造 `Order` / `Cart` / `UserProfile` 等对象。
  - 调用现有 Service。
  - 捕获异常并写入 view model。
- `src/main/resources/templates/demo/index.html`
  - Thymeleaf 模板。
  - 表单、结果区、错误区。
- `src/main/resources/static/css/demo.css`
  - 页面样式。

## 核心流程

1. 用户访问 `GET /demo`。
2. `DemoPageController` 返回 Thymeleaf 模板。
3. 用户填写表单并提交，例如 `POST /demo/discount`。
4. `DemoPageController` 将表单字段转换为 `Customer`、`OrderItem`、`Order`。
5. 调用 `DiscountService.calculate(Order)`。
6. 将 `Order` 放入 `Model`，服务端渲染结果 HTML。

## 改动范围

新增：

- `src/main/java/com/lrj/drools/controller/DemoPageController.java`
- `src/main/resources/templates/demo/index.html`
- `src/main/resources/static/css/demo.css`

修改：

- `pom.xml` 新增 Thymeleaf 依赖。
- `README.md` 增加 `/demo` 访问说明。

可能新增：

- 页面专用 form record 或 view model，例如 `DemoDiscountForm`，路径待实现时确认。

## 扩展性

优点：

- 与 Spring Boot 后端技术栈一致。
- 服务端可直接复用 Service，减少前端 fetch 和 JSON 解析逻辑。
- 表单提交天然适合简单演示。

限制：

- 对 `/hot/upsert`、`/scanner/deploy`、`/campaign/create` 这类 DRL 大文本编辑体验较弱。
- 对 `auditTrail` 时间线、TMS 并排对比、JSON 原始响应展示等交互不如客户端渲染灵活。
- 新增页面 Controller 容易重复现有 REST Controller 的对象构造逻辑，形成第二套入口。
- 如果页面 Controller 直接调 Service，会绕开 REST Controller 的异常响应语义，页面行为可能与 API 行为不一致。

## 实施成本

- 中。
- 需要修改 `pom.xml`。
- 需要新增 Java Controller。
- 每个演示功能都要写表单绑定逻辑；Step 数量多时重复工作明显。

## 风险与约束

- 易把“前端演示”变成“新增一套 MVC 接口”，和现有 REST API 形成维护分叉。
- 页面 Controller 如果复用 Service 而非 REST，会导致接口响应和页面结果不完全一致。
- Thymeleaf 模板复杂后可维护性下降。
- 仍然需要客户端 JavaScript 来做 DRL 编辑器、动态 JSON 表格、异步请求时，方案边界会变得混乱。

## 适用判断

适合教学项目严格要求“纯 Spring Boot 后端渲染”的情况。不适合作为本项目首选，因为当前项目已经有完整 REST API，用户诉求是“看到演示效果”，而不是新增服务端页面流。
