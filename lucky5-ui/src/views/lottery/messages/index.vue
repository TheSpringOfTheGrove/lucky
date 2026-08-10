<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useLucky5Store } from '@/store/modules/lottery'

const store = useLucky5Store()
const period = ref('')
const content = ref('')
const nickname = ref('')
const visible = ref(false)
const form = reactive({ memberId: '', period: '', content: '', channel: '网页群' })
const rows = computed(() =>
  store.messages.filter(
    (item) =>
      (!period.value || String(item.period || '').includes(period.value.trim())) &&
      (!content.value || item.content.includes(content.value.trim())) &&
      (!nickname.value || item.member.includes(nickname.value.trim()))
  )
)

const open = () => {
  Object.assign(form, { memberId: '', period: '', content: '', channel: '网页群' })
  visible.value = true
}

const submit = async () => {
  if (!form.memberId || !form.content.trim()) {
    ElMessage.warning('请选择会员并填写消息内容')
    return
  }
  const saved = await store.processIncomingMessage({
    ...form,
    period: form.period.trim() || undefined
  })
  if (saved) visible.value = false
}
</script>

<template>
  <div class="lucky-page">
    <h1 class="lucky-page__heading">消息列表</h1>
    <div class="lucky-toolbar">
      <div class="lucky-toolbar__filters">
        <el-tooltip content="录入消息">
          <el-button type="primary" circle @click="open"><Icon icon="ep:plus" /></el-button>
        </el-tooltip>
        <el-input v-model="period" clearable placeholder="期数" />
        <el-input v-model="content" clearable placeholder="内容" />
        <el-input v-model="nickname" clearable placeholder="昵称" />
        <el-button type="primary">搜索</el-button>
      </div>
    </div>
    <el-card shadow="never">
      <PaginatedTable :data="rows" border>
        <el-table-column label="发送人" min-width="140">
          <template #default="{ row }">
            <div>{{ row.member }}</div>
            <small class="lucky-muted">{{ row.channel }}</small>
          </template>
        </el-table-column>
        <el-table-column label="内容" min-width="420">
          <template #default="{ row }">
            <div>{{ row.content }}</div>
            <div v-if="row.reply" class="lucky-muted">回执：{{ row.reply }}</div>
            <div class="message-meta">
              <span v-if="row.period">期号 {{ row.period }}</span>
              <el-tag
                size="small"
                :type="
                  row.status === '未识别'
                    ? 'danger'
                    : row.status === '处理中'
                      ? 'warning'
                      : 'success'
                "
              >
                {{ row.status }}
              </el-tag>
              <span v-if="row.orderId">订单 {{ row.orderId }}</span>
              <span v-if="row.error" class="lucky-danger">{{ row.error }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="time" label="创建时间" min-width="200" />
      </PaginatedTable>
    </el-card>

    <el-dialog v-model="visible" title="录入消息" width="520px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="会员">
          <el-select v-model="form.memberId" filterable placeholder="选择会员">
            <el-option
              v-for="member in store.members.filter((item) => item.memberType !== 'BOT' && !item.autoProxy)"
              :key="member.id"
              :label="`${member.name}（余分 ${member.balance}）`"
              :value="member.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="期号"><el-input v-model="form.period" /></el-form-item>
        <el-form-item label="来源">
          <el-select v-model="form.channel">
            <el-option
              v-for="item in ['网页群', '微信', '飞鱼', '蓝鲸', '跟', '私聊']"
              :key="item"
              :label="item"
              :value="item"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="form.content" type="textarea" :rows="4" placeholder="大100 单50" />
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
.message-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 12px;
  align-items: center;
  justify-content: center;
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>

