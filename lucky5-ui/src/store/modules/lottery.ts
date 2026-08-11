import { defineStore } from 'pinia'
import { ElMessage } from 'element-plus'
import {
  auditAmountApi,
  applyRebatesApi,
  bindIntegrationApi,
  cancelOrderApi,
  changeMemberAvatarApi,
  clearAllFingerprintsApi,
  clearAllMemberFlowsApi,
  clearChimaRecordsApi,
  clearMemberFingerprintApi,
  clearMemberApi,
  createAmountRequestApi,
  deleteFakeOrderApi,
  deleteQuickCommandApi,
  deleteFollowOrderApi,
  deleteMemberApi,
  getLucky5Bootstrap,
  getAmountRecordsApi,
  getChimaRecordsApi,
  getOrdersApi,
  getMemberSnapshotsApi,
  getMemberDetailsApi,
  getMemberLinksApi,
  rotateMemberLinkApi,
  markMessageApi,
  placeBetApi,
  processIncomingMessageApi,
  saveChimaConfigApi,
  saveConfigApi,
  saveDiscountsApi,
  saveFakeOrderApi,
  saveQuickCommandApi,
  saveFollowOrderApi,
  saveLinksApi,
  saveMemberApi,
  saveOddsApi,
  setRoomApi,
  setSwitchApi,
  settlePeriodApi,
  settlePendingIssuesApi,
  setIssueStatusApi,
  syncMarketApi,
  testConfigApi,
  transferMemberApi,
  unbindIntegrationApi
} from '@/api/lottery'
import type { IntegrationKey } from '@/api/lottery'
import { useUserStoreWithOut } from '@/store/modules/user'

export type SwitchKey =
  | 'autoDiscount'
  | 'openCancel'
  | 'groupImage'
  | 'privateImage'
  | 'privateMode'
  | 'pullEnable'
  | 'dailyClear'
  | 'wangkaEnable'
  | 'delayOrder'
  | 'enableFingerCheck'
  | 'syncEnable'
  | 'dragonTigerSeparateRebate'
  | 'urlEncode'
  | 'delayOpen'
  | 'linkToCode'
  | 'prizeCard'
  | 'imageBold'

const defaultSwitchLabels: Record<SwitchKey, string> = {
  autoDiscount: '关盘后自动反水',
  openCancel: '开启退码',
  groupImage: '群发图',
  privateImage: '私发图',
  privateMode: '开启私聊',
  pullEnable: '网页群',
  dailyClear: '每天自动清理流水',
  wangkaEnable: '网咔模式',
  delayOrder: '延迟跟单',
  enableFingerCheck: '校验指纹',
  syncEnable: '同步网盘',
  dragonTigerSeparateRebate: '龙琥分开反水',
  urlEncode: '网址加密',
  delayOpen: '延迟开',
  linkToCode: '拉发二维码',
  prizeCard: '刮刮卡',
  imageBold: '图加粗'
}

