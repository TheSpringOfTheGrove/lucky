<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref } from 'vue'
import { useLucky5Store } from '@/store/modules/lottery'

const store = useLucky5Store()
const period = ref('')
const timeType = ref(1)
const refreshing = ref(false)
let refreshTimer: number | undefined

const refreshOrders = async () => {
  if (refreshing.value) return
  refreshing.value = true
  try {
    await store.refreshOrders()
  } finally {
    refreshing.value = false
  }
}

const startAutoRefresh = () => {
  if (refreshTimer) return
  void refreshOrders()
  refreshTimer = window.setInterval(() => void refreshOrders(), 5000)
}

const stopAutoRefresh = () => {
  if (!refreshTimer) return
  window.clearInterval(refreshTimer)
  refreshTimer = undefined
}

const money = (value: unknown) => Number(value || 0).toFixed(2)

onMounted(startAutoRefresh)
onActivated(startAutoRefresh)
onDeactivated(stopAutoRefresh)
onBeforeUnmount(stopAutoRefresh)

const rows = computed(() => {
  const groups = new Map<string, any>()
  store.orders.forEach((item) => {
    if (item.orderType === 'AUTO_PROXY' || item.autoProxy) return
    if (!['已中奖', '未中奖'].includes(item.status)) return
    if (period.value && !item.period.includes(period.value.trim())) return
    const current = groups.get(item.period) || {
      periods: item.period,
      zongTou: 0,
      zhongJiang: 0,
      yinKui: 0,
      shiTou: 0,
      betMoney: 0,
      marketWin: 0,
      marketProfit: 0,
      marketRebate: 0
    }
    current.zongTou += Number(item.amount || 0)
    current.zhongJiang += Number(item.win || 0)
    current.yinKui = current.zhongJiang - current.zongTou
    current.shiTou += Number(item.amount || 0)
    current.betMoney += Number(item.marketBet || 0)
    current.marketWin += Number(item.marketWin || 0)
    current.marketRebate += Number(item.marketRebate || 0)
    current.marketProfit = current.marketWin + current.marketRebate - current.betMoney
    groups.set(item.period, current)
  })
  return [...groups.values()]
})
const totals = computed(() =>
  rows.value.reduce(
    (sum, row) => ({
      bet: sum.bet + row.zongTou,
      win: sum.win + row.zhongJiang,
      profit: sum.profit + row.yinKui,
      real: sum.real + row.shiTou,
      marketBet: sum.marketBet + row.betMoney,
      marketWin: sum.marketWin + row.marketWin,
      marketProfit: sum.marketProfit + row.marketProfit,
      marketRebate: sum.marketRebate + row.marketRebate
    }),
    { bet: 0, win: 0, profit: 0, real: 0, marketBet: 0, marketWin: 0, marketProfit: 0, marketRebate: 0 }
  )
)
</script>

<template>
  <div class="lucky-page">
    <h1 class="lucky-page__heading">历史记录 <small>查询历史记录</small></h1>
    <el-card shadow="never">
      <div class="lucky-summary">
        <p
          >总盈亏：{{ money(totals.profit) }}，总中奖：{{ money(totals.win) }}，总投分：{{
            money(totals.bet)
          }}，总实投：{{ money(totals.real) }}</p
        >
        <p
          >网盈亏：{{ money(totals.marketProfit) }}，网中：{{ money(totals.marketWin) }}，网投：{{
            money(totals.marketBet)
          }}，回水：{{ money(totals.marketRebate) }}</p
        >
      </div>
      <div class="lucky-toolbar">
        <div class="lucky-toolbar__filters">
          <el-input v-model="period" clearable placeholder="期数" />
          <el-select v-model="timeType">
            <el-option label="全部" :value="0" />
            <el-option label="今天" :value="1" />
            <el-option label="昨天" :value="2" />
            <el-option label="本周" :value="3" />
          </el-select>
          <el-button type="primary" :loading="refreshing" @click="refreshOrders">搜索</el-button>
        </div>
      </div>
      <PaginatedTable :data="rows" border>
        <template #mobile="{ row }">
          <div class="lucky-mobile-card__title">
            <span>{{ row.periods }}</span>
            <span :class="Number(row.yinKui) >= 0 ? 'lucky-danger' : ''">
              盈亏 {{ money(row.yinKui) }}
            </span>
          </div>
          <div class="lucky-mobile-card__meta">
            <span>投额：{{ money(row.zongTou) }}</span>
            <span>中奖：{{ money(row.zhongJiang) }}</span>
            <span>实投：{{ money(row.shiTou) }}</span>
          </div>
        </template>
        <el-table-column prop="periods" label="期号" min-width="160" />
        <el-table-column prop="zongTou" label="投额" min-width="110" />
        <el-table-column prop="zhongJiang" label="中奖" min-width="110" />
        <el-table-column prop="yinKui" label="盈亏" min-width="110" />
        <el-table-column prop="shiTou" label="实投" min-width="110" />
        <el-table-column prop="betMoney" label="网盘下单" min-width="130" />
      </PaginatedTable>
    </el-card>
  </div>
</template>
