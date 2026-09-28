<script setup lang="ts">
import LegacyTable from '@/components/LegacyTable'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useLucky5Store } from '@/store/modules/lottery'
import { legacyFooterHeaders } from '@/views/lottery/utils/legacyTable'

const store = useLucky5Store()
const puller = ref('')
const keyword = ref('')
const editVisible = ref(false)
const batchVisible = ref(false)
const listVersion = ref(0)
const page = ref(1)
const pageSize = ref(10)

const editForm = reactive<Record<string, any>>({
  id: '',
  name: '',
  normalRate: 0,
  lhhRate: 0,
  partner: '无',
  partnerNormalRate: 0,
  partnerLhhRate: 0,
  puller: false
})
const batchForm = reactive({ normalRate: 0, lhhRate: 0 })

const realMembers = computed(() =>
  store.members.filter((item) => item.memberType !== 'BOT' && !item.autoProxy)
)
const isPuller = (row: Record<string, any>) => row.isPuller === true || row.tag === '拉手'
const pullerMembers = computed(() => realMembers.value.filter(isPuller))
const pullers = computed(() => pullerMembers.value.map((item) => item.name))
const availablePullers = computed(() =>
  pullerMembers.value.filter((item) => item.id !== editForm.id)
)
const filteredRows = computed(() => {
  const search = keyword.value.trim().toLowerCase()
  return realMembers.value.filter((item) => {
    if (puller.value && item.partner !== puller.value) return false
    if (!search) return true
    return String(item.name || '').toLowerCase().includes(search)
  })
})
const total = computed(() => filteredRows.value.length)
const rows = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return filteredRows.value.slice(start, start + pageSize.value)
})
const startRow = computed(() => (total.value ? (page.value - 1) * pageSize.value + 1 : 0))
const endRow = computed(() => Math.min(page.value * pageSize.value, total.value))
const lastPage = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
const changePage = (value: number) => {
  page.value = Math.max(1, Math.min(value, lastPage.value))
}
const changePageSize = (value: number) => {
  pageSize.value = value
  page.value = 1
}
watch([puller, keyword], () => {
  page.value = 1
})
watch(lastPage, (value) => {
  page.value = Math.min(page.value, value)
})

const numberValue = (value: unknown) => Number(value || 0)
const money = (value: unknown) => numberValue(value).toFixed(2)
const rateSummary = (value: unknown) => money(value).replace(/\.00$/, '')
const rate = (value: unknown) => `${numberValue(value).toFixed(2).replace(/\.00$/, '')}%`
const ownRebate = (row: Record<string, any>) =>
  numberValue(row.normalRebate) + numberValue(row.dragonRebate)
const partnerRebate = (row: Record<string, any>) => numberValue(row.partnerRebate)
const rowTotal = (row: Record<string, any>) => ownRebate(row) + partnerRebate(row)

const normalTotal = computed(() =>
  realMembers.value.reduce((sum, row) => sum + numberValue(row.normalRebate), 0)
)
const dragonTotal = computed(() =>
  realMembers.value.reduce((sum, row) => sum + numberValue(row.dragonRebate), 0)
)
const pullerTotal = computed(() =>
  realMembers.value.reduce((sum, row) => sum + partnerRebate(row), 0)
)
const eatTotal = computed(() =>
  realMembers.value.reduce((sum, row) => sum + (row.eatEnabled ? ownRebate(row) : 0), 0)
)
const playerTotal = computed(() =>
  realMembers.value.reduce((sum, row) => sum + (!row.eatEnabled ? ownRebate(row) : 0), 0)
)
const totalRebate = computed(() => normalTotal.value + dragonTotal.value + pullerTotal.value)
const rateOptions = Array.from({ length: 101 }, (_, index) => Number((index / 10).toFixed(1)))
const rebuildList = () => {
  listVersion.value += 1
}

onMounted(() => void store.refreshRebateMembers())

