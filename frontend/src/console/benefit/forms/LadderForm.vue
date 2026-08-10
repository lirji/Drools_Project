<script setup lang="ts">
import DynRowTable from '../../DynRowTable.vue'
import TierRuler from '../TierRuler.vue'
import type { LadderRow } from '../../logic'

defineProps<{ plain: string }>()
const ladder = defineModel<LadderRow[]>({ required: true })
</script>

<template>
  <TierRuler v-model="ladder" />
  <p class="plain-preview" data-testid="tier-plain">{{ plain }}</p>
  <details class="raw-tiers">
    <summary>精确编辑（起 / 止 / 奖励）</summary>
    <DynRowTable :rows="ladder" :headers="['起(min)', '止(max,空=无上限)', '奖励(reward)']" :make-row="() => ({ min: '', max: '', reward: '' })" label="阶梯档" v-slot="{ row }">
      <input type="number" v-model="(row as LadderRow).min" />
      <input type="number" v-model="(row as LadderRow).max" />
      <input type="number" v-model="(row as LadderRow).reward" />
    </DynRowTable>
  </details>
</template>
