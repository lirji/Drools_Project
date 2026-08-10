<script setup lang="ts">
const nth = defineModel<number | string>('nth', { required: true })
const amount = defineModel<number | string>('amount', { required: true })
</script>

<template>
  <div class="fg">
    <label>第几件 *
      <input v-model="nth" type="number" min="2" step="1" data-testid="form-nth" />
      <small>2 = 第二件（每满 2 件享 1 件折扣）</small>
    </label>
    <label>折数 *
      <input v-model="amount" type="number" step="0.1" min="0.1" max="9.9" placeholder="5 = 半价" data-testid="form-nth-zhe" />
      <small>按<b>同款</b>逐行算；调用方必须传订单行（含逐行单价），否则本活动不适用</small>
    </label>
  </div>
</template>