const apply = async () => {
  try {
    await ElMessageBox.confirm(`确认发放返水 ${money(totalRebate.value)} 分？`, '一键返水', {
      type: 'warning'
    })
    const result = await store.applyRebates()
    if (result !== false) {
      await store.refreshRebateMembers()
      rebuildList()
    }
  } catch {
    // User cancelled.
  }
}

const openEdit = (row: Record<string, any>) => {
  Object.assign(editForm, {
    id: row.id,
    name: row.name,
    normalRate: numberValue(row.normalRate),
    lhhRate: numberValue(row.lhhRate),
    partner: row.partner && row.partner !== row.name ? row.partner : '无',
    partnerNormalRate: numberValue(row.partnerNormalRate),
    partnerLhhRate: numberValue(row.partnerLhhRate),
    puller: isPuller(row)
  })
  editVisible.value = true
}

const saveEdit = async () => {
  if (!editForm.partner || editForm.partner === '无') {
    editForm.partner = '无'
  }
  const result = await store.saveDiscounts([{ ...editForm }])
  if (result !== false) {
    await store.refreshRebateMembers()
    rebuildList()
    editVisible.value = false
  }
}

const togglePuller = async (row: Record<string, any>) => {
  const next = !isPuller(row)
  const action = next ? '设为拉手' : '取消拉手'
  try {
    await ElMessageBox.confirm(
      next ? `确认将「${row.name}」设为拉手？` : `确认取消「${row.name}」的拉手身份？其下级关系将一并解除。`,
      action,
      { type: 'warning' }
    )
    const result = await store.saveDiscounts([{ ...row, puller: next }])
    if (result !== false) {
      await store.refreshRebateMembers()
      rebuildList()
    }
  } catch {
    // User cancelled.
  }
}

const openBatch = () => {
  batchForm.normalRate = 0
  batchForm.lhhRate = 0
  batchVisible.value = true
}

const saveBatch = async () => {
  if (!filteredRows.value.length) {
    ElMessage.warning('当前没有可设置的会员')
    return
  }
  const payload = filteredRows.value.map((row) => ({
    ...row,
    normalRate: batchForm.normalRate,
    lhhRate: batchForm.lhhRate,
    puller: isPuller(row)
  }))
  const result = await store.saveDiscounts(payload)
  if (result !== false) {
    await store.refreshRebateMembers()
    rebuildList()
    batchVisible.value = false
  }
}
</script>

