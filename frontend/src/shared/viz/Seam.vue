<script setup lang="ts">
/**
 * 齿孔撕线——本设计方向的头号签名手法（PR-3）。
 *
 * **它不是装饰边，是语义分段器**：撕线以上永远是「券面」（身份 / 时间窗 / 人群条件），
 * 以下永远是「副券」（金额 / 额度 / 操作）。位置恒定，运营找「钱在哪」不用读小标题、找那道切口即可。
 *
 * **缺口底色必须由使用点给**：同一个 Seam 会出现在三种上下文——页底（有 grain 网纹）、
 * 券卡白底、模态。写死 `var(--bg)` 三种里错两种，这是设计评审点名的致命项 X3。
 * 用法：在承载它的容器上设 `--notch-bg`，缺省回落到 `--bg`。
 */
withDefaults(defineProps<{ inset?: number }>(), { inset: 0 })
</script>

<template>
  <div class="seam" role="separator" :style="{ marginInline: inset + 'px' }" />
</template>

<style scoped>
.seam {
  position: relative; height: 1px; margin-block: var(--gap-group);
  background: radial-gradient(circle, var(--seam) 0 1.4px, transparent 1.6px) 0 50% / 9px 3px repeat-x;
}
.seam::before, .seam::after {
  content: ''; position: absolute; top: 50%;
  width: var(--notch); height: var(--notch); border-radius: 50%;
  background: var(--notch-bg, var(--bg));
  transform: translateY(-50%);
}
.seam::before { left: calc(var(--notch) / -2); }
.seam::after { right: calc(var(--notch) / -2); }
</style>
