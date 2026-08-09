<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useLucky5Store } from '@/store/modules/lottery'

const store = useLucky5Store()
const settleVisible = ref(false)
const issuePeriod = ref('')
const form = reactive({ period: '', result: '' })
const issue = computed(() => store.market.issue)

const submit = async () => {
  if (!form.period.trim() || !form.result.trim()) {
    ElMessage.warning('请填写期号和开奖号码')
    return
  }
  const saved: any = await store.settlePeriod(form.period.trim(), form.result.trim())
  if (saved) {
    settleVisible.value = false
    const result = saved.data
    await ElMessageBox.alert(
      `开奖结果：${result.result}（${result.bigSmall} / ${result.oddEven} / ${result.dragonTiger}）\n结算 ${result.orders} 单，投额 ${result.totalBet}，派彩 ${result.totalPayout}`,
      '结算完成',
      { confirmButtonText: '确定' }
    )
  }
}

const changeIssue = async (status: 'open' | 'close') => {
  const period = issuePeriod.value.trim() || issue.value?.period || ''
  if (!period) return ElMessage.warning('请填写期号')
  await store.setIssueStatus(period, status)
  issuePeriod.value = period
}
</script>

<template>
  <div class="lucky-page">
    <h1 class="lucky-page__heading">开奖历史记录 <small>期号状态与开奖结算</small></h1>

    <el-card shadow="never" class="mb-16px">
      <el-descriptions :column="4" border>
        <el-descriptions-item label="当前期号">{{ issue?.period || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{
          issue?.status || 'UNAVAILABLE'
        }}</el-descriptions-item>
        <el-descriptions-item label="剩余秒数">{{
          issue?.remainingSeconds ?? '-'
        }}</el-descriptions-item>
        <el-descriptions-item label="下一期">{{ issue?.nextPeriod || '-' }}</el-descriptions-item>
      </el-descriptions>
      <div class="issue-actions">
        <el-input v-model="issuePeriod" placeholder="手动期号" style="width: 220px" />
        <el-button type="success" :loading="store.saving" @click="changeIssue('open')"
          >手动开盘</el-button
        >
        <el-button type="warning" :loading="store.saving" @click="changeIssue('close')"
          >手动封盘</el-button
        >
        <el-button :loading="store.saving" @click="store.settlePendingIssues()"
          >补跑自动结算</el-button
        >
        <el-button type="primary" @click="settleVisible = true">手动开奖/结算</el-button>
      </div>
      <el-alert
        title="盘口同步会自动驱动开盘、封盘和开奖；老板模式无开奖源时可使用手动操作。自动结算具备重复处理保护。"
        type="info"
        :closable="false"
      />
    </el-card>

    <el-card shadow="never">
      <PaginatedTable :data="store.drawHistory" border>
        <el-table-column prop="period" label="期号" min-width="160" />
        <el-table-column prop="settledAt" label="开奖时间" min-width="200" />
        <el-table-column prop="result" label="开奖号码" min-width="160" />
        <el-table-column label="状态" min-width="110">
          <template #default="{ row }">{{ row.status || '已开奖' }}</template>
        </el-table-column>
      </PaginatedTable>
    </el-card>

    <el-dialog v-model="settleVisible" title="手动开奖/结算" width="500px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="期号"
          ><el-input v-model="form.period" placeholder="例如：20260808001"
        /></el-form-item>
        <el-form-item label="开奖号码"
          ><el-input v-model="form.result" placeholder="例如：12345"
        /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="settleVisible = false">取消</el-button>
        <el-button type="primary" :loading="store.saving" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.issue-actions {
  display: flex;
  gap: 10px;
  align-items: center;
  margin: 16px 0;
}
</style>


