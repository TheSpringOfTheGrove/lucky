<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useLucky5Store } from '@/store/modules/lottery'

const store = useLucky5Store()
const visible = ref(false)
const form = reactive({ id: '', label: '', content: '', sort: 1, enabled: true })

const openForm = (row?: any) => {
  Object.assign(form, {
    id: row?.id || '',
    label: row?.label || '',
    content: row?.content || '',
    sort: Number(row?.sort || store.quickCommands.length + 1),
    enabled: row?.enabled ?? true
  })
  visible.value = true
}

const submit = async () => {
  const saved = await store.saveQuickCommand({ ...form })
  if (saved) visible.value = false
}
</script>

<template>
  <div class="lucky-page">
    <h1 class="lucky-page__heading">快捷指令 <small>玩家端加号快捷指令</small></h1>
    <el-card shadow="never">
      <div class="mb-14px">
        <el-button type="primary" @click="openForm()">
          <Icon icon="ep:plus" class="mr-5px" />新增快捷指令
        </el-button>
      </div>
      <PaginatedTable :data="store.quickCommands" border>
        <template #mobile="{ row }">
          <div class="lucky-mobile-card__title">
            <span>{{ row.label }}</span>
            <el-tag :type="row.enabled ? 'success' : 'info'">
              {{ row.enabled ? '启用' : '停用' }}
            </el-tag>
          </div>
          <div class="lucky-mobile-card__content">{{ row.content }}</div>
          <div class="lucky-mobile-card__meta">
            <span>排序：{{ row.sort }}</span>
          </div>
          <div class="lucky-mobile-card__actions">
            <el-button size="small" type="warning" @click="openForm(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="store.remove('quickCommands', row.id)">
              删除
            </el-button>
          </div>
        </template>
        <el-table-column type="index" label="序号" width="80" />
        <el-table-column prop="label" label="按钮文字" min-width="180" />
        <el-table-column prop="content" label="完整下注指令" min-width="500" />
        <el-table-column prop="sort" label="排序" width="90" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">{{
              row.enabled ? '启用' : '停用'
            }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <div class="lucky-table-actions">
              <el-button size="small" type="warning" circle @click="openForm(row)">
                <Icon icon="ep:edit" />
              </el-button>
              <el-button
                size="small"
                type="danger"
                circle
                @click="store.remove('quickCommands', row.id)"
              >
                <Icon icon="ep:delete" />
              </el-button>
            </div>
          </template>
        </el-table-column>
      </PaginatedTable>
    </el-card>

    <el-dialog
      v-model="visible"
      :title="form.id ? '编辑快捷指令' : '新增快捷指令'"
      width="680px"
      class="lucky-dialog"
    >
      <el-form :model="form" label-width="110px">
        <el-form-item label="按钮文字">
          <el-input v-model="form.label" maxlength="60" show-word-limit />
        </el-form-item>
        <el-form-item label="完整下注指令">
          <el-input v-model="form.content" type="textarea" :rows="5" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="store.saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
