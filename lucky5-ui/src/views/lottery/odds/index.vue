<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { useLucky5Store } from '@/store/modules/lottery'

type OddsRow = {
  id: string
  label: string
  rate: number
  secondaryRate?: number
  minLimit?: number
  maxLimit?: number
}

const store = useLucky5Store()
const dragonTigerOddsIds = new Set(['regexlh', 'regexh'])
const rows = reactive<OddsRow[]>([
  { id: 'regex4x', label: '四字现', rate: 360, minLimit: 1, maxLimit: 100 },
  { id: 'regex3x', label: '三字现', rate: 45, minLimit: 1, maxLimit: 100 },
  { id: 'regex2x', label: '二字现', rate: 9, minLimit: 1, maxLimit: 500 },
  { id: 'regex4d', label: '四定位', rate: 9600, secondaryRate: 9600, minLimit: 0.1, maxLimit: 50 },
  { id: 'regex4d4', label: '四条', rate: 7000 },
  { id: 'regex3d', label: '三定位', rate: 960, secondaryRate: 960, minLimit: 0.1, maxLimit: 100 },
  { id: 'regex2d', label: '二定位', rate: 96, minLimit: 1, maxLimit: 2000 },
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
  if (playType.value === 0) {
    return rows.filter((row) => !dragonTigerOddsIds.has(row.id))
  }
  if (playType.value === 1) {
    return rows.filter((row) => dragonTigerOddsIds.has(row.id))
  }
  return rows
})

watch(
  () => store.odds,
  (odds) => {
    rows.forEach((row) => {
      const saved = odds.find((item) => item.id === row.id || item.play === row.label)
      if (!saved) return
      row.rate = Number(saved.rate || 0)
      if (row.secondaryRate !== undefined) row.secondaryRate = Number(saved.secondaryRate || 0)
      if (row.minLimit !== undefined) row.minLimit = Number(saved.minLimit || 0)
      if (row.maxLimit !== undefined) row.maxLimit = Number(saved.maxLimit || 0)
    })
  },
  { deep: true, immediate: true }
)

const save = () => {
  store.odds = rows.map((row) => ({
    id: row.id,
    play: row.label,
    item: '',
    rate: row.rate,
    secondaryRate: row.secondaryRate,
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
                  v-if="row.secondaryRate !== undefined"
                  v-model="row.secondaryRate"
                  :min="0"
                  :controls="false"
                  placeholder="一元以下"
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
</style>

