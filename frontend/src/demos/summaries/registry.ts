// 摘要渲染器 registry（平移 app.js 的 SUMMARY 分发表模式）。
// 按 demo.summary key 选组件；未登记的 key 一律走 GenericSummary（结构化 JSON + 顶层键高亮）。
// 定制可视化在旧原生页保留完整 21 套；此处示范 registry 架构并对高频类型给定制视图，其余诚实兜底。
import type { Component } from 'vue'
import GenericSummary from './GenericSummary.vue'
import OrderSummary from './OrderSummary.vue'

const REGISTRY: Record<string, Component> = {
  order: OrderSummary,
  orderBatch: OrderSummary,
}

export function summaryComponent(key: string): Component {
  return REGISTRY[key] || GenericSummary
}
