<script setup lang="ts">
const amount = defineModel<number | string>('amount', { required: true })
const inventory = defineModel<number | string>('inventory', { required: true })
</script>

<template>
  <div class="fg">
    <label>一口价 *
      <input v-model="amount" type="number" min="0.01" step="0.01" placeholder="如 9.9" data-testid="form-price" />
      <small>不管原价多少就卖这个数，减免 = 订单金额 − 一口价</small>
    </label>
    <label>库存 *
      <input v-model="inventory" type="number" min="1" data-testid="form-seckill-inventory" />
      <small>秒杀库存由 claim 端点原子扣减防超发；决策只报价、不扣减</small>
    </label>
  </div>
</template>