<template>
  <div class="lucky-page lucky-legacy-content rebate-page">
    <div class="rebate-heading">返水列表</div>
    <el-card class="legacy-list-box" shadow="never">
      <div class="rebate-toolbar">
        <div class="rebate-toolbar__actions">
          <el-button class="rebate-apply-button" @click="apply">一键返水</el-button>
          <el-button class="rebate-batch-button" @click="openBatch">一键设置</el-button>
        </div>
        <div class="rebate-summary">
          <span>返水合计：<strong>{{ rateSummary(totalRebate) }}</strong></span>
          <span>真实玩家：{{ rateSummary(playerTotal) }}</span>
          <span>吃：{{ rateSummary(eatTotal) }}</span>
          <span>普通：{{ rateSummary(normalTotal) }}</span>
          <span>龙虎：{{ rateSummary(dragonTotal) }}</span>
          <span>拉手：{{ rateSummary(pullerTotal) }}</span>
        </div>
      </div>

      <div class="lucky-toolbar__filters rebate-filters">
        <el-select v-model="puller" clearable placeholder="选择拉手">
          <el-option v-for="item in pullers" :key="item" :label="item" :value="item" />
        </el-select>
        <span class="rebate-search-label">搜索:</span>
        <el-input v-model="keyword" clearable />
      </div>

      <div class="rebate-table-length">
        <span>显示</span>
        <el-select v-model="pageSize" @change="changePageSize">
          <el-option :value="10" label="10" />
          <el-option :value="25" label="25" />
          <el-option :value="50" label="50" />
          <el-option :value="100" label="100" />
        </el-select>
        <span>条目</span>
      </div>
      <LegacyTable
        :key="listVersion"
        v-loading="store.rebateMembersRefreshing || store.saving"
        :data="rows"
        border
        class="rebate-table"
        empty-text="No data available in table"
        show-summary
        :summary-method="legacyFooterHeaders"
      >

        <el-table-column prop="name" label="昵称" min-width="110" />
        <el-table-column label="拉手设置" width="90" align="center">
          <template #default="{ row }">
            <el-tooltip :content="isPuller(row) ? '取消拉手' : '设为拉手'">
              <el-button class="rebate-puller-button" size="small" @click="togglePuller(row)">{{ isPuller(row) ? '取消' : '拉' }}</el-button>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="是否拉手" width="90" align="center">
          <template #default="{ row }">{{ isPuller(row) ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column label="幸运五比例" min-width="115" align="center">
          <template #default="{ row }">{{ rate(row.normalRate) }}</template>
        </el-table-column>
        <el-table-column label="返水金额" min-width="105" align="right">
          <template #default="{ row }">{{ money(row.normalRebate) }}</template>
        </el-table-column>
        <el-table-column label="龙虎比例" min-width="105" align="center">
          <template #default="{ row }">{{ rate(row.lhhRate) }}</template>
        </el-table-column>
        <el-table-column label="返水金额" min-width="105" align="right">
          <template #default="{ row }">{{ money(row.dragonRebate) }}</template>
        </el-table-column>
        <el-table-column label="拉手返水" min-width="105" align="right">
          <template #default="{ row }">{{ money(row.partnerRebate) }}</template>
        </el-table-column>
        <el-table-column label="合计返水" min-width="110" align="right">
          <template #default="{ row }">{{ money(rowTotal(row)) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right" align="center">
          <template #default="{ row }">
            <el-tooltip content="编辑">
              <el-button size="small" type="primary" circle @click="openEdit(row)">
                <Icon icon="fa:edit" :size="14" />
              </el-button>
            </el-tooltip>
          </template>
        </el-table-column>
      </LegacyTable>
      <div class="rebate-pagination">
        <span>显示{{ total }}个条目中的{{ startRow }}到{{ endRow }}</span>
        <div class="rebate-pagination__buttons">
          <el-button :disabled="page <= 1" @click="changePage(page - 1)">上一页</el-button>
          <el-button :disabled="page >= lastPage" @click="changePage(page + 1)">下一页</el-button>
        </div>
      </div>
    </el-card>

    <el-dialog v-model="editVisible" title="编辑会员" width="560px" class="lucky-dialog">
      <el-form :model="editForm" label-width="145px">
        <el-form-item label="昵称">
          <el-input v-model="editForm.name" disabled />
        </el-form-item>
        <el-form-item label="普通返水比例">
          <el-select v-model="editForm.normalRate">
            <el-option v-for="item in rateOptions" :key="item" :label="rate(item)" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="龙虎和返水比例">
          <el-select v-model="editForm.lhhRate">
            <el-option v-for="item in rateOptions" :key="item" :label="rate(item)" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="所属拉手">
          <el-select v-model="editForm.partner">
            <el-option label="未选择" value="无" />
            <el-option v-for="item in availablePullers" :key="item.id" :label="item.name" :value="item.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="拉手普通返水比例">
          <el-select v-model="editForm.partnerNormalRate">
            <el-option v-for="item in rateOptions" :key="item" :label="rate(item)" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="拉手龙虎返水比例">
          <el-select v-model="editForm.partnerLhhRate">
            <el-option v-for="item in rateOptions" :key="item" :label="rate(item)" :value="item" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="store.saving" @click="saveEdit">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="batchVisible" title="一键设置返水比例" width="480px" class="lucky-dialog">
      <el-alert title="将应用到当前筛选结果中的全部真实玩家" type="info" :closable="false" class="mb-16px" />
      <el-form :model="batchForm" label-width="125px">
        <el-form-item label="普通返水比例">
          <el-select v-model="batchForm.normalRate">
            <el-option v-for="item in rateOptions" :key="item" :label="rate(item)" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="龙虎和返水比例">
          <el-select v-model="batchForm.lhhRate">
            <el-option v-for="item in rateOptions" :key="item" :label="rate(item)" :value="item" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchVisible = false">取消</el-button>
        <el-button type="primary" :loading="store.saving" @click="saveBatch">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.rebate-heading {
  display: block !important;
  margin: 0 0 10px;
  padding-top: 15px;
  color: #222;
  font-size: 24px;
  font-weight: 500;
  line-height: 1.1;
}

