<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, watch } from 'vue'
import { useLucky5Store } from '@/store/modules/lottery'

const store = useLucky5Store()
const form = reactive({
  url: '',
  account: '',
  password: '',
  alertValue: 0,
  bossMode: false,
  playType: 2,
  useProxy: true
})

watch(
  () => store.config,
  (value) =>
    Object.assign(form, value, {
      password: value.password || ''
    }),
  { deep: true, immediate: true }
)

const connection = computed(() => store.market.connection)
const showConnectionStatus = computed(
  () => connection.value.status && !connection.value.status.includes('老板模式')
)
const statusType = computed(() => {
  if (connection.value.status === '已连接') return 'success'
  if (connection.value.status === '连接失败') return 'danger'
  return 'info'
})
const balanceText = computed(() => Number(connection.value.balance || 0).toFixed(2))
const passwordPlaceholder = computed(() => (store.config.hasPassword ? '已设置' : '请输入盘口密码'))

let balanceRefreshTimer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  void store.refreshMarketConnection()
  balanceRefreshTimer = setInterval(() => void store.refreshMarketConnection(), 1_000)
})
onBeforeUnmount(() => {
  if (balanceRefreshTimer) clearInterval(balanceRefreshTimer)
})

</script>

<template>
  <div class="lucky-page">
    <el-card class="lucky-card" shadow="never">
      <template #header>
        <div class="config-header">
          <strong>配置信息</strong>
          <span class="lucky-config-balance">余额：{{ balanceText }}</span>
          <el-tag v-if="showConnectionStatus" :type="statusType">{{ connection.status }}</el-tag>
        </div>
      </template>

      <el-form :model="form" class="lucky-original-form" label-width="150px">
        <el-form-item label="会员网址">
          <el-input v-model="form.url" placeholder="请输入盘口会员网址" />
        </el-form-item>
        <el-form-item label="会员用户名">
          <el-input v-model="form.account" placeholder="请输入盘口用户名" autocomplete="off" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="passwordPlaceholder"
            autocomplete="new-password"
          />
        </el-form-item>
        <el-form-item label="报警值">
          <el-input-number v-model="form.alertValue" :controls="false" placeholder="报警值" />
        </el-form-item>
        <el-form-item label="老板模式">
          <el-select v-model="form.bossMode">
            <el-option label="关闭" :value="false" />
            <el-option label="开启" :value="true" />
          </el-select>
        </el-form-item>
        <el-form-item label="玩法">
          <el-select v-model="form.playType">
            <el-option label="普通" :value="0" />
            <el-option label="龙虎和" :value="1" />
            <el-option label="普通+龙虎和" :value="2" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="form.useProxy">使用代理</el-checkbox>
          <el-button
            type="primary"
            class="ml-12px"
            :loading="store.saving"
            @click="store.saveConfig(form)"
          >
            保存
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.config-header {
  display: flex;
  align-items: center;
  gap: 20px;
}

.lucky-config-balance {
  margin-left: 60px;
  color: var(--el-color-danger);
}

.error-text {
  color: var(--el-color-danger);
}

@media (width <= 768px) {
  .config-header {
    flex-wrap: wrap;
    gap: 8px 12px;
  }

  .config-header strong {
    flex-basis: 100%;
  }

  .lucky-config-balance {
    margin-left: 0;
  }
}
</style>
