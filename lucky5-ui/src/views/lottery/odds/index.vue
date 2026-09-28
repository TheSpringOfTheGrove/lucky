<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { useLucky5Store } from '@/store/modules/lottery'

type OddsRow = {
  id: string
  label: string
  fallbackId?: string
  rate: number
  secondaryRate?: number
  hasBelowOneRate?: boolean
  minLimit?: number
  maxLimit?: number
}

const store = useLucky5Store()
const dragonTigerOddsIds = new Set(['regexlh', 'regexh'])
const rows = reactive<OddsRow[]>([
  { id: 'regex4x', label: '四字现', rate: 360, minLimit: 1, maxLimit: 100 },
  { id: 'regex3x', label: '三字现', rate: 45, minLimit: 1, maxLimit: 100 },
  { id: 'regex2x', label: '二字现', rate: 9, minLimit: 1, maxLimit: 500 },
  {
    id: 'regex4d',
    label: '四定位',
    rate: 9600,
    hasBelowOneRate: true,
    minLimit: 0.1,
    maxLimit: 50
  },
  { id: 'regex4d4', label: '四条', rate: 7000 },
  { id: 'regex3d', label: '三定位', rate: 960, minLimit: 0.1, maxLimit: 100 },
  {
    id: 'regex2d',
    label: '二定位',
    rate: 96,
    hasBelowOneRate: true,
    minLimit: 1,
    maxLimit: 2000
  },
  {
    id: 'regex5d2',
    fallbackId: 'regex2d',
    label: '五位二定',
    rate: 96,
    minLimit: 1,
    maxLimit: 2000
  },
  { id: 'regex1d', label: '一定位', rate: 9, minLimit: 1, maxLimit: 10000 },
  { id: 'regexlh', label: '龙虎', rate: 0, minLimit: 0, maxLimit: 0 },
  { id: 'regexh', label: '和', rate: 0, minLimit: 0, maxLimit: 0 }
])

const playType = computed(() => Number(store.config.playType ?? 2))
const playTypeLabel = computed(() => {
  if (playType.value === 0) return '普通'
  if (playType.value === 1) return '龙虎和'
  return '普通+龙虎和'
})
const visibleRows = computed(() => {
  // 五位二定仍保留在 rows 中以免保存普通赔率时覆盖已有独立配置，
  // 但按后台要求不在赔率设置页展示。
  const normalRows = rows.filter((row) => row.id !== 'regex5d2')
  if (playType.value === 0) {
    return normalRows.filter((row) => !dragonTigerOddsIds.has(row.id))
  }
  if (playType.value === 1) {
    return normalRows.filter((row) => dragonTigerOddsIds.has(row.id))
  }
  return normalRows
})

watch(
  () => store.odds,
  (odds) => {
    rows.forEach((row) => {
      const saved =
        odds.find((item) => item.id === row.id || item.play === row.label) ||
        (row.fallbackId ? odds.find((item) => item.id === row.fallbackId) : undefined)
      if (!saved) return
      row.rate = Number(saved.rate || 0)
      if (row.hasBelowOneRate) {
        row.secondaryRate =
          saved.secondaryRate === undefined || saved.secondaryRate === null
            ? undefined
            : Number(saved.secondaryRate)
      }
      if (row.minLimit !== undefined) row.minLimit = Number(saved.minLimit || 0)
      if (row.maxLimit !== undefined) row.maxLimit = Number(saved.maxLimit || 0)
    })
  },
  { deep: true, immediate: true }
)

const save = () => {
  const existingOdds = store.odds
  store.odds = rows.map((row) => ({
    // 四定位、二定位支持独立填写一元以下赔率；其它玩法不在此页编辑
    // 次赔率，保存时继续保留原值。
    secondaryRate: row.hasBelowOneRate
      ? row.secondaryRate
      : existingOdds.find((item) => item.id === row.id)?.secondaryRate,
    id: row.id,
    play: row.label,
    item: '',
    rate: row.rate,
    minLimit: row.minLimit,
    maxLimit: row.maxLimit,
    status: '启用'
  }))
  store.saveOdds()
}
</script>

<template>
  <div class="lucky-page">
    <el-card class="lucky-card" shadow="never">
      <template #header>
        <div class="lucky-odds-header">
          <strong>配置信息</strong>
          <el-tag type="info">当前玩法：{{ playTypeLabel }}</el-tag>
        </div>
      </template>
      <el-form class="lucky-original-form lucky-odds-form" label-width="150px">
        <el-form-item class="lucky-odds-form__header">
          <el-row :gutter="10" class="w-100%">
            <el-col :span="8">赔率</el-col>
            <el-col :span="8">最小限额</el-col>
            <el-col :span="8">最大限额</el-col>
          </el-row>
        </el-form-item>
        <el-form-item v-for="row in visibleRows" :key="row.id" :label="row.label">
          <el-row :gutter="10" class="w-100%">
            <el-col :span="8">
              <div class="lucky-odds-form__rates">
                <el-input-number v-model="row.rate" :min="0" :controls="false" placeholder="赔率" />
                <el-input-number
                  v-if="row.hasBelowOneRate"
                  class="lucky-odds-form__below-one-hint"
                  v-model="row.secondaryRate"
                  :min="0"
                  :controls="false"
                  placeholder="一元以下"
                  aria-label="一元以下说明"
                />
              </div>
            </el-col>
            <el-col :span="8">
              <el-input-number
                v-if="row.minLimit !== undefined"
                v-model="row.minLimit"
                :min="0"
                :controls="false"
                placeholder="最小限额"
              />
            </el-col>
            <el-col :span="8">
              <el-input-number
                v-if="row.maxLimit !== undefined"
                v-model="row.maxLimit"
                :min="0"
                :controls="false"
                placeholder="最大限额"
              />
            </el-col>
          </el-row>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="store.saving" @click="save">保存</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped lang="less">
.lucky-odds-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.lucky-odds-form__header {
  font-weight: 600;
  text-align: center;
}

.lucky-odds-form__rates {
  display: flex;
  gap: 8px;
}

/* Element Plus 的数字框默认居中；旧后台赔率表内所有输入均从左侧开始。 */
.lucky-odds-form :deep(.el-input__inner) {
  text-align: left;
}

@media (width <= 768px) {
  .lucky-odds-header {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }

  .lucky-odds-form__header {
    display: none;
  }

  .lucky-odds-form :deep(.el-row) {
    display: grid;
    grid-template-columns: 1fr;
    gap: 8px;
    margin-right: 0 !important;
    margin-left: 0 !important;
  }

  .lucky-odds-form :deep(.el-col) {
    width: 100%;
    max-width: none;
    flex: none;
    padding-right: 0 !important;
    padding-left: 0 !important;
  }

  .lucky-odds-form__rates {
    flex-direction: column;
  }
}
</style>
