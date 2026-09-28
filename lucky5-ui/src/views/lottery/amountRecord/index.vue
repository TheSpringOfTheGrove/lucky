<script setup lang="ts">
import LegacyTable from '@/components/LegacyTable'
import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  ref,
  watch
} from 'vue'
import { ElMessageBox } from 'element-plus'
import { getAmountRecordPageApi } from '@/api/lottery'
import { useLucky5Store } from '@/store/modules/lottery'
import { legacyFooterHeaders } from '@/views/lottery/utils/legacyTable'

const store = useLucky5Store()
const nickname = ref('')
const timeType = ref(1)
const refreshing = ref(false)
const page = ref(1)
const pageSize = ref(10)
const pageResult = ref<{ items: Record<string, any>[]; total: number; summary: Record<string, number> }>({
  items: [] as Record<string, any>[],
  total: 0,
  summary: { topup: 0, withdraw: 0, balance: 0 }
})
let refreshTimer: number | undefined

const refreshAmountRecords = async () => {
  if (refreshing.value) return
  refreshing.value = true
  try {
    pageResult.value = await getAmountRecordPageApi({
      pageNo: page.value,
      pageSize: pageSize.value,
      nickname: nickname.value.trim() || undefined,
      timeType: timeType.value
    })
  } finally {
    refreshing.value = false
  }
}

