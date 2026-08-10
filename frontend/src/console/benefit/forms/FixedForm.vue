<script setup lang="ts">
const amount = defineModel<number | string>('amount', { required: true })
</script>

<template>
  <div class="fg">
    <label>红包金额<input v-model="amount" type="number" placeholder="0 ~ 999999" data-testid="form-amount" /></label>
  </div>
</template>
