<script setup lang="ts">
import { useMediaQuery } from '@vueuse/core'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import {
  getOrderItemsApi,
  getOrdersApi,
  reviewMarketOrderApi,
  type LotteryOrderPageParams
} from '@/api/lottery'
import { useLucky5Store } from '@/store/modules/lottery'

const store = useLucky5Store()
const nickname = ref('')
const period = ref('')
const orderType = ref('')
const refreshing = ref(false)
const rows = ref<any[]>([])
const pageNo = ref(1)
const pageSize = ref(20)
const total = ref(0)
const detailVisible = ref(false)
const detailOrder = ref<any>(null)
const detailItems = ref<any[]>([])
const detailLoading = ref(false)
const detailPageNo = ref(1)
const detailPageSize = ref(20)
const detailTotal = ref(0)
const cancellingId = ref('')
const reviewVisible = ref(false)
const reviewingId = ref('')
const reviewOrder = ref<any>(null)
const reviewDecision = ref<'ACCEPTED' | 'NOT_ACCEPTED'>('ACCEPTED')
const reviewExternalOrderId = ref('')
const reviewReason = ref('')
const isMobile = useMediaQuery('(max-width: 600px)')
const detailDescriptionColumns = computed(() => (isMobile.value ? 1 : 3))
const startRow = computed(() => (total.value ? (pageNo.value - 1) * pageSize.value + 1 : 0))
const endRow = computed(() => Math.min(pageNo.value * pageSize.value, total.value))
const detailStartRow = computed(() =>
  detailTotal.value ? (detailPageNo.value - 1) * detailPageSize.value + 1 : 0
)
const detailEndRow = computed(() =>
  Math.min(detailPageNo.value * detailPageSize.value, detailTotal.value)
)

const orderQuery = (): LotteryOrderPageParams => ({
  pageNo: pageNo.value,
  pageSize: pageSize.value,
  nickname: nickname.value.trim() || undefined,
  period: period.value.trim() || undefined,
  orderType: orderType.value || undefined
})

const loadOrders = async () => {
  if (refreshing.value) return
  refreshing.value = true
  try {
    const result = await getOrdersApi(orderQuery())
    rows.value = result.list || []
    total.value = Number(result.total || 0)
  } finally {
    refreshing.value = false
  }
}

const search = () => {
  pageNo.value = 1
  void loadOrders()
}

const changePage = (value: number) => {
  pageNo.value = value
  void loadOrders()
}

const changePageSize = (value: number) => {
  pageSize.value = value
  pageNo.value = 1
  void loadOrders()
}

onMounted(() => void loadOrders())

const normalizedOrderType = (item: any) =>
  item?.orderType === 'AUTO_PROXY' || item?.autoProxy === true ? 'AUTO_PROXY' : 'PLAYER'

const loadDetailItems = async () => {
  if (!detailOrder.value?.id || detailLoading.value) return
  detailLoading.value = true
  try {
    const result = await getOrderItemsApi(
      detailOrder.value.id,
      detailPageNo.value,
      detailPageSize.value
    )
    detailOrder.value = { ...detailOrder.value, ...(result.order || {}) }
    detailItems.value = result.list || []
    detailTotal.value = Number(result.total || 0)
  } finally {
    detailLoading.value = false
  }
}

const openDetails = async (row: any) => {
  detailOrder.value = row
  detailItems.value = []
  detailPageNo.value = 1
  detailTotal.value = Number(row.itemCount || 0)
  detailVisible.value = true
  await loadDetailItems()
}

const changeDetailPage = (value: number) => {
  detailPageNo.value = value
  void loadDetailItems()
}

const changeDetailPageSize = (value: number) => {
  detailPageSize.value = value
  detailPageNo.value = 1
  void loadDetailItems()
}

const detailRowClassName = ({ row }: { row: any }) => {
  if (row.won === true) return 'order-detail-row--won'
  if (row.won === false) return 'order-detail-row--lost'
  return ''
}

type OrderStatusTagType = 'primary' | 'success' | 'warning' | 'info' | 'danger'

const statusTagType = (status: string): OrderStatusTagType => {
  if (status === '未开奖') return 'warning'
  if (status === '已中奖') return 'danger'
  if (status === '未中奖') return 'success'
  if (status === '已退码') return 'info'
  if (status === '盘口提交中' || status === '退码处理中') return 'warning'
  if (status === '盘口待核对' || status === '退码待核对') return 'danger'
  if (status.includes('异常')) return 'danger'
  return 'primary'
}

