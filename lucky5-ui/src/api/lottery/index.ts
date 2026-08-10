import request from '@/config/axios'

const base = '/lottery'

export type IntegrationKey = 'blueWhale' | 'fish' | 'wechat'

export interface LotteryMessageRow {
  id: string
  sender: string
  sourceMember: string
  period: string
  content: string
  time: string
  kind: 'member' | 'robot'
}

export interface LotteryMessagePageParams {
  pageNo: number
  pageSize: number
  period?: string
  content?: string
  nickname?: string
}

export const getLucky5Bootstrap = () =>
  request.get<Record<string, any>>({ url: `${base}/bootstrap` })

export const initializeLotteryOwnerApi = (userId: number) =>
  request.post<Record<string, any>>({ url: `${base}/owners/${userId}/initialize` })

export const setSwitchApi = (key: string, value: boolean) =>
  request.patch({ url: `${base}/switches/${key}`, data: { value } })

export const setRoomApi = (open: boolean) => request.patch({ url: `${base}/room`, data: { open } })

export const saveConfigApi = (data: Record<string, any>) =>
  request.put({ url: `${base}/config`, data })

export const testConfigApi = (data: Record<string, any>) =>
  request.post<Record<string, any>>({ url: `${base}/config/test`, data })

export const syncMarketApi = () => request.post<Record<string, any>>({ url: `${base}/config/sync` })

export const saveLinksApi = (data: Record<string, any>) =>
  request.put({ url: `${base}/links`, data })

export const saveChimaConfigApi = (data: Record<string, any>) =>
  request.put({ url: `${base}/chima-config`, data })

export const bindIntegrationApi = (key: IntegrationKey, data: Record<string, string>) =>
  request.put({ url: `${base}/integrations/${key}`, data })

export const unbindIntegrationApi = (key: IntegrationKey) =>
  request.delete({ url: `${base}/integrations/${key}` })

export const saveMemberApi = (data: Record<string, any>) =>
  data.id
    ? request.put({ url: `${base}/members/${data.id}`, data })
    : request.post({ url: `${base}/members`, data })

export const transferMemberApi = (id: string, amount: number, type: '上分' | '下分') =>
  request.post({ url: `${base}/members/${id}/transfer`, data: { amount, type } })

export const createAmountRequestApi = (
  id: string,
  data: { amount: number; type: '上分' | '下分'; remark?: string }
) => request.post({ url: `${base}/members/${id}/amount-request`, data })

export const getAmountRecordsApi = () =>
  request.get<Record<string, any>[]>({ url: `${base}/amount-records` })

export const getOrdersApi = () =>
  request.get<Record<string, any>[]>({ url: `${base}/orders` })

export const getMessagesApi = (params: LotteryMessagePageParams) =>
  request.get<PageResult<LotteryMessageRow[]>>({ url: `${base}/messages`, params })

export const getMemberDetailsApi = (id: string) =>
  request.get<Record<string, any>>({ url: `${base}/members/${id}/details` })

export const getMemberLinksApi = (id: string) =>
  request.get<Record<string, string>>({ url: `${base}/members/${id}/links` })

export const rotateMemberLinkApi = (id: string) =>
  request.post<Record<string, string>>({ url: `${base}/members/${id}/rotate-link` })

export const clearMemberFingerprintApi = (id: string) =>
  request.post({ url: `${base}/members/${id}/clear-fingerprint` })

export const clearAllFingerprintsApi = () =>
  request.post({ url: `${base}/members/clear-fingerprints` })

export const changeMemberAvatarApi = (id: string) =>
  request.post({ url: `${base}/members/${id}/change-avatar` })

export const clearAllMemberFlowsApi = (password: string) =>
  request.post({ url: `${base}/members/clear-flows`, data: { password } })

export const clearMemberApi = (id: string) => request.post({ url: `${base}/members/${id}/clear` })

export const deleteMemberApi = (id: string) => request.delete({ url: `${base}/members/${id}` })

export const saveDiscountsApi = (members: Record<string, any>[]) =>
  request.put({ url: `${base}/member-discounts`, data: { members } })

export const auditAmountApi = (id: string, status: '已通过' | '已拒绝', remark = '') =>
  request.post({ url: `${base}/amount-records/${id}/audit`, data: { status, remark } })

export const placeBetApi = (data: Record<string, any>) =>
  request.post({ url: `${base}/bets`, data })

export const processIncomingMessageApi = (data: Record<string, any>) =>
  request.post({ url: `${base}/messages/incoming`, data })

export const cancelOrderApi = (id: string) => request.post({ url: `${base}/orders/${id}/cancel` })

export const settlePeriodApi = (period: string, result: string, reason: string) =>
  request.post({ url: `${base}/draws/${period}/settle`, data: { result, reason } })

export const setIssueStatusApi = (period: string, status: 'open' | 'close') =>
  request.post({ url: `${base}/issues/${encodeURIComponent(period)}/${status}` })

export const settlePendingIssuesApi = () => request.post({ url: `${base}/issues/settle-pending` })

export const applyRebatesApi = () => request.post({ url: `${base}/rebates/apply` })

export const clearChimaRecordsApi = (password: string) =>
  request.post({ url: `${base}/chima-records/clear`, data: { password } })

export const saveOddsApi = (odds: Record<string, any>[]) =>
  request.put({ url: `${base}/odds`, data: { odds } })

export const saveFakeOrderApi = (data: Record<string, any>) =>
  data.id
    ? request.put({ url: `${base}/fake-orders/${data.id}`, data })
    : request.post({ url: `${base}/fake-orders`, data })

export const deleteFakeOrderApi = (id: string) =>
  request.delete({ url: `${base}/fake-orders/${id}` })

export const saveQuickCommandApi = (data: Record<string, any>) =>
  data.id
    ? request.put({ url: `${base}/quick-commands/${data.id}`, data })
    : request.post({ url: `${base}/quick-commands`, data })

export const deleteQuickCommandApi = (id: string) =>
  request.delete({ url: `${base}/quick-commands/${id}` })

export const saveFollowOrderApi = (data: Record<string, any>) =>
  data.id
    ? request.put({ url: `${base}/follow-orders/${data.id}`, data })
    : request.post({ url: `${base}/follow-orders`, data })

export const deleteFollowOrderApi = (id: string) =>
  request.delete({ url: `${base}/follow-orders/${id}` })

export const markMessageApi = (id: number, status: string) =>
  request.patch({ url: `${base}/messages/${id}`, data: { status } })
