<script setup lang="ts">
import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  ref
} from 'vue'
import { ElMessage } from 'element-plus'
import { useLucky5Store } from '@/store/modules/lottery'

const store = useLucky5Store()
const visible = ref(false)
const password = ref('')
const totalBet = computed(() =>
  store.chimaRecords.reduce((sum, item) => sum + Number(item.fakeAmount || 0), 0)
)
const totalWin = computed(() =>
  store.chimaRecords.reduce((sum, item) => sum + Number(item.totalWin || 0), 0)
)

let refreshTimer: ReturnType<typeof setInterval> | undefined
const startRefresh = () => {
  if (refreshTimer) return
  void store.refreshChimaRecords()
  refreshTimer = setInterval(() => void store.refreshChimaRecords(), 2_000)
}
const stopRefresh = () => {
  if (refreshTimer) clearInterval(refreshTimer)
  refreshTimer = undefined
}
onMounted(startRefresh)
onActivated(startRefresh)
onDeactivated(stopRefresh)
onBeforeUnmount(stopRefresh)

const clear = async () => {
  if (!password.value) {
    ElMessage.warning('请输入管理员密码')
    return
  }
  const saved = await store.clearChimaRecords(password.value)
  if (saved) {
    visible.value = false
    password.value = ''
  }
}
</script>

<template>
  <div class="lucky-page">
    <h1 class="lucky-page__heading">吃码盈亏</h1>
    <div class="lucky-summary"
      >总盈亏：{{ totalBet - totalWin }}，总中奖：{{ totalWin }}，总投分：{{ totalBet }}</div
    >
    <el-card shadow="never">
      <div class="mb-14px">
        <el-tooltip content="清理数据">
          <el-button type="danger" circle @click="visible = true"
            ><Icon icon="ep:delete"
          /></el-button>
        </el-tooltip>
      </div>
      <PaginatedTable :data="store.chimaRecords" border>
        <template #mobile="{ row }">
          <div class="lucky-mobile-card__title">
            <span>{{ row.periods || row.member || '-' }}</span>
            <strong>{{ Number(row.fakeAmount || 0) - Number(row.totalWin || 0) }}</strong>
          </div>
          <div class="lucky-mobile-card__meta">
            <span>投额：{{ row.fakeAmount || 0 }}</span>
            <span>中奖：{{ row.totalWin || 0 }}</span>
            <span>盈亏：{{ Number(row.fakeAmount || 0) - Number(row.totalWin || 0) }}</span>
          </div>
        </template>
        <el-table-column label="期数" min-width="160"
          ><template #default="{ row }">{{ row.periods || row.member }}</template></el-table-column
        >
        <el-table-column prop="fakeAmount" label="投额" min-width="120" />
        <el-table-column prop="totalWin" label="中奖" min-width="120" />
        <el-table-column label="盈亏" min-width="120"
          ><template #default="{ row }">{{
            Number(row.fakeAmount || 0) - Number(row.totalWin || 0)
          }}</template></el-table-column
        >
      </PaginatedTable>
    </el-card>

    <el-dialog v-model="visible" title="清理数据" width="460px" class="lucky-dialog">
      <el-form label-width="80px">
        <el-form-item label="密码"
          ><el-input v-model="password" type="password" show-password placeholder="Password"
        /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="store.saving" @click="clear">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>
