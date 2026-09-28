<script setup lang="ts">
import LegacyTable from '@/components/LegacyTable'
import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  ref,
  watch
} from 'vue'
import { ElMessage } from 'element-plus'
import { useLucky5Store } from '@/store/modules/lottery'
import { legacyFooterHeaders } from '@/views/lottery/utils/legacyTable'

const store = useLucky5Store()
const visible = ref(false)
const password = ref('')
const page = ref(1)
const pageSize = 10
const totalBet = computed(() =>
  store.chimaRecords.reduce((sum, item) => sum + Number(item.fakeAmount || 0), 0)
)
const totalWin = computed(() =>
  store.chimaRecords.reduce((sum, item) => sum + Number(item.totalWin || 0), 0)
)
const pagedRows = computed(() => {
  const start = (page.value - 1) * pageSize
  return store.chimaRecords.slice(start, start + pageSize)
})
const firstItem = computed(() => (store.chimaRecords.length ? (page.value - 1) * pageSize + 1 : 0))
const lastItem = computed(() => Math.min(page.value * pageSize, store.chimaRecords.length))
const lastPage = computed(() => Math.max(1, Math.ceil(store.chimaRecords.length / pageSize)))
const money = (value: number) => value.toFixed(2)

watch(
  () => store.chimaRecords.length,
  () => {
    page.value = Math.min(page.value, lastPage.value)
  }
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
    <div class="chima-record-summary"
      >总盈亏：{{ money(totalBet - totalWin) }}，总中奖：{{ money(totalWin) }}，总投分：{{ money(totalBet) }}</div
    >
    <el-card shadow="never" class="lucky-card chima-record-card">
      <div class="chima-record-toolbar">
        <el-tooltip content="清理数据">
          <el-button class="chima-clear-button" @click="visible = true"
            ><Icon icon="fa:trash" :size="14"
          /></el-button>
        </el-tooltip>
      </div>
      <LegacyTable
        :data="pagedRows"
        border
        empty-text="No data available in table"
        class="chima-record-table"
        show-summary
        :summary-method="legacyFooterHeaders"
      >
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
      </LegacyTable>
      <div class="chima-record-footer">
        <span>显示{{ store.chimaRecords.length }}个条目中的{{ firstItem }}到{{ lastItem }}</span>
        <div class="chima-record-footer__pager">
          <el-button :disabled="page <= 1" @click="page -= 1">上一页</el-button>
          <el-button :disabled="page >= lastPage" @click="page += 1">下一页</el-button>
        </div>
      </div>
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

<style scoped>
.chima-record-summary {
  margin: 0 0 14px;
  color: #ff1e1e;
  font-size: 14px;
  line-height: 20px;
}

.chima-record-card :deep(.el-card__body) {
  padding: 10px;
}

.chima-record-toolbar {
  margin: 0 0 20px;
}

.chima-clear-button {
  width: 43px;
  height: 34px;
  padding: 0;
  color: #fff !important;
  background: #dd4b39 !important;
  border-color: #d73925 !important;
  border-radius: 0;
}

.chima-clear-button:hover,
.chima-clear-button:focus {
  background: #d73925 !important;
  border-color: #d73925 !important;
}

.chima-record-footer {
  position: relative;
  min-height: 44px;
  padding-top: 14px;
  color: #444;
  font-size: 14px;
}

.chima-record-footer__pager {
  position: absolute;
  top: 10px;
  left: 50%;
  display: flex;
  transform: translateX(-50%);
}

.chima-record-footer__pager :deep(.el-button) {
  min-height: 30px;
  margin: 0;
  padding: 5px 12px;
  border-radius: 2px;
}

.chima-record-footer__pager :deep(.el-button + .el-button) {
  margin-left: -1px;
}
</style>
