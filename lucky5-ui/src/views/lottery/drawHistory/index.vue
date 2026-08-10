<script setup lang="ts">
import { useMediaQuery } from '@vueuse/core'
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useLucky5Store } from '@/store/modules/lottery'

const store = useLucky5Store()
const settleVisible = ref(false)
const issuePeriod = ref('')
const form = reactive({ period: '', result: '', reason: '' })
const issue = computed(() => store.market.issue)
const isMobile = useMediaQuery('(max-width: 768px)')

const submit = async () => {
  const normalizedResult = form.result.replace(/[,，\s]/g, '')
  if (!form.period.trim() || !normalizedResult || !form.reason.trim()) {
    ElMessage.warning('请填写期号、开奖号码和人工开奖原因')
    return
  }
  if (!/^\d{5}$/.test(normalizedResult)) {
    ElMessage.warning('开奖号码必须是完整的五位数字')
    return
  }
  if (normalizedResult === '00000') {
    ElMessage.error('00000 属于异常开奖号码，禁止结算和派奖')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认使用 ${normalizedResult} 对 ${form.period.trim()} 期执行人工开奖、结算和派奖？`,
      '人工开奖二次确认',
      { type: 'warning', confirmButtonText: '确认结算', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  const saved: any = await store.settlePeriod(
    form.period.trim(),
    normalizedResult,
    form.reason.trim()
  )
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
      <el-descriptions :column="isMobile ? 1 : 4" border>
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
        title="只有开奖API二次确认的正常五位号码才会自动结算；00000 和非法号码会标记异常并停止派奖。"
        type="info"
        :closable="false"
      />
    </el-card>

    <el-card v-if="store.drawAlerts.length" shadow="never" class="mb-16px">
      <template #header><strong>开奖确认与异常</strong></template>
      <div v-if="isMobile" class="draw-alert-list">
        <article v-for="row in store.drawAlerts" :key="row.period" class="draw-alert-item">
          <div class="lucky-mobile-card__title">
            <span>{{ row.period }}</span>
            <el-tag type="danger" size="small">{{ row.status }}</el-tag>
          </div>
          <div class="lucky-mobile-card__meta">
            <span>API号码：{{ row.result || '-' }}</span>
            <span>确认 {{ row.drawConfirmations || 0 }} 次</span>
          </div>
          <div v-if="row.error" class="lucky-mobile-card__content lucky-danger">{{
            row.error
          }}</div>
        </article>
      </div>
      <el-table v-else :data="store.drawAlerts" border>
        <el-table-column prop="period" label="期号" min-width="150" />
        <el-table-column prop="status" label="状态" min-width="140" />
        <el-table-column prop="result" label="API号码" min-width="120" />
        <el-table-column prop="drawConfirmations" label="确认次数" min-width="100" />
        <el-table-column prop="error" label="说明" min-width="260" />
      </el-table>
    </el-card>

    <el-card shadow="never">
      <PaginatedTable :data="store.drawHistory" border>
        <template #mobile="{ row }">
          <div class="lucky-mobile-card__title">
            <span>{{ row.period }}</span>
            <span>{{ row.result }}</span>
          </div>
          <div class="lucky-mobile-card__meta">
            <span>{{ row.settledAt }}</span>
            <span>{{ row.status || '已开奖' }}</span>
          </div>
        </template>
        <el-table-column prop="period" label="期号" min-width="160" />
        <el-table-column prop="settledAt" label="开奖时间" min-width="200" />
        <el-table-column prop="result" label="开奖号码" min-width="160" />
        <el-table-column label="状态" min-width="110">
          <template #default="{ row }">{{ row.status || '已开奖' }}</template>
        </el-table-column>
      </PaginatedTable>
    </el-card>

    <el-dialog v-model="settleVisible" title="手动开奖/结算" width="500px" class="lucky-dialog">
      <el-form :model="form" label-width="100px">
        <el-form-item label="期号"
          ><el-input v-model="form.period" placeholder="例如：20260808001"
        /></el-form-item>
        <el-form-item label="开奖号码"
          ><el-input v-model="form.result" placeholder="例如：12345"
        /></el-form-item>
        <el-form-item label="开奖原因"
          ><el-input
            v-model="form.reason"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="请填写人工开奖原因"
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
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin: 16px 0;
}

.draw-alert-list {
  display: grid;
  gap: 10px;
}

.draw-alert-item {
  padding: 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
}

@media (width <= 768px) {
  .issue-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .issue-actions :deep(.el-input),
  .issue-actions :deep(.el-button) {
    width: 100% !important;
    margin-left: 0;
  }
}
</style>
