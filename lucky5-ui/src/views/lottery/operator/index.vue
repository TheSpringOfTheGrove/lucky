<script setup lang="ts">
import { computed, ref } from 'vue'
import { useLucky5Store } from '@/store/modules/lottery'

const store = useLucky5Store()
const nickname = ref('')
const type = ref<'ALL' | 'MANUAL' | 'SYSTEM'>('ALL')

const isSystemOperator = (item: any) => {
  const operator = String(item.operator || '')
    .trim()
    .toLowerCase()
  return !operator || operator === 'system' || operator === '系统' || operator === '[系统]'
}

const memberLabel = (item: any) => {
  const member = String(item.member || '').trim()
  return member && member !== '-' ? member : '【系统】'
}

const rows = computed(() => {
  const keyword = nickname.value.trim()
  return store.operators.filter((item) => {
    const matchesType =
      type.value === 'ALL' ||
      (type.value === 'SYSTEM' ? isSystemOperator(item) : !isSystemOperator(item))
    const matchesKeyword =
      !keyword ||
      [memberLabel(item), item.action].some((value) =>
        String(value || '')
          .toLowerCase()
          .includes(keyword.toLowerCase())
      )
    return matchesType && matchesKeyword
  })
})
</script>

<template>
  <div class="lucky-page">
    <h1 class="lucky-page__heading">操作记录 <small>会员操作记录</small></h1>
    <div class="lucky-toolbar">
      <div class="lucky-toolbar__filters">
        <el-input v-model="nickname" clearable placeholder="会员昵称或内容" />
        <el-select v-model="type">
          <el-option label="全部操作" value="ALL" />
          <el-option label="人工操作" value="MANUAL" />
          <el-option label="系统操作" value="SYSTEM" />
        </el-select>
        <el-button type="primary">搜索</el-button>
      </div>
    </div>
    <el-card shadow="never">
      <PaginatedTable :data="rows" border>
        <template #mobile="{ row }">
          <div class="lucky-mobile-card__title">
            <span>{{ memberLabel(row) }}</span>
            <span class="lucky-muted">{{ row.time }}</span>
          </div>
          <div class="lucky-mobile-card__content">{{ row.action }}</div>
        </template>
        <el-table-column label="会员昵称" min-width="160">
          <template #default="{ row }">
            <span :class="{ 'operator-system-member': memberLabel(row) === '【系统】' }">
              {{ memberLabel(row) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="action" label="内容" min-width="320" />
        <el-table-column prop="time" label="创建时间" min-width="200" />
      </PaginatedTable>
    </el-card>
  </div>
</template>

<style scoped>
.operator-system-member {
  font-weight: 600;
  color: var(--el-text-color-primary);
}
</style>
