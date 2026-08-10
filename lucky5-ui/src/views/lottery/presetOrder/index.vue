<script setup lang="ts">
import { useMediaQuery } from '@vueuse/core'
import { computed, reactive, ref, watch } from 'vue'
import { useLucky5Store } from '@/store/modules/lottery'

const store = useLucky5Store()
const visible = ref(false)
const form = reactive({ id: '', content: '' })
const keyword = ref('')
const page = ref(1)
const pageSize = ref(20)
const isMobile = useMediaQuery('(max-width: 768px)')

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
    <el-card shadow="never">
      <div class="preset-toolbar mb-14px">
        <el-tooltip content="添加格式">
          <el-button type="primary" circle @click="openForm()">
            <Icon icon="ep:plus" />
          </el-button>
        </el-tooltip>
        <div class="preset-toolbar__right">
          <span>显示</span>
          <el-select v-model="pageSize" class="page-size-select">
            <el-option v-for="size in [10, 20, 50, 100]" :key="size" :label="size" :value="size" />
          </el-select>
          <span>条</span>
          <span class="search-label">搜索：</span>
          <el-input v-model="keyword" clearable class="search-input" />
        </div>
      </div>
      <div v-if="isMobile" class="preset-mobile-list">
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
      <el-table v-else :data="pagedRows" border>
        <el-table-column type="index" label="序号" width="80" />
        <el-table-column prop="content" label="文本" min-width="300" />
        <el-table-column prop="parsedCount" label="注数" width="90" />
        <el-table-column prop="parsedAmount" label="合计" width="110">
          <template #default="{ row }">
            {{ Number(row.parsedAmount || 0).toFixed(2) }}
          </template>
        </el-table-column>
        <el-table-column label="校验" width="180">
          <template #default="{ row }">
            <el-tag v-if="!row.validationError" type="success">可用</el-tag>
            <el-tooltip v-else :content="row.validationError">
              <el-tag type="danger">格式错误</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="200" />
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <div class="lucky-table-actions">
              <el-tooltip content="编辑">
                <el-button size="small" type="warning" circle @click="openForm(row)">
                  <Icon icon="ep:edit" />
                </el-button>
              </el-tooltip>
              <el-tooltip content="删除">
                <el-button
                  size="small"
                  type="danger"
                  circle
                  @click="store.remove('fakeOrders', row.id)"
                >
                  <Icon icon="ep:delete" />
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
.preset-toolbar__right,
.preset-pagination {
  display: flex;
  align-items: center;
}

.preset-toolbar {
  justify-content: space-between;
}

.preset-toolbar__right {
  gap: 8px;
}

.page-size-select {
  width: 86px;
}

.search-label {
  margin-left: 16px;
}

.search-input {
  width: 220px;
}

.preset-pagination {
  justify-content: space-between;
  margin-top: 16px;
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
    align-items: stretch;
    flex-direction: column;
    gap: 10px;
  }

  .preset-toolbar__right {
    flex-wrap: wrap;
  }

  .preset-toolbar__right .search-label {
    display: none;
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
