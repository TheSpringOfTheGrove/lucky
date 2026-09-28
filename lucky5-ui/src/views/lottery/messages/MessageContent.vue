<script setup lang="ts">
import { computed } from 'vue'
import type { LotteryMessageRow } from '@/api/lottery'
import { receiptAction, receiptText } from '../utils/receipt'

const props = defineProps<{ row: LotteryMessageRow }>()
const action = computed(() =>
  props.row.kind === 'robot' ? receiptAction(props.row.content) : undefined
)
const text = computed(() => (action.value ? receiptText(props.row.content) : props.row.content))
// Replay the server-generated snapshot as an image, not inline SVG or untrusted message HTML.
const image = computed(() =>
  props.row.kind === 'robot' && props.row.drawImage?.startsWith('data:image/svg+xml;base64,')
    ? props.row.drawImage
    : ''
)
</script>

<template>
  <div class="message-content">
    <div class="message-content__text">{{ text }}</div>
    <span v-if="action === 'cancelable'" class="message-content__cancel">点击退码</span>
    <span v-else-if="action === 'canceled'" class="message-content__canceled">已退码</span>
    <img
      v-if="image"
      class="message-content__draw"
      :src="image"
      :alt="`第 ${row.period} 期开奖图`"
      loading="lazy"
    />
  </div>
</template>

<style scoped>
.message-content {
  margin: 0;
  line-height: 20px;
  text-align: left;
  overflow-wrap: anywhere;
}

.message-content__text {
  white-space: pre-wrap;
}

.message-content__cancel,
.message-content__canceled {
  display: block;
  margin-top: 20px;
  color: #ffa500;
}

.message-content__canceled {
  color: #888;
}

.message-content__draw {
  display: block;
  width: 381px;
  height: auto;
  max-width: 100%;
  margin-top: 5px;
}
</style>
