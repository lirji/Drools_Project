<script setup lang="ts">
import { DEMOS } from './catalog'
</script>
<template>
  <div class="home" data-testid="demo-home">
    <h2>Drools 18 Step 演示台</h2>
    <p class="sub">从左侧选一个 Step，填参数、发请求、看规则引擎的效果。共 {{ DEMOS.length }} 个可运行 demo。</p>
  </div>
</template>
<style scoped>
.home { padding: var(--sp-5); }
.home h2 { margin: 0 0 var(--sp-2); }
.sub { color: var(--text-soft); font-size: 13px; }
</style>
