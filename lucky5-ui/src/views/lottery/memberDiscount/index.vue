<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useLucky5Store } from '@/store/modules/lottery'

const store = useLucky5Store()
const puller = ref('')
const keyword = ref('')
const editVisible = ref(false)
const batchVisible = ref(false)
const listVersion = ref(0)

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
const rows = computed(() => {
  const search = keyword.value.trim().toLowerCase()
  return realMembers.value.filter((item) => {
    if (puller.value && item.partner !== puller.value) return false
    if (!search) return true
    return String(item.name || '').toLowerCase().includes(search)
  })
})

const numberValue = (value: unknown) => Number(value || 0)
const money = (value: unknown) => numberValue(value).toFixed(2)
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
  if (!rows.value.length) {
    ElMessage.warning('当前没有可设置的会员')
    return
  }
  const payload = rows.value.map((row) => ({
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
  <div class="lucky-page rebate-page">
    <h1 class="lucky-page__heading">返水列表</h1>
    <el-card shadow="never">
      <div class="rebate-toolbar">
        <div class="rebate-toolbar__actions">
          <el-button type="danger" :disabled="totalRebate <= 0" @click="apply">一键返水</el-button>
          <el-button type="primary" @click="openBatch">一键设置</el-button>
        </div>
        <div class="rebate-summary">
          <span>返水合计：<strong>{{ money(totalRebate) }}</strong></span>
          <span>真实玩家：{{ money(playerTotal) }}</span>
          <span>吃：{{ money(eatTotal) }}</span>
          <span>普通：{{ money(normalTotal) }}</span>
          <span>龙虎：{{ money(dragonTotal) }}</span>
          <span>拉手：{{ money(pullerTotal) }}</span>
        </div>
      </div>

      <div class="lucky-toolbar__filters rebate-filters mb-16px">
        <el-select v-model="puller" clearable placeholder="选择拉手">
          <el-option v-for="item in pullers" :key="item" :label="item" :value="item" />
        </el-select>
        <el-input v-model="keyword" clearable placeholder="搜索昵称" />
      </div>

      <PaginatedTable
        :key="listVersion"
        v-loading="store.rebateMembersRefreshing || store.saving"
        :data="rows"
        border
      >
        <template #mobile="{ row }">
          <div class="lucky-mobile-card__title">
            <span>{{ row.name }}</span>
            <span>合计 {{ money(rowTotal(row)) }}</span>
          </div>
          <div class="lucky-mobile-card__meta rebate-mobile-meta">
            <span>是否拉手：{{ isPuller(row) ? '是' : '否' }}</span>
            <span>所属拉手：{{ row.partner || '无' }}</span>
            <span>幸运五比例：{{ rate(row.normalRate) }}</span>
            <span>幸运五返水：{{ money(row.normalRebate) }}</span>
            <span>龙虎比例：{{ rate(row.lhhRate) }}</span>
            <span>龙虎返水：{{ money(row.dragonRebate) }}</span>
            <span>拉手返水：{{ money(row.partnerRebate) }}</span>
          </div>
          <div class="lucky-mobile-card__actions">
            <el-button size="small" @click="togglePuller(row)">{{ isPuller(row) ? '取消拉手' : '设为拉手' }}</el-button>
            <el-button size="small" type="primary" @click="openEdit(row)">编辑</el-button>
          </div>
        </template>

        <el-table-column prop="name" label="昵称" min-width="110" />
        <el-table-column label="拉手设置" width="90" align="center">
          <template #default="{ row }">
            <el-tooltip :content="isPuller(row) ? '取消拉手' : '设为拉手'">
              <el-button size="small" @click="togglePuller(row)">{{ isPuller(row) ? '取消' : '拉' }}</el-button>
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
                <Icon icon="ep:edit" />
              </el-button>
            </el-tooltip>
          </template>
        </el-table-column>
      </PaginatedTable>
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
.rebate-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-start;
  gap: 12px 22px;
  margin-bottom: 14px;
}

.rebate-toolbar__actions {
  display: flex;
  flex: 0 0 auto;
  gap: 8px;
}

.rebate-toolbar__actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.rebate-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-start;
  gap: 6px 18px;
  color: var(--el-text-color-regular);
  font-size: 14px;
  line-height: 32px;
  text-align: left;
}

.rebate-summary strong {
  color: var(--el-color-danger);
  font-weight: 600;
}

.rebate-filters {
  justify-content: flex-start;
}

.rebate-mobile-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.lucky-dialog :deep(.el-select) {
  width: 100%;
}

@media (width <= 768px) {
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
}
</style>
