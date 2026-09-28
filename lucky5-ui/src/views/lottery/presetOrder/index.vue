<script setup lang="ts">
import { useMediaQuery } from '@vueuse/core'
import { computed, reactive, ref, watch } from 'vue'
import { useLucky5Store } from '@/store/modules/lottery'
import { legacyFooterHeaders } from '@/views/lottery/utils/legacyTable'

const store = useLucky5Store()
const visible = ref(false)
const form = reactive({ id: '', content: '' })
const keyword = ref('')
const page = ref(1)
const pageSize = ref(10)
const isMobile = useMediaQuery('(max-width: 768px)')
const useMobileCards = false

const filteredRows = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  if (!value) return store.fakeOrders
  return store.fakeOrders.filter((item: any) =>
    String(item.content || '')
      .toLowerCase()
      .includes(value)
  )
})

const pagedRows = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return filteredRows.value.slice(start, start + pageSize.value)
})

watch([keyword, pageSize], () => {
  page.value = 1
})

watch(
  () => filteredRows.value.length,
  (total) => {
    page.value = Math.min(page.value, Math.max(1, Math.ceil(total / pageSize.value)))
  }
)

const openForm = (row?: any) => {
  form.id = row?.id || ''
  form.content = row?.content || ''
  visible.value = true
}

const rowIndex = (index: number) => (page.value - 1) * pageSize.value + index + 1

const submit = async () => {
  const saved = await store.saveFakeOrder({
    id: form.id,
    member: '-',
    content: form.content
  })
  if (saved) visible.value = false
}
</script>

<template>
  <div class="lucky-page">
    <h1 class="lucky-page__heading">预设订单管理 <small>预设订单管理</small></h1>
    <el-card shadow="never" class="lucky-card legacy-list-box preset-order-card">
      <div class="preset-add-row">
        <el-tooltip content="添加格式">
          <el-button class="preset-add-button" @click="openForm()">
            <Icon icon="fa:user-plus" />
          </el-button>
        </el-tooltip>
      </div>
      <div class="preset-toolbar">
        <div class="preset-toolbar__length">
          <span>显示</span>
          <el-select v-model="pageSize" class="page-size-select">
            <el-option v-for="size in [10, 20, 50, 100]" :key="size" :label="size" :value="size" />
          </el-select>
          <span>条目</span>
        </div>
        <div class="preset-toolbar__search">
          <label for="preset-order-search">搜索:</label>
          <el-input id="preset-order-search" v-model="keyword" class="search-input" />
        </div>
      </div>
      <div v-if="useMobileCards" class="preset-mobile-list">
        <article v-for="row in pagedRows" :key="row.id" class="preset-mobile-item">
          <div class="lucky-mobile-card__title">
            <span>预设指令</span>
            <el-tag v-if="!row.validationError" type="success">可用</el-tag>
            <el-tag v-else type="danger">格式错误</el-tag>
          </div>
          <div class="lucky-mobile-card__content">{{ row.content }}</div>
          <div class="lucky-mobile-card__meta">
            <span>注数：{{ row.parsedCount || 0 }}</span>
            <span>合计：{{ Number(row.parsedAmount || 0).toFixed(2) }}</span>
            <span v-if="row.createdAt">{{ row.createdAt }}</span>
          </div>
          <div v-if="row.validationError" class="preset-mobile-error">
            {{ row.validationError }}
          </div>
          <div class="lucky-mobile-card__actions">
            <el-button size="small" type="warning" @click="openForm(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="store.remove('fakeOrders', row.id)">
              删除
            </el-button>
          </div>
        </article>
        <el-empty v-if="!pagedRows.length" description="暂无数据" :image-size="64" />
      </div>
      <el-table v-else :data="pagedRows" border class="preset-order-table" show-summary :summary-method="legacyFooterHeaders">
        <el-table-column type="index" label="序号" width="60" :index="rowIndex" />
        <el-table-column prop="content" label="文本" min-width="300" />
        <el-table-column prop="createdAt" label="创建时间" width="200" />
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <div class="preset-row-actions">
              <el-tooltip content="编辑">
                <el-button class="preset-action-button preset-action-button--edit" @click="openForm(row)">
                  <Icon icon="fa:edit" />
                </el-button>
              </el-tooltip>
              <el-tooltip content="删除">
                <el-button
                  class="preset-action-button preset-action-button--delete"
                  @click="store.remove('fakeOrders', row.id)"
                >
                  <Icon icon="fa:trash" />
                </el-button>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <div class="preset-pagination">
        <span
          >显示第 {{ filteredRows.length ? (page - 1) * pageSize + 1 : 0 }} 到
          {{ Math.min(page * pageSize, filteredRows.length) }} 条，共
          {{ filteredRows.length }} 条</span
        >
        <el-pagination
          v-model:current-page="page"
          :page-size="pageSize"
          :total="filteredRows.length"
          layout="prev, pager, next"
          :pager-count="isMobile ? 3 : 7"
          :small="isMobile"
          background
        />
      </div>
    </el-card>

    <el-dialog v-model="visible" title="添加格式" width="500px" class="lucky-dialog">
      <el-form :model="form" label-width="90px">
        <el-form-item label="格式文本">
          <el-input v-model="form.content" placeholder="逗号分隔支持多格式" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="store.saving" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.preset-toolbar,
