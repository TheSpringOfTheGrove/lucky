<script setup lang="ts">
import { computed, ref } from 'vue'
import { useLucky5Store } from '@/store/modules/lottery'

const store = useLucky5Store()
const nickname = ref('')
const type = ref<'ALL' | 'MANUAL' | 'SYSTEM'>('ALL')

const isSystemOperator = (item: any) => {
  const operator = String(item.operator || '').trim().toLowerCase()
  return !operator || operator === 'system' || operator === '系统' || operator === '[系统]'
}

const operatorLabel = (item: any) => (isSystemOperator(item) ? '系统' : item.operator)

const rows = computed(() => {
  const keyword = nickname.value.trim()
  return store.operators.filter((item) => {
    const matchesType =
      type.value === 'ALL' ||
      (type.value === 'SYSTEM' ? isSystemOperator(item) : !isSystemOperator(item))
    const matchesKeyword =
      !keyword ||
      [item.operator, item.member, item.action].some((value) =>
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
        <el-input v-model="nickname" clearable placeholder="操作人、会员或内容" />
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
        <el-table-column label="操作人" min-width="150">
          <template #default="{ row }">
            <el-tag :type="isSystemOperator(row) ? 'info' : 'primary'" effect="plain">
              {{ operatorLabel(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="会员昵称" min-width="150">
          <template #default="{ row }">{{ row.member && row.member !== '-' ? row.member : '—' }}</template>
        </el-table-column>
        <el-table-column prop="action" label="内容" min-width="320" />
        <el-table-column prop="time" label="创建时间" min-width="200" />
      </PaginatedTable>
    </el-card>
  </div>
</template>
