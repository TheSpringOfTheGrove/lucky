<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useLucky5Store } from '@/store/modules/lottery'

const store = useLucky5Store()
const puller = ref('')
const realMembers = computed(() =>
  store.members.filter((item) => item.memberType !== 'BOT' && !item.autoProxy)
)
const rows = computed(() =>
  puller.value
    ? realMembers.value.filter((item) => item.partner === puller.value)
    : realMembers.value
)
const pullers = computed(() => [
  ...new Set(realMembers.value.map((item) => item.partner).filter((item) => item && item !== '无'))
])
const totalRebate = computed(() =>
  rows.value.reduce(
    (sum, row) => sum + Number(row.normalRebate || 0) + Number(row.dragonRebate || 0),
    0
  )
)
const normalTotal = computed(() =>
  rows.value.reduce((sum, row) => sum + Number(row.normalRebate || 0), 0)
)
const dragonTotal = computed(() =>
  rows.value.reduce((sum, row) => sum + Number(row.dragonRebate || 0), 0)
)

const apply = async () => {
  try {
    await ElMessageBox.confirm(`确认发放返水 ${totalRebate.value} 分？`, '一键返水', {
      type: 'warning'
    })
    await store.applyRebates()
  } catch {
    // User cancelled.
  }
}
</script>

<template>
  <div class="lucky-page">
    <h1 class="lucky-page__heading">返水列表</h1>
    <el-card shadow="never">
      <div class="lucky-toolbar">
        <div>
          <el-button type="danger" :disabled="totalRebate <= 0" @click="apply">一键返水</el-button>
          <el-button type="primary" @click="store.saveDiscounts">一键设置</el-button>
        </div>
        <div
          >返水合计: {{ totalRebate }}&nbsp;&nbsp;&nbsp;&nbsp;真实玩家:
          {{ totalRebate }}&nbsp;&nbsp;&nbsp;&nbsp;吃: 0&nbsp;&nbsp;&nbsp;&nbsp;普通:
          {{ normalTotal }}&nbsp;&nbsp;&nbsp;&nbsp;龙虎:
          {{ dragonTotal }}&nbsp;&nbsp;&nbsp;&nbsp;拉手: 0</div
        >
      </div>
      <div class="lucky-toolbar__filters mb-16px">
        <el-select v-model="puller" clearable placeholder="选择拉手">
          <el-option v-for="item in pullers" :key="item" :label="item" :value="item" />
        </el-select>
      </div>
      <PaginatedTable :data="rows" border>
        <template #mobile="{ row }">
          <div class="lucky-mobile-card__title">
            <span>{{ row.name }}</span>
            <span>合计 {{ Number(row.normalRebate || 0) + Number(row.dragonRebate || 0) }}</span>
          </div>
          <div class="rebate-mobile-rates">
            <label>
              <span>幸运五比例</span>
              <el-input-number v-model="row.normalRate" :min="0" :precision="2" />
            </label>
            <label>
              <span>龙虎比例</span>
              <el-input-number v-model="row.lhhRate" :min="0" :precision="2" />
            </label>
          </div>
          <div class="lucky-mobile-card__meta">
            <span>普通返水：{{ row.normalRebate || 0 }}</span>
            <span>龙虎返水：{{ row.dragonRebate || 0 }}</span>
            <span>拉手：{{ row.partner || '无' }}</span>
          </div>
          <div class="lucky-mobile-card__actions">
            <el-button size="small" type="primary" @click="store.saveDiscounts">保存</el-button>
          </div>
        </template>
        <el-table-column prop="name" label="昵称" min-width="110" />
        <el-table-column prop="partner" label="拉手设置" min-width="110" />
        <el-table-column label="是否拉手" min-width="100"
          ><template #default="{ row }">{{
            row.tag === '拉手' ? '是' : '否'
          }}</template></el-table-column
        >
        <el-table-column label="幸运五比例" min-width="150"
          ><template #default="{ row }"
            ><el-input-number v-model="row.normalRate" :min="0" :precision="2" /></template
        ></el-table-column>
        <el-table-column label="返水金额" min-width="110"
          ><template #default="{ row }">{{ row.normalRebate || 0 }}</template></el-table-column
        >
        <el-table-column label="龙虎比例" min-width="150"
          ><template #default="{ row }"
            ><el-input-number v-model="row.lhhRate" :min="0" :precision="2" /></template
        ></el-table-column>
        <el-table-column label="返水金额" min-width="110"
          ><template #default="{ row }">{{ row.dragonRebate || 0 }}</template></el-table-column
        >
        <el-table-column label="拉手返水" min-width="110"
          ><template #default>0</template></el-table-column
        >
        <el-table-column label="合计返水" min-width="110"
          ><template #default="{ row }">{{
            Number(row.normalRebate || 0) + Number(row.dragonRebate || 0)
          }}</template></el-table-column
        >
        <el-table-column label="操作" width="100" fixed="right"
          ><template #default
            ><el-button size="small" type="primary" @click="store.saveDiscounts"
              >保存</el-button
            ></template
          ></el-table-column
        >
      </PaginatedTable>
    </el-card>
  </div>
</template>

<style scoped>
.rebate-mobile-rates {
  display: grid;
  margin-top: 10px;
  gap: 8px;
}

.rebate-mobile-rates label {
  display: grid;
  grid-template-columns: 90px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.rebate-mobile-rates :deep(.el-input-number) {
  width: 100%;
}

@media (width <= 768px) {
  .lucky-toolbar > div:last-child {
    font-size: 13px;
    line-height: 1.7;
  }
}
</style>
