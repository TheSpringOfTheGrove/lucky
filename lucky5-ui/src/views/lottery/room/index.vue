<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import {
  cancelRoomOrderApi,
  getRoomDrawStateApi,
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
import { resolveDragonTiger, roomReplyTemplates } from './replyTemplates'
import logo from '@/assets/imgs/logo.png'
import memberAvatar from '@/assets/imgs/avatar.jpg'

type ChatKind = 'member' | 'other' | 'robot'
type ChatType = 'text' | 'order' | 'amount' | 'draw'

const SCRATCH_COUNTDOWN_COMPENSATION_SECONDS = 5
const SCRATCH_PERIOD_SECONDS = 300
const DRAW_NUMBER_COLOR_CLASSES: Record<string, string> = {
  '1': 'is-purple',
  '6': 'is-pink',
  '8': 'is-blue',
  '9': 'is-green'
}

interface ChatItem {
  id: string
  kind: ChatKind
  type: ChatType
  content: string
  createdAt: string
  displayTimeAt: string
  serverMessageId?: number
  senderName?: string
  order?: RoomOrder
  amountRecord?: RoomAmountRecord
  draw?: RoomDraw
  showTime?: boolean
  sequenceRank?: number
}

const route = useRoute()
const session = ref<RoomSession | null>(null)
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const composer = ref('')
const chatRef = ref<HTMLElement>()
const composerRef = ref<HTMLTextAreaElement>()
const composerPanelRef = ref<HTMLElement>()
const bottomPanel = ref<'keyboard' | 'commands' | ''>('')
const quickPickerVisible = ref(false)
const localMessages = ref<ChatItem[]>([])
// 乐观消息被服务端记录替换后，仍保留用户第一次点击发送时看到的时间。
// 服务端创建时间可能与浏览器时间相差一秒，不能因此让玩家气泡的时间跳变。
const playerSentAtOverrides = ref<Record<number, string>>({})
const betReplyFastPollUntilMs = ref(0)
const trackedBetMessageIds = ref<number[]>([])
const sessionStartedAt = ref(new Date().toISOString())
const autoFollowMessages = ref(true)
const unreadMessageKeys = ref<string[]>([])
const scratchVisible = ref(false)
const scratchRemaining = ref(0)
const clockNowMs = ref(Date.now())
const authoritativeClockOffsetMs = ref(0)
const bettingCutoffAtMs = ref<number | null>(null)
const autoScratch = ref(localStorage.getItem('lucky5-auto-scratch') !== 'false')
const scratchLauncherTop = ref<number | null>(null)
const scratchLauncherDragging = ref(false)
let scratchLauncherDragActive = false
let scratchLauncherStartY = 0
let scratchLauncherStartTop = 0
let suppressScratchLauncherClick = false
const scratchLauncherStyle = computed(() =>
  scratchLauncherTop.value === null ? {} : { top: `${scratchLauncherTop.value}px` }
)
const uniqueId = () =>
  globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(16).slice(2)}`
const drawNumberClass = (number: string) => DRAW_NUMBER_COLOR_CLASSES[number] || 'is-gray'

const credential = computed<RoomCredential>(() => {
  const queryOpenId = typeof route.query.openId === 'string' ? route.query.openId : ''
  const shortOpenId = typeof route.params.openId === 'string' ? route.params.openId : ''
  const legacyFingerprint = typeof route.query.fp === 'string' ? route.query.fp : ''
  const queryRoomMode =
    route.query.roomMode === 'GROUP' || route.query.roomMode === 'PRIVATE'
      ? route.query.roomMode
      : undefined
  const roomMode = route.path.startsWith('/g/')
    ? 'GROUP'
    : route.path.startsWith('/p/')
      ? 'PRIVATE'
      : queryRoomMode
  return {
    tenantId: Number(route.query.tenantId || 1),
    uid: typeof route.query.uid === 'string' ? route.query.uid : undefined,
    openId: queryOpenId || shortOpenId || legacyFingerprint,
    fp: legacyFingerprint || undefined,
    roomMode
  }
})

const playerSentAtStorageKey = computed(
  () =>
    `lucky5-room-sent-at:${credential.value.tenantId}:${credential.value.openId}:${credential.value.roomMode || 'DEFAULT'}`
)
const restorePlayerSentAtOverrides = () => {
  try {
    const parsed = JSON.parse(sessionStorage.getItem(playerSentAtStorageKey.value) || '{}')
    playerSentAtOverrides.value = Object.fromEntries(
      Object.entries(parsed).filter(
        ([messageId, sentAt]) => Number.isFinite(Number(messageId)) && typeof sentAt === 'string'
      )
    )
  } catch {
    playerSentAtOverrides.value = {}
  }
}
const rememberPlayerSentAt = (messageId: number, sentAt: string) => {
  const entries = Object.entries({ ...playerSentAtOverrides.value, [messageId]: sentAt }).slice(
    -120
  )
  playerSentAtOverrides.value = Object.fromEntries(entries)
  try {
    sessionStorage.setItem(
      playerSentAtStorageKey.value,
      JSON.stringify(playerSentAtOverrides.value)
    )
  } catch {
    // 不能写入会话存储时，当前页面仍保留首次发送时间。
  }
}

const orderById = computed(() =>
  Object.fromEntries((session.value?.orders || []).map((order) => [order.id, order]))
)
const resolvePendingDrawPeriod = (currentSession: RoomSession | null) => {
  if (!currentSession) return ''
  const latestDrawPeriod = currentSession.draws[0]?.period || ''
  const pendingPeriods = new Set(
    (currentSession.issueTransitions || [])
      .filter(
        (transition) =>
          transition.status === 'CLOSED' &&
          (!latestDrawPeriod || transition.period > latestDrawPeriod) &&
          (currentSession.issue.status !== 'OPEN' ||
            transition.period < currentSession.issue.currentPeriod)
      )
      .map((transition) => transition.period)
  )
  return [...pendingPeriods].sort((left, right) => left.localeCompare(right))[0] || ''
}
const scratchPendingPeriod = computed(
  () => session.value?.issue.pendingPeriod || resolvePendingDrawPeriod(session.value)
)
const pendingCandidateNumbers = computed(
  () => (session.value?.issue.pendingResult || '').match(/\d/g)?.slice(0, 5) || []
)
const hasPendingCandidate = computed(() => pendingCandidateNumbers.value.length === 5)
const pendingOfficialDrawTime = computed(() =>
  dayjs(session.value?.issue.pendingDrawTime || session.value?.issue.drawTime)
)
const officialDrawBoundaryPassed = computed(
  () =>
    pendingOfficialDrawTime.value.isValid() &&
    clockNowMs.value >= pendingOfficialDrawTime.value.valueOf()
)
const scratchDisplayDrawTime = computed(() =>
  pendingOfficialDrawTime.value.add(SCRATCH_COUNTDOWN_COMPENSATION_SECONDS, 'second')
)
// Keep the zero visible for one normal clock tick before moving every scratch-card field
// to the next period. The API can cache a candidate early, but the dialog must not expose
// its period or numbers before the compensated display cycle has completed.
const scratchDisplayCycleComplete = computed(
  () =>
    scratchDisplayDrawTime.value.isValid() &&
    clockNowMs.value >= scratchDisplayDrawTime.value.add(1, 'second').valueOf()
)
const waitingForCandidateAtBoundary = computed(
  () =>
    Boolean(scratchPendingPeriod.value) &&
    officialDrawBoundaryPassed.value &&
    !hasPendingCandidate.value
)
const pendingCandidateDraw = computed<RoomDraw | null>(() => {
  const period = scratchPendingPeriod.value
  if (!period || !officialDrawBoundaryPassed.value) return null
  if (session.value?.draws.some((draw) => draw.period === period)) return null
  if (!hasPendingCandidate.value) return null
  const result = pendingCandidateNumbers.value.join('')
  return {
    period,
    result,
    numbers: pendingCandidateNumbers.value,
    valid: true,
    bigSmall: '',
    oddEven: '',
    dragonTiger: '',
    status: session.value?.issue.pendingStatus || 'DRAW_PENDING',
    drawTime: pendingOfficialDrawTime.value.toISOString(),
    settledAt: ''
  }
})
const displayedDraws = computed(() => {
  const draws = session.value?.draws || []
  const candidate = pendingCandidateDraw.value
  if (!candidate) return draws
  return [candidate, ...draws.filter((draw) => draw.period !== candidate.period)]
})
const latestDraw = computed(() => displayedDraws.value[0] || null)
const scratchPreviousResultPeriod = computed(() => {
  const pendingPeriod = scratchPendingPeriod.value
  if (!pendingPeriod) return latestDraw.value?.period || session.value?.issue.currentPeriod || ''

  const previousDraw = session.value?.draws.find((draw) => draw.period < pendingPeriod)
  if (previousDraw) return previousDraw.period

  const latestPeriod = latestDraw.value?.period || ''
  return latestPeriod && latestPeriod < pendingPeriod ? latestPeriod : ''
})
const scratchResultPeriod = computed(() => {
  if (scratchPendingPeriod.value && scratchDisplayCycleComplete.value) {
    return scratchPendingPeriod.value
  }
  return scratchPreviousResultPeriod.value
})
const scratchResultDraw = computed<RoomDraw | null>(() => {
  const settledDraw = session.value?.draws.find((draw) => draw.period === scratchResultPeriod.value)
  if (settledDraw) return settledDraw
  if (hasPendingCandidate.value && scratchPendingPeriod.value === scratchResultPeriod.value) {
    const result = pendingCandidateNumbers.value.join('')
    return {
      period: scratchPendingPeriod.value,
      result,
      numbers: pendingCandidateNumbers.value,
      valid: true,
      bigSmall: '',
      oddEven: '',
      dragonTiger: '',
      status: session.value?.issue.pendingStatus || 'DRAW_PENDING',
      drawTime: pendingOfficialDrawTime.value.toISOString(),
      settledAt: ''
    }
  }
  return null
})
const scratchWaitingPeriod = computed(() => {
  const issue = session.value?.issue
  if (!issue) return ''
  if (scratchPendingPeriod.value && !scratchDisplayCycleComplete.value) {
    return scratchPendingPeriod.value
  }
  if (scratchPendingPeriod.value) {
    if (issue.currentPeriod && issue.currentPeriod > scratchPendingPeriod.value) {
      return issue.currentPeriod
    }
    if (issue.nextPeriod && issue.nextPeriod > scratchPendingPeriod.value) {
      return issue.nextPeriod
    }
    return String(Number(scratchPendingPeriod.value) + 1)
  }
  return issue.currentPeriod || issue.nextPeriod || scratchPendingPeriod.value
})
const scratchNextDrawAtMs = computed(() => {
  const issue = session.value?.issue
  if (!issue) return null
  if (pendingOfficialDrawTime.value.isValid()) {
    if (!scratchDisplayCycleComplete.value) return scratchDisplayDrawTime.value.valueOf()

    const currentDrawTime = dayjs(issue.drawTime)
    if (
      issue.currentPeriod &&
      issue.currentPeriod > scratchPendingPeriod.value &&
      currentDrawTime.isValid()
    ) {
      return currentDrawTime.add(SCRATCH_COUNTDOWN_COMPENSATION_SECONDS, 'second').valueOf()
    }
    return pendingOfficialDrawTime.value
      .add(SCRATCH_PERIOD_SECONDS + SCRATCH_COUNTDOWN_COMPENSATION_SECONDS, 'second')
      .valueOf()
  }
  const currentDrawTime = dayjs(issue.drawTime)
  return currentDrawTime.isValid()
    ? currentDrawTime.add(SCRATCH_COUNTDOWN_COMPENSATION_SECONDS, 'second').valueOf()
    : null
})
const scratchDrawRemaining = computed(() => {
  if (!scratchNextDrawAtMs.value) return 0
  return Math.max(0, Math.ceil((scratchNextDrawAtMs.value - clockNowMs.value) / 1000))
})
const chatMessages = computed<ChatItem[]>(() => {
  if (!session.value) return localMessages.value

  const sourceDates = [
    ...session.value.messages.map((item) => item.createdAt),
    ...session.value.amountRecords.map((item) => item.createdAt)
  ].filter(Boolean)
  const firstDate = sourceDates.sort()[0] || sessionStartedAt.value
  const messages: ChatItem[] = [
    {
      id: 'room-welcome',
      kind: 'robot',
      type: 'text',
      content: roomReplyTemplates.welcome(
        session.value.room.name,
        session.value.member.name,
        session.value.member.balance,
        session.value.suggestedPeriod
      ),
      createdAt: dayjs(firstDate).subtract(2, 'second').toISOString(),
      displayTimeAt: dayjs(firstDate).subtract(2, 'second').toISOString()
    }
  ]

  if (session.value.room.announcement) {
    messages.push({
      id: 'room-announcement',
      kind: 'robot',
      type: 'text',
      content: session.value.room.announcement,
      createdAt: dayjs(firstDate).subtract(1, 'second').toISOString(),
      displayTimeAt: dayjs(firstDate).subtract(1, 'second').toISOString()
    })
  }

  for (const transition of session.value.issueTransitions || []) {
    messages.push({
      id: `issue-transition-${transition.id}`,
      kind: 'robot',
      type: 'text',
      content: roomReplyTemplates.issueTransition(transition.status),
      createdAt: transition.createdAt,
      displayTimeAt: transition.createdAt,
      sequenceRank:
        transition.status === 'CLOSED' ? 10 : transition.status === 'OPEN' ? 60 : undefined
    })
  }

  const drawSequenceAnchors = new Map<string, string>()
  for (const message of session.value.messages) {
    if (!message.reply || !['SETTLEMENT', 'PAYOUT_SUMMARY'].includes(message.commandType)) continue
    const replyDelay = message.commandType === 'PAYOUT_SUMMARY' ? 3 : 2
    const displayedAt = dayjs(message.createdAt).add(replyDelay, 'millisecond')
    const currentAnchor = dayjs(drawSequenceAnchors.get(message.period))
    if (!currentAnchor.isValid() || displayedAt.isAfter(currentAnchor)) {
      drawSequenceAnchors.set(message.period, displayedAt.toISOString())
    }
  }

  for (const draw of session.value.draws.filter((item) => Boolean(item.settledAt))) {
    const sequenceAnchor = drawSequenceAnchors.get(draw.period)
    messages.push({
      id: `draw-${draw.period}`,
      kind: 'robot',
      type: 'draw',
      content: roomReplyTemplates.draw(draw.period, draw.numbers, draw.dragonTiger),
      createdAt: sequenceAnchor
        ? dayjs(sequenceAnchor).add(1, 'millisecond').toISOString()
        : draw.settledAt,
      displayTimeAt: sequenceAnchor
        ? dayjs(sequenceAnchor).add(1, 'millisecond').toISOString()
        : draw.settledAt,
      draw,
      sequenceRank: 50
    })
  }

  for (const message of session.value.messages) {
    const order = message.orderId ? orderById.value[message.orderId] : undefined
    const showSharedRobotReply =
      session.value.room.mode === 'GROUP' &&
      message.own === false &&
      ['BET', 'SETTLEMENT', 'PERIOD_SUMMARY', 'PAYOUT_SUMMARY'].includes(message.commandType) &&
      Boolean(message.reply)
    if (!['SETTLEMENT', 'PERIOD_SUMMARY', 'PAYOUT_SUMMARY'].includes(message.commandType)) {
      messages.push({
        id: `member-message-${message.id}`,
        kind: message.own === false ? 'other' : 'member',
        type: 'text',
        content: message.content,
        createdAt: message.createdAt,
        displayTimeAt:
          playerSentAtOverrides.value[message.id] || message.sentAt || message.createdAt,
        senderName: message.member
      })
    }
    const waitingForFinalResult = Boolean(order?.processing) && !message.reply
    const silentCancelCommand = message.commandType === 'CANCEL' && !message.reply
    if (
      (message.own !== false || showSharedRobotReply) &&
      message.commandType !== 'CHAT' &&
      !silentCancelCommand &&
      !waitingForFinalResult
    ) {
      const robotReplyDelay =
        message.commandType === 'PAYOUT_SUMMARY' ? 3 : message.commandType === 'SETTLEMENT' ? 2 : 1
      messages.push({
        id: `robot-message-${message.id}`,
        kind: 'robot',
        type: order ? 'order' : 'text',
        content: order
          ? formatRobotReply(message.reply || '下注成功')
          : formatRobotReply(
              message.reply ||
                message.error ||
                (message.status === '处理中' ? '正在处理' : message.status)
            ),
        createdAt: dayjs(message.createdAt).add(robotReplyDelay, 'millisecond').toISOString(),
        // 机器人回复可能在外盘确认或审核后被更新；气泡时间以最后一次回复修改为准，
        // 但排序仍保持原始消息时间，避免更新后的旧回复打乱聊天时序。
        displayTimeAt: message.replyUpdatedAt || message.createdAt,
        order,
        sequenceRank:
          message.commandType === 'PERIOD_SUMMARY'
            ? 20
            : message.commandType === 'SETTLEMENT'
              ? 30
              : message.commandType === 'PAYOUT_SUMMARY'
                ? 40
                : undefined
      })
    }
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
        createdAt: amountRecord.createdAt,
        displayTimeAt: amountRecord.createdAt
      })
    }
    if (!relatedMessage) {
      messages.push({
        id: `robot-amount-${amountRecord.id}`,
        kind: 'robot',
        type: 'amount',
        content: `${amountRecord.type}${isMemberRequest ? '申请' : ''}${amountRecord.status}`,
        createdAt:
          amountRecord.auditedAt ||
          dayjs(amountRecord.createdAt).add(1, 'millisecond').toISOString(),
        displayTimeAt:
          amountRecord.auditedAt ||
          dayjs(amountRecord.createdAt).add(1, 'millisecond').toISOString(),
        amountRecord
      })
    }
  }

  const serverMessageIds = new Set(session.value.messages.map((message) => message.id))
  messages.push(
    ...localMessages.value.filter(
      (message) => !message.serverMessageId || !serverMessageIds.has(message.serverMessageId)
    )
  )
  messages.sort((a, b) => {
    const left = dayjs(a.createdAt)
    const right = dayjs(b.createdAt)
    const timeDifference =
      (left.isValid() ? left.valueOf() : 0) - (right.isValid() ? right.valueOf() : 0)
    if (
      a.sequenceRank !== undefined &&
      b.sequenceRank !== undefined &&
      Math.abs(timeDifference) <= 1000 &&
      a.sequenceRank !== b.sequenceRank
    ) {
      return a.sequenceRank - b.sequenceRank
    }
    return timeDifference || a.id.localeCompare(b.id)
  })
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
let drawRefreshTimer: number | undefined
let countdownTimer: number | undefined
let sessionRequestSequence = 0
let drawStateRequestSequence = 0
let lastDrawStateAppliedAt = 0
let chatMessageBaselineReady = false
const knownChatMessageVersions = new Set<string>()

const money = (value: number) => Number(value || 0).toFixed(2)
const drawDisplayTime = (draw: RoomDraw) => {
  const parsed = dayjs(draw.drawTime || draw.settledAt)
  return parsed.isValid() ? parsed.format('HH:mm') : '--:--'
}
const applyAuthoritativeIssueClock = (issue: RoomSession['issue'], responseReceivedAt: number) => {
  const serverTime = dayjs(issue.serverTime)
  if (serverTime.isValid()) {
    authoritativeClockOffsetMs.value = serverTime.valueOf() - responseReceivedAt
  }
  clockNowMs.value = responseReceivedAt + authoritativeClockOffsetMs.value

  const cutoffTime = dayjs(issue.bettingCutoffTime)
  if (issue.status === 'OPEN' && cutoffTime.isValid()) {
    bettingCutoffAtMs.value = cutoffTime.valueOf()
    scratchRemaining.value = Math.max(
      0,
      Math.ceil((bettingCutoffAtMs.value - clockNowMs.value) / 1000)
    )
    return
  }
  bettingCutoffAtMs.value = null
  scratchRemaining.value =
    issue.status === 'OPEN' ? Math.max(0, Number(issue.remainingSeconds || 0)) : 0
}
const receiptText = (value: string) => value.replace(/\n点击退码\s*$/, '')
const chatMessageVersion = (message: ChatItem) =>
  [message.id, message.content, message.order?.status || '', message.order?.win || ''].join(
    '\u0000'
  )
const isUnreadEligibleMessage = (message: ChatItem) =>
  !['room-welcome', 'room-announcement'].includes(message.id) &&
  !message.id.startsWith('pending-') &&
  message.kind !== 'member'
const markKnownChatMessages = () => {
  for (const message of chatMessages.value) {
    if (isUnreadEligibleMessage(message)) knownChatMessageVersions.add(chatMessageVersion(message))
  }
}
const clearUnreadMessages = () => {
  unreadMessageKeys.value = []
}
const collectUnreadMessages = (wasFollowing: boolean) => {
  if (!chatMessageBaselineReady) {
    markKnownChatMessages()
    chatMessageBaselineReady = true
    return
  }
  const nextUnreadKeys: string[] = []
  for (const message of chatMessages.value) {
    if (!isUnreadEligibleMessage(message)) continue
    const version = chatMessageVersion(message)
    if (knownChatMessageVersions.has(version)) continue
    knownChatMessageVersions.add(version)
    if (!wasFollowing) nextUnreadKeys.push(version)
  }
  if (wasFollowing) {
    clearUnreadMessages()
  } else if (nextUnreadKeys.length) {
    unreadMessageKeys.value = [...unreadMessageKeys.value, ...nextUnreadKeys]
  }
}
const resetChatMessageTracking = () => {
  chatMessageBaselineReady = false
  knownChatMessageVersions.clear()
  clearUnreadMessages()
}
const formatRobotReply = (value: string) =>
  value
    .split(/\r?\n/)
    .flatMap((line) => {
      const parts = line
        .split(/[，,、；;]/)
        .map((part) => part.trim())
        .filter(Boolean)
      if (
        parts.length < 2 ||
        !parts.every((part) =>
          /(?:各\d+(?:\.\d+)?|(?:倒[一二三四]定|[二三四]定倒)\/\d+(?:\.\d+)?)$/.test(part)
        )
      ) {
        return [line]
      }
      return parts
    })
    .join('\n')

const copyInstruction = async (message: ChatItem) => {
  if (message.kind === 'robot' || !message.content.trim()) return
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(message.content)
    } else {
      const input = document.createElement('textarea')
      input.value = message.content
      input.setAttribute('readonly', '')
      input.style.position = 'fixed'
      input.style.opacity = '0'
      document.body.appendChild(input)
      try {
        input.select()
        if (!document.execCommand('copy')) throw new Error('copy failed')
      } finally {
        input.remove()
      }
    }
    await ElMessageBox.alert('复制成功', {
      confirmButtonText: '确定',
      showClose: false,
      center: true,
      customClass: 'room-copy-success'
    })
  } catch (reason) {
    if (reason !== 'cancel' && reason !== 'close') ElMessage.error('复制失败')
  }
}
const messageTime = (value: string) =>
  dayjs(value).isSame(dayjs(), 'day')
    ? dayjs(value).format('HH:mm')
    : dayjs(value).format('MM-DD HH:mm')
const bubbleTime = (value: string) => dayjs(value).format('HH:mm:ss')
const orderStatusClass = (status: string) => {
  if (status === '已中奖') return 'is-win'
  if (status === '未中奖' || status === '已退码') return 'is-failed'
  return 'is-pending'
}

const scrollToBottom = async (behavior: ScrollBehavior = 'auto') => {
  await nextTick()
  chatRef.value?.scrollTo({ top: chatRef.value.scrollHeight, behavior })
  autoFollowMessages.value = true
  clearUnreadMessages()
}

const handleChatScroll = () => {
  const stream = chatRef.value
  if (!stream) return
  autoFollowMessages.value = stream.scrollHeight - stream.scrollTop - stream.clientHeight <= 80
  if (autoFollowMessages.value) clearUnreadMessages()
}

const loadSession = async (quiet = false) => {
  if (!credential.value.openId) {
    error.value = '会员链接已失效'
    loading.value = false
    return
  }
  const requestSequence = ++sessionRequestSequence
  if (!quiet) loading.value = true
  try {
    const requestStartedAt = Date.now()
    const previousDrawPeriod = session.value?.draws[0]?.period || ''
    const wasFollowing = autoFollowMessages.value
    const previousMessageStatus = new Map(
      (session.value?.messages || []).map((message) => [message.id, message.status])
    )
    const hadSession = Boolean(session.value)
    const nextSession = await getRoomSessionApi(credential.value)
    const responseReceivedAt = Date.now()
    if (requestSequence !== sessionRequestSequence) return
    const preserveNewerDrawState = lastDrawStateAppliedAt > requestStartedAt
    const currentDrawState = session.value
    const appliedSession =
      preserveNewerDrawState && currentDrawState
        ? {
            ...nextSession,
            suggestedPeriod: currentDrawState.suggestedPeriod,
            issue: currentDrawState.issue,
            draws: currentDrawState.draws
          }
        : nextSession
    const nextDrawPeriod = appliedSession.draws[0]?.period || ''
    session.value = appliedSession
    await nextTick()
    collectUnreadMessages(wasFollowing)
    if (trackedBetMessageIds.value.length) {
      trackedBetMessageIds.value = trackedBetMessageIds.value.filter((messageId) => {
        const trackedMessage = nextSession.messages.find((message) => message.id === messageId)
        const trackedOrder = nextSession.orders.find((order) => order.id === trackedMessage?.orderId)
        return Boolean(trackedOrder?.processing) || trackedMessage?.reply?.trim().endsWith('提交中')
      })
      if (!trackedBetMessageIds.value.length) betReplyFastPollUntilMs.value = 0
    }
    if (hadSession) {
      for (const message of nextSession.messages) {
        if (message.own === false || message.commandType !== 'BET') continue
        const previousStatus = previousMessageStatus.get(message.id)
        if (message.status === '退码失败' && previousStatus !== message.status) {
          ElMessage.error('退码失败：当前订单不能退码')
        } else if (message.status === '退码待确认' && previousStatus !== message.status) {
          ElMessage.warning('退码结果待确认，请联系管理员')
        }
      }
    }
    applyAuthoritativeIssueClock(appliedSession.issue, responseReceivedAt)
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
    if (requestSequence !== sessionRequestSequence) return
    error.value = reason?.message || '会员链接已失效'
  } finally {
    if (requestSequence === sessionRequestSequence) loading.value = false
  }
}

const loadDrawState = async () => {
  if (!credential.value.openId || !session.value) {
    await loadSession(true)
    return
  }
  const requestSequence = ++drawStateRequestSequence
  try {
    const previousDrawPeriod = session.value.draws[0]?.period || ''
    const wasFollowing = autoFollowMessages.value
    const nextState = await getRoomDrawStateApi(credential.value)
    const responseReceivedAt = Date.now()
    if (requestSequence !== drawStateRequestSequence || !session.value) return
    const nextDrawPeriod = nextState.draws[0]?.period || ''
    session.value = { ...session.value, ...nextState }
    await nextTick()
    collectUnreadMessages(wasFollowing)
    lastDrawStateAppliedAt = responseReceivedAt
    applyAuthoritativeIssueClock(nextState.issue, responseReceivedAt)
    if (
      previousDrawPeriod &&
      nextDrawPeriod &&
      previousDrawPeriod !== nextDrawPeriod &&
      session.value.room.features.prizeCard &&
      autoScratch.value
    ) {
      scratchVisible.value = true
    }
    error.value = ''
  } catch (reason: any) {
    if (requestSequence !== drawStateRequestSequence) return
    error.value = reason?.message || '开奖状态刷新失败'
  }
}

const scheduleSessionRefresh = () => {
  if (refreshTimer) window.clearTimeout(refreshTimer)
  const waitingForBetReply = trackedBetMessageIds.value.length > 0
  const fastBetReplyPoll = waitingForBetReply && Date.now() < betReplyFastPollUntilMs.value
  const delay = fastBetReplyPoll ? 1000 : 5000
  refreshTimer = window.setTimeout(async () => {
    await loadSession(true)
    scheduleSessionRefresh()
    scheduleDrawStateRefresh()
  }, delay)
}

const scheduleDrawStateRefresh = () => {
  if (drawRefreshTimer) window.clearTimeout(drawRefreshTimer)
  const waitingForResult = Boolean(scratchPendingPeriod.value && !hasPendingCandidate.value)
  // The scratch countdown includes a five-second display compensation, so 15 means the
  // authoritative market draw is at most ten seconds away. Poll faster only in this small window.
  const approachingOfficialDraw = waitingForResult && scratchDrawRemaining.value <= 15
  if (
    !waitingForCandidateAtBoundary.value &&
    !approachingOfficialDraw &&
    !scratchVisible.value &&
    !waitingForResult
  ) {
    drawRefreshTimer = undefined
    return
  }
  const delay = waitingForCandidateAtBoundary.value ? 250 : approachingOfficialDraw ? 500 : 2000
  drawRefreshTimer = window.setTimeout(async () => {
    await loadDrawState()
    scheduleDrawStateRefresh()
  }, delay)
}

watch(waitingForCandidateAtBoundary, (waiting) => {
  if (waiting) scheduleDrawStateRefresh()
})

const updateAutoScratch = (value: boolean) => {
  autoScratch.value = value
  localStorage.setItem('lucky5-auto-scratch', String(value))
}

const clampScratchLauncherTop = (value: number) => {
  const minimum = 12
  const maximum = Math.max(minimum, window.innerHeight - 66 - 92)
  return Math.min(Math.max(minimum, value), maximum)
}

const restoreScratchLauncherTop = () => {
  const storedValue = localStorage.getItem('lucky5-scratch-launcher-top')
  const stored = storedValue === null ? Number.NaN : Number(storedValue)
  const fallback = window.innerHeight - 66 - 118
  scratchLauncherTop.value = clampScratchLauncherTop(Number.isFinite(stored) ? stored : fallback)
}

const beginScratchLauncherDrag = (clientY: number) => {
  scratchLauncherDragActive = true
  scratchLauncherStartY = clientY
  scratchLauncherStartTop = scratchLauncherTop.value ?? window.innerHeight - 66 - 118
  scratchLauncherDragging.value = false
}

const moveScratchLauncher = (clientY: number) => {
  if (!scratchLauncherDragActive) return
  const delta = clientY - scratchLauncherStartY
  if (Math.abs(delta) >= 4) scratchLauncherDragging.value = true
  scratchLauncherTop.value = clampScratchLauncherTop(scratchLauncherStartTop + delta)
}

const finishScratchLauncherDrag = () => {
  if (!scratchLauncherDragActive) return
  scratchLauncherDragActive = false
  const wasDragging = scratchLauncherDragging.value
  scratchLauncherDragging.value = false
  if (!wasDragging || scratchLauncherTop.value === null) return
  localStorage.setItem('lucky5-scratch-launcher-top', String(Math.round(scratchLauncherTop.value)))
  suppressScratchLauncherClick = true
  window.setTimeout(() => {
    suppressScratchLauncherClick = false
  }, 0)
}

const moveScratchLauncherByMouse = (event: MouseEvent) => moveScratchLauncher(event.clientY)

const finishScratchLauncherMouseDrag = () => {
  window.removeEventListener('mousemove', moveScratchLauncherByMouse)
  window.removeEventListener('mouseup', finishScratchLauncherMouseDrag)
  finishScratchLauncherDrag()
}

const startScratchLauncherMouseDrag = (event: MouseEvent) => {
  if (event.button !== 0) return
  beginScratchLauncherDrag(event.clientY)
  window.addEventListener('mousemove', moveScratchLauncherByMouse)
  window.addEventListener('mouseup', finishScratchLauncherMouseDrag)
}

const startScratchLauncherTouchDrag = (event: TouchEvent) => {
  const touch = event.touches[0]
  if (touch) beginScratchLauncherDrag(touch.clientY)
}

const moveScratchLauncherByTouch = (event: TouchEvent) => {
  const touch = event.touches[0]
  if (touch) moveScratchLauncher(touch.clientY)
}

const openScratchCard = () => {
  if (suppressScratchLauncherClick) return
  scratchVisible.value = true
  scheduleDrawStateRefresh()
}

const handleRoomResize = () => {
  if (scratchLauncherTop.value !== null) {
    scratchLauncherTop.value = clampScratchLauncherTop(scratchLauncherTop.value)
  }
}

const shouldShowBetSubmitting = (content: string) => {
  const normalized = content.trim()
  if (!normalized || normalized === '查' || normalized === '余额') return false
  if (/^(?:上|下)(?:分)?\d+(?:\.\d+)?$/.test(normalized)) return false
  if (/^退(?:码)?\s*[A-Za-z0-9_-]+$/.test(normalized)) return false
  return true
}

const submitChat = async () => {
  const content = composer.value.trim()
  if (!content || !session.value || saving.value) return

  const externalId = uniqueId()
  const optimisticMessageId = `pending-member-message-${externalId}`
  const submittedAt = new Date().toISOString()
  const optimisticMessage: ChatItem = {
    id: optimisticMessageId,
    kind: 'member',
    type: 'text',
    content,
    createdAt: submittedAt,
    displayTimeAt: submittedAt,
    senderName: session.value.member.name
  }
  const optimisticRobotMessageId = `pending-robot-message-${externalId}`
  const optimisticRobotMessage: ChatItem | null = shouldShowBetSubmitting(content)
    ? {
        id: optimisticRobotMessageId,
        kind: 'robot',
        type: 'text',
        content: `@${session.value.member.name}\n提交中`,
        createdAt: dayjs(optimisticMessage.createdAt).add(1, 'millisecond').toISOString(),
        displayTimeAt: dayjs(optimisticMessage.createdAt).add(1, 'millisecond').toISOString()
      }
    : null
  bottomPanel.value = ''
  saving.value = true
  composer.value = ''
  localMessages.value = [
    ...localMessages.value,
    optimisticMessage,
    ...(optimisticRobotMessage ? [optimisticRobotMessage] : [])
  ]
  await scrollToBottom('smooth')
  try {
    const result = await sendRoomMessageApi(credential.value, {
      period: session.value.issue.status === 'OPEN' ? session.value.suggestedPeriod : undefined,
      content,
      externalId,
      sentAt: new Date(submittedAt).getTime()
    })
    optimisticMessage.serverMessageId = result.messageId
    rememberPlayerSentAt(result.messageId, submittedAt)
    if (optimisticRobotMessage) optimisticRobotMessage.serverMessageId = result.messageId
    if (optimisticRobotMessage && result.reply) optimisticRobotMessage.content = result.reply
    if (result.commandType === 'BET' && result.reply?.trim().endsWith('提交中')) {
      trackedBetMessageIds.value = [...new Set([...trackedBetMessageIds.value, result.messageId])]
      betReplyFastPollUntilMs.value = Math.max(betReplyFastPollUntilMs.value, Date.now() + 30_000)
    }
    await loadSession(true)
    scheduleSessionRefresh()
    if (session.value.messages.some((message) => message.id === result.messageId)) {
      localMessages.value = localMessages.value.filter(
        (message) => message.id !== optimisticMessageId && message.id !== optimisticRobotMessageId
      )
    }
  } catch (reason: any) {
    localMessages.value = localMessages.value.filter(
      (message) => message.id !== optimisticMessageId && message.id !== optimisticRobotMessageId
    )
    if (!composer.value.trim()) composer.value = content
    ElMessage.error(reason?.message || '发送失败')
  } finally {
    saving.value = false
    if (!optimisticMessage.serverMessageId) await loadSession(true)
    await scrollToBottom('smooth')
    composerRef.value?.focus()
  }
}

const appendKey = (key: string) => {
  if (key === '←') composer.value = composer.value.slice(0, -1)
  else if (key === '清除') composer.value = ''
  else if (key === '换行') composer.value += '\n'
  else composer.value += key
}

const togglePanel = async (panel: 'keyboard' | 'commands') => {
  bottomPanel.value = bottomPanel.value === panel ? '' : panel
  if (bottomPanel.value === 'keyboard') composerRef.value?.blur()
  if (bottomPanel.value === 'commands') await loadSession(true)
  if (bottomPanel.value === '') composerRef.value?.focus()
  void scrollToBottom()
}

const handleDocumentPointerDown = (event: PointerEvent) => {
  if (!bottomPanel.value) return
  const target = event.target
  if (!(target instanceof Element)) return
  if (composerPanelRef.value?.contains(target)) return
  if (target.closest('.keyboard-toggle, .history-toggle')) return
  bottomPanel.value = ''
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
    if (reason !== 'cancel' && reason !== 'close') {
      ElMessage.error(reason?.message ? `退码失败：${reason.message}` : '退码失败')
    }
  } finally {
    saving.value = false
  }
}

watch(
  () => {
    const lastMessage = chatMessages.value.at(-1)
    return lastMessage
      ? `${lastMessage.id}:${lastMessage.order?.status || ''}:${lastMessage.content}`
      : ''
  },
  () => {
    if (autoFollowMessages.value) void scrollToBottom()
  }
)

watch(
  () => `${credential.value.openId}:${credential.value.roomMode || 'DEFAULT'}`,
  async (credentialKey, previousCredentialKey) => {
    if (!credential.value.openId || credentialKey === previousCredentialKey) return
    sessionStartedAt.value = new Date().toISOString()
    localMessages.value = []
    restorePlayerSentAtOverrides()
    resetChatMessageTracking()
    trackedBetMessageIds.value = []
    betReplyFastPollUntilMs.value = 0
    bettingCutoffAtMs.value = null
    await loadSession()
    await scrollToBottom()
    composerRef.value?.focus()
  }
)

onMounted(async () => {
  restoreScratchLauncherTop()
  restorePlayerSentAtOverrides()
  window.addEventListener('resize', handleRoomResize)
  document.addEventListener('pointerdown', handleDocumentPointerDown)
  await loadSession()
  await scrollToBottom()
  composerRef.value?.focus()
  scheduleSessionRefresh()
  scheduleDrawStateRefresh()
  countdownTimer = window.setInterval(() => {
    clockNowMs.value = Date.now() + authoritativeClockOffsetMs.value
    if (session.value?.issue.status === 'OPEN') {
      scratchRemaining.value = bettingCutoffAtMs.value
        ? Math.max(0, Math.ceil((bettingCutoffAtMs.value - clockNowMs.value) / 1000))
        : Math.max(0, scratchRemaining.value - 1)
    }
  }, 1000)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleRoomResize)
  document.removeEventListener('pointerdown', handleDocumentPointerDown)
  window.removeEventListener('mousemove', moveScratchLauncherByMouse)
  window.removeEventListener('mouseup', finishScratchLauncherMouseDrag)
  if (refreshTimer) window.clearTimeout(refreshTimer)
  if (drawRefreshTimer) window.clearTimeout(drawRefreshTimer)
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
        v-if="session.room.features.prizeCard && scratchResultPeriod"
        class="scratch-launcher"
        :class="{ 'is-dragging': scratchLauncherDragging }"
        :style="scratchLauncherStyle"
        type="button"
        aria-label="打开刮刮卡"
        @click="openScratchCard"
        @mousedown="startScratchLauncherMouseDrag"
        @touchstart="startScratchLauncherTouchDrag"
        @touchmove.prevent="moveScratchLauncherByTouch"
        @touchend="finishScratchLauncherDrag"
        @touchcancel="finishScratchLauncherDrag"
      >
        刮牌
      </button>

      <ScratchCard
        :visible="scratchVisible"
        :draw="scratchResultDraw"
        :result-period="scratchResultPeriod"
        :current-period="scratchWaitingPeriod"
        :remaining-seconds="scratchDrawRemaining"
        :auto-popup="autoScratch"
        @close="scratchVisible = false"
        @refresh="loadSession(true)"
        @update:auto-popup="updateAutoScratch"
      />

      <main ref="chatRef" class="chat-stream" aria-live="polite" @scroll.passive="handleChatScroll">
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
              :alt="message.kind === 'robot' ? '机器人' : message.senderName || session.member.name"
            />
            <div class="chat-body">
              <h5>
                {{
                  message.kind === 'robot' ? '机器人' : message.senderName || session.member.name
                }}
              </h5>
              <div class="chat-bubble">
                <template v-if="message.type === 'draw' && message.draw">
                  <pre class="draw-brief">{{ message.content }}</pre>
                  <div
                    v-if="
                      (session.room.mode === 'GROUP'
                        ? session.room.features.groupImage
                        : session.room.features.privateImage) &&
                      message.draw.period === latestDraw?.period
                    "
                    :class="['lottery-table', { 'is-bold': session.room.features.imageBold }]"
                  >
                    <div class="lottery-table__head">
                      <strong>期数</strong><strong>时间</strong><strong>成功</strong
                      ><strong aria-hidden="true"></strong>
                    </div>
                    <div
                      v-for="draw in session.draws.slice(0, 15)"
                      :key="draw.period"
                      class="lottery-table__history-row"
                    >
                      <span>{{ draw.period.slice(-3) }}</span>
                      <span>{{ drawDisplayTime(draw) }}</span>
                      <span
                        class="lottery-table__history-numbers"
                        :aria-label="draw.numbers.join(' ')"
                      >
                        <i
                          v-for="(number, index) in draw.numbers"
                          :key="`${draw.period}-history-${index}`"
                          :class="drawNumberClass(number)"
                        >
                          {{ number }}
                        </i>
                      </span>
                      <strong
                        :class="[
                          'lottery-table__dragon-tiger',
                          `is-${resolveDragonTiger(draw.numbers, draw.dragonTiger)}`
                        ]"
                      >
                        {{ resolveDragonTiger(draw.numbers, draw.dragonTiger) }}
                      </strong>
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
                      message.order.status === '未开奖' &&
                      session.room.cancelEnabled &&
                      !saving &&
                      message.order.cancelable !== false
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

                <pre
                  v-else
                  :class="{ 'is-copyable': message.kind !== 'robot' }"
                  @click.stop="copyInstruction(message)"
                  >{{ message.content }}</pre
                >
              </div>
              <div class="chat-bubble-time">{{ bubbleTime(message.displayTimeAt) }}</div>
            </div>
          </div>
        </article>
      </main>

      <button
        v-if="unreadMessageKeys.length"
        class="new-message-tip"
        type="button"
        :aria-label="`回到最新消息，${unreadMessageKeys.length} 条未读`"
        @click="scrollToBottom('smooth')"
      >
        <span aria-hidden="true">▼</span>{{ unreadMessageKeys.length }}条新消息
      </button>

      <form class="chat-composer" @submit.prevent="submitChat">
        <div
          v-if="bottomPanel === 'keyboard'"
          ref="composerPanelRef"
          class="composer-panel virtual-keyboard"
          @pointerdown.stop
        >
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
          ref="composerPanelRef"
          class="composer-panel history-panel command-panel"
          @pointerdown.stop
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
            placeholder="输入聊天或下注指令"
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
        @submit="submitQuickGenerated"
      />
    </template>
  </div>
</template>

<style scoped>
.chat-room {
  --room-overview-height: 0px;
  position: fixed;
  z-index: 1;
  overflow: hidden;
  font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif;
  color: #333;
  color-scheme: light;
  background: #f5f5f7;
  inset: 0;
}

.room-overview {
  position: fixed;
  z-index: 9;
  top: 0;
  right: 0;
  left: 0;
  height: var(--room-overview-height);
  padding-top: env(safe-area-inset-top);
  color: #253247;
  background: rgb(255 255 255 / 97%);
  border-bottom: 1px solid #d9e0e8;
  box-shadow: 0 2px 8px rgb(26 45 70 / 10%);
  box-sizing: content-box;
  backdrop-filter: blur(10px);
}

.room-overview__profile,
.room-overview__market,
.room-overview__history {
  box-sizing: border-box;
}

.room-overview__profile {
  display: flex;
  height: 48px;
  padding: 5px 12px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.room-member {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 9px;
}

.room-member img {
  width: 36px;
  height: 36px;
  object-fit: cover;
  border: 1px solid #d9e0e8;
  border-radius: 50%;
}

.room-member > div,
.room-balance {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.room-member strong {
  max-width: min(44vw, 260px);
  overflow: hidden;
  font-size: 15px;
  line-height: 19px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.room-member span,
.room-balance span {
  overflow: hidden;
  color: #8792a2;
  font-size: 11px;
  line-height: 15px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.room-balance {
  align-items: flex-end;
  flex: none;
}

.room-balance strong {
  color: #d05b2d;
  font-size: 17px;
  line-height: 20px;
}

.room-overview__market {
  display: grid;
  height: 40px;
  padding: 5px 12px;
  align-items: center;
  grid-template-columns: auto auto minmax(0, 1fr);
  gap: 8px;
  background: #f7f9fc;
  border-top: 1px solid #edf0f4;
  border-bottom: 1px solid #e7ebf0;
}

.room-current-period {
  display: flex;
  align-items: baseline;
  gap: 3px;
  white-space: nowrap;
}

.room-current-period span {
  color: #7b8694;
  font-size: 11px;
}

.room-current-period strong {
  color: #26364c;
  font-size: 17px;
}

.room-status {
  padding: 3px 7px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 700;
  line-height: 16px;
  white-space: nowrap;
}

.room-status.is-open {
  color: #087a3d;
  background: #dff6e9;
}

.room-status.is-closed {
  color: #a35a00;
  background: #fff0d8;
}

.room-status.is-pending {
  color: #1764aa;
  background: #e1efff;
}

.room-status.is-abnormal {
  color: #b4232e;
  background: #ffe4e7;
}

.room-status.is-muted {
  color: #66717e;
  background: #e9edf2;
}

.room-countdown {
  color: #d05b2d;
  font-size: 13px;
  white-space: nowrap;
}

.room-countdown.is-urgent {
  color: #d01824;
}

.room-overview__history {
  display: flex;
  height: 40px;
  padding: 4px 8px;
  align-items: center;
}

.has-pending-draw .room-overview__history {
  height: 74px;
  flex-direction: column;
  gap: 7px;
}

.room-pending-draw {
  display: flex;
  width: 100%;
  height: 27px;
  padding: 0 9px;
  align-items: center;
  color: #b4232e;
  background: #fff1f2;
  border: 1px solid #ffd4d8;
  border-radius: 6px;
  box-sizing: border-box;
  gap: 5px;
  font-size: 11px;
}

.room-pending-draw strong {
  font-size: 12px;
}

.room-pending-draw span {
  color: #d05b2d;
  font-weight: 700;
}

.room-history-latest,
.room-history-panel__title button {
  font: inherit;
  cursor: pointer;
  border: 0;
}

.room-history-latest {
  display: grid;
  width: 100%;
  height: 30px;
  padding: 0 9px;
  align-items: center;
  grid-template-columns: auto auto auto auto minmax(0, 1fr) auto;
  gap: 6px;
  min-width: 0;
  color: #48556a;
  background: #f3f6f9;
  border: 1px solid #e1e6ec;
  border-radius: 6px;
  text-align: left;
}

.room-history-label,
.room-history-latest-time {
  color: #7b8694;
  font-size: 11px;
  white-space: nowrap;
}

.room-history-latest strong {
  color: #26364c;
  font-size: 12px;
  white-space: nowrap;
}

.room-history-latest-numbers {
  overflow: hidden;
  gap: 4px;
}

.room-history-empty {
  color: #9aa3ad;
  font-size: 12px;
}

.room-history-panel {
  position: absolute;
  z-index: 12;
  top: calc(100% + 1px);
  left: 50%;
  width: min(520px, calc(100% - 16px));
  max-height: min(390px, calc(100vh - var(--room-overview-height) - 70px));
  overflow: hidden;
  background: #fff;
  border: 1px solid #dbe1e8;
  border-radius: 0 0 10px 10px;
  box-shadow: 0 12px 30px rgb(30 47 68 / 18%);
  transform: translateX(-50%);
}

.room-history-panel__title {
  display: flex;
  height: 40px;
  padding: 0 12px;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e8ecf1;
}

.room-history-panel__title button {
  display: grid;
  width: 30px;
  height: 30px;
  padding: 0;
  color: #687383;
  background: transparent;
  place-items: center;
}

.room-history-panel__list {
  max-height: min(350px, calc(100vh - var(--room-overview-height) - 110px));
  overflow-y: auto;
}

.room-history-panel__list > div {
  display: grid;
  min-height: 42px;
  padding: 5px 12px;
  align-items: center;
  grid-template-columns: 94px 42px minmax(126px, 1fr) auto;
  gap: 7px;
  border-bottom: 1px solid #eef1f4;
  box-sizing: border-box;
}

.room-history-panel__list > div:nth-child(even) {
  background: #f8f9fb;
}

.room-history-period {
  font-size: 12px;
  font-weight: 700;
}

.room-history-time,
.room-history-panel__list small {
  color: #87919e;
  font-size: 11px;
  white-space: nowrap;
}

.room-history-numbers {
  display: flex;
  gap: 4px;
}

.room-history-numbers i {
  display: grid;
  width: 22px;
  height: 22px;
  color: #fff;
  background: #d05b4a;
  border-radius: 50%;
  place-items: center;
  font-size: 12px;
  font-style: normal;
  font-weight: 700;
}

.room-history-latest .room-history-numbers i {
  width: 20px;
  height: 20px;
  font-size: 11px;
}

.room-history-panel__empty {
  padding: 28px;
  color: #98a1ac;
  text-align: center;
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
  inset: calc(var(--room-overview-height) + env(safe-area-inset-top)) 0 49px;
  -webkit-overflow-scrolling: touch;
}

.new-message-tip {
  position: fixed;
  right: 18px;
  bottom: calc(58px + env(safe-area-inset-bottom));
  z-index: 7;
  min-height: 32px;
  padding: 5px 12px;
  font: inherit;
  font-size: 14px;
  font-weight: 700;
  line-height: 20px;
  color: #08a737;
  background: #fff;
  border: 1px solid #e1e5e1;
  border-radius: 18px;
  box-shadow: 0 2px 8px rgb(35 72 40 / 18%);
}

.new-message-tip span {
  margin-right: 5px;
  font-size: 15px;
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

.chat-body h5 em {
  margin-left: 4px;
  padding: 0 4px;
  color: #b26b00;
  background: #fff2d6;
  border-radius: 3px;
  font-size: 11px;
  font-style: normal;
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

.chat-message-robot .chat-bubble::before,
.chat-message-other .chat-bubble::before {
  left: -9px;
  border-color: transparent #d1d1d1 transparent transparent;
  border-width: 7px 9px 7px 0;
}

.chat-message-robot .chat-bubble::after,
.chat-message-other .chat-bubble::after {
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

.chat-bubble pre.is-copyable {
  cursor: pointer;
}

.chat-bubble-time {
  margin-top: 3px;
  color: #9a9a9a;
  font-size: 11px;
  line-height: 15px;
}

.chat-message-member .chat-bubble-time {
  text-align: right;
}

:global(.room-copy-success .el-message-box__message) {
  justify-content: center;
  color: #00b83f;
  font-size: 17px;
}

:global(.room-copy-success .el-message-box__btns) {
  justify-content: center;
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

.composer-row.is-chat-only {
  grid-template-columns: minmax(80px, 1fr) 50px;
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
  color: #202124 !important;
  caret-color: #202124;
  -webkit-text-fill-color: #202124 !important;
  color-scheme: light;
  background: #fff !important;
  border: 1px solid #dcdcde;
  border-radius: 8px;
  outline: none;
  box-sizing: border-box;
  resize: none;
}

.composer-row textarea::placeholder {
  color: #8a8f98 !important;
  opacity: 1;
  -webkit-text-fill-color: #8a8f98 !important;
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
  width: 100%;
  height: 190px;
  padding: 5px clamp(3px, 1.5vw, 8px);
  overflow: hidden;
  color-scheme: light;
  background: #dfe3ea;
  grid-template-rows: repeat(5, minmax(0, 1fr));
}

.keyboard-row {
  display: grid;
  grid-template-columns: repeat(9, minmax(0, 1fr));
  gap: clamp(2px, 0.8vw, 5px);
  min-width: 0;
}

.keyboard-row button {
  min-width: 0;
  min-height: 32px;
  padding: 0;
  overflow: hidden;
  font-size: 15px;
  font-weight: 700;
  color: #202124 !important;
  -webkit-text-fill-color: #202124 !important;
  cursor: pointer;
  background: #fff !important;
  border: 1px solid #b8bec8;
  border-radius: 5px;
  box-shadow: 0 1px 2px rgb(0 0 0 / 14%);
  text-overflow: clip;
  white-space: nowrap;
}

.keyboard-row button:active {
  background: #ffb24a !important;
}

.keyboard-row button.is-danger {
  color: #d90000 !important;
  -webkit-text-fill-color: #d90000 !important;
}

.keyboard-row button.is-accent {
  color: #3d2a00 !important;
  -webkit-text-fill-color: #3d2a00 !important;
  background: #ffb533 !important;
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
  touch-action: none;
  user-select: none;
}

.scratch-launcher.is-dragging {
  cursor: grabbing;
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
  grid-template-columns: 46px 58px minmax(92px, 1fr) 28px;
  padding: 8px 10px;
  color: #555;
  background: #f1f2f4;
  border-bottom: 1px solid #d6d8dc;
  font-size: 13px;
}

.lottery-table__history-row {
  display: grid;
  grid-template-columns: 46px 58px minmax(92px, 1fr) 28px;
  padding: 4px 10px;
  border-bottom: 1px solid #eee;
  font-size: 13px;
}

.lottery-table__history-row:nth-child(even) {
  background: #f2f2f2;
}

.lottery-table__history-numbers {
  display: grid;
  align-items: center;
  justify-content: start;
  grid-template-columns: repeat(5, 14px);
  gap: 6px;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.lottery-table__history-numbers i {
  color: #707070;
  font-style: normal;
  text-align: center;
}

.lottery-table__history-numbers i.is-purple {
  color: #8d3299;
}

.lottery-table__history-numbers i.is-pink {
  color: #ff4f82;
}

.lottery-table__history-numbers i.is-blue {
  color: #409eff;
}

.lottery-table__history-numbers i.is-green {
  color: #10c957;
}

.lottery-table__dragon-tiger {
  font-family: 'Microsoft YaHei', 'PingFang SC', sans-serif;
  font-size: 16px;
  font-weight: 700;
  line-height: 20px;
  text-align: center;
}

.lottery-table__dragon-tiger.is-龙 {
  color: #ff1493;
}

.lottery-table__dragon-tiger.is-虎 {
  color: #0000ff;
}

.lottery-table__dragon-tiger.is-和 {
  color: #00cc44;
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
  .room-overview__profile,
  .room-overview__market {
    padding-right: 9px;
    padding-left: 9px;
  }

  .room-overview__market {
    gap: 6px;
  }

  .room-history-panel__list > div {
    padding-right: 9px;
    padding-left: 9px;
    grid-template-columns: 94px 38px minmax(120px, 1fr);
    gap: 5px;
  }

  .room-history-panel__list small {
    display: none;
  }

  .composer-row {
    grid-template-columns: 28px minmax(52px, 1fr) 42px 42px 28px;
    gap: 4px;
  }

  .fast-select,
  .send-button {
    width: 42px;
    font-size: 13px;
  }

  .keyboard-row button {
    font-size: 13px;
  }
}

@media (width <= 350px) {
  .composer-row {
    grid-template-columns: 26px minmax(0, 1fr) 38px 38px 26px;
    gap: 3px;
    padding-right: 4px;
    padding-left: 4px;
  }

  .keyboard-toggle,
  .history-toggle {
    width: 26px;
    height: 26px;
  }

  .fast-select,
  .send-button {
    width: 38px;
    font-size: 12px;
  }

  .keyboard-row button {
    font-size: 12px;
  }

  .room-history-latest {
    grid-template-columns: auto auto auto minmax(0, 1fr) auto;
  }

  .room-history-label {
    display: none;
  }

  .room-overview__market {
    grid-template-columns: auto auto minmax(0, 1fr);
  }

  .room-countdown {
    overflow: hidden;
    text-align: right;
    text-overflow: ellipsis;
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
