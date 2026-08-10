<script setup lang="ts">
import { useMediaQuery } from '@vueuse/core'
import { computed, onMounted, ref } from 'vue'
import { useLucky5Store } from '@/store/modules/lottery'

const store = useLucky5Store()
const nickname = ref('')
const period = ref('')
const orderType = ref('')
const refreshing = ref(false)
const detailVisible = ref(false)
const detailOrder = ref<any>(null)
const isMobile = useMediaQuery('(max-width: 600px)')
const detailDescriptionColumns = computed(() => (isMobile.value ? 1 : 3))

const refreshOrders = async () => {
  if (refreshing.value) return
  refreshing.value = true
  try {
    await store.refreshOrders()
  } finally {
    refreshing.value = false
  }
}

onMounted(() => void refreshOrders())

const rows = computed(() => {
  const name = nickname.value.trim()
  const periodValue = period.value.trim()
  return store.orders.filter(
    (item) =>
      (!name || item.member.includes(name)) && (!periodValue || item.period.includes(periodValue))
      && (!orderType.value || item.orderType === orderType.value)
  )
})
const detailItems = computed(() =>
  [...(detailOrder.value?.items || [])].sort(
    (left, right) => Number(right.won === true) - Number(left.won === true)
  )
)

const openDetails = (row: any) => {
  detailOrder.value = row
  detailVisible.value = true
}

const detailRowClassName = ({ row }: { row: any }) => {
  if (row.won === true) return 'order-detail-row--won'
  if (row.won === false) return 'order-detail-row--lost'
  return ''
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
        <el-button type="primary" :loading="refreshing" @click="refreshOrders">搜索</el-button>
      </div>
    </div>
    <el-card shadow="never">
      <PaginatedTable :data="rows" border>
        <el-table-column prop="period" label="期号" min-width="150" />
        <el-table-column label="文本" min-width="240">
          <template #default="{ row }">
            <el-link class="order-content-link" type="primary" :underline="false" @click="openDetails(row)">
              {{ row.content }}
            </el-link>
          </template>
        </el-table-column>
        <el-table-column prop="member" label="会员" min-width="120" />
        <el-table-column prop="amount" label="总金额" min-width="110" />
        <el-table-column prop="status" label="状态" min-width="110" />
        <el-table-column label="类型" min-width="110">
          <template #default="{ row }">
            <el-tag v-if="row.orderType === 'AUTO_PROXY'" type="warning">自动托</el-tag>
            <el-tag v-else type="success">真实玩家</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="来源" min-width="100">
          <template #default>网页</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="200" />
      </PaginatedTable>
    </el-card>

    <el-dialog
      v-model="detailVisible"
      :title="`${detailOrder?.period || ''} 订单详情`"
      :width="isMobile ? 'calc(100vw - 16px)' : '820px'"
      class="order-detail-dialog"
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
          {{ detailOrder.orderType === 'AUTO_PROXY' ? '自动托' : '真实玩家' }}
        </el-descriptions-item>
        <el-descriptions-item v-if="!isMobile" label="来源">网页</el-descriptions-item>
      </el-descriptions>
      <div v-if="detailOrder" class="order-detail-content">
        <strong>原始文本</strong>
        <div>{{ detailOrder.content }}</div>
      </div>
      <el-table :data="detailItems" border max-height="420" :row-class-name="detailRowClassName">
        <el-table-column prop="play" label="玩法" :min-width="isMobile ? 72 : 100" />
        <el-table-column prop="selection" label="选项" :min-width="isMobile ? 74 : 90" />
        <el-table-column prop="amount" label="金额" :min-width="isMobile ? 56 : 90" />
        <el-table-column v-if="!isMobile" prop="odds" label="赔率" min-width="90" />
        <el-table-column label="结果" :min-width="isMobile ? 76 : 90">
          <template #default="scope">
            <el-tag v-if="scope.row.won === true" type="danger" effect="dark">中奖</el-tag>
            <el-tag v-else-if="scope.row.won === false" type="success" effect="plain">未中奖</el-tag>
            <el-tag v-else type="info" effect="plain">待开奖</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="!isMobile" prop="payout" label="派彩" min-width="90" />
      </el-table>
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

@media (max-width: 600px) {
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
