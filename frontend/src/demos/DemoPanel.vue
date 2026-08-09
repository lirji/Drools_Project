<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { GROUPS, DEMOS, type DemoDef } from './catalog'
import { api } from '@/shared/apiClient'
import { summaryComponent } from './summaries/registry'
import { useToast } from '@/shared/useToast'
import Banner from '@/shared/ui/Banner.vue'
import Icon from '@/shared/ui/Icon.vue'
import Skeleton from '@/shared/ui/Skeleton.vue'
import Sparkline from '@/shared/viz/Sparkline.vue'

const route = useRoute()
const toast = useToast()
const demo = computed<DemoDef | undefined>(() => DEMOS.find((item) => item.id === route.params.demoId))
const demoIndex = computed(() => DEMOS.findIndex((item) => item.id === demo.value?.id))
const group = computed(() => GROUPS.find((item) => item.id === demo.value?.group))
const previousDemo = computed(() => demoIndex.value > 0 ? DEMOS[demoIndex.value - 1] : undefined)
const nextDemo = computed(() => demoIndex.value >= 0 && demoIndex.value < DEMOS.length - 1 ? DEMOS[demoIndex.value + 1] : undefined)

const bodyText = ref('')
const pathValues = ref<Record<string, string>>({})
const selectedExample = ref(0)
const running = ref(false)
const elapsedMs = ref<number | null>(null)
/**
 * 真实耗时序列（最近 12 次）。它是**实测值**不是造出来的曲线——所以可以画：
 * 单看「18 ms」不知道快慢，有了序列才知道「这次比前几次慢」。
 *
 * 作用域是 **(能力, 示例)** 而不是「能力」：清空点在 loadExample 里，切换示例也会清。
 * 这是刻意的——不同 payload 的耗时混进同一条线会让趋势失真，那就成了另一种假图。
 */
const latency = ref<number[]>([])
const resp = ref<{ ok: boolean; status: number; json: unknown; text: string } | null>(null)
const runErr = ref('')
let activeRequest: AbortController | null = null
let requestSequence = 0

const resolvedPath = computed(() => {
  const current = demo.value
  if (!current) return ''
  let path = current.path
  for (const param of current.pathParams || []) {
    const value = pathValues.value[param.name]
    if (value) path = path.replace('{' + param.name + '}', encodeURIComponent(value))
  }
  return path
})

const responseText = computed(() => {
  if (!resp.value) return ''
  if (demo.value?.responseType === 'text') return resp.value.text
  if (resp.value.json != null) return JSON.stringify(resp.value.json, null, 2)
  return resp.value.text
})

watch(demo, (current) => {
  activeRequest?.abort()
  activeRequest = null
  requestSequence += 1
  running.value = false
  if (current) loadExample(0)
}, { immediate: true })

function loadExample(index: number): void {
  const current = demo.value
  if (!current) return
  selectedExample.value = index
  const example = current.examples[index]
  bodyText.value = example?.body != null ? JSON.stringify(example.body, null, 2) : ''
  const nextPathValues: Record<string, string> = {}
  for (const param of current.pathParams || []) {
    nextPathValues[param.name] = String(example?.pathParams?.[param.name] ?? '')
  }
  pathValues.value = nextPathValues
  resp.value = null
  runErr.value = ''
  elapsedMs.value = null
  latency.value = []
}

function buildUrl(): string {
  let path = demo.value!.path
  for (const param of demo.value!.pathParams || []) {
    const value = pathValues.value[param.name]
    if (!value) throw new Error(`请先填写路径参数「${param.label}」`)
    path = path.replace('{' + param.name + '}', encodeURIComponent(value))
  }
  return path
}

function formatBody(): void {
  if (!bodyText.value.trim()) return
  try {
    bodyText.value = JSON.stringify(JSON.parse(bodyText.value), null, 2)
    toast.ok('请求 JSON 已格式化')
  } catch {
    toast.err('请求体不是合法 JSON，暂时无法格式化')
  }
}

async function copyText(text: string, successMessage: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    toast.ok(successMessage)
  } catch {
    toast.err('复制失败，请手动选择文本复制')
  }
}