.legacy-list-box :deep(.el-card__body) {
  padding: 10px;
}

.rebate-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-start;
  gap: 8px 14px;
  margin-bottom: 10px;
}

.rebate-toolbar__actions {
  display: flex;
  flex: 0 0 auto;
  gap: 8px;
}

.rebate-toolbar__actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.rebate-apply-button {
  min-height: 34px;
  color: #fff !important;
  background: #dd4b39 !important;
  border-color: #d73925 !important;
}

.rebate-batch-button {
  min-height: 34px;
  color: #fff !important;
  background: #3c8dbc !important;
  border-color: #367fa9 !important;
}

.rebate-puller-button {
  min-width: 28px;
  color: #fff !important;
  background: #3c8dbc !important;
  border-color: #367fa9 !important;
}

.rebate-puller-button:hover,
.rebate-puller-button:focus {
  color: #fff !important;
  background: #367fa9 !important;
  border-color: #204d74 !important;
}

.rebate-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-start;
  gap: 6px 20px;
  color: #555;
  font-size: 16px;
  line-height: 34px;
  text-align: left;
}

.rebate-summary strong {
  color: #555;
  font-weight: 600;
}

.rebate-filters {
  justify-content: flex-start;
  align-items: center;
  gap: 24px !important;
  margin: 0 0 10px;
}

.rebate-filters :deep(.el-select),
.rebate-filters :deep(.el-input) {
  width: 148px;
}

.rebate-search-label {
  margin-right: -18px;
  color: #333;
  font-size: 14px;
  font-weight: 600;
}

.rebate-table-length {
  display: flex;
  align-items: center;
  height: 38px;
  gap: 8px;
  color: #333;
  font-size: 14px;
  font-weight: 600;
}

.rebate-table-length :deep(.el-select) {
  width: 64px;
}

.rebate-table-length :deep(.el-select__wrapper) {
  min-height: 30px;
}

.rebate-table :deep(th.el-table__cell) {
  height: 40px;
  padding: 0;
  color: #333;
  font-size: 15px;
  font-weight: 600;
  background: #fff;
}

.rebate-table :deep(td.el-table__cell) {
  height: 46px;
  padding: 0 8px;
  color: #444;
  font-size: 14px;
}

.rebate-table :deep(.el-table__empty-block) {
  left: 0;
  width: 100% !important;
  transform: none;
}

.rebate-table :deep(.el-table__empty-text) {
  width: 100%;
  padding-left: 10px;
  color: #555;
  text-align: left;
}

.rebate-pagination {
  position: relative;
  display: flex;
  align-items: center;
  min-height: 54px;
  color: #555;
  font-size: 13px;
}

.rebate-pagination__buttons {
  position: absolute;
  left: 50%;
  display: flex;
  gap: 4px;
  transform: translateX(-50%);
}

.rebate-pagination__buttons :deep(.el-button) {
  min-height: 30px;
  margin: 0;
  border-radius: 3px;
}

.rebate-mobile-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.lucky-dialog :deep(.el-select) {
  width: 100%;
}

@media (width <= 767px) {
  .rebate-toolbar {
    align-items: flex-start;
    flex-direction: column;
    gap: 10px;
  }

  .rebate-summary {
    width: 100%;
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 4px 12px;
    font-size: 13px;
    line-height: 1.6;
  }

  .rebate-mobile-meta {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .rebate-pagination__buttons {
    position: static;
    transform: none;
  }
}
</style>
