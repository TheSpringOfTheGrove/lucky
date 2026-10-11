<template>
  <div>
    <ContentWrap>
      <div class="mb-16px text-20px font-bold">代理统计</div>
      <div class="grid gap-12px sm:grid-cols-2 lg:grid-cols-4">
        <button v-if="summary?.admin" class="stat-card" type="button" @click="selectView('agents')">
          <span>代理账号数</span><strong>{{ summary.agentCount }}</strong>
          <small>查看代理及金币</small>
        </button>
        <div class="stat-card">
          <span>老板账号数</span><strong>{{ summary?.ownerCount ?? 0 }}</strong>
        </div>
        <button v-if="summary?.admin" class="stat-card" type="button" @click="selectView('recharge')">
          <span>金币充值数量</span><strong>{{ summary.rechargedGold }}</strong>
          <small>查看充值流水</small>
        </button>
        <button class="stat-card" type="button" @click="selectView('consumption')">
          <span>金币消耗数量</span><strong>{{ summary?.consumedGold ?? 0 }}</strong>
          <small>查看金币使用流水</small>
        </button>
      </div>
      <div v-if="summary && !summary.admin" class="mt-16px">当前可用金币：{{ summary.availableGold ?? 0 }}</div>
    </ContentWrap>

    <ContentWrap>
      <div class="mb-16px flex items-center justify-between">
        <span class="text-16px font-bold">{{ viewTitle }}</span>
        <el-button @click="refresh">刷新</el-button>
      </div>
      <el-table v-loading="loading" class="statistics-desktop-table" :data="rows" empty-text="暂无记录">
        <template v-if="activeView === 'agents'">
          <el-table-column prop="id" label="用户编号" min-width="100" />
          <el-table-column prop="username" label="登录账号" min-width="140" />
          <el-table-column prop="nickname" label="用户昵称" min-width="140" />
          <el-table-column prop="availableGold" label="可用金币" min-width="110" />
          <el-table-column label="创建时间" min-width="180">
            <template #default="scope">{{ formatDate(scope.row.createTime) }}</template>
          </el-table-column>
        </template>
        <template v-else>
          <el-table-column prop="id" label="流水编号" min-width="100" />
          <el-table-column label="时间" min-width="180">
            <template #default="scope">{{ formatDate(scope.row.createTime) }}</template>
          </el-table-column>
          <el-table-column v-if="summary?.admin || activeView === 'recharge'" prop="sourceAccount" label="来源账号" min-width="140" />
          <el-table-column prop="targetAccount" :label="activeView === 'recharge' ? '充值账号' : '老板账号'" min-width="140" />
          <el-table-column prop="amount" label="金币数量" min-width="110" />
          <el-table-column v-if="activeView === 'consumption'" prop="agentBalanceAfter" label="操作后代理余额" min-width="150">
            <template #default="scope">{{ scope.row.agentBalanceAfter ?? '无限' }}</template>
          </el-table-column>
          <el-table-column v-if="activeView === 'consumption'" label="老板到期时间" min-width="180">
            <template #default="scope">{{ formatDate(scope.row.ownerExpireAfter) }}</template>
          </el-table-column>
        </template>
      </el-table>
      <div v-loading="loading" class="statistics-mobile-list">
        <div v-if="rows.length === 0" class="py-30px text-center text-#909399">暂无记录</div>
        <div v-for="row in rows" :key="row.id" class="statistics-mobile-row">
          <template v-if="activeView === 'agents'">
            <div class="font-bold">{{ (row as StatisticsApi.AgentStatisticsAgent).username }}</div>
            <div>用户昵称：{{ (row as StatisticsApi.AgentStatisticsAgent).nickname }}</div>
            <div>可用金币：{{ (row as StatisticsApi.AgentStatisticsAgent).availableGold }}</div>
            <div>创建时间：{{ formatDate(row.createTime) }}</div>
          </template>
          <template v-else>
            <div class="font-bold">金币数量：{{ (row as StatisticsApi.AgentStatisticsLedger).amount }}</div>
            <div v-if="summary?.admin">来源账号：{{ (row as StatisticsApi.AgentStatisticsLedger).sourceAccount }}</div>
            <div>{{ activeView === 'recharge' ? '充值账号' : '老板账号' }}：{{ (row as StatisticsApi.AgentStatisticsLedger).targetAccount }}</div>
            <div v-if="activeView === 'consumption'">操作后代理余额：{{ (row as StatisticsApi.AgentStatisticsLedger).agentBalanceAfter ?? '无限' }}</div>
            <div v-if="activeView === 'consumption'">老板到期时间：{{ formatDate((row as StatisticsApi.AgentStatisticsLedger).ownerExpireAfter) }}</div>
            <div>时间：{{ formatDate(row.createTime) }}</div>
          </template>
        </div>
      </div>
      <Pagination v-model:page="page.pageNo" v-model:limit="page.pageSize" :total="total" @pagination="loadRows" />
    </ContentWrap>
  </div>
</template>

<script setup lang="ts">
import * as StatisticsApi from '@/api/system/agentStatistics'
import { formatDate } from '@/utils/formatTime'

defineOptions({ name: 'SystemAgentStatistics' })

type View = 'agents' | 'recharge' | 'consumption'
const summary = ref<StatisticsApi.AgentStatisticsSummary | null>(null)
const activeView = ref<View>('consumption')
const page = reactive({ pageNo: 1, pageSize: 10 })
const rows = ref<(StatisticsApi.AgentStatisticsAgent | StatisticsApi.AgentStatisticsLedger)[]>([])
const total = ref(0)
const loading = ref(false)
const viewTitle = computed(() => ({ agents: '代理账号列表', recharge: '金币充值列表', consumption: '金币使用列表' })[activeView.value])

const loadRows = async () => {
  loading.value = true
  try {
    const result = activeView.value === 'agents'
      ? await StatisticsApi.getAgents(page)
      : await StatisticsApi.getLedger(activeView.value === 'recharge' ? 'RECHARGE' : 'CONSUMPTION', page)
    rows.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

const selectView = (view: View) => {
  activeView.value = view
  page.pageNo = 1
  void loadRows()
}

const refresh = async () => {
  summary.value = await StatisticsApi.getSummary()
  await loadRows()
}

onMounted(() => void refresh())
onActivated(() => {
  if (summary.value) void refresh()
})
</script>

<style scoped>
.stat-card {
  min-height: 112px;
  padding: 16px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  background: #fff;
  color: #303133;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  text-align: left;
}
.stat-card strong { font-size: 28px; line-height: 1.3; }
.stat-card small { color: #409eff; }
button.stat-card:hover { border-color: #409eff; cursor: pointer; }
.statistics-mobile-list { display: none; }
.statistics-mobile-row { padding: 12px 0; border-bottom: 1px solid #ebeef5; overflow-wrap: anywhere; }
.statistics-mobile-row > div + div { margin-top: 5px; }
@media (max-width: 767px) {
  .statistics-desktop-table { display: none; }
  .statistics-mobile-list { display: block; }
  .stat-card { min-height: 88px; }
}
</style>