const useLucky5StoreBase = defineStore('lucky5', {
  state: () => ({
    loaded: false,
    loadedUserId: 0,
    loading: false,
    membersRefreshing: false,
    saving: false,
    operator: { username: '', expireAt: '' },
    room: { open: false, online: 0 },
    dashboardStats: { totalMembers: 0, onlineMembers: 0, pendingDeposits: 0 },
    switches: Object.fromEntries(
      Object.keys(defaultSwitchLabels).map((key) => [key, false])
    ) as Record<SwitchKey, boolean>,
    switchLabels: { ...defaultSwitchLabels } as Record<SwitchKey, string>,
    integrations: {
      blueWhale: { name: '蓝鲸', account: '', group: '', status: '未登录' },
      fish: { name: '飞鱼', account: '', group: '', status: '未登录' },
      wechat: { name: '微信', account: '', group: '', status: '未登录' }
    },
    config: {
      url: '',
      account: '',
      password: '',
      hasPassword: false,
      alertValue: 0,
      bossMode: false,
      playType: 2,
      useProxy: true
    },
    market: {
      connection: {
        status: '未配置',
        lineUrl: '',
        displayAccount: '',
        balance: null as number | null,
        error: '',
        lastLoginAt: null as string | null,
        lastSyncAt: null as string | null
      },
      issue: null as Record<string, any> | null,
      recentIssues: [] as any[]
    },
    links: { groupLinkEnabled: true, privateLinkEnabled: true, defaultRoomMode: 'GROUP' },
    odds: [] as any[],
    members: [] as any[],
    amountRecords: [] as any[],
    orders: [] as any[],
    drawHistory: [] as any[],
    drawAlerts: [] as any[],
    fakeOrders: [] as any[],
    quickCommands: [] as any[],
    followOrders: [] as any[],
    operators: [] as any[],
    messages: [] as any[],
    chimaConfig: {
      siZiXian: 0,
      sanZiXian: 0,
      erZiXian: 0,
      siDingWei: 0,
      sanDingWei: 0,
      erDingWei: 0,
      yiDingWei: 0,
      yinKuiMax: 0,
      yinKuiMin: 0
    },
    chimaRecords: [] as any[],
    chimaRecordsRefreshing: false
  }),
  getters: {
    switchList: (state) =>
      Object.keys(defaultSwitchLabels).map((key) => ({
        key,
        label: state.switchLabels[key as SwitchKey] || key,
        value: state.switches[key as SwitchKey]
      })),
    stats: (state) => ({
      totalMembers: state.dashboardStats.totalMembers,
      onlineMembers: state.dashboardStats.onlineMembers,
      pendingDeposits: state.dashboardStats.pendingDeposits,
      totalBalance: state.members
        .filter((item) => item.memberType !== 'BOT' && !item.autoProxy)
        .reduce((sum, item) => sum + Number(item.balance || 0), 0)
    }),
    historyOrders: (state) => state.orders.filter((item) => item.status !== '未开奖')
  },
  actions: {
    async initialize(force = false) {
      const userId = useUserStoreWithOut().getUser.id
      if (this.loadedUserId !== 0 && this.loadedUserId !== userId) this.$reset()
      if (this.loading || (this.loaded && this.loadedUserId === userId && !force)) return
      this.loading = true
      try {
        const data = await getLucky5Bootstrap()
        Object.assign(this.operator, data.operator)
        Object.assign(this.room, data.room)
        Object.assign(this.dashboardStats, data.dashboardStats)
        this.switches = {
          ...Object.fromEntries(Object.keys(defaultSwitchLabels).map((key) => [key, false])),
          ...(data.switches || {})
        } as Record<SwitchKey, boolean>
        this.switchLabels = {
          ...defaultSwitchLabels,
          ...(data.switchLabels || {})
        } as Record<SwitchKey, string>
        this.integrations = data.integrations
        this.config = data.config
        this.market = {
          ...this.market,
          connection: data.market || this.market.connection,
          issue: data.issue || null
        }
        this.links = data.links
        this.odds = data.odds
        this.members = data.members
        this.amountRecords = data.amountRecords
        this.orders = data.orders
        this.drawHistory = data.drawHistory
        this.drawAlerts = data.drawAlerts || []
        this.fakeOrders = data.fakeOrders
        this.quickCommands = data.quickCommands || []
        this.followOrders = data.followOrders
        this.operators = data.operators
        this.messages = data.messages
        this.chimaConfig = data.chimaConfig
        this.chimaRecords = data.chimaRecords
        this.loadedUserId = userId
        this.loaded = true
        this.connectRealtime()
      } catch (error: any) {
        ElMessage.error(error?.message || '后台数据加载失败')
      } finally {
        this.loading = false
      }
    },
    connectRealtime() {},
    disconnectRealtime() {},
    notify(message = '操作成功') {
      ElMessage.success(message)
    },
    async perform(action: () => Promise<unknown>, successMessage: string) {
      this.saving = true
      try {
        const response = await action()
        await this.initialize(true)
        ElMessage.success(successMessage)
        return response
      } catch (error: any) {
        // 业务异常已经由 Axios 统一展示，避免随后再弹出一条笼统的“操作失败”。
        if (error !== 'error') {
          ElMessage.error(error?.message || '操作失败')
        }
        await this.initialize(true)
        return false
      } finally {
        this.saving = false
      }
    },
    setSwitch(key: SwitchKey, value: boolean) {
      return this.perform(() => setSwitchApi(key, value), '开关已保存')
    },
    setRoomOpen(value: boolean) {
      return this.perform(() => setRoomApi(value), value ? '启动成功' : '关闭成功')
    },
    saveConfig(payload: Record<string, any>) {
      return this.perform(() => saveConfigApi(payload), '配置已保存')
    },
    async testConfig(payload: Record<string, any>) {
      try {
        const response = await testConfigApi(payload)
        ElMessage.success(`盘口连接检查通过：${response.status}`)
        return response
      } catch (error: any) {
        ElMessage.error(error?.message || '连接配置检查失败')
        return false
      }
    },
    syncMarket() {
      return this.perform(() => syncMarketApi(), '盘口数据已同步')
    },
    async refreshMembers() {
      if (this.membersRefreshing) return false
      this.membersRefreshing = true
      try {
        const snapshots = await getMemberSnapshotsApi()
        const snapshotById = new Map(snapshots.map((item) => [item.id, item]))
        this.members = this.members.map((member) => ({
          ...member,
          ...(snapshotById.get(member.id) || {})
        }))
        return true
      } catch {
        return false
      } finally {
        this.membersRefreshing = false
      }
    },
    async refreshChimaRecords() {
      if (this.chimaRecordsRefreshing) return false
      this.chimaRecordsRefreshing = true
      try {
        this.chimaRecords = await getChimaRecordsApi()
        return true
      } catch {
        return false
      } finally {
        this.chimaRecordsRefreshing = false
      }
    },
    saveLinks(payload: Record<string, any>) {
      return this.perform(() => saveLinksApi(payload), '链接配置已保存')
    },
    saveChimaConfig(payload: Record<string, any>) {
      return this.perform(() => saveChimaConfigApi(payload), '吃码配置已保存')
    },
    bindIntegration(key: IntegrationKey, payload: Record<string, string>) {
      return this.perform(() => bindIntegrationApi(key, payload), '配置已保存，等待连接验证')
    },
    unbindIntegration(key: IntegrationKey) {
      return this.perform(() => unbindIntegrationApi(key), '已解绑')
    },
    saveMember(payload: Record<string, any>) {
      return this.perform(() => saveMemberApi(payload), '会员已保存')
    },
    transferMember(id: string, amount: number, type: '上分' | '下分') {
      return this.perform(() => transferMemberApi(id, amount, type), '余额已更新')
    },
    createAmountRequest(id: string, amount: number, type: '上分' | '下分', remark = '') {
      return this.perform(
        () => createAmountRequestApi(id, { amount, type, remark }),
        '上下分申请已提交'
      )
    },
    async refreshAmountRecords() {
      try {
        this.amountRecords = await getAmountRecordsApi()
        return true
      } catch {
        return false
      }
    },
    async refreshOrders() {
      try {
        this.orders = await getOrdersApi()
        return true
      } catch {
        return false
      }
    },
    async getMemberDetails(id: string) {
      try {
        return await getMemberDetailsApi(id)
      } catch (error: any) {
        ElMessage.error(error?.message || '会员资料加载失败')
        return null
      }
    },
    async getMemberLinks(id: string) {
      try {
        const data = await getMemberLinksApi(id)
        await this.initialize(true)
        return data
      } catch (error: any) {
        ElMessage.error(error?.message || '会员链接生成失败')
        return null
      }
    },
    async rotateMemberLink(id: string) {
      try {
        const data = await rotateMemberLinkApi(id)
        await this.initialize(true)
        this.notify('会员链接已更换')
        return data
      } catch (error: any) {
        ElMessage.error(error?.message || '会员链接更换失败')
        return null
      }
    },
    clearMemberFingerprint(id: string) {
      return this.perform(() => clearMemberFingerprintApi(id), '会员标识已抹除')
    },
    clearAllFingerprints() {
      return this.perform(() => clearAllFingerprintsApi(), '全部会员标识已抹除')
    },
    changeMemberAvatar(id: string) {
      return this.perform(() => changeMemberAvatarApi(id), '头像已更换')
    },
    clearAllMemberFlows(password: string) {
      return this.perform(() => clearAllMemberFlowsApi(password), '全部会员流水已清理')
    },
    clearMember(id: string) {
      return this.perform(() => clearMemberApi(id), '会员流水已清理')
    },
    auditAmount(id: string, status: '已通过' | '已拒绝', remark = '') {
      return this.perform(() => auditAmountApi(id, status, remark), '审核结果已保存')
    },
    placeBet(payload: Record<string, any>) {
      return this.perform(() => placeBetApi(payload), '下注已受理并生成订单')
    },
    processIncomingMessage(payload: Record<string, any>) {
      return this.perform(() => processIncomingMessageApi(payload), '消息命令已处理')
    },
    cancelOrder(id: string) {
      return this.perform(() => cancelOrderApi(id), '订单已退码')
    },
    settlePeriod(period: string, result: string, reason: string) {
      return this.perform(() => settlePeriodApi(period, result, reason), '期号已结算')
    },
    setIssueStatus(period: string, status: 'open' | 'close') {
      return this.perform(
        () => setIssueStatusApi(period, status),
        status === 'open' ? '期号已开盘' : '期号已封盘'
      )
    },
    settlePendingIssues() {
      return this.perform(() => settlePendingIssuesApi(), '待结算期号已处理')
    },
    saveOdds() {
      return this.perform(() => saveOddsApi(this.odds), '赔率已保存')
    },
    saveDiscounts(members: Record<string, any>[] = this.members) {
      const payload = members.map(
        ({ id, normalRate, lhhRate, partner, partnerNormalRate, partnerLhhRate, puller, tag }) => ({
          id,
          normalRate,
          lhhRate,
          partner,
          partnerNormalRate,
          partnerLhhRate,
          puller: puller ?? tag === '拉手'
        })
      )
      return this.perform(() => saveDiscountsApi(payload), '返水及拉手设置已保存')
    },
    applyRebates() {
      return this.perform(() => applyRebatesApi(), '返水已结算到会员余额')
    },
    clearChimaRecords(password: string) {
      return this.perform(() => clearChimaRecordsApi(password), '吃码记录已清理')
    },
    saveFakeOrder(payload: Record<string, any>) {
      return this.perform(() => saveFakeOrderApi(payload), '预设订单已保存')
    },
    saveQuickCommand(payload: Record<string, any>) {
      return this.perform(() => saveQuickCommandApi(payload), '快捷指令已保存')
    },
    saveFollowOrder(payload: Record<string, any>) {
      return this.perform(() => saveFollowOrderApi(payload), '跟单设置已保存')
    },
    remove(collection: 'fakeOrders' | 'quickCommands' | 'followOrders' | 'members', id: string) {
      const action =
        collection === 'fakeOrders'
          ? () => deleteFakeOrderApi(id)
          : collection === 'quickCommands'
            ? () => deleteQuickCommandApi(id)
            : collection === 'followOrders'
              ? () => deleteFollowOrderApi(id)
              : () => deleteMemberApi(id)
      return this.perform(action, '已删除')
    },
    markMessage(id: number, status: string) {
      return this.perform(() => markMessageApi(id, status), '消息状态已更新')
    }
  }
})

export const useLucky5Store = (...args: Parameters<typeof useLucky5StoreBase>) => {
  const store = useLucky5StoreBase(...args)
  const userId = useUserStoreWithOut().getUser.id
  if ((!store.loaded || store.loadedUserId !== userId) && !store.loading) void store.initialize()
  return store
}
