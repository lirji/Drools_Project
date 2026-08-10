<script setup lang="ts">
const min = defineModel<number | string>('min', { required: true })
const max = defineModel<number | string>('max', { required: true })
</script>

<template>
  <div class="fg">
    <label>随机下限 *<input v-model="min" type="number" min="0" placeholder="如 5" data-testid="form-range-min" /></label>
    <label>随机上限 *<input v-model="max" type="number" min="0" placeholder="如 20" data-testid="form-range-max" /></label>
    <small class="full">确定性随机：同一用户同一购物车金额固定，刷新不变价</small>
  </div>
</template>
