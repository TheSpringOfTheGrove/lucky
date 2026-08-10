<script setup lang="ts">
import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  reactive,
  ref
} from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useLucky5Store } from '@/store/modules/lottery'

const store = useLucky5Store()
const nickname = ref('')
const timeType = ref(1)
const visible = ref(false)
const refreshing = ref(false)
const form = reactive({ memberId: '', type: '上分' as '上分' | '下分', amount: 0, remark: '' })
let refreshTimer: number | undefined

const refreshAmountRecords = async () => {
  if (refreshing.value) return
  refreshing.value = true
  try {
    await store.refreshAmountRecords()
  } finally {
    refreshing.value = false
  }
}

const startAutoRefresh = () => {
  if (refreshTimer) return
  void refreshAmountRecords()
  refreshTimer = window.setInterval(() => void refreshAmountRecords(), 5000)
}

const stopAutoRefresh = () => {
  if (!refreshTimer) return
  window.clearInterval(refreshTimer)
  refreshTimer = undefined
}

onMounted(startAutoRefresh)
onActivated(startAutoRefresh)
onDeactivated(stopAutoRefresh)
onBeforeUnmount(stopAutoRefresh)

const rows = computed(() => {
  const keyword = nickname.value.trim()
  if (!keyword) return store.amountRecords
  return store.amountRecords.filter((item) => item.member.includes(keyword))
})
const totalUp = computed(() =>
  rows.value
    .filter((item) => item.type === '上分' && item.status === '已通过')
    .reduce((sum, item) => sum + Number(item.amount || 0), 0)
)
const totalDown = computed(() =>
  rows.value
    .filter((item) => item.type === '下分' && item.status === '已通过')
    .reduce((sum, item) => sum + Number(item.amount || 0), 0)
)

const audit = async (row: any, status: '已通过' | '已拒绝') => {
  try {
    if (status === '已拒绝') {
      const { value } = await ElMessageBox.prompt('请输入拒绝原因', '拒绝申请', {
        inputValidator: (text) => Boolean(String(text || '').trim()) || '请输入拒绝原因'
      })
      await store.auditAmount(row.id, status, value)
      return
    }
    await ElMessageBox.confirm(
      `确认通过 ${row.member} 的${row.type} ${row.amount} 分申请？`,
      '审核确认',
      { type: 'warning' }
    )
    await store.auditAmount(row.id, status)
  } catch {
    // User cancelled.
  }
}

const submit = async () => {
  if (!form.memberId || form.amount <= 0) {
    ElMessage.warning('请选择会员并填写分数')
    return
  }
  const saved = await store.createAmountRequest(form.memberId, form.amount, form.type, form.remark)
  if (saved) visible.value = false
}
</script>

<template>
  <div class="lucky-page">
    <h1 class="lucky-page__heading">
      积分列表 <small>上下分审核</small>
      <span class="lucky-amount-total"
        >上分：{{ totalUp }}，下分：{{ totalDown }}，净额：{{ totalUp - totalDown }}</span
      >
    </h1>
    <div class="lucky-toolbar">
      <div class="lucky-toolbar__filters">
        <el-tooltip content="提交上下分申请">
          <el-button type="primary" circle @click="visible = true"
            ><Icon icon="ep:plus"
          /></el-button>
        </el-tooltip>
        <el-input v-model="nickname" clearable placeholder="昵称" />
        <el-select v-model="timeType">
          <el-option label="全部" :value="0" />
          <el-option label="今天" :value="1" />
          <el-option label="昨天" :value="2" />
          <el-option label="本周" :value="3" />
        </el-select>
        <el-button type="primary" :loading="refreshing" @click="refreshAmountRecords"
          >搜索</el-button
        >
      </div>
    </div>
    <el-card shadow="never">
      <PaginatedTable :data="rows" border>
        <template #mobile="{ row }">
          <div class="lucky-mobile-card__title">
            <span>{{ row.member }}</span>
            <el-tag :type="row.type === '上分' ? 'success' : 'warning'" size="small">
              {{ row.type }} {{ row.amount }}
            </el-tag>
          </div>
          <div v-if="row.remark" class="lucky-mobile-card__content">{{ row.remark }}</div>
          <div class="lucky-mobile-card__meta">
            <span>状态：{{ row.status }}</span>
            <span>{{ row.createdAt }}</span>
          </div>
          <div v-if="row.status === '待审核'" class="lucky-mobile-card__actions">
            <el-button size="small" type="warning" @click="audit(row, '已通过')">通过</el-button>
            <el-button size="small" type="danger" @click="audit(row, '已拒绝')">拒绝</el-button>
          </div>
        </template>
        <el-table-column prop="member" label="昵称" min-width="140" />
        <el-table-column prop="amount" label="分数" min-width="100" />
        <el-table-column prop="type" label="类型" min-width="100" />
        <el-table-column prop="status" label="状态" min-width="110" />
        <el-table-column prop="remark" label="备注" min-width="160" />
        <el-table-column prop="createdAt" label="创建时间" min-width="200" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <div v-if="row.status === '待审核'" class="lucky-table-actions">
              <el-button size="small" type="warning" @click="audit(row, '已通过')">通过</el-button>
              <el-button size="small" type="danger" @click="audit(row, '已拒绝')">拒绝</el-button>
            </div>
            <span v-else>{{ row.status }}</span>
          </template>
        </el-table-column>
      </PaginatedTable>
    </el-card>

    <el-dialog v-model="visible" title="提交上下分申请" width="480px" class="lucky-dialog">
      <el-form :model="form" label-width="80px">
        <el-form-item label="会员">
          <el-select v-model="form.memberId" filterable>
            <el-option
              v-for="member in store.members.filter(
                (item) => item.memberType !== 'BOT' && !item.autoProxy
              )"
              :key="member.id"
              :label="`${member.name}（余分 ${member.balance}）`"
              :value="member.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="form.type">
            <el-radio-button label="上分" />
            <el-radio-button label="下分" />
          </el-radio-group>
        </el-form-item>
        <el-form-item label="分数"
          ><el-input-number v-model="form.amount" :min="0.01"
        /></el-form-item>
        <el-form-item label="备注"
          ><el-input v-model="form.remark" type="textarea" :rows="3"
        /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="store.saving" @click="submit">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.lucky-amount-total {
  margin-left: 12px;
  font-size: 14px;
  color: var(--el-color-danger);
}
</style>
