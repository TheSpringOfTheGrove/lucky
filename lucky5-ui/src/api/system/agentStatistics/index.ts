import request from '@/config/axios'

export interface AgentStatisticsSummary {
  admin: boolean
  availableGold: number | null
  agentCount: number | null
  ownerCount: number
  rechargedGold: number | null
  consumedGold: number
}

export interface AgentStatisticsAgent {
  id: number
  username: string
  nickname: string
  availableGold: number
  createTime: string
}

export interface AgentStatisticsLedger {
  id: number
  amount: number
  sourceAccount: string
  targetAccount: string
  agentBalanceAfter: number | null
  ownerExpireAfter: string | null
  createTime: string
}

export interface StatisticsPage<T> {
  list: T[]
  total: number
}

export const getSummary = (): Promise<AgentStatisticsSummary> =>
  request.get({ url: '/system/agent-statistics/summary' })

export const getAgents = (params: { pageNo: number; pageSize: number }): Promise<StatisticsPage<AgentStatisticsAgent>> =>
  request.get({ url: '/system/agent-statistics/agents', params })

export const getLedger = (
  type: 'RECHARGE' | 'CONSUMPTION',
  params: { pageNo: number; pageSize: number }
): Promise<StatisticsPage<AgentStatisticsLedger>> =>
  request.get({ url: '/system/agent-statistics/ledger', params: { ...params, type } })