const displayStatus = (row: any) => {
  if (row.status !== '未开奖') return row.status
  if (['PENDING', 'SUBMITTING', 'RETRY'].includes(row.marketStatus)) return '盘口提交中'
  if (row.marketStatus === 'VERIFYING') return '盘口确认中'
  if (row.marketStatus === 'MANUAL_REVIEW') return '盘口待核对'
  if (['CANCEL_REQUESTED', 'CANCEL_PENDING'].includes(row.marketStatus)) return '退码处理中'
  if (row.marketStatus === 'CANCEL_FAILED') return '退码待核对'
  return row.status
}

const canCancel = (row: any) => row.cancelable === true
const canReview = (row: any) => row.status === '未开奖' && row.marketStatus === 'MANUAL_REVIEW'

const openMarketReview = (row: any) => {
  reviewOrder.value = row
  reviewDecision.value = 'ACCEPTED'
  reviewExternalOrderId.value = ''
  reviewReason.value = ''
  reviewVisible.value = true
}

const submitMarketReview = async () => {
  const row = reviewOrder.value
  const reason = reviewReason.value.trim()
  const externalOrderId = reviewExternalOrderId.value.trim()
  if (!row || reviewingId.value) return
  if (!reason) {
    ElMessage.warning('请填写核对依据')
    return
  }
  if (reviewDecision.value === 'ACCEPTED' && !externalOrderId) {
    ElMessage.warning('请填写盘口注单编号')
    return
  }
  const accepted = reviewDecision.value === 'ACCEPTED'
  const warning = accepted
    ? '确认后系统会把该订单视为盘口已全部受理，并继续等待结算；系统不会重新下注。'
    : `仅当你已在盘口确认完全没有这笔订单时才可继续。确认后将立即退回 ${row.amount} 积分，且不会自动重投。`
  try {
    await ElMessageBox.confirm(warning, accepted ? '确认盘口已受理' : '确认盘口未受理并退款', {
      confirmButtonText: accepted ? '确认已受理' : '确认未受理并退款',
      cancelButtonText: '取消',
      type: accepted ? 'warning' : 'error'
    })
  } catch {
    return
  }
  reviewingId.value = row.id
  try {
    await reviewMarketOrderApi(row.id, {
      decision: reviewDecision.value,
      externalOrderId: accepted ? externalOrderId : undefined,
      reason
    })
    ElMessage.success(accepted ? '已记录为盘口受理成功' : '已确认未受理并完成退款')
    reviewVisible.value = false
    await loadOrders()
  } finally {
    reviewingId.value = ''
  }
}

