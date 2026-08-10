<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, watch } from 'vue'
import { useLucky5Store } from '@/store/modules/lottery'

const store = useLucky5Store()
const form = reactive({
  url: '',
  account: '',
  password: '',
  alertValue: 0,
  bossMode: true,
  playType: 2,
  useProxy: true
})

watch(
  () => store.config,
  (value) =>
    Object.assign(form, value, {
      password: value.password || ''
    }),
  { deep: true, immediate: true }
)

const connection = computed(() => store.market.connection)
const statusType = computed(() => {
  if (connection.value.status === '已连接' || connection.value.status === '模拟已连接') return 'success'
  if (connection.value.status === '连接失败') return 'danger'
  return 'info'
})
const balanceText = computed(() =>
  connection.value.balance === null || connection.value.balance === undefined
    ? '--'
    : Number(connection.value.balance).toFixed(2)
)
const balanceLabel = computed(() =>
  connection.value.mode === 'SIMULATED' ? '模拟余额' : '盘口只读余额'
)
const recentRoutes = computed(() => connection.value.recentRoutes || [])
const routeLabel = (value: string) =>
  ({ LOCAL_EAT: '本地吃入', SIMULATED_MARKET: '模拟上盘', MIXED: '拆分' })[value] || value
const routeStatus = (value: string) =>
  ({ CONFIRMED: '模拟已接单', SETTLED: '已结算', REFUNDED: '已退码' })[value] || value
const passwordPlaceholder = computed(() => (store.config.hasPassword ? '已设置' : '请输入盘口密码'))

let balanceRefreshTimer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  if (connection.value.mode === 'SIMULATED') void store.refreshMarketConnection()
  balanceRefreshTimer = setInterval(() => void store.refreshMarketConnection(), 2_000)
})
onBeforeUnmount(() => {
  if (balanceRefreshTimer) clearInterval(balanceRefreshTimer)
})
</script>