.preset-toolbar__length,
.preset-toolbar__search,
.preset-pagination,
.preset-row-actions {
  display: flex;
  align-items: center;
}

.preset-order-card :deep(.el-card__body) {
  padding: 10px;
}

.preset-add-row {
  margin-bottom: 18px;
}

.preset-add-button {
  --el-button-text-color: #fff;
  --el-button-bg-color: #00c0ef;
  --el-button-border-color: #00acd6;
  --el-button-hover-text-color: #fff;
  --el-button-hover-bg-color: #00acd6;
  --el-button-hover-border-color: #009abf;
  --el-button-active-text-color: #fff;
  --el-button-active-bg-color: #00acd6;
  --el-button-active-border-color: #009abf;
  width: 42px;
  height: 42px;
  padding: 0;
  color: #fff !important;
  background: #00c0ef !important;
  border: 1px solid #00acd6 !important;
  border-radius: 3px;
  box-shadow: inset 0 -2px rgb(0 0 0 / 15%);
}

.preset-add-button:hover,
.preset-add-button:focus {
  color: #fff !important;
  background: #00acd6 !important;
  border-color: #009abf !important;
}

.preset-add-button :deep(.svg-icon) {
  color: #fff !important;
  font-size: 17px;
}

.preset-toolbar {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  min-height: 34px;
  margin-bottom: 8px;
}

.preset-toolbar__length {
  gap: 8px;
  justify-self: start;
  color: #444;
  font-size: 14px;
}

.preset-toolbar__search {
  grid-column: 2;
  gap: 8px;
  justify-self: center;
  color: #444;
  font-size: 14px;
  font-weight: 600;
}

.page-size-select {
  width: 60px;
}

.search-input {
  width: 170px;
}

.preset-pagination {
  justify-content: space-between;
  margin-top: 16px;
  color: #555;
  font-size: 13px;
}

.preset-order-table :deep(th.el-table__cell) {
  height: 40px;
  padding: 0;
  color: #333;
  font-size: 16px;
  font-weight: 600;
  background: #fff;
}

.preset-order-table :deep(td.el-table__cell) {
  height: 46px;
  padding: 0 8px;
  color: #444;
  font-size: 15px;
}

.preset-row-actions {
  gap: 4px;
}

.preset-action-button {
  --el-button-text-color: #fff;
  --el-button-hover-text-color: #fff;
  --el-button-active-text-color: #fff;
  width: 34px;
  min-width: 34px;
  height: 32px;
  min-height: 32px;
  padding: 0;
  color: #fff !important;
  border-radius: 3px;
  box-shadow: inset 0 -2px rgb(0 0 0 / 20%);
}

.preset-action-button--edit {
  --el-button-bg-color: #f39c12;
  --el-button-border-color: #e08e0b;
  --el-button-hover-bg-color: #e08e0b;
  --el-button-hover-border-color: #c87f0a;
  --el-button-active-bg-color: #e08e0b;
  --el-button-active-border-color: #c87f0a;
  background: #f39c12 !important;
  border-color: #e08e0b !important;
}

.preset-action-button--edit:hover,
.preset-action-button--edit:focus {
  color: #fff !important;
  background: #e08e0b !important;
  border-color: #c87f0a !important;
}

.preset-action-button--delete {
  --el-button-bg-color: #dd4b39;
  --el-button-border-color: #d73925;
  --el-button-hover-bg-color: #d73925;
  --el-button-hover-border-color: #c23321;
  --el-button-active-bg-color: #d73925;
  --el-button-active-border-color: #c23321;
  background: #dd4b39 !important;
  border-color: #d73925 !important;
}

.preset-action-button--delete:hover,
.preset-action-button--delete:focus {
  color: #fff !important;
  background: #d73925 !important;
  border-color: #c23321 !important;
}

.preset-action-button :deep(.svg-icon) {
  color: #fff !important;
}

.preset-mobile-list {
  display: grid;
  gap: 10px;
}

.preset-mobile-item {
  min-width: 0;
  padding: 12px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
}

.preset-mobile-error {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-color-danger);
  overflow-wrap: anywhere;
}

@media (width <= 768px) {
  .preset-toolbar {
    display: flex;
    align-items: stretch;
    flex-direction: column;
    gap: 10px;
  }

  .preset-toolbar__length {
    flex-wrap: wrap;
  }

  .preset-toolbar__search {
    justify-content: flex-start;
  }

  .search-input {
    width: 100%;
    flex-basis: 100%;
  }

  .preset-pagination {
    align-items: stretch;
    flex-direction: column;
    gap: 10px;
    font-size: 12px;
  }

  .preset-pagination :deep(.el-pagination) {
    justify-content: center;
  }
}
</style>
