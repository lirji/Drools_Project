<script setup lang="ts">
defineProps<{ plain: string }>()
const amount = defineModel<number | string>('amount', { required: true })
const maxDiscount = defineModel<number | string>('maxDiscount', { required: true })
</script>

<template>
  <div class="fg">
    <label>折数 *
      <input v-model="amount" type="number" step="0.1" min="0.1" max="9.9" placeholder="8 = 八折" data-testid="form-zhe" />
      <small>8 = 八折（按原价 80% 收，减免 20%）</small>
    </label>
    <label>封顶减免额 (元) *
      <input v-model="maxDiscount" type="number" min="0.01" placeholder="例如 50" data-testid="form-max-discount" />
      <small>必填。打 8 折在一笔 10 万的订单上就是减 2 万</small>
    </label>
    <p class="plain-preview full" data-testid="ratio-plain">{{ plain }}</p>
  </div>
</template>
