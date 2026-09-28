<script setup lang="ts">
import LegacyTable from '@/components/LegacyTable'
import { useMediaQuery } from '@vueuse/core'
import { computed, onMounted, ref } from 'vue'
import {
  getMessagesApi,
  type LotteryMessagePageParams,
  type LotteryMessageRow
} from '@/api/lottery'
import { legacyFooterHeaders } from '@/views/lottery/utils/legacyTable'
import MessageContent from './MessageContent.vue'

const period = ref('')
const content = ref('')
const nickname = ref('')
const rows = ref<LotteryMessageRow[]>([])
const loading = ref(false)
const pageNo = ref(1)
const pageSize = ref(10)
const total = ref(0)
const isMobile = useMediaQuery('(max-width: 767px)')
const useMobileCards = false

const startRow = computed(() => (total.value ? (pageNo.value - 1) * pageSize.value + 1 : 0))
const endRow = computed(() => Math.min(pageNo.value * pageSize.value, total.value))

const query = (): LotteryMessagePageParams => ({
  pageNo: pageNo.value,
  pageSize: pageSize.value,
  period: period.value.trim() || undefined,
  content: content.value.trim() || undefined,
  nickname: nickname.value.trim() || undefined
})

const load = async () => {
  loading.value = true
  try {
    const result = await getMessagesApi(query())
    rows.value = result.list || []
    total.value = Number(result.total || 0)
  } finally {
    loading.value = false
  }
}

const search = () => {
  pageNo.value = 1
  load()
}

const changePage = (value: number) => {
  pageNo.value = value
  load()
}

const changePageSize = (value: number) => {
  pageSize.value = value
  pageNo.value = 1
  load()
}

onMounted(load)
</script>

<template>
  <div class="lucky-page message-page">
    <h1 class="lucky-page__heading">消息列表</h1>

    <div class="lucky-toolbar message-toolbar">
      <div class="lucky-toolbar__filters message-filters">
        <el-input v-model="period" clearable placeholder="期数" @keyup.enter="search" />
        <el-input v-model="content" clearable placeholder="内容" @keyup.enter="search" />
        <el-input v-model="nickname" clearable placeholder="昵称" @keyup.enter="search" />
        <el-button type="primary" @click="search">搜索</el-button>
      </div>
    </div>

    <el-card v-loading="loading" class="legacy-list-box" shadow="never">
      <div v-if="useMobileCards" class="message-mobile-list">
        <article v-for="row in rows" :key="row.id" class="lucky-mobile-card message-mobile-item">
          <div class="lucky-mobile-card__title">
            <span>{{ row.sender }}</span>
            <span class="message-time">{{ row.time }}</span>
          </div>
          <MessageContent :row="row" class="lucky-mobile-card__content" />
          <div v-if="row.period" class="lucky-mobile-card__meta">
            <span>期号 {{ row.period }}</span>
          </div>
        </article>
        <el-empty v-if="!rows.length" description="暂无数据" :image-size="64" />
      </div>

      <LegacyTable
        v-else
        :data="rows"
        row-key="id"
        border
        class="message-table"
        show-summary
        :summary-method="legacyFooterHeaders"
      >
        <el-table-column prop="sender" label="发送人" min-width="140" />
        <el-table-column label="内容" min-width="520" align="left" class-name="message-content-column">
          <template #default="{ row }">
            <MessageContent :row="row" />
          </template>
        </el-table-column>
        <el-table-column prop="time" label="创建时间" min-width="180" />
      </LegacyTable>

      <div class="message-pagination">
        <span>显示第 {{ startRow }} 到 {{ endRow }} 条，共 {{ total }} 条</span>
        <el-pagination
          :current-page="pageNo"
          :page-size="pageSize"
          :page-sizes="[10, 25, 50, 100]"
          :total="total"
          :layout="isMobile ? 'prev, pager, next' : 'sizes, prev, pager, next, jumper'"
          :pager-count="isMobile ? 5 : 7"
          :small="isMobile"
          background
          @current-change="changePage"
          @size-change="changePageSize"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.message-toolbar {
  margin-bottom: 18px;
}

.legacy-list-box :deep(.el-card__body) {
  padding: 10px;
}

.message-filters {
  display: grid;
  grid-template-columns: repeat(3, minmax(180px, 240px)) auto;
  justify-content: start;
  width: 100%;
}

.message-table :deep(td.message-content-column),
.message-table :deep(td.message-content-column .cell) {
  text-align: left !important;
}

.message-time {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 400;
}

.message-mobile-list {
  display: grid;
  gap: 10px;
}

.message-mobile-item {
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
}

.message-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 16px;
}

:deep(.el-table__cell) {
  vertical-align: top;
}

@media (max-width: 767px) {
  .message-filters {
    display: flex;
  }

  .message-filters :deep(.el-button) {
    width: 100%;
  }

  .message-pagination {
    align-items: stretch;
    flex-direction: column;
    gap: 10px;
    font-size: 12px;
  }

  .message-pagination :deep(.el-pagination) {
    justify-content: center;
  }
}
</style>
