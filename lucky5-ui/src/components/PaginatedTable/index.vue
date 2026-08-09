<script setup lang="ts">
import { computed, ref, watch } from 'vue'

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
    <el-table v-bind="$attrs" :data="pagedRows">
      <slot></slot>
    </el-table>
    <div class="paginated-table__footer">
      <span>显示第 {{ startRow }} 到 {{ endRow }} 条，共 {{ total }} 条</span>
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="total"
        layout="sizes, prev, pager, next"
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

@media (max-width: 720px) {
  .paginated-table__footer {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>