async function run(): Promise<void> {
  const current = demo.value
  if (!current || running.value) return
  activeRequest?.abort()
  const controller = new AbortController()
  const sequence = ++requestSequence
  activeRequest = controller
  running.value = true
  runErr.value = ''
  resp.value = null
  elapsedMs.value = null
  const startedAt = performance.now()
  try {
    const url = buildUrl()
    let body: unknown = undefined
    if (current.method !== 'GET' && bodyText.value.trim()) {
      try { body = JSON.parse(bodyText.value) } catch { throw new Error('请求体不是合法 JSON，请检查红色标点附近') }
    }
    const raw = current.responseType === 'text'
    const result = await api('root', current.method, url, body, { raw, signal: controller.signal })
    if (sequence === requestSequence) resp.value = result
  } catch (error) {
    if (sequence === requestSequence && (error as Error).name !== 'AbortError') {
      runErr.value = (error as Error).message
    }
  } finally {
    if (sequence === requestSequence) {
      elapsedMs.value = Math.max(1, Math.round(performance.now() - startedAt))
      latency.value = [...latency.value, elapsedMs.value].slice(-12)
      running.value = false
      activeRequest = null
    }
  }
}

function onShortcut(event: KeyboardEvent): void {
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter' && demo.value) {
    event.preventDefault()
    void run()
  }
}

onMounted(() => window.addEventListener('keydown', onShortcut))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onShortcut)
  activeRequest?.abort()
  requestSequence += 1
})
</script>

