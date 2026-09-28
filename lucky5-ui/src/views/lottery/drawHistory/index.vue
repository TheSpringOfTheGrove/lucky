<script setup lang="ts">
import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  ref
} from 'vue'
import { ElMessage } from 'element-plus'
import { useLucky5Store } from '@/store/modules/lottery'
import { legacyFooterHeaders } from '@/views/lottery/utils/legacyTable'

const store = useLucky5Store()
const settleVisible = ref(false)
const settleForm = ref({ period: '', result: '' })
const periodFilter = ref('')
const activePeriodFilter = ref('')
const refreshing = ref(false)
const pageNo = ref(1)
const pageSize = ref(10)
let refreshTimer: number | undefined

const refreshDrawHistory = async () => {
  if (refreshing.value) return
  refreshing.value = true
  try {
    await store.refreshDrawHistory(activePeriodFilter.value)
    pageNo.value = 1
  } finally {
    refreshing.value = false
  }
}

const searchDrawHistory = async () => {
  activePeriodFilter.value = periodFilter.value.trim()
  await refreshDrawHistory()
}

const total = computed(() => store.drawHistory.length)
const rows = computed(() => {
  const start = (pageNo.value - 1) * pageSize.value
  return store.drawHistory.slice(start, start + pageSize.value)
})
const startRow = computed(() => (total.value ? (pageNo.value - 1) * pageSize.value + 1 : 0))
const endRow = computed(() => Math.min(pageNo.value * pageSize.value, total.value))
const lastPage = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
const changePage = (value: number) => {
  pageNo.value = Math.max(1, Math.min(value, lastPage.value))
}
const changePageSize = (value: number) => {
  pageSize.value = value
  pageNo.value = 1
}
const displayResult = (result: unknown) => {
  const value = String(result || '')
  return /^\d{5}$/.test(value) ? value.split('').join(',') : value
}

const submitSettle = async () => {
  const period = settleForm.value.period.trim()
  const result = settleForm.value.result.replace(/[,，\s]/g, '')
  if (!period || !result) {
    ElMessage.warning('请输入期号和开奖号码')
    return
  }
  if (!/^\d{5}$/.test(result)) {
    ElMessage.warning('开奖号码必须是完整的五位数字')
    return
  }
  if (result === '00000') {
    ElMessage.error('00000 属于异常开奖号码，不能结算')
    return
  }
  const settled = await store.settlePeriod(period, result, '手动结算')
  if (settled) {
    settleVisible.value = false
    settleForm.value = { period: '', result: '' }
    await refreshDrawHistory()
  }
}

const startAutoRefresh = () => {
  if (refreshTimer) return
  void refreshDrawHistory()
  refreshTimer = window.setInterval(() => void refreshDrawHistory(), 5 * 60 * 1000)
}

const stopAutoRefresh = () => {
  if (!refreshTimer) return
  window.clearInterval(refreshTimer)
  refreshTimer = undefined
}

onMounted(startAutoRefresh)
onActivated(startAutoRefresh)
onDeactivated(stopAutoRefresh)
onBeforeUnmount(stopAutoRefresh)

</script>

