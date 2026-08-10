<script setup lang="ts">
import { useLucky5Store } from '@/store/modules/lottery'

const store = useLucky5Store()
</script>

<template>
  <div class="lucky-page">
    <h1 class="lucky-page__heading">跟单列表</h1>
    <el-card shadow="never">
      <PaginatedTable :data="store.followOrders" border>
        <template #mobile="{ row }">
          <div class="lucky-mobile-card__title">
            <span>{{ row.source || '未命名会员' }}</span>
            <span class="lucky-muted">{{ row.createdAt }}</span>
          </div>
          <div class="lucky-mobile-card__content">{{ row.target || '-' }}</div>
          <div class="lucky-mobile-card__actions">
            <el-button size="small" type="danger" @click="store.remove('followOrders', row.id)">
              删除
            </el-button>
          </div>
        </template>
        <el-table-column prop="source" label="昵称" min-width="180" />
        <el-table-column prop="target" label="订单" min-width="240" />
        <el-table-column prop="createdAt" label="创建时间" min-width="220" />
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-tooltip content="删除">
              <el-button
                size="small"
                type="danger"
                circle
                @click="store.remove('followOrders', row.id)"
              >
                <Icon icon="ep:delete" />
              </el-button>
            </el-tooltip>
          </template>
        </el-table-column>
      </PaginatedTable>
    </el-card>
  </div>
</template>