<template>
  <div v-if="demo" class="panel" :data-testid="'demo-panel-' + demo.id">
    <header class="demo-header">
      <nav class="breadcrumbs" aria-label="面包屑">
        <router-link :to="{ name: 'demos' }">规则能力中心</router-link>
        <Icon name="chevron-right" :size="13" />
        <span>{{ group?.title.split('：')[0] }}</span>
        <Icon name="chevron-right" :size="13" />
        <span>能力 C{{ String(demo.step).padStart(2, '0') }}</span>
      </nav>

      <div class="title-row">
        <div>
          <span class="capability-label">{{ group?.title || 'Drools 能力' }}</span>
          <h1>{{ demo.title }}</h1>
        </div>
        <div class="sequence-nav" aria-label="上一个或下一个能力">
          <router-link v-if="previousDemo" :to="{ name: 'demo', params: { demoId: previousDemo.id } }" :title="previousDemo.title">
            <Icon name="arrow-left" :size="16" /><span>上一个</span>
          </router-link>
          <span v-else class="disabled"><Icon name="arrow-left" :size="16" /><span>上一个</span></span>
          <router-link v-if="nextDemo" :to="{ name: 'demo', params: { demoId: nextDemo.id } }" :title="nextDemo.title">
            <span>下一个</span><Icon name="arrow-right" :size="16" />
          </router-link>
          <span v-else class="disabled"><span>下一个</span><Icon name="arrow-right" :size="16" /></span>
        </div>
      </div>

      <div class="endpoint-bar">
        <span class="method" :class="demo.method === 'GET' ? 'get' : 'post'">{{ demo.method }}</span>
        <code>{{ resolvedPath }}</code>
        <button type="button" aria-label="复制接口路径" @click="copyText(resolvedPath, '接口路径已复制')"><Icon name="copy" :size="14" /></button>
        <span class="step">CAPABILITY C{{ String(demo.step).padStart(2, '0') }}</span>
      </div>

      <div class="learning-note">
        <span><Icon name="info" :size="17" /></span>
        <div><strong>能力说明</strong><p>{{ demo.desc }}</p></div>
      </div>
    </header>

    <section v-if="demo.examples.length > 1" class="example-section" aria-labelledby="example-title">
      <div class="section-title">
        <div><span class="section-number">01</span><div><h2 id="example-title">选择请求方案</h2><p>预设方案会自动填入请求参数，你可以继续修改。</p></div></div>
        <span>{{ demo.examples.length }} 个预设</span>
      </div>
      <div class="examples">
        <button
          v-for="(example, index) in demo.examples"
          :key="index"
          type="button"
          class="example-card"
          :class="{ active: selectedExample === index }"
          :aria-pressed="selectedExample === index"
          @click="loadExample(index)"
        >
          <span class="radio-dot"><i /></span>
          <span><strong>{{ example.label }}</strong><small>载入这组请求参数</small></span>
          <Icon v-if="selectedExample === index" name="check" :size="16" />
        </button>
      </div>
    </section>

    <section class="workbench" aria-label="请求与响应工作区">
      <div class="request-card">
        <div class="card-head">
          <div class="card-title"><span class="section-number">{{ demo.examples.length > 1 ? '02' : '01' }}</span><div><h2>在线调用</h2><p>确认请求参数后执行规则能力。</p></div></div>
          <span class="request-shortcut"><Icon name="terminal" :size="14" /> ⌘ / Ctrl + Enter</span>
        </div>

        <div v-for="param in demo.pathParams || []" :key="param.name" class="path-field">
          <label :for="'path-' + param.name">{{ param.label }} <span>路径参数</span></label>
          <div class="path-input"><span>/</span><input :id="'path-' + param.name" v-model="pathValues[param.name]" :placeholder="param.placeholder || ''" /></div>
        </div>

        <div v-if="demo.method !== 'GET'" class="editor-shell">
          <div class="editor-bar">
            <span><i /> JSON 请求体</span>
            <div>
              <button type="button" @click="formatBody"><Icon name="code" :size="14" /> 格式化</button>
              <button type="button" @click="loadExample(selectedExample)"><Icon name="refresh" :size="14" /> 重置</button>
              <button type="button" @click="copyText(bodyText, '请求体已复制')"><Icon name="copy" :size="14" /> 复制</button>
            </div>
          </div>
          <textarea v-model="bodyText" class="body" rows="18" spellcheck="false" data-testid="demo-body" aria-label="JSON 请求体" />
        </div>
        <div v-else class="no-body">
          <span><Icon name="info" :size="18" /></span>
          <div><strong>GET 请求，无请求体</strong><small>确认上方路径参数后即可直接发送。</small></div>
        </div>

        <button class="run" :disabled="running" data-testid="demo-run" type="button" @click="run">
          <Icon :name="running ? 'refresh' : 'play'" :size="17" :class="{ spinning: running }" />
          <span>{{ running ? '规则执行中…' : '执行规则能力' }}</span>
          <small v-if="!running">{{ demo.method }} {{ resolvedPath }}</small>
        </button>
      </div>

      <div class="response-card" :aria-busy="running">
        <div class="card-head response-head">
          <div class="card-title"><span class="section-number">{{ demo.examples.length > 1 ? '03' : '02' }}</span><div><h2>查看结果</h2><p>响应摘要与规则执行结果。</p></div></div>
          <button v-if="resp" type="button" class="copy-response" @click="copyText(responseText, '响应已复制')"><Icon name="copy" :size="14" /> 复制响应</button>
        </div>

        <div v-if="running" class="loading-state" role="status" aria-live="polite">
          <span class="loader"><Icon name="zap" :size="22" /></span>
          <h3>正在执行规则</h3>
          <p>请求已发送，等待 Drools 返回命中结果…</p>
          <Skeleton :rows="3" />
        </div>
        <Banner v-else-if="runErr" kind="err" class="error-banner" role="alert" data-testid="demo-error">
          <strong>请求没有发出</strong><span>{{ runErr }}</span>
        </Banner>
        <template v-else-if="resp">
          <div class="result-meta">
            <span class="status" :class="resp.ok ? 'ok' : 'err'" role="status" aria-live="polite" data-testid="demo-status">
              <i /> HTTP {{ resp.status }} · {{ resp.ok ? '请求成功' : '业务拒绝' }}
            </span>
            <span v-if="elapsedMs !== null" class="elapsed">
                <Icon name="clock" :size="13" /> {{ elapsedMs }} ms
                <Sparkline
                  v-if="latency.length > 1"
                  class="elapsed-spark"
                  :values="latency"
                  :label="`最近 ${latency.length} 次执行耗时趋势，当前 ${elapsedMs} 毫秒`"
                />
              </span>
          </div>
          <div class="result-content">
            <pre v-if="demo.responseType === 'text'" class="text-box">{{ resp.text }}</pre>
            <component v-else :is="summaryComponent(demo.summary)" :data="resp.json ?? resp.text" />
          </div>
        </template>
        <div v-else class="idle">
          <span class="idle-icon"><Icon name="terminal" :size="26" /></span>
          <h3>结果会显示在这里</h3>
          <p>左侧已载入预设请求参数。你可以直接执行，也可以修改 JSON 后验证规则结果。</p>
          <div class="idle-flow"><span>选择方案</span><Icon name="arrow-right" :size="14" /><span>执行规则</span><Icon name="arrow-right" :size="14" /><span>查看结果</span></div>
        </div>
      </div>
    </section>

    <footer class="next-experiment">
      <div v-if="nextDemo"><span>关联能力</span><strong>能力 C{{ String(nextDemo.step).padStart(2, '0') }} · {{ nextDemo.title }}</strong></div>
      <div v-else><span>你已浏览到最后</span><strong>返回能力地图选择其他主题</strong></div>
      <router-link v-if="nextDemo" :to="{ name: 'demo', params: { demoId: nextDemo.id } }">下一个能力 <Icon name="arrow-right" :size="16" /></router-link>
      <router-link v-else :to="{ name: 'demos' }">返回能力地图 <Icon name="arrow-right" :size="16" /></router-link>
    </footer>
  </div>
  <div v-else class="notfound">
    <Icon name="alert-triangle" :size="28" />
    <h2>没有找到这个能力</h2>
    <router-link :to="{ name: 'demos' }">返回能力地图</router-link>
  </div>
