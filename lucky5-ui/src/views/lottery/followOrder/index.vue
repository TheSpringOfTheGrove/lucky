<script setup lang="ts">
import LegacyTable from '@/components/LegacyTable'
import { computed, ref, watch } from 'vue'
import { useLucky5Store } from '@/store/modules/lottery'
import { legacyFooterHeaders } from '@/views/lottery/utils/legacyTable'

const store = useLucky5Store()
const keyword = ref('')
const page = ref(1)
const pageSize = ref(10)

const filteredRows = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  if (!value) return store.followOrders
  return store.followOrders.filter((item: any) =>
    [item.source, item.target]
      .filter(Boolean)
      .some((field) => String(field).toLowerCase().includes(value))
  )
})

const pagedRows = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return filteredRows.value.slice(start, start + pageSize.value)
})

const firstItem = computed(() => (filteredRows.value.length ? (page.value - 1) * pageSize.value + 1 : 0))
const lastItem = computed(() => Math.min(page.value * pageSize.value, filteredRows.value.length))
const lastPage = computed(() => Math.max(1, Math.ceil(filteredRows.value.length / pageSize.value)))

watch([keyword, pageSize], () => {
  page.value = 1
})

watch(lastPage, (value) => {
  page.value = Math.min(page.value, value)
})
</script>

<template>
  <div class="lucky-page">
    <h1 class="lucky-page__heading">跟单列表</h1>
    <el-card shadow="never" class="lucky-card legacy-list-box follow-order-card">
      <div class="follow-order-toolbar">
        <div class="follow-order-toolbar__length">
          <span>显示</span>
          <el-select v-model="pageSize" class="follow-page-size-select">
            <el-option v-for="size in [10, 25, 50, 100]" :key="size" :label="size" :value="size" />
          </el-select>
          <span>条目</span>
        </div>
        <div class="follow-order-toolbar__search">
          <label for="follow-order-search">搜索:</label>
          <el-input id="follow-order-search" v-model="keyword" class="follow-search-input" />
        </div>
      </div>

      <LegacyTable
        :data="pagedRows"
        border
        empty-text="No data available in table"
        class="follow-order-table"
        show-summary
        :summary-method="legacyFooterHeaders"
      >
        <el-table-column prop="source" label="昵称" min-width="180">
          <template #default="{ row }">{{ row.source || '-' }}</template>
        </el-table-column>
        <el-table-column prop="target" label="订单" min-width="280">
          <template #default="{ row }">{{ row.target || '-' }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="220" />
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-tooltip content="删除">
              <el-button class="follow-delete-button" @click="store.remove('followOrders', row.id)">
                <Icon icon="fa:trash" :size="14" />
              </el-button>
            </el-tooltip>
          </template>
        </el-table-column>
      </LegacyTable>

      <div class="follow-order-footer">
        <span>显示{{ filteredRows.length }}个条目中的{{ firstItem }}到{{ lastItem }}</span>
        <div class="follow-order-footer__pager">
          <el-button :disabled="page <= 1" @click="page -= 1">上一页</el-button>
          <el-button :disabled="page >= lastPage" @click="page += 1">下一页</el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.follow-order-card :deep(.el-card__body) {
  padding: 10px;
}

.follow-order-toolbar,
.follow-order-toolbar__length,
.follow-order-toolbar__search,
.follow-order-footer,
.follow-order-footer__pager {
  display: flex;
  align-items: center;
}

.follow-order-toolbar {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  min-height: 34px;
  margin: 10px 0 8px;
}

.follow-order-toolbar__length {
  gap: 8px;
  justify-self: start;
  color: #444;
  font-size: 14px;
}

.follow-order-toolbar__search {
  grid-column: 2;
  gap: 8px;
  justify-self: center;
  color: #444;
  font-size: 14px;
  font-weight: 600;
}

.follow-page-size-select {
  width: 60px;
}

.follow-search-input {
  width: 170px;
}

.follow-order-table :deep(th.el-table__cell) {
  height: 40px;
  padding: 0;
  color: #333;
  font-size: 16px;
  font-weight: 600;
  background: #fff;
}

.follow-order-table :deep(td.el-table__cell) {
  height: 46px;
  padding: 0 8px;
  color: #444;
  font-size: 15px;
}

.follow-order-table :deep(.el-table__empty-block) {
  left: 0;
  width: 100% !important;
  transform: none;
}

.follow-order-table :deep(.el-table__empty-text) {
  width: 100%;
  padding-left: 10px;
  color: #555;
  text-align: left;
}

.follow-delete-button {
  --el-button-text-color: #fff;
  --el-button-bg-color: #dd4b39;
  --el-button-border-color: #d73925;
  --el-button-hover-text-color: #fff;
  --el-button-hover-bg-color: #d73925;
  --el-button-hover-border-color: #c23321;
  --el-button-active-text-color: #fff;
  --el-button-active-bg-color: #d73925;
  --el-button-active-border-color: #c23321;
  width: 34px;
  min-width: 34px;
  height: 32px;
  min-height: 32px;
  padding: 0;
  color: #fff !important;
  background: #dd4b39 !important;
  border-color: #d73925 !important;
  border-radius: 3px;
  box-shadow: inset 0 -2px rgb(0 0 0 / 20%);
}

.follow-delete-button:hover,
.follow-delete-button:focus {
  color: #fff !important;
  background: #d73925 !important;
  border-color: #c23321 !important;
}

.follow-delete-button :deep(.svg-icon) {
  color: #fff !important;
}

.follow-order-footer {
  position: relative;
  min-height: 54px;
  color: #555;
  font-size: 13px;
}

.follow-order-footer__pager {
  position: absolute;
  left: 50%;
  gap: 0;
  transform: translateX(-50%);
}

.follow-order-footer__pager :deep(.el-button) {
  min-height: 30px;
  margin: 0;
  border-radius: 3px;
}

.follow-order-footer__pager :deep(.el-button + .el-button) {
  margin-left: 4px;
}

@media (width <= 767px) {
  .follow-order-toolbar {
    display: flex;
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
  }

  .follow-order-toolbar__search {
    justify-content: flex-start;
  }

  .follow-search-input {
    flex: 1;
    width: auto;
  }

  .follow-order-footer {
    align-items: flex-start;
    flex-direction: column;
    gap: 10px;
    padding-bottom: 40px;
  }

  .follow-order-footer__pager {
    bottom: 0;
    left: 0;
    transform: none;
  }
}
</style>
