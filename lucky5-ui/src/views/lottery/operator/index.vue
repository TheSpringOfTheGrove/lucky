<script setup lang="ts">
import { computed, ref } from 'vue'
import { useLucky5Store } from '@/store/modules/lottery'

const store = useLucky5Store()
const nickname = ref('')
const type = ref(0)
const rows = computed(() => {
  const keyword = nickname.value.trim()
  if (!keyword) return store.operators
  return store.operators.filter((item) => item.member.includes(keyword))
})
</script>

<template>
  <div class="lucky-page">
    <h1 class="lucky-page__heading">操作记录 <small>会员操作记录</small></h1>
    <div class="lucky-toolbar">
      <div class="lucky-toolbar__filters">
        <el-input v-model="nickname" clearable placeholder="昵称" />
        <el-select v-model="type">
          <el-option label="操作记录" :value="0" />
          <el-option label="失败原因" :value="1" />
        </el-select>
        <el-button type="primary">搜索</el-button>
      </div>
    </div>
    <el-card shadow="never">
      <PaginatedTable :data="rows" border>
        <el-table-column prop="member" label="会员昵称" min-width="160" />
        <el-table-column prop="action" label="内容" min-width="320" />
        <el-table-column prop="time" label="创建时间" min-width="200" />
      </PaginatedTable>
    </el-card>
  </div>
</template>