</template>

<style scoped>
.panel { display: flex; flex-direction: column; gap: var(--sp-5); min-width: 0; }
.demo-header { min-width: 0; }
.breadcrumbs { display: flex; align-items: center; flex-wrap: wrap; gap: var(--sp-1); margin-bottom: var(--sp-3); color: var(--text-faint); font-size: 11px; }
.breadcrumbs a { color: var(--text-soft); text-decoration: none; }
.breadcrumbs a:hover { color: var(--accent); }
.title-row { display: flex; align-items: end; justify-content: space-between; gap: var(--sp-4); }
.capability-label { color: var(--accent); font-size: var(--fs-xs); font-weight: var(--fw-bold); letter-spacing: .1em; text-transform: uppercase; }
.title-row h1 { margin: 3px 0 0; font-size: clamp(25px, 3vw, 34px); line-height: var(--lh-tight); letter-spacing: -.035em; }
.sequence-nav { display: flex; align-items: center; gap: var(--sp-1); }
.sequence-nav a, .sequence-nav > span { display: inline-flex; align-items: center; gap: var(--sp-1); min-height: 36px; padding: 0 var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text-soft); font-size: var(--fs-xs); text-decoration: none; }
.sequence-nav a:hover { border-color: var(--accent-line); color: var(--accent); }
.sequence-nav .disabled { opacity: .4; }
.endpoint-bar { display: flex; align-items: center; min-width: 0; margin-top: var(--sp-4); padding: var(--sp-2); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-soft); }
.method { flex: 0 0 auto; padding: 4px var(--sp-2); border-radius: 5px; color: var(--text-invert); font-family: var(--mono); font-size: var(--fs-xs); font-weight: var(--fw-bold); }
.method.get { background: var(--blue); } .method.post { background: var(--accent); }
.endpoint-bar code { overflow: hidden; flex: 1; margin-left: var(--sp-2); color: var(--text-soft); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.endpoint-bar button { display: inline-flex; padding: 5px; border: 0; background: transparent; color: var(--text-faint); cursor: pointer; }
.endpoint-bar button:hover { color: var(--accent); }
.endpoint-bar .step { flex: 0 0 auto; margin-left: var(--sp-2); padding-left: var(--sp-3); border-left: 1px solid var(--border); color: var(--text-faint); font-size: var(--fs-2xs); letter-spacing: .08em; }
.learning-note { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: var(--sp-3); margin-top: var(--sp-3); padding: var(--sp-3) var(--sp-4); border-left: 3px solid var(--accent); border-radius: 0 var(--radius-sm) var(--radius-sm) 0; background: linear-gradient(90deg, var(--accent-soft), transparent); }
.learning-note > span { display: inline-flex; align-items: center; justify-content: center; width: 30px; height: 30px; border-radius: 50%; background: var(--bg-elev); color: var(--accent); }
.learning-note strong { font-size: var(--fs-xs); }
.learning-note p { margin: 2px 0 0; color: var(--text-soft); font-size: 12px; line-height: 1.65; }
.example-section, .request-card, .response-card { border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--bg-elev); box-shadow: var(--shadow-sm); }
.example-section { padding: var(--sp-4); }
.section-title, .card-head { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-3); }
.section-title > div, .card-title { display: flex; align-items: center; gap: var(--sp-3); }
.section-number { display: inline-flex; align-items: center; justify-content: center; width: 34px; height: 34px; border-radius: 9px; background: var(--accent-soft); color: var(--accent); font-size: 11px; font-weight: var(--fw-bold); font-variant-numeric: tabular-nums; }
.section-title h2, .card-title h2 { margin: 0; font-size: var(--fs-md); }
.section-title p, .card-title p { margin: 1px 0 0; color: var(--text-faint); font-size: var(--fs-xs); }
.section-title > span { color: var(--text-faint); font-size: var(--fs-xs); }
.examples { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: var(--sp-2); margin-top: var(--sp-3); }
.example-card { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: var(--sp-2); min-height: 54px; padding: var(--sp-2) var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-soft); color: var(--text); cursor: pointer; text-align: left; }
.example-card:hover { border-color: var(--border-strong); background: var(--bg-hover); }
.example-card.active { border-color: var(--accent); background: var(--accent-soft); color: var(--accent); box-shadow: 0 0 0 1px var(--accent-line); }
.radio-dot { display: inline-flex; align-items: center; justify-content: center; width: 16px; height: 16px; border: 1px solid var(--border-strong); border-radius: 50%; background: var(--bg-elev); }
.example-card.active .radio-dot { border-color: var(--accent); }
.example-card.active .radio-dot i { width: 8px; height: 8px; border-radius: 50%; background: var(--accent); }
.example-card strong, .example-card small { display: block; }
.example-card strong { overflow: hidden; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.example-card small { margin-top: 2px; color: var(--text-faint); font-size: var(--fs-2xs); }
.workbench { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: var(--sp-4); align-items: stretch; }
.request-card, .response-card { min-width: 0; overflow: hidden; }
.card-head { min-height: 70px; padding: var(--sp-3) var(--sp-4); border-bottom: 1px solid var(--border); background: var(--bg-soft); }
.request-shortcut { display: inline-flex; align-items: center; gap: var(--sp-1); color: var(--text-faint); font-family: var(--mono); font-size: var(--fs-2xs); }
.path-field { padding: var(--sp-3) var(--sp-4) 0; }
.path-field label { display: flex; justify-content: space-between; margin-bottom: var(--sp-1); color: var(--text-soft); font-size: 11px; font-weight: var(--fw-semibold); }
.path-field label span { color: var(--text-faint); font-size: var(--fs-2xs); font-weight: var(--fw-medium); }
.path-input { display: flex; align-items: center; overflow: hidden; border: 1px solid var(--border-ctl); border-radius: var(--radius-sm); background: var(--bg-soft); }
.path-input:focus-within { border-color: var(--accent); box-shadow: var(--focus-ring); }
.path-input span { padding-left: var(--sp-3); color: var(--text-faint); font-family: var(--mono); }
.path-input input { width: 100%; min-width: 0; padding: var(--sp-2); border: 0; outline: 0; background: transparent; color: var(--text); font: inherit; font-size: var(--fs-sm); }
.editor-shell { margin: var(--sp-3) var(--sp-4) 0; overflow: hidden; border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--surface-deep); }
.editor-bar { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-2); min-height: 38px; padding: 0 var(--sp-2) 0 var(--sp-3); border-bottom: 1px solid color-mix(in srgb, var(--on-deep) 14%, transparent); background: var(--surface-deep-2); color: var(--on-deep-soft); font-family: var(--mono); font-size: var(--fs-2xs); }
.editor-bar > span { display: inline-flex; align-items: center; gap: var(--sp-2); }
.editor-bar > span i { width: 7px; height: 7px; border-radius: 50%; background: var(--ok); box-shadow: 0 0 0 3px rgba(74,222,128,.12); }
.editor-bar > div { display: flex; gap: 2px; }
.editor-bar button { display: inline-flex; align-items: center; gap: 3px; padding: 5px 6px; border: 0; border-radius: 4px; background: transparent; color: var(--on-deep-faint); cursor: pointer; font: inherit; }
.editor-bar button:hover { background: color-mix(in srgb, var(--on-deep) 12%, transparent); color: var(--on-deep); }
.body { display: block; width: 100%; min-height: 340px; padding: var(--sp-3); border: 0; outline: 0; background: var(--surface-deep); color: var(--on-deep); caret-color: var(--accent); font-family: var(--mono); font-size: 11px; line-height: 1.65; resize: vertical; tab-size: 2; }
.no-body { display: grid; grid-template-columns: auto 1fr; gap: var(--sp-3); margin: var(--sp-3) var(--sp-4) 0; padding: var(--sp-5); border: 1px dashed var(--border-strong); border-radius: var(--radius-sm); background: var(--bg-soft); color: var(--text-soft); }
.no-body > span { display: inline-flex; align-items: center; justify-content: center; width: 34px; height: 34px; border-radius: 50%; background: var(--bg-elev); color: var(--blue); }
.no-body strong, .no-body small { display: block; }
.no-body strong { font-size: var(--fs-sm); }.no-body small { margin-top: 2px; color: var(--text-faint); font-size: var(--fs-xs); }
.run { width: calc(100% - var(--sp-8)); display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: var(--sp-2); min-height: 48px; margin: var(--sp-4); padding: 0 var(--sp-4); border: 0; border-radius: var(--radius-sm); background: linear-gradient(100deg, var(--accent), var(--accent-2)); color: var(--text-invert); box-shadow: 0 8px 18px color-mix(in srgb, var(--accent) 22%, transparent); cursor: pointer; font: inherit; font-size: var(--fs-sm); font-weight: var(--fw-semibold); }
.run:hover:not(:disabled) { background: linear-gradient(100deg, var(--accent-hover), var(--accent)); transform: translateY(-1px); }
.run:disabled { opacity: .68; cursor: wait; }
.run small { overflow: hidden; max-width: 190px; opacity: .72; font-family: var(--mono); font-size: var(--fs-2xs); font-weight: var(--fw-medium); text-overflow: ellipsis; white-space: nowrap; }
.spinning { animation: spin .9s linear infinite; }

