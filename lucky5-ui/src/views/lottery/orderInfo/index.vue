<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useLucky5Store } from '@/store/modules/lottery'

const store = useLucky5Store()
const nickname = ref('')
const period = ref('')
const orderType = ref('')
const refreshing = ref(false)

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

const cancel = async (row: any) => {
  await ElMessageBox.confirm(`确认退回 ${row.member} 的 ${row.amount} 分？`, '退码确认', {
    type: 'warning'
  })
  await store.cancelOrder(row.id)
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
        <el-table-column type="expand" width="48">
          <template #default="{ row }">
            <el-table :data="row.items || []" border class="order-item-table">
              <el-table-column prop="play" label="玩法" min-width="100" />
              <el-table-column prop="selection" label="选项" min-width="90" />
              <el-table-column prop="amount" label="金额" min-width="90" />
              <el-table-column prop="odds" label="赔率" min-width="90" />
              <el-table-column label="结果" min-width="90">
                <template #default="scope">
                  {{ scope.row.won === null ? '待开奖' : scope.row.won ? '中' : '未中' }}
                </template>
              </el-table-column>
              <el-table-column prop="payout" label="派彩" min-width="90" />
            </el-table>
          </template>
        </el-table-column>
        <el-table-column prop="period" label="期号" min-width="150" />
        <el-table-column prop="content" label="文本" min-width="240" />
        <el-table-column prop="member" label="会员" min-width="120" />
        <el-table-column prop="amount" label="总金额" min-width="110" />
        <el-table-column prop="status" label="状态" min-width="110" />
        <el-table-column label="类型" min-width="110">
          <template #default="{ row }">
            <el-tag v-if="row.orderType === 'AUTO_PROXY'" type="warning">自动托</el-tag>
            <el-tag v-else type="success">真实玩家</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="来源" min-width="110"
          ><template #default="{ row }">{{ row.source || '网页群' }}</template></el-table-column
        >
        <el-table-column label="盘口状态" min-width="180">
          <template #default="{ row }">
            <div>{{
              row.orderType === 'AUTO_PROXY'
                ? '虚拟订单/永不提交网盘'
                : row.deliveryMode === 'LOCAL_ONLY'
                  ? '老板模式/本地吃单'
                  : row.marketStatus
            }}</div>
            <small v-if="row.marketError" class="lucky-danger">{{ row.marketError }}</small>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="200" />
        <el-table-column v-if="store.switches.openCancel" label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === '未开奖' && row.orderType !== 'AUTO_PROXY'"
              size="small"
              type="danger"
              @click="cancel(row)"
              >退</el-button
            >
            <span v-else>-</span>
          </template>
        </el-table-column>
      </PaginatedTable>
    </el-card>
  </div>
</template>

<style scoped>
.order-item-table {
  margin: 10px 18px;
  width: calc(100% - 36px) !important;
}

.order-type-select {
  width: 140px;
}
</style>
