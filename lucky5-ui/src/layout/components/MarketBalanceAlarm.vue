<script lang="ts" setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useLucky5Store } from '@/store/modules/lottery'
import { useUserStore } from '@/store/modules/user'
import alarmAudioUrl from '@/assets/audio/im/message-tip.mp3'
import {
  playMarketBalanceAlarmAudio,
  stopMarketBalanceAlarmAudio
} from '@/utils/marketBalanceAlarmAudio'

defineOptions({ name: 'MarketBalanceAlarm' })

const CHECK_INTERVAL = 3 * 60 * 1000
const store = useLucky5Store()
const userStore = useUserStore()
const refreshing = ref(false)
const alarmDialogVisible = ref(false)
const alarmAcknowledged = ref(false)
const soundBlocked = ref(false)

const canReadMarket = computed(
  () =>
    userStore.getPermissions.has('*:*:*') || userStore.getPermissions.has('lottery:config:manage')
)

const threshold = computed(() => Number(store.config.alertValue || 0))
const balance = computed(() => {
  const value = store.market.connection.balance
  return value === null || value === undefined ? null : Number(value)
})
const insufficient = computed(
  () =>
    !store.config.bossMode &&
    canReadMarket.value &&
    threshold.value > 0 &&
    balance.value !== null &&
    Number.isFinite(balance.value) &&
    balance.value < threshold.value
)

let timer: ReturnType<typeof setInterval> | undefined
let alarmAudio: HTMLAudioElement | undefined
let pendingPlayback = false
let lastAlarmAt = 0

const formatMoney = (value: number | null) => Number(value || 0).toFixed(2)

const stopAlarmSound = () => {
  stopMarketBalanceAlarmAudio()
  alarmAudio?.pause()
  if (alarmAudio) alarmAudio.currentTime = 0
}

const playAlarm = async () => {
  if (!insufficient.value || alarmAcknowledged.value) return
  try {
    await playMarketBalanceAlarmAudio()
    pendingPlayback = false
    soundBlocked.value = false
  } catch {
    alarmAudio ||= new Audio(alarmAudioUrl)
    alarmAudio.volume = 1
    alarmAudio.currentTime = 0
    try {
      await alarmAudio.play()
      pendingPlayback = false
      soundBlocked.value = false
    } catch {
      // 浏览器可能禁止无交互自动播放，保留一次待播放任务，在用户首次操作页面时补播。
      pendingPlayback = true
      soundBlocked.value = true
    }
  }
}

const playPendingAlarm = () => {
  if (!pendingPlayback || !insufficient.value || alarmAcknowledged.value) return
  void playAlarm()
}

const triggerAlarm = async () => {
  if (!insufficient.value || alarmAcknowledged.value) return
  const now = Date.now()
  if (now - lastAlarmAt < 5_000) return
  lastAlarmAt = now
  alarmDialogVisible.value = true
  await playAlarm()
}

const acknowledgeAlarm = () => {
  alarmAcknowledged.value = true
  alarmDialogVisible.value = false
  pendingPlayback = false
  soundBlocked.value = false
  lastAlarmAt = 0
  stopAlarmSound()
}

const refreshAndAlarm = async () => {
  refreshing.value = true
  try {
    await store.refreshMarketConnection()
    if (insufficient.value) await triggerAlarm()
  } finally {
    refreshing.value = false
  }
}

watch(insufficient, (current, previous) => {
  if (current && !previous) void triggerAlarm()
  if (!current) {
    pendingPlayback = false
    soundBlocked.value = false
    alarmAcknowledged.value = false
    alarmDialogVisible.value = false
    lastAlarmAt = 0
    stopAlarmSound()
  }
})

onMounted(async () => {
  document.addEventListener('pointerdown', playPendingAlarm)
  await store.initialize()
  if (canReadMarket.value) {
    await refreshAndAlarm()
    timer = setInterval(() => void refreshAndAlarm(), CHECK_INTERVAL)
  }
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  document.removeEventListener('pointerdown', playPendingAlarm)
  stopAlarmSound()
})
</script>

<template>
  <div v-if="insufficient" class="market-balance-alarm" role="alert">
    <div class="market-balance-alarm__icon">!</div>
    <div class="market-balance-alarm__content">
      <strong>盘口余额不足</strong>
      <span>
        当前余额 {{ formatMoney(balance) }}，低于报警值 {{ formatMoney(threshold) }}，请及时充值
      </span>
    </div>
    <el-button type="danger" plain size="small" :loading="refreshing" @click="refreshAndAlarm">
      刷新余额
    </el-button>
  </div>

  <el-dialog
    v-model="alarmDialogVisible"
    title="盘口余额不足"
    width="min(480px, calc(100vw - 32px))"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
    append-to-body
  >
    <div class="market-alarm-dialog">
      <div class="market-alarm-dialog__icon">!</div>
      <div>
        <strong>当前盘口余额已低于报警值</strong>
        <p>当前余额：{{ formatMoney(balance) }}</p>
        <p>报警值：{{ formatMoney(threshold) }}</p>
        <span>请及时检查并补充盘口余额。</span>
        <span v-if="soundBlocked" class="market-alarm-dialog__sound-tip">
          浏览器已阻止自动播放，请点击“播放报警声”。
        </span>
      </div>
    </div>
    <template #footer>
      <el-button type="warning" plain @pointerdown.stop @click="playAlarm">播放报警声</el-button>
      <el-button type="danger" @pointerdown.stop @click="acknowledgeAlarm">解除报警</el-button>
    </template>
  </el-dialog>
</template>

<style lang="scss" scoped>
.market-balance-alarm {
  position: fixed;
  top: calc(var(--top-tool-height) + 12px);
  left: 50%;
  z-index: 4000;
  display: flex;
  align-items: center;
  gap: 12px;
  width: min(620px, calc(100vw - 32px));
  padding: 12px 16px;
  color: var(--el-color-danger-dark-2);
  background: var(--el-color-danger-light-9);
  border: 1px solid var(--el-color-danger-light-5);
  border-radius: 8px;
  box-shadow: 0 8px 24px rgb(0 0 0 / 18%);
  transform: translateX(-50%);

  &__icon {
    display: grid;
    flex: 0 0 28px;
    width: 28px;
    height: 28px;
    color: #fff;
    font-size: 20px;
    font-weight: 700;
    background: var(--el-color-danger);
    border-radius: 50%;
    place-items: center;
  }

  &__content {
    display: flex;
    flex: 1;
    flex-direction: column;
    min-width: 0;
    line-height: 1.45;

    strong {
      font-size: 16px;
    }

    span {
      font-size: 13px;
    }
  }
}

.market-alarm-dialog {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  color: var(--el-text-color-primary);

  &__icon {
    display: grid;
    flex: 0 0 52px;
    width: 52px;
    height: 52px;
    color: #fff;
    font-size: 34px;
    font-weight: 700;
    background: var(--el-color-danger);
    border-radius: 50%;
    place-items: center;
  }

  strong {
    display: block;
    margin-bottom: 10px;
    color: var(--el-color-danger);
    font-size: 18px;
  }

  p {
    margin: 4px 0;
    font-size: 15px;
  }

  span {
    display: block;
    margin-top: 10px;
    color: var(--el-text-color-secondary);
  }

  &__sound-tip {
    color: var(--el-color-warning-dark-2) !important;
  }
}

@media (max-width: 600px) {
  .market-balance-alarm {
    top: calc(var(--top-tool-height) + 8px);
    align-items: flex-start;
    padding: 10px 12px;

    .el-button {
      display: none;
    }
  }
}
</style>