.copy-response { display: inline-flex; align-items: center; gap: var(--sp-1); padding: 6px var(--sp-2); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text-soft); cursor: pointer; font: inherit; font-size: var(--fs-xs); }
.loading-state, .idle { display: flex; min-height: 430px; flex-direction: column; align-items: center; justify-content: center; padding: var(--sp-6); text-align: center; }
.loader, .idle-icon { display: inline-flex; align-items: center; justify-content: center; width: 58px; height: 58px; border-radius: 18px; background: var(--accent-soft); color: var(--accent); }
.loader { animation: breathe 1.1s ease-in-out infinite alternate; }

.loading-state h3, .idle h3 { margin: var(--sp-3) 0 var(--sp-1); font-size: var(--fs-md); }
.loading-state p, .idle p { max-width: 360px; margin: 0; color: var(--text-faint); font-size: 11px; line-height: 1.6; }
.loading-lines { width: min(280px, 80%); margin-top: var(--sp-5); }
.loading-lines i { display: block; height: 7px; margin-top: var(--sp-2); border-radius: var(--radius-pill); background: linear-gradient(90deg, var(--bg-soft), var(--bg-hover), var(--bg-soft)); background-size: 200% 100%; animation: shimmer 1.1s linear infinite; }
.loading-lines i:nth-child(2) { width: 82%; } .loading-lines i:nth-child(3) { width: 64%; }

