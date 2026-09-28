<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { RoomDraw } from '@/api/lottery/room'

const props = defineProps<{
  visible: boolean
  draw: RoomDraw | null
  resultPeriod: string
  currentPeriod: string
  remainingSeconds: number
  autoPopup: boolean
}>()

const emit = defineEmits<{
  close: []
  refresh: []
  'update:autoPopup': [value: boolean]
}>()

const canvasRef = ref<HTMLCanvasElement>()
const hasResult = computed(() => (props.draw?.numbers || []).length === 5)
const displayNumbers = computed(() =>
  hasResult.value ? props.draw!.numbers : ['', '', '', '', '']
)
let drawing = false
let context: CanvasRenderingContext2D | null = null
let lastPoint: { x: number; y: number } | null = null

const pointFromEvent = (event: PointerEvent) => {
  const canvas = canvasRef.value
  if (!canvas) return null
  const rect = canvas.getBoundingClientRect()
  return {
    x: event.clientX - rect.left,
    y: event.clientY - rect.top
  }
}

const resetCover = async () => {
  await nextTick()
  const canvas = canvasRef.value
  if (!canvas) return
  const rect = canvas.getBoundingClientRect()
  const ratio = window.devicePixelRatio || 1
  canvas.width = Math.max(1, Math.round(rect.width * ratio))
  canvas.height = Math.max(1, Math.round(rect.height * ratio))
  context = canvas.getContext('2d')
  if (!context) return
  context.setTransform(ratio, 0, 0, ratio, 0, 0)
  context.globalCompositeOperation = 'source-over'
  context.fillStyle = '#c4c6c8'
  context.fillRect(0, 0, rect.width, rect.height)
  context.fillStyle = '#f3f3f3'
  context.font = '600 18px Arial, sans-serif'
  context.textAlign = 'center'
  context.textBaseline = 'middle'
  context.fillText('刮开查看开奖结果', rect.width / 2, rect.height / 2)
  context.globalCompositeOperation = 'destination-out'
  context.lineCap = 'round'
  context.lineJoin = 'round'
  context.lineWidth = 30
}

const startScratch = (event: PointerEvent) => {
  if (!context || !canvasRef.value) return
  drawing = true
  lastPoint = pointFromEvent(event)
  canvasRef.value.setPointerCapture(event.pointerId)
}

const scratch = (event: PointerEvent) => {
  if (!drawing || !context || !lastPoint) return
  const point = pointFromEvent(event)
  if (!point) return
  context.beginPath()
  context.moveTo(lastPoint.x, lastPoint.y)
  context.lineTo(point.x, point.y)
  context.stroke()
  lastPoint = point
}

const stopScratch = () => {
  drawing = false
  lastPoint = null
}

const refresh = () => {
  emit('refresh')
  void resetCover()
}

watch(
  () => props.visible,
  (visible, previousVisible) => {
    if (visible && !previousVisible) {
      void resetCover()
    }
  }
)
</script>

<template>
  <div v-if="visible && resultPeriod" class="scratch-mask" @click.self="emit('close')">
    <section class="scratch-dialog" role="dialog" aria-modal="true" aria-label="刮刮卡">
      <h2>第 {{ resultPeriod }} 期</h2>

      <div class="scratch-stage" :class="{ 'is-empty': !hasResult }">
        <div class="scratch-numbers">
          <span v-for="(number, index) in displayNumbers" :key="`${number}-${index}`">
            {{ number }}
          </span>
        </div>
        <canvas
          ref="canvasRef"
          aria-label="刮奖覆盖层"
          @pointerdown="startScratch"
          @pointermove="scratch"
          @pointerup="stopScratch"
          @pointercancel="stopScratch"
          @pointerleave="stopScratch"
        ></canvas>
      </div>

      <h3>当前 {{ currentPeriod || '等待开盘' }} 期</h3>
      <p class="scratch-countdown">
        {{ `距离开奖 ${remainingSeconds}秒` }}
      </p>

      <button class="scratch-refresh" type="button" @click="refresh">刷新</button>
      <button class="scratch-close" type="button" @click="emit('close')">关闭</button>
      <label class="scratch-auto">
        <span>自动弹出</span>
        <input
          type="checkbox"
          :checked="autoPopup"
          @change="emit('update:autoPopup', ($event.target as HTMLInputElement).checked)"
        />
      </label>
    </section>
  </div>
</template>

<style scoped>
.scratch-mask {
  position: fixed;
  z-index: 40;
  display: grid;
  padding: 24px;
  background: rgb(0 0 0 / 48%);
  place-items: center;
  inset: 0;
}

.scratch-dialog {
  width: min(340px, 100%);
  height: 600px;
  padding: 20px;
  color: #b44c8d;
  text-align: center;
  background: #f7e6f2;
  border: 0;
  border-radius: 10px;
  box-shadow: none;
  box-sizing: border-box;
}

.scratch-dialog h2,
.scratch-dialog h3 {
  margin: 5px 0 15px;
  font-size: 14px;
  letter-spacing: 0;
}

.scratch-stage {
  position: relative;
  width: 300px;
  height: 150px;
  margin: 20px auto;
  background: #fff;
  aspect-ratio: auto;
}

.scratch-numbers {
  position: absolute;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  inset: 0;
}

.scratch-numbers span {
  display: grid;
  width: 44px;
  max-width: none;
  color: #fff;
  background: #cd665d;
  border-radius: 50%;
  aspect-ratio: 1;
  place-items: center;
  font-size: 16px;
}

.scratch-stage canvas {
  position: absolute;
  width: 100%;
  height: 100%;
  cursor: crosshair;
  touch-action: none;
  inset: 0;
}

.scratch-countdown {
  min-height: 21px;
  margin: 5px 0 20px;
  font-size: 14px;
  font-weight: 400;
}

.scratch-refresh,
.scratch-close {
  display: block;
  width: 100%;
  height: 40px;
  margin-top: 20px;
  color: #fff;
  border: 0;
  border-radius: 5px;
  font-size: 16px;
  cursor: pointer;
}

.scratch-refresh {
  background: #c8ce5f;
}

.scratch-close {
  background: #cd635a;
}

.scratch-auto {
  display: block;
  margin-top: 20px;
  color: #333;
  font-size: 14px;
}

.scratch-auto input {
  width: 20px;
  height: 20px;
  margin: -10px 0 0 4px;
  vertical-align: middle;
}

@media (max-width: 420px) {
  .scratch-mask {
    padding: 18px;
  }

  .scratch-dialog {
    padding: 26px 18px 22px;
  }

  .scratch-numbers {
    gap: 6px;
  }
}
</style>
