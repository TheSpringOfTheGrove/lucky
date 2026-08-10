<script setup lang="ts">
import { useMediaQuery } from '@vueuse/core'
import { computed, ref, useSlots, watch } from 'vue'

defineOptions({ inheritAttrs: false })

const props = withDefaults(
  defineProps<{
    data: any[]
    defaultPageSize?: number
  }>(),
  { defaultPageSize: 20 }
)

const page = ref(1)
const pageSize = ref(props.defaultPageSize)
const slots = useSlots()
const isMobile = useMediaQuery('(max-width: 768px)')
const useMobileList = computed(() => isMobile.value && Boolean(slots.mobile))
const total = computed(() => props.data?.length || 0)
const pagedRows = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return (props.data || []).slice(start, start + pageSize.value)
})
const startRow = computed(() => (total.value ? (page.value - 1) * pageSize.value + 1 : 0))
const endRow = computed(() => Math.min(page.value * pageSize.value, total.value))

watch(pageSize, () => {
  page.value = 1
})

watch(total, (value) => {
  page.value = Math.min(page.value, Math.max(1, Math.ceil(value / pageSize.value)))
})
</script>

<template>
  <div class="paginated-table">
    <div v-if="useMobileList" class="paginated-table__mobile-list">
      <article
        v-for="(row, index) in pagedRows"
        :key="row?.id || row?.period || row?.periods || `${page}-${index}`"
        class="paginated-table__mobile-item"
      >
        <slot name="mobile" :row="row" :index="(page - 1) * pageSize + index"></slot>
      </article>
      <el-empty v-if="!pagedRows.length" description="暂无数据" :image-size="64" />
    </div>
    <el-table v-else v-bind="$attrs" :data="pagedRows">
      <slot></slot>
    </el-table>
    <div class="paginated-table__footer">
      <span>显示第 {{ startRow }} 到 {{ endRow }} 条，共 {{ total }} 条</span>
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="total"
        :layout="isMobile ? 'prev, pager, next' : 'sizes, prev, pager, next'"
        :pager-count="isMobile ? 3 : 7"
        :small="isMobile"
        background
      />
    </div>
  </div>
</template>

<style scoped>
.paginated-table__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 16px;
}

.paginated-table__mobile-list {
  display: grid;
  gap: 10px;
}

.paginated-table__mobile-item {
  min-width: 0;
  padding: 12px;
  color: var(--el-text-color-regular);
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
}

@media (width <= 720px) {
  .paginated-table__footer {
    align-items: stretch;
    flex-direction: column;
    gap: 10px;
    font-size: 12px;
  }

  .paginated-table__footer :deep(.el-pagination) {
    justify-content: center;
  }
}
</style>