const startAutoRefresh = () => {
  if (refreshTimer) return
  void refreshAmountRecords()
  refreshTimer = window.setInterval(() => void refreshAmountRecords(), 5000)
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

const rows = computed(() => pageResult.value.items || [])
const total = computed(() => Number(pageResult.value.total || 0))
const firstItem = computed(() => (total.value ? (page.value - 1) * pageSize.value + 1 : 0))
const lastItem = computed(() => Math.min(page.value * pageSize.value, total.value))
const lastPage = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
const totalUp = computed(() => pageResult.value.summary?.topup || 0)
const totalDown = computed(() => pageResult.value.summary?.withdraw || 0)
const netAmount = computed(() => pageResult.value.summary?.balance || 0)
const legacyStatus = (status: string) =>
  ({ '待审核': '未确认', '已通过': '已确认', '已拒绝': '作废' })[status] || '作废'
const legacyAmount = (row: Record<string, any>) => {
  const amount = Number(row.amount || 0)
  return row.type === '下分' && amount > 0 ? -amount : amount
}

watch(pageSize, () => {
  page.value = 1
  void refreshAmountRecords()
})

watch(lastPage, (value) => {
  page.value = Math.min(page.value, value)
})

watch(page, () => void refreshAmountRecords())

const audit = async (row: any, status: '已通过' | '已拒绝') => {
  try {
    if (status === '已拒绝') {
      const { value } = await ElMessageBox.prompt('请输入拒绝原因', '拒绝申请', {
        inputValidator: (text) => Boolean(String(text || '').trim()) || '请输入拒绝原因'
      })
      await store.auditAmount(row.id, status, value)
      await refreshAmountRecords()
      return
    }
    await ElMessageBox.confirm(
      `确认通过 ${row.member} 的${row.type} ${row.amount} 分申请？`,
      '审核确认',
      { type: 'warning' }
    )
    await store.auditAmount(row.id, status)
    await refreshAmountRecords()
  } catch {
    // User cancelled.
  }
}

</script>

<template>
  <div class="lucky-page lucky-legacy-content">
    <div class="amount-record-heading">
      积分列表 <small>上下分审核</small>
      <span class="lucky-amount-total"
        >{{ totalUp }} - {{ totalDown }} = {{ netAmount }}</span
      >
    </div>
    <div class="lucky-toolbar amount-record-toolbar">
      <div class="lucky-toolbar__filters amount-record-toolbar__filters">
        <el-input v-model="nickname" clearable placeholder="昵称" />
        <el-select v-model="timeType">
          <el-option label="全部" :value="0" />
          <el-option label="今天" :value="1" />
          <el-option label="昨天" :value="2" />
          <el-option label="本周" :value="3" />
        </el-select>
        <el-button class="amount-record-search-button" :loading="refreshing" @click="page = 1; refreshAmountRecords()"
          >搜索</el-button
        >
      </div>
    </div>
    <el-card class="legacy-list-box" shadow="never">
      <LegacyTable
        :data="rows"
        border
        empty-text="No data available in table"
        class="amount-record-table"
        show-summary
        :summary-method="legacyFooterHeaders"
      >
        <el-table-column prop="member" label="昵称" min-width="140" />
        <el-table-column label="分数" min-width="100">
          <template #default="{ row }">{{ legacyAmount(row) }}</template>
        </el-table-column>
        <el-table-column prop="type" label="类型" min-width="100" />
        <el-table-column label="状态" min-width="110">
          <template #default="{ row }">{{ legacyStatus(row.status) }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="200" />
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <div v-if="row.status === '待审核'" class="lucky-table-actions">
              <el-button size="small" type="warning" @click="audit(row, '已通过')">通过</el-button>
              <el-button size="small" type="danger" @click="audit(row, '已拒绝')">拒绝</el-button>
            </div>
            <span v-else>{{ legacyStatus(row.status) }}</span>
          </template>
        </el-table-column>
      </LegacyTable>
      <div class="amount-record-footer">
        <span>显示{{ total }}个条目中的{{ firstItem }}到{{ lastItem }}</span>
        <div class="amount-record-footer__pager">
          <el-button :disabled="page <= 1" @click="page -= 1">上一页</el-button>
          <el-button :disabled="page >= lastPage" @click="page += 1">下一页</el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.amount-record-heading {
  display: block !important;
  margin: 0 0 24px;
  padding-top: 15px;
  color: #222;
  font-size: 24px;
  font-weight: 500;
  line-height: 1.1;
}

.amount-record-heading small {
  margin-left: 6px;
  color: #777;
  font-size: 15px;
  font-weight: 400;
}

.lucky-amount-total {
  display: inline-block;
  margin-left: 12px;
  font-size: 22px;
  font-weight: 600;
  line-height: 1;
  color: #f00;
  visibility: visible;
}

.legacy-list-box :deep(.el-card__body) {
  padding: 10px;
}

.amount-record-toolbar {
  margin-bottom: 34px;
}

.amount-record-toolbar__filters {
  gap: 26px !important;
}

.amount-record-toolbar__filters :deep(.el-input),
.amount-record-toolbar__filters :deep(.el-select) {
  width: 142px;
}

.amount-record-search-button {
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

.amount-record-search-button:hover,
.amount-record-search-button:focus {
  color: #fff !important;
  background: #00acd6 !important;
  border-color: #009abf !important;
}

.amount-record-table :deep(th.el-table__cell) {
  height: 40px;
  padding: 0;
  color: #333;
  font-size: 16px;
  font-weight: 600;
  background: #fff;
}

.amount-record-table :deep(td.el-table__cell) {
  height: 46px;
  padding: 0 8px;
  color: #444;
  font-size: 15px;
}

.amount-record-table :deep(.el-table__empty-block) {
  left: 0;
  width: 100% !important;
  transform: none;
}

.amount-record-table :deep(.el-table__empty-text) {
  width: 100%;
  padding-left: 10px;
  color: #555;
  text-align: left;
}

.amount-record-footer,
.amount-record-footer__pager {
  display: flex;
  align-items: center;
}

.amount-record-footer {
  position: relative;
  min-height: 54px;
  color: #555;
  font-size: 13px;
}

.amount-record-footer__pager {
  position: absolute;
  left: 50%;
  gap: 0;
  transform: translateX(-50%);
}

.amount-record-footer__pager :deep(.el-button) {
  min-height: 30px;
  margin: 0;
  border-radius: 3px;
}

.amount-record-footer__pager :deep(.el-button + .el-button) {
  margin-left: 4px;
}

@media (width <= 767px) {
  .amount-record-toolbar__filters {
    gap: 8px !important;
  }

  .amount-record-footer {
    align-items: flex-start;
    flex-direction: column;
    gap: 10px;
    padding-bottom: 40px;
  }

  .amount-record-footer__pager {
    bottom: 0;
    left: 0;
    transform: none;
  }
}
</style>
