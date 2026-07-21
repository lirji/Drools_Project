<script setup lang="ts">
// 单个 demo 的请求/响应面板 —— 平移 app.js 的 selectDemo/runDemo/buildUrl/parseBody。
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { DEMOS, type DemoDef } from './catalog'
import { api } from '@/shared/apiClient'
import { summaryComponent } from './summaries/registry'
import Banner from '@/shared/ui/Banner.vue'

const route = useRoute()
const demo = computed<DemoDef | undefined>(() => DEMOS.find((d) => d.id === route.params.demoId))

const bodyText = ref('')
const pathValues = ref<Record<string, string>>({})
const running = ref(false)
const resp = ref<{ ok: boolean; status: number; json: unknown; text: string } | null>(null)
const runErr = ref('')

// 选中/切换 demo：载入第 0 个示例
watch(demo, (d) => { if (d) loadExample(0) }, { immediate: true })

function loadExample(i: number): void {
  const d = demo.value
  if (!d) return
  const ex = d.examples[i]
  bodyText.value = ex && ex.body != null ? JSON.stringify(ex.body, null, 2) : ''
  const pv: Record<string, string> = {}
  for (const p of d.pathParams || []) pv[p.name] = (ex?.pathParams?.[p.name] as string) ?? ''
  pathValues.value = pv
  resp.value = null
  runErr.value = ''
}

function buildUrl(): string {
  let path = demo.value!.path
  for (const p of demo.value!.pathParams || []) {
    const v = pathValues.value[p.name]
    if (!v) throw new Error(`路径参数 ${p.label} 未填`)
    path = path.replace('{' + p.name + '}', encodeURIComponent(v))
  }
  return path
}

async function run(): Promise<void> {
  const d = demo.value
  if (!d) return
  running.value = true
  runErr.value = ''
  resp.value = null
  try {
    const url = buildUrl()
    let body: unknown = undefined
    if (d.method !== 'GET' && bodyText.value.trim()) {
      try { body = JSON.parse(bodyText.value) } catch { throw new Error('请求体不是合法 JSON') }
    }
    const raw = d.responseType === 'text'
    resp.value = await api('root', d.method, url, body, { raw })
  } catch (e) {
    runErr.value = (e as Error).message
  } finally {
    running.value = false
  }
}
</script>

<template>
  <div v-if="demo" class="panel" :data-testid="'demo-panel-' + demo.id">
    <div class="head">
      <span class="method" :class="demo.method === 'GET' ? 'get' : 'post'">{{ demo.method }}</span>
      <span class="path mono">{{ demo.path }}</span>
      <span class="step">Step {{ demo.step }}</span>
    </div>
    <h2>{{ demo.title }}</h2>
    <p class="desc">{{ demo.desc }}</p>

    <div class="grid">
      <div class="col">
        <div class="col-label">请求</div>
        <div v-if="demo.examples.length > 1" class="examples">
          <button v-for="(ex, i) in demo.examples" :key="i" class="chip" @click="loadExample(i)">{{ ex.label }}</button>
        </div>
        <div v-for="p in demo.pathParams || []" :key="p.name" class="pp">
          <label>{{ p.label }}<input v-model="pathValues[p.name]" :placeholder="p.placeholder || ''" /></label>
        </div>
        <textarea v-if="demo.method !== 'GET'" v-model="bodyText" class="body" rows="12" spellcheck="false" data-testid="demo-body" />
        <div v-else class="no-body">GET 请求，无请求体</div>
        <button class="run" :disabled="running" data-testid="demo-run" @click="run">
          <span class="ico">▶</span> {{ running ? '请求中…' : '发送请求' }}
        </button>
      </div>

      <div class="col">
        <div class="col-label">响应</div>
        <Banner v-if="running" kind="info">请求中…</Banner>
        <Banner v-else-if="runErr" kind="err" data-testid="demo-error">{{ runErr }}</Banner>
        <template v-else-if="resp">
          <div class="status" :class="resp.ok ? 'ok' : 'err'" data-testid="demo-status">HTTP {{ resp.status }}</div>
          <pre v-if="demo.responseType === 'text'" class="text-box">{{ resp.text }}</pre>
          <component v-else :is="summaryComponent(demo.summary)" :data="resp.json ?? resp.text" />
        </template>
        <div v-else class="idle">填参数后点「发送请求」</div>
      </div>
    </div>
  </div>
  <div v-else class="notfound">未找到 demo。<router-link :to="{ name: 'demos' }">返回列表</router-link></div>
</template>

<style scoped>
.head { display: flex; align-items: center; gap: var(--sp-2); }
.method { font-size: 11px; font-weight: 700; padding: 2px var(--sp-2); border-radius: var(--radius-sm); color: #fff; }
.method.get { background: var(--blue); }
.method.post { background: var(--accent); }
.path { font-size: 13px; color: var(--text-soft); }
.step { font-size: 11px; color: var(--text-faint); }
h2 { margin: var(--sp-2) 0 var(--sp-1); }
.desc { color: var(--text-soft); font-size: 13px; margin: 0 0 var(--sp-3); }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-4); }
.col-label { font-weight: 600; font-size: 13px; margin-bottom: var(--sp-2); }
.examples { display: flex; flex-wrap: wrap; gap: var(--sp-1); margin-bottom: var(--sp-2); }
.chip { padding: var(--sp-1) var(--sp-2); border: 1px solid var(--border); border-radius: 999px; background: var(--bg-elev); color: var(--text); font-size: 12px; cursor: pointer; }
.pp label { display: flex; flex-direction: column; gap: var(--sp-1); font-size: 12px; color: var(--text-soft); margin-bottom: var(--sp-2); }
.pp input { padding: var(--sp-2); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text); }
.body { width: 100%; font-family: var(--mono); font-size: 12px; padding: var(--sp-2); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-soft); color: var(--text); resize: vertical; }
.no-body { color: var(--text-faint); font-size: 13px; padding: var(--sp-3) 0; }
.run { margin-top: var(--sp-3); background: var(--accent); color: #fff; border: none; border-radius: var(--radius-sm); padding: var(--sp-2) var(--sp-5); cursor: pointer; font-size: 14px; }
.run:disabled { opacity: .5; cursor: not-allowed; }
.ico { font-size: 11px; }
.status { font-size: 12px; font-family: var(--mono); margin-bottom: var(--sp-2); }
.status.ok { color: var(--green); }
.status.err { color: var(--err); }
.text-box { font-family: var(--mono); font-size: 12px; background: var(--bg-soft); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: var(--sp-3); overflow-x: auto; white-space: pre-wrap; word-break: break-all; }
.idle { color: var(--text-faint); font-size: 13px; padding: var(--sp-4); }
.notfound { padding: var(--sp-5); }
@media (max-width: 1023px) { .grid { grid-template-columns: 1fr; } }
</style>
