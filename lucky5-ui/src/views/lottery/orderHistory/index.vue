<script setup lang="ts">
import LegacyTable from '@/components/LegacyTable'
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref } from 'vue'
import { getOrderHistoryApi } from '@/api/lottery'
import { legacyFooterHeaders } from '@/views/lottery/utils/legacyTable'

const period = ref('')
const timeType = ref(1)
const refreshing = ref(false)
const rows = ref<any[]>([])
const pageNo = ref(1)
const pageSize = ref(10)
const total = ref(0)
const summary = ref<Record<string, number>>({})
const useMobileCards = false
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
const lastPage = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

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
  <div class="lucky-page lucky-legacy-content">
    <div class="history-heading">历史记录 <small>查询历史记录</small></div>
    <el-card v-loading="refreshing" class="legacy-list-box" shadow="never">
    <div class="history-summary">
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
    <div class="lucky-toolbar history-toolbar">
        <div class="lucky-toolbar__filters history-toolbar__filters">
          <el-input v-model="period" clearable placeholder="期数" />
          <el-select v-model="timeType">
            <el-option label="全部" :value="0" />
            <el-option label="今天" :value="1" />
            <el-option label="昨天" :value="2" />
            <el-option label="本周" :value="3" />
          </el-select>
          <el-button class="history-search-button" :loading="refreshing" @click="search">搜索</el-button>
        </div>
    </div>
      <div class="history-table-length">
        <span>显示</span>
        <el-select v-model="pageSize" @change="changePageSize">
          <el-option :value="10" label="10" />
          <el-option :value="25" label="25" />
          <el-option :value="50" label="50" />
          <el-option :value="100" label="100" />
        </el-select>
        <span>条目</span>
      </div>
      <div v-if="useMobileCards" class="history-mobile-list">
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
      <LegacyTable v-else :data="rows" row-key="periods" border class="history-table" empty-text="No data available in table" show-summary :summary-method="legacyFooterHeaders">
        <el-table-column prop="periods" label="期号" min-width="160" />
        <el-table-column prop="zongTou" label="投额" min-width="110" />
        <el-table-column prop="zhongJiang" label="中奖" min-width="110" />
        <el-table-column prop="yinKui" label="盈亏" min-width="110" />
        <el-table-column prop="shiTou" label="实投" min-width="110" />
        <el-table-column prop="betMoney" label="网盘下单" min-width="130" />
      </LegacyTable>
      <div class="history-pagination">
        <span>显示{{ total }}个条目中的{{ startRow }}到{{ endRow }}</span>
        <div class="history-pagination__buttons">
          <el-button :disabled="pageNo <= 1" @click="changePage(pageNo - 1)">上一页</el-button>
          <el-button :disabled="pageNo >= lastPage" @click="changePage(pageNo + 1)">下一页</el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.history-heading {
  display: block !important;
  margin: 0 0 0;
  padding: 15px 0 17px;
  color: #222;
  font-size: 24px;
  font-weight: 500;
  line-height: 1.1;
  border-bottom: 1px solid #f4f4f4;
}

.history-heading small {
  margin-left: 6px;
  color: #777;
  font-size: 15px;
  font-weight: 400;
}

.history-summary {
  margin: 0 10px 44px;
  color: #f00;
  font-size: 16px;
  font-weight: 600;
  line-height: 1.6;
}

.history-summary p {
  margin: 0;
}

.history-toolbar {
  margin: 0 10px 38px;
}

.history-toolbar__filters {
  gap: 30px !important;
}

.history-toolbar__filters :deep(.el-input),
.history-toolbar__filters :deep(.el-select) {
  width: 166px;
}

.history-search-button {
  --el-button-text-color: #fff;
  --el-button-bg-color: #00c0ef;
  --el-button-border-color: #00acd6;
  --el-button-hover-text-color: #fff;
  --el-button-hover-bg-color: #00acd6;
  --el-button-hover-border-color: #009abf;
  min-height: 34px;
  color: #fff !important;
  background: #00c0ef !important;
  border-color: #00acd6 !important;
}

.legacy-list-box :deep(.el-card__body) {
  padding: 10px;
}

.history-table-length {
  display: flex;
  align-items: center;
  height: 38px;
  gap: 8px;
  color: #333;
  font-size: 14px;
  font-weight: 600;
}

.history-table-length :deep(.el-select) {
  width: 64px;
}

.history-table-length :deep(.el-select__wrapper) {
  min-height: 30px;
}

.history-table :deep(th.el-table__cell) {
  height: 40px;
  padding: 0;
  color: #333;
  font-size: 16px;
  font-weight: 600;
  background: #fff;
}

.history-table :deep(td.el-table__cell) {
  height: 46px;
  padding: 0 8px;
  color: #444;
  font-size: 15px;
}

.history-table :deep(.el-table__empty-block) {
  left: 0;
  width: 100% !important;
  transform: none;
}

.history-table :deep(.el-table__empty-text) {
  width: 100%;
  padding-left: 10px;
  color: #555;
  text-align: left;
}

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
  position: relative;
  display: flex;
  align-items: center;
  min-height: 54px;
  color: #555;
  font-size: 13px;
}

.history-pagination__buttons {
  position: absolute;
  left: 50%;
  display: flex;
  gap: 4px;
  transform: translateX(-50%);
}

.history-pagination__buttons :deep(.el-button) {
  min-height: 30px;
  margin: 0;
  border-radius: 3px;
}

@media (max-width: 767px) {
  .history-summary {
    margin: 0 0 24px;
    font-size: 14px;
  }

  .history-toolbar {
    margin: 0 0 20px;
  }

  .history-toolbar__filters {
    gap: 8px !important;
  }

  .history-pagination {
    align-items: stretch;
    flex-direction: column;
    gap: 10px;
    font-size: 12px;
  }

  .history-pagination__buttons {
    position: static;
    transform: none;
  }
}
</style>
