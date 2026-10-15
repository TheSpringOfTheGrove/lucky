<script setup lang="ts">
import { computed } from 'vue'
import { receiptText } from '../utils/receipt'
import { cancelSuccessText } from '../room/replyTemplates'

const props = defineProps<{ value: string }>()
const lines = computed(() =>
  receiptText(props.value)
    .split('\n')
    .map((text) => {
      const field = /^(【(?:编号|套内|退面积|面积)】)(：.*)$/.exec(text)
      return field ? { label: field[1], value: field[2] } : { label: '', value: text }
    })
)
</script>

<template>
  <div class="cancel-success-receipt">
    <div v-for="(line, index) in lines" :key="index">
      <strong v-if="line.label">{{ line.label }}</strong><span>{{ line.value }}</span>
    </div>
    <div class="cancel-success-receipt__status">{{ cancelSuccessText }}</div>
  </div>
</template>

<style scoped>
.cancel-success-receipt {
  margin: 6px;
  font-family: 'Times New Roman', SimSun, '宋体', serif;
  font-size: 16px;
  font-weight: 400;
  line-height: 18px;
  text-align: left;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.cancel-success-receipt strong {
  font-weight: 700;
}

.cancel-success-receipt__status {
  color: #f5222d;
}
</style>