<template>
  <div class="lucky-page">
    <el-card class="lucky-card" shadow="never">
      <template #header>
        <div class="config-header">
          <strong>配置信息</strong>
          <span class="lucky-config-balance">{{ balanceLabel }}：{{ balanceText }}</span>
          <el-tag :type="statusType">{{ connection.status }}</el-tag>
          <el-tag type="danger" effect="plain">真实盘口写入：永久关闭</el-tag>
        </div>
      </template>

      <el-form :model="form" class="lucky-original-form" label-width="150px">
        <el-alert
          v-if="!form.bossMode"
          class="simulation-alert"
          title="当前为模拟网盘测试模式：下注、退码和派奖只操作本地模拟账户，绝不会提交真实盘口。"
          type="warning"
          :closable="false"
          show-icon
        />
        <el-form-item label="会员网址">
          <el-input v-model="form.url" placeholder="请输入盘口会员网址" />
        </el-form-item>
        <el-form-item label="会员用户名">
          <el-input v-model="form.account" placeholder="请输入盘口用户名" autocomplete="off" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="passwordPlaceholder"
            autocomplete="new-password"
          />
        </el-form-item>
        <el-form-item label="报警值">
          <el-input-number v-model="form.alertValue" :controls="false" placeholder="报警值" />
        </el-form-item>
        <el-form-item label="运行模式">
          <el-select v-model="form.bossMode">
            <el-option label="老板模式（全部本地）" :value="true" />
            <el-option label="模拟网盘（测试专用）" :value="false" />
          </el-select>
        </el-form-item>
        <el-form-item label="玩法">
          <el-select v-model="form.playType">
            <el-option label="普通" :value="0" />
            <el-option label="龙虎和" :value="1" />
            <el-option label="普通+龙虎和" :value="2" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="form.useProxy">使用代理</el-checkbox>
          <el-button
            type="primary"
            class="ml-12px"
            :loading="store.saving"
            @click="store.saveConfig(form)"
          >
            保存
          </el-button>
        </el-form-item>
      </el-form>

      <template v-if="connection.mode === 'SIMULATED'">
        <el-divider content-position="left">模拟网盘测试情况</el-divider>
        <el-descriptions class="simulation-summary" :column="4" border>
          <el-descriptions-item label="初始余额">
            {{ Number(connection.initialBalance || 0).toFixed(2) }}
          </el-descriptions-item>
          <el-descriptions-item label="累计模拟上盘">
            {{ Number(connection.totalStake || 0).toFixed(2) }}
          </el-descriptions-item>
          <el-descriptions-item label="累计模拟派彩">
            {{ Number(connection.totalPayout || 0).toFixed(2) }}
          </el-descriptions-item>
          <el-descriptions-item label="累计模拟退码">
            {{ Number(connection.totalRefund || 0).toFixed(2) }}
          </el-descriptions-item>
        </el-descriptions>
        <div v-if="recentRoutes.length" class="simulation-routes">
          <strong>最近模拟网盘明细</strong>
          <el-table :data="recentRoutes" border class="simulation-route-table mt-12px">
            <el-table-column prop="period" label="期号" min-width="150" />
            <el-table-column prop="play" label="玩法" min-width="100" />
            <el-table-column prop="selection" label="选项" min-width="100" />
            <el-table-column label="流向" min-width="110">
              <template #default="{ row }">{{ routeLabel(row.routeType) }}</template>
            </el-table-column>
            <el-table-column label="本地吃入" min-width="100">
              <template #default="{ row }">{{ Number(row.localAmount || 0).toFixed(2) }}</template>
            </el-table-column>
            <el-table-column label="模拟上盘" min-width="100">
              <template #default="{ row }">{{ Number(row.simulatedAmount || 0).toFixed(2) }}</template>
            </el-table-column>
            <el-table-column label="状态" min-width="110">
              <template #default="{ row }">{{ routeStatus(row.status) }}</template>
            </el-table-column>
          </el-table>
          <div class="simulation-route-cards">
            <div v-for="row in recentRoutes" :key="row.id" class="simulation-route-card">
              <div class="simulation-route-card__title">
                <strong>{{ row.period }}</strong>
                <el-tag size="small">{{ routeStatus(row.status) }}</el-tag>
              </div>
              <div>{{ row.play }} · {{ row.selection }}</div>
              <div>流向：{{ routeLabel(row.routeType) }}</div>
              <div>
                本地吃入 {{ Number(row.localAmount || 0).toFixed(2) }} / 模拟上盘
                {{ Number(row.simulatedAmount || 0).toFixed(2) }}
              </div>
            </div>
          </div>
        </div>
      </template>
    </el-card>
  </div>
</template>

<style scoped>
.config-header {
  display: flex;
  align-items: center;
  gap: 20px;
}

.lucky-config-balance {
  margin-left: 60px;
  color: var(--el-color-danger);
}

.error-text {
  color: var(--el-color-danger);
}

.simulation-alert {
  margin-bottom: 20px;
}

.simulation-routes {
  margin-top: 20px;
}

.simulation-route-cards {
  display: none;
}

@media (width <= 768px) {
  .config-header {
    flex-wrap: wrap;
    gap: 8px 12px;
  }

  .config-header strong {
    flex-basis: 100%;
  }

  .lucky-config-balance {
    margin-left: 0;
  }

  .simulation-summary :deep(.el-descriptions__body) {
    overflow: hidden;
  }

  .simulation-summary :deep(.el-descriptions__table) {
    display: block;
  }

  .simulation-summary :deep(tbody),
  .simulation-summary :deep(tr) {
    display: block;
  }

  .simulation-summary :deep(td) {
    display: grid;
    grid-template-columns: 110px 1fr;
    width: 100%;
  }

  .simulation-route-table {
    display: none;
  }

  .simulation-route-cards {
    display: grid;
    gap: 10px;
    margin-top: 12px;
  }

  .simulation-route-card {
    display: grid;
    gap: 6px;
    padding: 12px;
    border: 1px solid var(--el-border-color);
    border-radius: 6px;
    color: var(--el-text-color-regular);
  }

  .simulation-route-card__title {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
  }
}
</style>
