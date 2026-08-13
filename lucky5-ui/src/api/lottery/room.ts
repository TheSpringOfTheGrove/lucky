export interface RoomCredential {
  tenantId: number
  uid?: string
  openId: string
  fp?: string
  roomMode?: 'GROUP' | 'PRIVATE'
}

export interface RoomBetItem {
  id: string
  play: string
  selection: string
  amount: number
  odds: number
  won: boolean | null
  payout: number
}

export interface RoomOrder {
  id: string
  period: string
  content: string
  amount: number
  win: number
  status: string
  createdAt: string
  itemCount: number
  items: RoomBetItem[]
  processing?: boolean
  cancelable?: boolean
}

export interface RoomAmountRecord {
  id: string
  type: string
  amount: number
  status: string
  remark: string
  createdAt: string
  auditedAt: string | null
}

export interface RoomDraw {
  period: string
  result: string
  numbers: string[]
  valid?: boolean
  bigSmall: string
  oddEven: string
  dragonTiger: string
  status: string
  settledAt: string
}

export interface RoomSession {
  member: {
    id: string
    name: string
    balance: number
    totalBet: number
    profitLoss: number
    avatar: number
  }
  room: {
    name: string
    announcement: string
    mode: 'GROUP' | 'PRIVATE'
    modeName: string
    open: boolean
    online: number
    bettingEnabled: boolean
    cancelEnabled: boolean
    features: {
      groupImage: boolean
      privateImage: boolean
      prizeCard: boolean
      imageBold: boolean
      linkToCode: boolean
    }
  }
  suggestedPeriod: string
  issue: {
    currentPeriod: string
    status: string
    remainingSeconds: number
    nextPeriod: string
    serverTime: string | number | null
    sourceStale?: boolean
  }
  issueTransitions: Array<{
    id: number
    period: string
    status: 'OPEN' | 'CLOSED'
    summary: string
    createdAt: string
  }>
  draws: RoomDraw[]
  orders: RoomOrder[]
  amountRecords: RoomAmountRecord[]
  messages: Array<{
    id: number
    memberId: string | null
    member: string
    period: string
    content: string
    status: string
    orderId: string | null
    error: string
    reply: string
    commandType: string
    messageType: 'PLAYER' | 'AUTO_PROXY'
    own: boolean
    createdAt: string
  }>
  quickCommands: Array<{
    id: string
    label: string
    content: string
  }>
}

interface ApiEnvelope<T> {
  code: number
  data: T
  msg?: string
  message?: string
}

const request = async <T>(url: string, options?: RequestInit) => {
  const response = await fetch(url, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...options?.headers
    }
  })
  const result = (await response.json()) as ApiEnvelope<T>
  if (!response.ok || result.code !== 0) {
    throw new Error(result.msg || result.message || '请求失败')
  }
  return result.data
}

const credentialQuery = (credential: RoomCredential) => {
  const query = new URLSearchParams({ tenantId: String(credential.tenantId), openId: credential.openId })
  if (credential.uid) query.set('uid', credential.uid)
  if (credential.fp) query.set('fp', credential.fp)
  if (credential.roomMode) query.set('roomMode', credential.roomMode)
  return query.toString()
}

const normalizeDraw = (draw: RoomDraw): RoomDraw => {
  const source =
    Array.isArray(draw.numbers) && draw.numbers.length
      ? draw.numbers.join('')
      : draw.result || ''
  return { ...draw, numbers: (source.match(/\d/g) || []).slice(0, 5) }
}

export const getRoomSessionApi = async (credential: RoomCredential) => {
  const session = await request<RoomSession>(
    `/app-api/lottery/room/session?${credentialQuery(credential)}`
  )
  return { ...session, draws: (session.draws || []).map(normalizeDraw) }
}

export const placeRoomBetApi = (
  credential: RoomCredential,
  data: { period: string; content: string; externalId: string }
) =>
  request<{ orderId: string; balance: number }>('/app-api/lottery/room/bets', {
    method: 'POST',
    body: JSON.stringify({ ...credential, ...data })
  })

export const previewRoomBetApi = (credential: RoomCredential, content: string) =>
  request<{ count: number; total: number; selections: string[] }>('/app-api/lottery/room/bets/preview', {
    method: 'POST',
    body: JSON.stringify({ ...credential, content })
  })

export const sendRoomMessageApi = (
  credential: RoomCredential,
  data: { period?: string; content: string; externalId: string }
) =>
  request<{ reply: string; commandType: string; orderId?: string }>('/app-api/lottery/room/messages', {
    method: 'POST',
    body: JSON.stringify({ ...credential, ...data })
  })

export const createRoomAmountRequestApi = (
  credential: RoomCredential,
  data: { type: '上分' | '下分'; amount: number; remark: string }
) =>
  request<{ id: string }>('/app-api/lottery/room/amount-requests', {
    method: 'POST',
    body: JSON.stringify({ ...credential, ...data })
  })

export const cancelRoomOrderApi = (credential: RoomCredential, orderId: string) =>
  request<{ id: string; status: string; refunded: number }>(
    `/app-api/lottery/room/orders/${encodeURIComponent(orderId)}/cancel`,
    {
      method: 'POST',
      body: JSON.stringify(credential)
    }
  )
