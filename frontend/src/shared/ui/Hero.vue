<script setup lang="ts">
/**
 * Hero 原语（视觉换代 0809 · 步骤 6）—— /home、/login 等门面页共用的顶部大区块。
 *
 * 为什么单独成件：换代前 HomeView 的区域卡、PlaybooksView 的券卡都是各自手搓的，
 * 全站**没有任何「大区块」原语**，导致同一个概念在三个文件里长出三种写法。
 *
 * 视觉约定：
 * - 面走 --hero-bg（**永远深色**，不随主题翻面：hero 的深色是语义不是主题偏好），
 *   故其上的字一律 --on-deep*，**不能用 --text**（浅色档下是近黑，会看不见）。
 * - 右上角一团 accent 径向光。这是全屏唯一允许的大面积辉光，配额里占 1。
 * - 标题渐变走 .u-gradient-text（effects.css 已带 @supports not 兜底，clip 失效时不会让标题消失）。
 */
withDefaults(defineProps<{ kicker?: string; title: string; desc?: string; gradient?: boolean }>(), {
  gradient: true,
})
</script>

<template>
  <section class="hero">
    <div class="hero-inner">
      <div class="hero-copy">
        <p v-if="kicker" class="hero-kicker">{{ kicker }}</p>
        <h1 class="hero-title" :class="{ 'u-gradient-text': gradient }">{{ title }}</h1>
        <p v-if="desc" class="hero-desc">{{ desc }}</p>
        <div v-if="$slots.actions" class="hero-actions"><slot name="actions" /></div>
      </div>
      <div v-if="$slots.stats" class="hero-stats"><slot name="stats" /></div>
    </div>
    <slot />
  </section>
</template>

<style scoped>
.hero {
  position: relative;
  overflow: hidden;
  margin-bottom: var(--gap-block);
  padding: clamp(24px, 4vw, 40px);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-lg);
  background: var(--hero-bg);
  box-shadow: var(--shadow-md);
  color: var(--on-deep);
}
/* 右上角径向光。pointer-events:none 免得盖住按钮。 */
.hero::after {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: radial-gradient(30rem 16rem at 88% 0%, color-mix(in srgb, var(--accent) 26%, transparent), transparent 70%);
}
.hero-inner {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--sp-6);
}
.hero-copy { min-width: 0; }
.hero-kicker {
  margin: 0;
  font-family: var(--mono);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  letter-spacing: .18em;
  color: var(--on-deep-accent);
}
.hero-title {
  margin: var(--sp-2) 0 var(--sp-1);
  font-size: var(--fs-3xl);
  font-weight: var(--fw-bold);
  letter-spacing: -.02em;
  line-height: var(--lh-tight);
}
/* .u-gradient-text 的默认渐变起点是 --text（随主题翻面），hero 永远深色，故就地覆写成 --on-deep。 */
.hero-title.u-gradient-text {
  background: linear-gradient(120deg, var(--on-deep) 24%, var(--on-deep-accent) 96%);
  -webkit-background-clip: text;
  background-clip: text;
}
@supports not ((background-clip: text) or (-webkit-background-clip: text)) {
  .hero-title.u-gradient-text { color: var(--on-deep); background: none; }
}
.hero-desc {
  max-width: 62ch;
  margin: 0;
  color: var(--on-deep-soft);
  font-size: var(--fs-md);
  line-height: var(--lh-relaxed);
}
.hero-actions { display: flex; flex-wrap: wrap; gap: var(--sp-3); margin-top: var(--sp-5); }
.hero-stats {
  display: flex;
  flex: 0 0 auto;
  gap: 1px;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--on-deep) 16%, transparent);
  border-radius: var(--radius);
  background: color-mix(in srgb, var(--on-deep) 16%, transparent);
}

/* ≤1023：统计块下沉换行；≤767：hero 收窄留白并允许统计块横向滚动（不撑破视口）。 */
@media (max-width: 1023px) {
  .hero-inner { flex-direction: column; align-items: stretch; }
  .hero-stats { align-self: flex-start; }
}
@media (max-width: 767px) {
  .hero-title { font-size: var(--fs-2xl); }
  .hero-stats { align-self: stretch; overflow-x: auto; scrollbar-width: thin; }
}
</style>