<template>
  <div class="lucky-page lucky-legacy-content">
    <div class="draw-history-heading">开奖历史记录 <small>查询开奖记录</small></div>
    <div class="draw-history-source-status">
      <p>采集链接1状态: 状态正常</p>
      <p>采集链接2状态: 状态正常</p>
    </div>
    <el-card v-loading="refreshing" class="legacy-list-box" shadow="never">
      <el-button class="draw-history-add-button" @click="settleVisible = true">
        <Icon icon="fa-solid:user-plus" />
      </el-button>
      <div class="draw-history-filters">
        <el-input
          v-model="periodFilter"
          clearable
          placeholder="请输入期号"
          @keyup.enter="searchDrawHistory"
        />
        <el-button type="primary" :loading="refreshing" @click="searchDrawHistory"
          >搜索</el-button
        >
      </div>
      <div class="draw-history-table-length">
        <span>显示</span>
        <el-select v-model="pageSize" @change="changePageSize">
          <el-option :value="10" label="10" />
          <el-option :value="20" label="20" />
          <el-option :value="50" label="50" />
          <el-option :value="100" label="100" />
        </el-select>
        <span>条目</span>
      </div>
      <el-table :data="rows" row-key="period" border class="draw-history-table" empty-text="No data available in table" show-summary :summary-method="legacyFooterHeaders">
        <el-table-column prop="period" label="期号" min-width="160" />
        <el-table-column prop="drawTime" label="开奖时间" min-width="200">
          <template #default="{ row }">{{ row.drawTime || row.settledAt }}</template>
        </el-table-column>
        <el-table-column label="开奖号码" min-width="160">
          <template #default="{ row }">{{ displayResult(row.result) }}</template>
        </el-table-column>
        <el-table-column label="状态" min-width="110">
          <template #default="{ row }">{{ row.status || '已开奖' }}</template>
        </el-table-column>
      </el-table>
      <div class="draw-history-pagination">
        <span>显示{{ total }}个条目中的{{ startRow }}到{{ endRow }}</span>
        <div class="draw-history-pagination__buttons">
          <el-button :disabled="pageNo <= 1" @click="changePage(pageNo - 1)">上一页</el-button>
          <el-button :disabled="pageNo >= lastPage" @click="changePage(pageNo + 1)">下一页</el-button>
        </div>
      </div>
    </el-card>
    <el-dialog v-model="settleVisible" title="手动结算" width="720px" class="lucky-dialog">
      <el-form :model="settleForm" label-width="110px">
        <el-form-item label="期号">
          <el-input v-model="settleForm.period" placeholder="期号，例如：20200306127" />
        </el-form-item>
        <el-form-item label="开奖号码">
          <el-input v-model="settleForm.result" placeholder="开奖号码，例如：12345" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="settleVisible = false">取消</el-button>
        <el-button class="draw-history-confirm-button" :loading="store.saving" @click="submitSettle"
          >确定</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.draw-history-heading {
  display: block !important;
  margin: 0;
  padding: 15px 0 17px;
  color: #222;
  font-size: 24px;
  font-weight: 500;
  line-height: 1.1;
}

.draw-history-heading small {
  margin-left: 6px;
  color: #777;
  font-size: 15px;
  font-weight: 400;
}

.draw-history-source-status {
  margin: 0 15px 36px;
  color: #666;
  font-size: 14px;
  font-weight: 400;
  line-height: 1.9;
}

.draw-history-source-status p {
  margin: 0;
}

.legacy-list-box :deep(.el-card__body) {
  padding: 10px;
}

.draw-history-add-button {
  width: 44px;
  height: 36px;
  margin-bottom: 16px;
  color: #fff !important;
  background: #00c0ef !important;
  border-color: #00acd6 !important;
}

.draw-history-confirm-button {
  color: #fff !important;
  background: #00c0ef !important;
  border-color: #00acd6 !important;
}

.draw-history-filters {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 16px;
}

.draw-history-filters :deep(.el-input) {
  width: 166px;
}

.draw-history-filters :deep(.el-button) {
  min-height: 34px;
  color: #fff !important;
  background: #00c0ef !important;
  border-color: #00acd6 !important;
}

.draw-history-table-length {
  display: flex;
  align-items: center;
  height: 38px;
  gap: 8px;
  color: #333;
  font-size: 14px;
  font-weight: 600;
}

.draw-history-table-length :deep(.el-select) {
  width: 64px;
}

.draw-history-table-length :deep(.el-select__wrapper) {
  min-height: 30px;
}

.draw-history-table :deep(th.el-table__cell) {
  height: 40px;
  padding: 0;
  color: #333;
  font-size: 16px;
  font-weight: 600;
  background: #fff;
}

.draw-history-table :deep(td.el-table__cell) {
  height: 46px;
  padding: 0 8px;
  color: #444;
  font-size: 15px;
}

.draw-history-table :deep(.el-table__empty-block) {
  left: 0;
  width: 100% !important;
  transform: none;
}

.draw-history-table :deep(.el-table__empty-text) {
  width: 100%;
  padding-left: 10px;
  color: #555;
  text-align: left;
}

.draw-history-pagination {
  position: relative;
  display: flex;
  align-items: center;
  min-height: 54px;
  color: #555;
  font-size: 13px;
}

.draw-history-pagination__buttons {
  position: absolute;
  left: 50%;
  display: flex;
  gap: 4px;
  transform: translateX(-50%);
}

.draw-history-pagination__buttons :deep(.el-button) {
  min-height: 30px;
  margin: 0;
  border-radius: 3px;
}

@media (width <= 768px) {
  .draw-history-source-status {
    margin: 0 0 20px;
    font-size: 14px;
  }

  .draw-history-filters {
    align-items: stretch;
    flex-wrap: wrap;
  }

  .draw-history-filters :deep(.el-input) {
    width: 100%;
  }

  .draw-history-pagination__buttons {
    position: static;
    transform: none;
  }
}
</style>