.idle-flow { display: flex; align-items: center; gap: var(--sp-2); margin-top: var(--sp-4); color: var(--text-faint); font-size: var(--fs-2xs); }
.idle-flow span { padding: 4px 7px; border-radius: var(--radius-pill); background: var(--bg-soft); }
.error-banner { display: flex; min-height: 110px; flex-direction: column; justify-content: center; margin: var(--sp-4); }
.error-banner strong, .error-banner span { display: block; }
.error-banner span { margin-top: var(--sp-1); font-size: 11px; }
.result-meta { display: flex; align-items: center; justify-content: space-between; padding: var(--sp-3) var(--sp-4); border-bottom: 1px solid var(--border); }
.status { display: inline-flex; align-items: center; gap: var(--sp-2); font-family: var(--mono); font-size: var(--fs-xs); font-weight: var(--fw-semibold); }
.status i { width: 7px; height: 7px; border-radius: 50%; }.status.ok { color: var(--green); }.status.ok i { background: var(--green); box-shadow: 0 0 0 3px var(--green-soft); }.status.err { color: var(--err); }.status.err i { background: var(--err); box-shadow: 0 0 0 3px var(--err-soft); }
.elapsed-spark { width: 52px; height: 13px; }
.elapsed { display: inline-flex; align-items: center; gap: var(--sp-1); color: var(--text-faint); font-family: var(--mono); font-size: var(--fs-2xs); }
.result-content { min-width: 0; padding: var(--sp-4); }
.text-box { max-height: 520px; overflow: auto; margin: 0; padding: var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-soft); color: var(--text); font-family: var(--mono); font-size: 11px; line-height: 1.6; white-space: pre-wrap; word-break: break-all; }
.next-experiment { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-4); padding: var(--sp-4); border: 1px solid var(--border); border-radius: var(--radius-lg); background: linear-gradient(100deg, var(--bg-soft), var(--bg-elev)); }
.next-experiment span, .next-experiment strong { display: block; }.next-experiment span { color: var(--text-faint); font-size: var(--fs-xs); }.next-experiment strong { margin-top: 2px; font-size: var(--fs-sm); }
.next-experiment a { display: inline-flex; align-items: center; gap: var(--sp-2); flex: 0 0 auto; padding: var(--sp-2) var(--sp-3); border-radius: var(--radius-sm); background: var(--accent); color: var(--text-invert); font-size: var(--fs-xs); font-weight: var(--fw-semibold); text-decoration: none; }
.notfound { display: flex; flex-direction: column; align-items: center; gap: var(--sp-2); padding: var(--sp-8); color: var(--text-soft); text-align: center; }.notfound h2 { margin: var(--sp-2) 0; }
@media (max-width: 1180px) { .workbench { grid-template-columns: 1fr; } .loading-state, .idle { min-height: 300px; } }
@media (max-width: 680px) { .title-row { align-items: flex-start; flex-direction: column; } .sequence-nav { align-self: stretch; }.sequence-nav a, .sequence-nav > span { flex: 1; justify-content: center; }.endpoint-bar .step { display: none; }.example-section { padding: var(--sp-3); }.examples { grid-template-columns: 1fr; }.request-shortcut { display: none; }.card-head { padding: var(--sp-3); }.editor-shell, .no-body { margin-right: var(--sp-3); margin-left: var(--sp-3); }.run { width: calc(100% - var(--sp-6)); margin: var(--sp-3); }.run small { display: none; }.editor-bar button { font-size: 0; }.editor-bar button :deep(svg) { margin: 0; }.next-experiment { align-items: stretch; flex-direction: column; }.next-experiment a { justify-content: center; }.idle-flow { display: none; } }
</style>
