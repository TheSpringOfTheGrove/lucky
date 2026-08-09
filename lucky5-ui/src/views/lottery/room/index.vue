<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import {
  cancelRoomOrderApi,
  getRoomSessionApi,
  sendRoomMessageApi,
  type RoomAmountRecord,
  type RoomCredential,
  type RoomDraw,
  type RoomOrder,
  type RoomSession
} from '@/api/lottery/room'
import ScratchCard from './components/ScratchCard.vue'
import QuickPickDialog from './components/QuickPickDialog.vue'
import logo from '@/assets/imgs/logo.png'
import memberAvatar from '@/assets/imgs/avatar.jpg'

type ChatKind = 'member' | 'robot'
type ChatType = 'text' | 'order' | 'amount' | 'draw'

interface ChatItem {
  id: string
  kind: ChatKind
  type: ChatType
  content: string
  createdAt: string
  order?: RoomOrder
  amountRecord?: RoomAmountRecord
  draw?: RoomDraw
  showTime?: boolean
}

const route = useRoute()
const session = ref<RoomSession | null>(null)
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const composer = ref('')
const chatRef = ref<HTMLElement>()
const composerRef = ref<HTMLTextAreaElement>()
const bottomPanel = ref<'keyboard' | 'commands' | ''>('')
const quickPickerVisible = ref(false)
const localMessages = ref<ChatItem[]>([])
const sessionStartedAt = ref(new Date().toISOString())
const scratchVisible = ref(false)
const scratchRemaining = ref(0)
const autoScratch = ref(localStorage.getItem('lucky5-auto-scratch') !== 'false')
const uniqueId = () =>
  globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(16).slice(2)}`

const credential = computed<RoomCredential>(() => {
  const queryOpenId = typeof route.query.openId === 'string' ? route.query.openId : ''
  const shortOpenId = typeof route.params.openId === 'string' ? route.params.openId : ''
  const legacyFingerprint = typeof route.query.fp === 'string' ? route.query.fp : ''
  return {
    tenantId: Number(route.query.tenantId || 1),
    uid: typeof route.query.uid === 'string' ? route.query.uid : undefined,
    openId: queryOpenId || shortOpenId || legacyFingerprint,
    fp: legacyFingerprint || undefined
  }
})

const orderById = computed(() =>
  Object.fromEntries((session.value?.orders || []).map((order) => [order.id, order]))
)
const latestDraw = computed(() => session.value?.draws[0] || null)

const chatMessages = computed<ChatItem[]>(() => {
  if (!session.value) return localMessages.value

  const sourceDates = [
    ...session.value.messages.map((item) => item.createdAt),
    ...session.value.amountRecords.map((item) => item.createdAt)
  ]
  const firstDate = sourceDates.sort()[0] || sessionStartedAt.value
  const messages: ChatItem[] = [
    {
      id: 'room-welcome',
      kind: 'robot',
      type: 'text',
      content: `${session.value.room.name}\n会员：${session.value.member.name}\n可用积分：${money(
        session.value.member.balance
      )}\n当前期号：${session.value.suggestedPeriod || '等待开盘'}\n${
        session.value.room.open && session.value.room.bettingEnabled ? '房间运行中' : '当前已停盘'
      }`,
      createdAt: dayjs(firstDate).subtract(2, 'second').toISOString()
    }
  ]

  if (session.value.room.announcement) {
    messages.push({
      id: 'room-announcement',
      kind: 'robot',
      type: 'text',
      content: session.value.room.announcement,
      createdAt: dayjs(firstDate).subtract(1, 'second').toISOString()
    })
  }

  for (const transition of session.value.issueTransitions || []) {
    if (transition.status === 'CLOSED' && transition.summary) {
      messages.push({
        id: `issue-summary-${transition.id}`,
        kind: 'robot',
        type: 'text',
        content: transition.summary,
        createdAt: dayjs(transition.createdAt).subtract(1, 'millisecond').toISOString()
      })
    }
    messages.push({
      id: `issue-transition-${transition.id}`,
      kind: 'robot',
      type: 'text',
      content: transition.status === 'OPEN' ? '^^★★★开始-答题★★★' : '^^★★★停止-上课★★★',
      createdAt: transition.createdAt
    })
  }

  for (const draw of session.value.draws) {
    messages.push({
      id: `draw-${draw.period}`,
      kind: 'robot',
      type: 'draw',
      content: `^^ --| ${draw.period.slice(-3)}房间号-${draw.numbers.join('|')}`,
      createdAt: draw.settledAt,
      draw
    })
  }

  for (const message of session.value.messages) {
    const order = message.orderId ? orderById.value[message.orderId] : undefined
    if (!['SETTLEMENT', 'PERIOD_SUMMARY'].includes(message.commandType)) {
      messages.push({
        id: `member-message-${message.id}`,
        kind: 'member',
        type: 'text',
        content: message.content,
        createdAt: message.createdAt
      })
    }
    messages.push({
      id: `robot-message-${message.id}-${message.status}`,
      kind: 'robot',
      type: order ? 'order' : 'text',
      content: order
        ? message.reply || '下注成功'
        : message.reply ||
          message.error ||
          (message.status === '处理中' ? '正在处理' : message.status),
      createdAt: dayjs(message.createdAt).add(1, 'millisecond').toISOString(),
      order
    })
  }

  for (const amountRecord of session.value.amountRecords) {
    const isMemberRequest = amountRecord.remark !== '后台手动操作'
    const commandType = amountRecord.type === '上分' ? 'DEPOSIT_REQUEST' : 'WITHDRAW_REQUEST'
    const relatedMessage = session.value.messages.find(
      (message) =>
        message.commandType === commandType &&
        Number(message.content.replace(/[^\d.]/g, '')) === Number(amountRecord.amount) &&
        Math.abs(dayjs(message.createdAt).diff(dayjs(amountRecord.createdAt), 'second')) <= 10
    )
    if (isMemberRequest && !relatedMessage) {
      messages.push({
        id: `member-amount-${amountRecord.id}`,
        kind: 'member',
        type: 'text',
        content: `${amountRecord.type === '上分' ? '上' : '下'}${money(amountRecord.amount)}`,
        createdAt: amountRecord.createdAt
      })
    }
    if (!relatedMessage) {
      messages.push({
        id: `robot-amount-${amountRecord.id}-${amountRecord.status}`,
        kind: 'robot',
        type: 'amount',
        content: `${amountRecord.type}${isMemberRequest ? '申请' : ''}${amountRecord.status}`,
        createdAt:
          amountRecord.auditedAt ||
          dayjs(amountRecord.createdAt).add(1, 'millisecond').toISOString(),
        amountRecord
      })
    }
  }

  messages.push(...localMessages.value)
  messages.sort((a, b) => dayjs(a.createdAt).valueOf() - dayjs(b.createdAt).valueOf())
  return messages.map((item, index) => {
    const previous = messages[index - 1]
    return {
      ...item,
      showTime:
        !previous || dayjs(item.createdAt).diff(dayjs(previous.createdAt), 'minute', true) >= 5
    }
  })
})

const keyboardRows = [
  ['查', '上', '下', '二', '三', '四', '定', '现', '←'],
  ['奖', '大', '千', '1', '2', '3', '除', '双重', '兄弟'],
  ['走', '小', '百', '4', '5', '6', '取', '三重', '两'],
  ['倒', '单', '十', '7', '8', '9', '。', '四重', '清除'],
  ['全', '双', '个', '0', '.', 'X', '各', '合', '换行']
]

let refreshTimer: number | undefined
let countdownTimer: number | undefined

const money = (value: number) => Number(value || 0).toFixed(2)
const receiptText = (value: string) => value.replace(/\n点击退码\s*$/, '')
const messageTime = (value: string) =>
  dayjs(value).isSame(dayjs(), 'day')
    ? dayjs(value).format('HH:mm')
    : dayjs(value).format('MM-DD HH:mm')
const orderStatusClass = (status: string) => {
  if (status === '已中奖') return 'is-win'
  if (status === '未中奖' || status === '已退码') return 'is-failed'
  return 'is-pending'
}

const scrollToBottom = async (behavior: ScrollBehavior = 'auto') => {
  await nextTick()
  chatRef.value?.scrollTo({ top: chatRef.value.scrollHeight, behavior })
}

const loadSession = async (quiet = false) => {
  if (!credential.value.openId) {
    error.value = '会员链接已失效'
    loading.value = false
    return
  }
  if (!quiet) loading.value = true
  try {
    const previousDrawPeriod = session.value?.draws[0]?.period || ''
    const nextSession = await getRoomSessionApi(credential.value)
    session.value = nextSession
    scratchRemaining.value = nextSession.issue.remainingSeconds
    const nextDrawPeriod = nextSession.draws[0]?.period || ''
    if (
      previousDrawPeriod &&
      nextDrawPeriod &&
      previousDrawPeriod !== nextDrawPeriod &&
      nextSession.room.features.prizeCard &&
      autoScratch.value
    ) {
      scratchVisible.value = true
    }
    error.value = ''
  } catch (reason: any) {
    error.value = reason?.message || '会员链接已失效'
  } finally {
    loading.value = false
  }
}

const updateAutoScratch = (value: boolean) => {
  autoScratch.value = value
  localStorage.setItem('lucky5-auto-scratch', String(value))
}

const submitChat = async () => {
  const content = composer.value.trim()
  if (!content || !session.value || saving.value) return

  bottomPanel.value = ''
  composer.value = ''
  saving.value = true
  try {
    await sendRoomMessageApi(credential.value, {
      period: session.value.issue.status === 'OPEN' ? session.value.suggestedPeriod : undefined,
      content,
      externalId: uniqueId()
    })
  } catch (reason: any) {
    ElMessage.error(reason?.message || '发送失败')
  } finally {
    saving.value = false
    await loadSession(true)
    await scrollToBottom('smooth')
    composerRef.value?.focus()
  }
}

const appendKey = (key: string) => {
  if (key === '←') composer.value = composer.value.slice(0, -1)
  else if (key === '清除') composer.value = ''
  else if (key === '换行') composer.value += '\n'
  else composer.value += key
  composerRef.value?.focus()
}

const togglePanel = async (panel: 'keyboard' | 'commands') => {
  bottomPanel.value = bottomPanel.value === panel ? '' : panel
  if (bottomPanel.value === 'commands') await loadSession(true)
  if (bottomPanel.value === '') composerRef.value?.focus()
  void scrollToBottom()
}

const useHistory = (content: string) => {
  composer.value = content
  bottomPanel.value = ''
  composerRef.value?.focus()
}

const openQuickPicker = () => {
  bottomPanel.value = ''
  quickPickerVisible.value = true
}

const useQuickGenerated = (content: string) => {
  composer.value = content
  quickPickerVisible.value = false
  composerRef.value?.focus()
}

const submitQuickGenerated = async (content: string) => {
  composer.value = content
  quickPickerVisible.value = false
  await submitChat()
}

const cancelOrder = async (order: RoomOrder) => {
  try {
    await ElMessageBox.confirm(`确认退回第 ${order.period} 期订单？`, '退码', {
      type: 'warning',
      confirmButtonText: '确认退码',
      cancelButtonText: '取消'
    })
    saving.value = true
    await cancelRoomOrderApi(credential.value, order.id)
    await loadSession(true)
    await scrollToBottom('smooth')
  } catch (reason: any) {
    if (reason !== 'cancel' && reason !== 'close') ElMessage.error(reason?.message || '退码失败')
  } finally {
    saving.value = false
  }
}

watch(
  () => chatMessages.value.at(-1)?.id,
  () => void scrollToBottom()
)

watch(
  () => credential.value.openId,
  async (openId, previousOpenId) => {
    if (!openId || openId === previousOpenId) return
    sessionStartedAt.value = new Date().toISOString()
    localMessages.value = []
    await loadSession()
    await scrollToBottom()
    composerRef.value?.focus()
  }
)

onMounted(async () => {
  await loadSession()
  await scrollToBottom()
  composerRef.value?.focus()
  refreshTimer = window.setInterval(() => void loadSession(true), 5000)
  countdownTimer = window.setInterval(() => {
    scratchRemaining.value = Math.max(0, scratchRemaining.value - 1)
  }, 1000)
})

onBeforeUnmount(() => {
  if (refreshTimer) window.clearInterval(refreshTimer)
  if (countdownTimer) window.clearInterval(countdownTimer)
})
</script>

<template>
  <div :class="['chat-room', bottomPanel ? `has-panel-${bottomPanel}` : '']">
    <div v-if="loading" class="room-state-page">
      <span class="room-loader"></span>
    </div>

    <div v-else-if="error" class="room-state-page room-state-page-error">
      <img :src="logo" alt="Lucky5" />
      <strong>{{ error }}</strong>
    </div>

    <template v-else-if="session">
      <button
        v-if="session.room.features.prizeCard && latestDraw"
        class="scratch-launcher"
        type="button"
        aria-label="打开刮刮卡"
        @click="scratchVisible = true"
      >
        刮牌
      </button>

      <ScratchCard
        :visible="scratchVisible"
        :draw="latestDraw"
        :current-period="session.issue.currentPeriod"
        :remaining-seconds="scratchRemaining"
        :auto-popup="autoScratch"
        @close="scratchVisible = false"
        @refresh="loadSession(true)"
        @update:auto-popup="updateAutoScratch"
      />

      <main ref="chatRef" class="chat-stream" aria-live="polite">
        <article
          v-for="message in chatMessages"
          :key="message.id"
          :class="['chat-message', `chat-message-${message.kind}`]"
        >
          <div v-if="message.showTime" class="chat-time"
            ><span>{{ messageTime(message.createdAt) }}</span></div
          >
          <div class="chat-row">
            <img
              class="chat-avatar"
              :src="message.kind === 'robot' ? logo : memberAvatar"
              :alt="message.kind === 'robot' ? '机器人' : session.member.name"
            />
            <div class="chat-body">
              <h5>{{ message.kind === 'robot' ? '机器人' : session.member.name }}</h5>
              <div class="chat-bubble">
                <template v-if="message.type === 'draw' && message.draw">
                  <pre class="draw-brief">{{ message.content }}</pre>
                  <div
                    v-if="
                      session.room.features.groupImage && message.draw.period === latestDraw?.period
                    "
                    :class="['lottery-table', { 'is-bold': session.room.features.imageBold }]"
                  >
                    <div class="lottery-table__head">
                      <strong>期数</strong><strong>时间</strong><strong>成功</strong>
                    </div>
                    <div
                      v-for="draw in session.draws.slice(0, 15)"
                      :key="draw.period"
                      class="lottery-table__history-row"
                    >
                      <span>{{ draw.period.slice(-3) }}</span>
                      <span>{{ dayjs(draw.settledAt).format('HH:mm') }}</span>
                      <span>{{ draw.numbers.join('　') }}</span>
                    </div>
                  </div>
                </template>

                <template v-else-if="message.type === 'order' && message.order">
                  <pre class="reference-receipt">{{ receiptText(message.content) }}</pre>
                  <div class="receipt-title">
                    <strong>第 {{ message.order.period }} 期</strong>
                    <span :class="orderStatusClass(message.order.status)">{{
                      message.order.status
                    }}</span>
                  </div>
                  <div class="receipt-content">{{ message.order.content }}</div>
                  <div v-if="message.order.items.length" class="receipt-items">
                    <div v-for="item in message.order.items" :key="item.id">
                      <span>{{ item.play }} · {{ item.selection }}</span>
                      <span>{{ money(item.amount) }} @ {{ item.odds }}</span>
                    </div>
                    <div v-if="message.order.itemCount > message.order.items.length">
                      <span>其余号码已收起</span>
                      <span>+{{ message.order.itemCount - message.order.items.length }} 注</span>
                    </div>
                  </div>
                  <div class="receipt-total">
                    <span>共 {{ message.order.itemCount }} 注</span
                    ><strong>合计 {{ money(message.order.amount) }}</strong>
                  </div>
                  <div v-if="message.order.status === '已中奖'" class="receipt-result is-win">
                    派彩 {{ money(message.order.win) }}
                  </div>
                  <button
                    v-if="
                      message.order.status === '未开奖' && session.room.cancelEnabled && !saving
                    "
                    class="cancel-link"
                    type="button"
                    @click="cancelOrder(message.order)"
                  >
                    点击退码
                  </button>
                </template>

                <template v-else-if="message.type === 'amount' && message.amountRecord">
                  <div class="amount-reply">
                    <strong>{{ message.content }}</strong>
                    <span>分数：{{ money(message.amountRecord.amount) }}</span>
                    <span v-if="message.amountRecord.remark">{{
                      message.amountRecord.remark
                    }}</span>
                  </div>
                </template>

                <pre v-else>{{ message.content }}</pre>
              </div>
            </div>
          </div>
        </article>
      </main>

      <form class="chat-composer" @submit.prevent="submitChat">
        <div v-if="bottomPanel === 'keyboard'" class="composer-panel virtual-keyboard">
          <div v-for="(row, rowIndex) in keyboardRows" :key="rowIndex" class="keyboard-row">
            <button
              v-for="key in row"
              :key="key"
              :class="{ 'is-danger': key === '←', 'is-accent': key === '换行' }"
              type="button"
              @click="appendKey(key)"
            >
              {{ key }}
            </button>
          </div>
        </div>

        <div
          v-else-if="bottomPanel === 'commands'"
          class="composer-panel history-panel command-panel"
        >
          <button
            v-for="command in session.quickCommands"
            :key="command.id"
            type="button"
            :title="command.content"
            @click="useHistory(command.content)"
          >
            <span>{{ command.label }}</span>
          </button>
          <div v-if="!session.quickCommands.length" class="history-empty">暂无快捷指令</div>
        </div>

        <div class="composer-row">
          <button
            class="keyboard-toggle"
            :class="{ 'is-active': bottomPanel === 'keyboard' }"
            type="button"
            aria-label="虚拟键盘"
            @click="togglePanel('keyboard')"
          >
            <span v-for="index in 8" :key="index"></span>
          </button>
          <textarea
            ref="composerRef"
            v-model="composer"
            rows="1"
            autocomplete="off"
            aria-label="聊天输入"
            @focus="bottomPanel = ''"
            @keydown.enter.exact.prevent="submitChat"
          ></textarea>
          <button class="fast-select" type="button" @click="openQuickPicker">快选</button>
          <button class="send-button" type="submit" :disabled="saving || !composer.trim()">
            {{ saving ? '处理中' : '发送' }}
          </button>
          <button
            class="history-toggle"
            :class="{ 'is-active': bottomPanel === 'commands' }"
            type="button"
            aria-label="快捷指令"
            @click="togglePanel('commands')"
          ></button>
        </div>
      </form>
      <QuickPickDialog
        :visible="quickPickerVisible"
        :period="session.suggestedPeriod"
        :balance="session.member.balance"
        :credential="credential"
        @close="quickPickerVisible = false"
        @use="useQuickGenerated"
        @submit="submitQuickGenerated"
      />
    </template>
  </div>
</template>

<style scoped>
.chat-room {
  position: fixed;
  z-index: 1;
  overflow: hidden;
  font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif;
  color: #333;
  background: #f5f5f7;
  inset: 0;
}

.room-state-page {
  display: grid;
  width: 100%;
  height: 100%;
  place-items: center;
}

.room-state-page-error {
  align-content: center;
  gap: 14px;
  color: #666;
}

.room-state-page-error img {
  width: 58px;
  height: 58px;
  object-fit: contain;
}

.room-loader,
.room-loader::before,
.room-loader::after {
  width: 8px;
  height: 16px;
  background: #6b9dc8;
  animation: loading 1.4s infinite ease-in-out;
}

.room-loader {
  position: relative;
  animation-delay: 0.15s;
}

.room-loader::before,
.room-loader::after {
  position: absolute;
  top: 0;
  content: '';
}

.room-loader::before {
  left: -15px;
}

.room-loader::after {
  right: -15px;
  animation-delay: 0.3s;
}

.chat-stream {
  position: absolute;
  padding: 8px 0 24px;
  overflow: hidden auto;
  box-sizing: border-box;
  inset: 0 0 49px;
  -webkit-overflow-scrolling: touch;
}

.reference-receipt {
  white-space: pre-wrap;
  line-height: 1.45;
}

.reference-receipt + .receipt-title,
.receipt-content,
.receipt-items,
.receipt-total {
  display: none;
}

.command-panel {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px;
  padding: 5px;
}

.command-panel > button {
  min-width: 0;
  height: 34px;
  padding: 0 7px;
  overflow: hidden;
  border: 1px solid #d3d9e2;
  background: #fff;
  color: #111;
  font-weight: 700;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-message {
  position: relative;
  display: flow-root;
  padding: 0 14px;
}

.chat-time {
  width: 100%;
  margin: 13px 0 7px;
  font-size: 12px;
  text-align: center;
}

.chat-time span {
  display: inline-block;
  padding: 2px 5px;
  color: #fff;
  background: #cecece;
  border-radius: 4px;
}

.chat-row {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-top: 20px;
}

.chat-message-member .chat-row {
  flex-direction: row-reverse;
}

.chat-avatar {
  width: 48px;
  height: 48px;
  background: #fff;
  border-radius: 4px;
  flex: 0 0 48px;
  object-fit: cover;
}

.chat-message-robot .chat-avatar {
  padding: 6px;
  background: #13233a;
  box-sizing: border-box;
}

.chat-body {
  position: relative;
  max-width: min(65%, 680px);
  min-width: 30px;
}

.chat-body h5 {
  height: 18px;
  margin: -2px 0 2px;
  overflow: hidden;
  font-size: 13px;
  font-weight: 400;
  line-height: 18px;
  color: #6f6f6f;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-message-member .chat-body h5 {
  text-align: right;
}

.chat-bubble {
  position: relative;
  min-height: 32px;
  padding: 7px 9px;
  font-size: 16px;
  line-height: 20px;
  background: #fff;
  border: 1px solid #d1d1d1;
  border-radius: 7px;
  box-sizing: border-box;
}

.chat-bubble::before,
.chat-bubble::after {
  position: absolute;
  top: 8px;
  width: 0;
  height: 0;
  border-style: solid;
  content: '';
}

.chat-message-robot .chat-bubble::before {
  left: -9px;
  border-color: transparent #d1d1d1 transparent transparent;
  border-width: 7px 9px 7px 0;
}

.chat-message-robot .chat-bubble::after {
  left: -7px;
  border-color: transparent #fff transparent transparent;
  border-width: 7px 9px 7px 0;
}

.chat-message-member .chat-bubble {
  color: #253f0f;
  background: #a1e85a;
  border-color: #84b559;
}

.chat-message-member .chat-bubble::before {
  right: -9px;
  border-color: transparent transparent transparent #84b559;
  border-width: 7px 0 7px 9px;
}

.chat-message-member .chat-bubble::after {
  right: -7px;
  border-color: transparent transparent transparent #a1e85a;
  border-width: 7px 0 7px 9px;
}

.chat-bubble pre {
  margin: 0;
  font: inherit;
  word-break: break-word;
  white-space: pre-wrap;
  user-select: text;
}

.receipt-title,
.receipt-total,
.receipt-items > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}

.receipt-title {
  padding-bottom: 6px;
  border-bottom: 1px solid #e5e5e5;
}

.receipt-title span {
  font-size: 13px;
  color: #a26a00;
}

.receipt-title .is-win,
.receipt-result.is-win {
  color: #169447;
}

.receipt-title .is-failed {
  color: #d33b32;
}

.receipt-content {
  padding: 7px 0 5px;
  word-break: break-word;
  white-space: pre-wrap;
}

.receipt-items {
  min-width: 240px;
  padding: 5px 0;
  font-size: 13px;
  color: #666;
  border-top: 1px dashed #ddd;
}

.receipt-items > div + div {
  margin-top: 3px;
}

.receipt-total {
  padding-top: 6px;
  border-top: 1px solid #e5e5e5;
}

.receipt-result {
  margin-top: 5px;
  font-weight: 700;
}

.cancel-link {
  padding: 8px 0 0;
  font: inherit;
  color: #e68a00;
  cursor: pointer;
  background: transparent;
  border: 0;
}

.amount-reply {
  display: grid;
  gap: 3px;
  min-width: 180px;
}

.amount-reply span {
  font-size: 13px;
  color: #666;
}

.chat-composer {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 5;
  padding-bottom: env(safe-area-inset-bottom);
  background: #f5f5f7;
  border-top: 1px solid #dadadc;
  box-sizing: border-box;
}

.composer-row {
  display: grid;
  min-height: 48px;
  grid-template-columns: 28px minmax(80px, 1fr) 50px 50px 28px;
  align-items: end;
  gap: 10px;
  padding: 7px 10px;
  box-sizing: border-box;
}

.composer-row textarea {
  width: 100%;
  max-height: 88px;
  min-height: 32px;
  padding: 6px 8px;
  overflow-y: auto;
  font-family: inherit;
  font-size: 16px;
  line-height: 18px;
  background: #fcfcfc;
  border: 1px solid #dcdcde;
  border-radius: 8px;
  outline: none;
  box-sizing: border-box;
  resize: none;
}

.has-panel-keyboard .chat-stream {
  bottom: 239px;
}

.has-panel-quick .chat-stream {
  bottom: 147px;
}

.has-panel-history .chat-stream {
  bottom: min(399px, 60vh);
}

.composer-row textarea:focus {
  border-color: #b8b8ba;
}

.keyboard-toggle,
.history-toggle {
  position: relative;
  width: 28px;
  height: 28px;
  padding: 0;
  margin-bottom: 2px;
  cursor: pointer;
  background: transparent;
  border: 1px solid #a7a7a9;
  border-radius: 50%;
}

.keyboard-toggle {
  display: grid;
  grid-template-columns: repeat(4, 4px);
  grid-auto-rows: 4px;
  place-content: center;
  gap: 2px;
}

.keyboard-toggle span {
  background: #7f8085;
  border-radius: 50%;
}

.keyboard-toggle.is-active,
.history-toggle.is-active {
  border-color: #d2691e;
}

.keyboard-toggle.is-active span,
.history-toggle.is-active::before,
.history-toggle.is-active::after {
  background: #d2691e;
}

.history-toggle::before,
.history-toggle::after {
  position: absolute;
  top: 50%;
  left: 50%;
  background: #7f8085;
  content: '';
  transform: translate(-50%, -50%);
}

.history-toggle::before {
  width: 16px;
  height: 2px;
}

.history-toggle::after {
  width: 2px;
  height: 16px;
}

.fast-select,
.send-button {
  width: 50px;
  height: 28px;
  padding: 0;
  margin-bottom: 2px;
  font-weight: 700;
  color: #fff;
  cursor: pointer;
  background: #1aac19;
  border: 1px solid #1f8b1b;
  border-radius: 3px;
}

.send-button:disabled {
  cursor: default;
  background: #aaa;
  border-color: #aaa;
}

.composer-panel {
  background: #d0d3dc;
  border-bottom: 1px solid #b9bcc4;
  box-sizing: border-box;
}

.virtual-keyboard {
  display: grid;
  gap: 5px;
  height: 190px;
  padding: 5px 2%;
}

.keyboard-row {
  display: grid;
  grid-template-columns: repeat(9, minmax(0, 1fr));
  gap: 2%;
}

.keyboard-row button {
  min-width: 0;
  font-weight: 700;
  color: #000;
  cursor: pointer;
  background: #fdffff;
  border: 0;
  border-radius: 5px;
}

.keyboard-row button:active {
  background: #ff8c00;
}

.keyboard-row button.is-danger {
  color: #e30000;
}

.keyboard-row button.is-accent {
  color: #666;
  background: #ffa500;
}

.quick-panel {
  padding: 12px;
}

.quick-options {
  display: grid;
  grid-template-columns: repeat(7, minmax(38px, 1fr));
  gap: 6px;
}

.quick-options button,
.quick-amount button,
.quick-amount input {
  height: 34px;
  background: #fff;
  border: 1px solid #b7bac2;
  border-radius: 4px;
}

.quick-options button.is-selected {
  color: #fff;
  background: #1aac19;
  border-color: #1f8b1b;
}

.quick-amount {
  display: grid;
  max-width: 420px;
  margin: 10px auto 0;
  grid-template-columns: 42px minmax(80px, 1fr) 42px 74px;
  gap: 6px;
}

.quick-amount input {
  min-width: 0;
  padding: 0 8px;
  text-align: center;
}

.quick-amount .quick-confirm {
  color: #fff;
  background: #1aac19;
  border-color: #1f8b1b;
}

.history-panel {
  display: grid;
  max-height: 350px;
  padding: 6px 2%;
  overflow-y: auto;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px;
}

.history-panel > button {
  display: flex;
  height: 34px;
  min-width: 0;
  padding: 0 8px;
  text-align: left;
  cursor: pointer;
  background: #fdffff;
  border: 0;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.history-panel > button span {
  color: #111;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-panel > button small {
  flex: none;
  color: #888;
}

.history-empty {
  padding: 24px;
  color: #777;
  text-align: center;
  grid-column: 1 / -1;
}

.scratch-launcher {
  position: fixed;
  z-index: 8;
  right: -10px;
  bottom: 118px;
  width: 66px;
  height: 66px;
  color: #fff;
  cursor: pointer;
  background: #b21a78;
  border: 4px solid #f4c231;
  border-radius: 50%;
  box-shadow: 0 4px 12px rgb(0 0 0 / 22%);
  font-size: 16px;
  font-weight: 700;
}

.lottery-table {
  width: min(310px, calc(100vw - 118px));
  overflow: hidden;
  color: #333;
  background: #fff;
  border: 1px solid #bfc2c7;
  border-radius: 3px;
}

.lottery-table__head,
.lottery-table__result {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.lottery-table__head {
  display: grid;
  grid-template-columns: 46px 58px 1fr;
  padding: 8px 10px;
  color: #555;
  background: #f1f2f4;
  border-bottom: 1px solid #d6d8dc;
  font-size: 13px;
}

.lottery-table__history-row {
  display: grid;
  grid-template-columns: 46px 58px 1fr;
  padding: 4px 10px;
  border-bottom: 1px solid #eee;
  font-size: 13px;
}

.lottery-table__history-row:nth-child(even) {
  background: #f2f2f2;
}

.draw-brief {
  margin: 0 0 6px;
}

.lottery-table__head span {
  color: #888;
  font-size: 12px;
}

.lottery-table__numbers {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 13px 10px;
}

.lottery-table__numbers span {
  display: grid;
  width: 34px;
  color: #fff;
  background: #c94f50;
  border-radius: 50%;
  aspect-ratio: 1;
  place-items: center;
  font-size: 20px;
}

.lottery-table.is-bold .lottery-table__numbers span {
  font-weight: 800;
}

.lottery-table__result {
  padding: 7px 12px;
  color: #6d6d6d;
  background: #f8f8f8;
  border-top: 1px solid #e1e1e1;
  font-size: 13px;
}

@media (width <= 700px) {
  .chat-message {
    padding: 0 10px;
  }

  .chat-avatar {
    width: 42px;
    height: 42px;
    flex-basis: 42px;
  }

  .chat-body {
    max-width: calc(100vw - 92px);
  }

  .receipt-items {
    min-width: 0;
  }

  .composer-row {
    grid-template-columns: 28px minmax(48px, 1fr) 46px 46px 28px;
    gap: 6px;
    padding-right: 8px;
    padding-left: 8px;
  }

  .fast-select,
  .send-button {
    width: 46px;
  }

  .quick-options {
    grid-template-columns: repeat(4, minmax(42px, 1fr));
  }
}

@media (width <= 420px) {
  .composer-row {
    grid-template-columns: 28px minmax(52px, 1fr) 42px 42px 28px;
    gap: 4px;
  }

  .fast-select,
  .send-button {
    width: 42px;
    font-size: 13px;
  }

  .keyboard-row {
    gap: 1%;
  }

  .keyboard-row button {
    font-size: 13px;
  }
}

@keyframes loading {
  0%,
  60%,
  100% {
    background: #dde2e7;
  }

  30% {
    background: #6b9dc8;
  }
}
</style>

