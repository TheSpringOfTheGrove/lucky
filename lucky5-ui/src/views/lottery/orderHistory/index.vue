<script setup lang="ts">
import { useMediaQuery } from '@vueuse/core'
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref } from 'vue'
import { getOrderHistoryApi } from '@/api/lottery'

const period = ref('')
const timeType = ref(1)
const refreshing = ref(false)
const rows = ref<any[]>([])
const pageNo = ref(1)
const pageSize = ref(20)
const total = ref(0)
const summary = ref<Record<string, number>>({})
const isMobile = useMediaQuery('(max-width: 768px)')
let refreshTimer: number | undefined

const refreshHistory = async () => {
  if (refreshing.value) return
  refreshing.value = true
  try {
    const result = await getOrderHistoryApi({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      period: period.value.trim() || undefined,
      timeType: timeType.value
    })
    rows.value = result.list || []
    total.value = Number(result.total || 0)
    summary.value = result.summary || {}
  } finally {
    refreshing.value = false
  }
}

const search = () => {
  pageNo.value = 1
  void refreshHistory()
}

const changePage = (value: number) => {
  pageNo.value = value
  void refreshHistory()
}

const changePageSize = (value: number) => {
  pageSize.value = value
  pageNo.value = 1
  void refreshHistory()
}

const startAutoRefresh = () => {
  if (refreshTimer) return
  void refreshHistory()
  refreshTimer = window.setInterval(() => void refreshHistory(), 5000)
}

const stopAutoRefresh = () => {
  if (!refreshTimer) return
  window.clearInterval(refreshTimer)
  refreshTimer = undefined
}

const money = (value: unknown) => Number(value || 0).toFixed(2)
const startRow = computed(() => (total.value ? (pageNo.value - 1) * pageSize.value + 1 : 0))
const endRow = computed(() => Math.min(pageNo.value * pageSize.value, total.value))

onMounted(startAutoRefresh)
onActivated(startAutoRefresh)
onDeactivated(stopAutoRefresh)
onBeforeUnmount(stopAutoRefresh)

const totals = computed(() => ({
  bet: Number(summary.value.bet || 0),
  win: Number(summary.value.win || 0),
  profit: Number(summary.value.profit || 0),
  real: Number(summary.value.real || 0),
  marketBet: Number(summary.value.marketBet || 0),
  marketWin: Number(summary.value.marketWin || 0),
  marketProfit: Number(summary.value.marketProfit || 0),
  marketRebate: Number(summary.value.marketRebate || 0)
}))
</script>

<template>
  <div class="lucky-page">
    <h1 class="lucky-page__heading">历史记录 <small>查询历史记录</small></h1>
    <el-card v-loading="refreshing" shadow="never">
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
          <el-button type="primary" :loading="refreshing" @click="search">搜索</el-button>
        </div>
      </div>
      <div v-if="isMobile" class="history-mobile-list">
        <article v-for="row in rows" :key="row.periods" class="lucky-mobile-card history-mobile-card">
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
        </article>
        <el-empty v-if="!rows.length" description="暂无数据" :image-size="64" />
      </div>
      <el-table v-else :data="rows" row-key="periods" border>
        <el-table-column prop="periods" label="期号" min-width="160" />
        <el-table-column prop="zongTou" label="投额" min-width="110" />
        <el-table-column prop="zhongJiang" label="中奖" min-width="110" />
        <el-table-column prop="yinKui" label="盈亏" min-width="110" />
        <el-table-column prop="shiTou" label="实投" min-width="110" />
        <el-table-column prop="betMoney" label="网盘下单" min-width="130" />
      </el-table>
      <div class="history-pagination">
        <span>显示第 {{ startRow }} 到 {{ endRow }} 条，共 {{ total }} 条</span>
        <el-pagination
          :current-page="pageNo"
          :page-size="pageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          :layout="isMobile ? 'prev, pager, next' : 'sizes, prev, pager, next, jumper'"
          :pager-count="isMobile ? 3 : 7"
          :small="isMobile"
          background
          @current-change="changePage"
          @size-change="changePageSize"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.history-mobile-list {
  display: grid;
  gap: 10px;
}

.history-mobile-card {
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
}

.history-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 16px;
}

@media (max-width: 768px) {
  .history-pagination {
    align-items: stretch;
    flex-direction: column;
    gap: 10px;
    font-size: 12px;
  }

  .history-pagination :deep(.el-pagination) {
    justify-content: center;
  }
}
</style>