const cancelOrder = async (row: any) => {
  if (!canCancel(row) || cancellingId.value) return
  try {
    await ElMessageBox.confirm(
      `确定退回会员“${row.member}”的这笔订单吗？退码后将返还 ${row.amount} 积分，且不可撤销。`,
      '确认退码',
      {
        confirmButtonText: '确认退码',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
  } catch {
    return
  }
  cancellingId.value = row.id
  try {
    const result = await store.cancelOrder(row.id)
    if (result) {
      await loadOrders()
      if (detailOrder.value?.id === row.id) detailVisible.value = false
    }
  } finally {
    cancellingId.value = ''
  }
}
</script>

<template>
  <div class="lucky-page">
    <h1 class="lucky-page__heading">订单查询 <small>历史订单查询</small></h1>
    <div class="lucky-toolbar">
      <div class="lucky-toolbar__filters">
        <el-input v-model="nickname" clearable placeholder="昵称" />
        <el-input v-model="period" clearable placeholder="期数" />
        <el-select v-model="orderType" clearable placeholder="订单类型" class="order-type-select">
          <el-option label="真实玩家" value="PLAYER" />
          <el-option label="自动托" value="AUTO_PROXY" />
        </el-select>
        <el-button type="primary" :loading="refreshing" @click="search">搜索</el-button>
      </div>
    </div>
    <el-card v-loading="refreshing" shadow="never">
      <div v-if="isMobile" class="order-mobile-list">
        <article v-for="row in rows" :key="row.id" class="lucky-mobile-card order-mobile-card">
          <div class="lucky-mobile-card__title">
            <span>{{ row.period }}</span>
            <span>{{ row.amount }} 分</span>
          </div>
          <div class="lucky-mobile-card__content">
            <el-link type="primary" :underline="false" @click="openDetails(row)">
              {{ row.content }}
            </el-link>
          </div>
          <div class="lucky-mobile-card__meta">
            <span>会员：{{ row.member }}</span>
            <el-tag :type="statusTagType(displayStatus(row))" size="small">
              {{ displayStatus(row) }}
            </el-tag>
            <span>{{ normalizedOrderType(row) === 'AUTO_PROXY' ? '自动托' : '真实玩家' }}</span>
          </div>
          <div v-if="canCancel(row) || canReview(row)" class="order-mobile-actions">
            <el-button
              type="danger"
              plain
              size="small"
              :loading="cancellingId === row.id"
              @click.stop="cancelOrder(row)"
            >
              退码
            </el-button>
            <el-button
              v-if="canReview(row)"
              type="warning"
              plain
              size="small"
              @click.stop="openMarketReview(row)"
            >
              核对处理
            </el-button>
          </div>
        </article>
        <el-empty v-if="!rows.length" description="暂无数据" :image-size="64" />
      </div>
      <el-table v-else :key="orderType || 'ALL'" :data="rows" row-key="id" border>
        <el-table-column prop="period" label="期号" min-width="150" />
        <el-table-column label="文本" min-width="240">
          <template #default="{ row }">
            <el-link
              class="order-content-link"
              type="primary"
              :underline="false"
              @click="openDetails(row)"
            >
              {{ row.content }}
            </el-link>
          </template>
        </el-table-column>
        <el-table-column prop="member" label="会员" min-width="120" />
        <el-table-column prop="amount" label="总金额" min-width="110" />
        <el-table-column label="状态" min-width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(displayStatus(row))">{{ displayStatus(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="类型" min-width="110">
          <template #default="{ row }">
            <el-tag v-if="normalizedOrderType(row) === 'AUTO_PROXY'" type="warning">自动托</el-tag>
            <el-tag v-else type="success">真实玩家</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="来源" min-width="100">
          <template #default>网页</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="200" />
        <el-table-column label="操作" width="110" fixed="right" align="center">
          <template #default="{ row }">
            <el-button
              v-if="canCancel(row)"
              type="danger"
              link
              :loading="cancellingId === row.id"
              @click="cancelOrder(row)"
            >
              退码
            </el-button>
            <el-button v-else-if="canReview(row)" type="warning" link @click="openMarketReview(row)">
              核对处理
            </el-button>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="order-pagination">
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

    <el-dialog
      v-model="detailVisible"
      :title="`${detailOrder?.period || ''} 订单详情`"
      :width="isMobile ? 'calc(100vw - 16px)' : '820px'"
      class="lucky-dialog order-detail-dialog"
      destroy-on-close
    >
      <el-descriptions
        v-if="detailOrder"
        :column="detailDescriptionColumns"
        border
        class="order-detail-summary mb-16px"
      >
        <el-descriptions-item label="期号">{{ detailOrder.period }}</el-descriptions-item>
        <el-descriptions-item label="会员">{{ detailOrder.member }}</el-descriptions-item>
        <el-descriptions-item label="总金额">{{ detailOrder.amount }}</el-descriptions-item>
        <el-descriptions-item label="开奖号码">
          {{ detailOrder.drawResult || '待开奖' }}
        </el-descriptions-item>
        <el-descriptions-item v-if="!isMobile" label="类型">
          {{ normalizedOrderType(detailOrder) === 'AUTO_PROXY' ? '自动托' : '真实玩家' }}
        </el-descriptions-item>
        <el-descriptions-item v-if="!isMobile" label="来源">网页</el-descriptions-item>
      </el-descriptions>
      <div v-if="detailOrder" class="order-detail-content">
        <strong>原始文本</strong>
        <div>{{ detailOrder.content }}</div>
      </div>
      <el-table
        v-loading="detailLoading"
        :data="detailItems"
        border
        max-height="420"
        :row-class-name="detailRowClassName"
      >
        <el-table-column prop="play" label="玩法" :min-width="isMobile ? 72 : 100" />
        <el-table-column prop="selection" label="选项" :min-width="isMobile ? 74 : 90" />
        <el-table-column prop="amount" label="金额" :min-width="isMobile ? 56 : 90" />
        <el-table-column v-if="!isMobile" prop="odds" label="赔率" min-width="90" />
        <el-table-column label="结果" :min-width="isMobile ? 76 : 90">
          <template #default="scope">
            <el-tag v-if="scope.row.won === true" type="danger" effect="dark">中奖</el-tag>
            <el-tag v-else-if="scope.row.won === false" type="success" effect="plain"
              >未中奖</el-tag
            >
            <el-tag v-else type="info" effect="plain">待开奖</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="!isMobile" prop="payout" label="派彩" min-width="90" />
      </el-table>
      <div class="order-pagination order-detail-pagination">
        <span>显示第 {{ detailStartRow }} 到 {{ detailEndRow }} 条，共 {{ detailTotal }} 条</span>
        <el-pagination
          :current-page="detailPageNo"
          :page-size="detailPageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="detailTotal"
          :layout="isMobile ? 'prev, pager, next' : 'sizes, prev, pager, next, jumper'"
          :pager-count="isMobile ? 3 : 7"
          :small="isMobile"
          background
          @current-change="changeDetailPage"
          @size-change="changeDetailPageSize"
        />
      </div>
    </el-dialog>

    <el-dialog
      v-model="reviewVisible"
      title="盘口待核对处理"
      :width="isMobile ? 'calc(100vw - 16px)' : '560px'"
      class="lucky-dialog"
      destroy-on-close
    >
      <el-alert
        title="此操作只修正本地订单状态，不会向盘口重新下注。请先在盘口按期号、会员、金额和注数核对。"
        type="warning"
        :closable="false"
        show-icon
        class="mb-16px"
      />
      <el-descriptions v-if="reviewOrder" :column="1" border class="mb-16px">
        <el-descriptions-item label="期号">{{ reviewOrder.period }}</el-descriptions-item>
        <el-descriptions-item label="会员">{{ reviewOrder.member }}</el-descriptions-item>
        <el-descriptions-item label="金额 / 注数">
          {{ reviewOrder.amount }} / {{ reviewOrder.itemCount }} 注
        </el-descriptions-item>
        <el-descriptions-item label="异常原因">
          {{ reviewOrder.marketError || '盘口结果不确定' }}
        </el-descriptions-item>
      </el-descriptions>
      <el-form label-position="top">
        <el-form-item label="核对结果" required>
          <el-radio-group v-model="reviewDecision">
            <el-radio value="ACCEPTED">盘口已全部受理</el-radio>
            <el-radio value="NOT_ACCEPTED">盘口完全未受理</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="reviewDecision === 'ACCEPTED'" label="盘口注单编号" required>
          <el-input
            v-model="reviewExternalOrderId"
            maxlength="100"
            placeholder="填写盘口明细中用于退码的注单编号"
          />
        </el-form-item>
        <el-form-item label="核对依据" required>
          <el-input
            v-model="reviewReason"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="例如：已在盘口该期总货明细核对，注数与金额一致"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button
          :type="reviewDecision === 'ACCEPTED' ? 'warning' : 'danger'"
          :loading="reviewingId === reviewOrder?.id"
          @click="submitMarketReview"
        >
          {{ reviewDecision === 'ACCEPTED' ? '确认已受理' : '确认未受理并退款' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.order-content-link {
  display: inline-flex;
  max-width: 100%;
  text-align: left;
  overflow-wrap: anywhere;
  white-space: normal;
}

.order-detail-content {
  display: grid;
  padding: 12px 14px;
  margin-bottom: 16px;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-light);
  border-radius: 4px;
  gap: 8px;
}

:deep(.el-table__body tr.order-detail-row--won > td.el-table__cell) {
  background: var(--el-color-danger-light-9);
}

:deep(.el-table__body tr.order-detail-row--lost > td.el-table__cell) {
  background: var(--el-color-success-light-9);
}

.order-type-select {
  width: 140px;
}

.order-mobile-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 10px;
}

.order-mobile-list {
  display: grid;
  gap: 10px;
}

.order-mobile-card {
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
}

.order-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 16px;
}

@media (width <= 600px) {
  .order-pagination {
    align-items: stretch;
    flex-direction: column;
    gap: 10px;
    font-size: 12px;
  }

  .order-pagination :deep(.el-pagination) {
    justify-content: center;
  }

  :deep(.order-detail-dialog) {
    margin-top: 4vh;
  }

  :deep(.order-detail-dialog .el-dialog__header) {
    padding: 14px 16px 10px;
  }

  :deep(.order-detail-dialog .el-dialog__body) {
    padding: 10px 12px 16px;
  }

  :deep(.order-detail-summary .el-descriptions__label.el-descriptions__cell) {
    width: 86px;
  }

  :deep(.order-detail-dialog .el-table .cell) {
    padding: 0 6px;
  }
}
</style>
