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
const balanceText = computed(() => Number(connection.value.balance || 0).toFixed(2))
let balanceRefreshTimer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  void store.refreshMarketConnection()
  balanceRefreshTimer = setInterval(() => void store.refreshMarketConnection(), 30_000)
})
onBeforeUnmount(() => {
  if (balanceRefreshTimer) clearInterval(balanceRefreshTimer)
})

</script>

<template>
  <div class="lucky-page lucky-legacy-content">
    <section class="legacy-box legacy-box-info">
      <header class="legacy-box__header">
        <strong>配置信息</strong>
        <span class="legacy-config-balance">余额：{{ balanceText }}</span>
      </header>

      <el-form :model="form" class="legacy-horizontal-form">
        <el-form-item label="会员网址">
          <el-input v-model="form.url" placeholder="请输入会员网址，例如：fs.ww369963.xyz" />
        </el-form-item>
        <el-form-item label="会员用户名">
          <el-input v-model="form.account" placeholder="请输入会员用户名" autocomplete="off" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            :placeholder="store.config.hasPassword ? '已设置' : '请输入会员密码'"
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
        <el-form-item class="legacy-form-actions">
          <el-checkbox v-model="form.useProxy">使用代理</el-checkbox>
          <el-button
            type="primary"
            class="legacy-save-button"
            :loading="store.saving"
            @click="store.saveConfig(form)"
          >
            保存
          </el-button>
        </el-form-item>
      </el-form>
    </section>
  </div>
</template>

<style scoped>
.lucky-legacy-content {
  padding-top: 15px;
}

.legacy-box {
  margin-bottom: 20px;
  background: #fff;
  border: 1px solid #f4f4f4;
  border-top: 3px solid #00c0ef;
  border-radius: 3px;
  box-shadow: 0 1px 1px rgb(0 0 0 / 10%);
}

.legacy-box__header {
  min-height: 47px;
  padding: 13px 15px;
  color: #444;
  border-bottom: 1px solid #f4f4f4;
  box-sizing: border-box;
  font-size: 14px;
  line-height: 20px;
}

.legacy-config-balance {
  margin-left: 96px;
  color: #f00;
}

.legacy-config-notice {
  color: #f00;
}

.legacy-horizontal-form {
  padding: 15px 0 0;
}

.legacy-horizontal-form :deep(.el-form-item) {
  display: grid;
  min-height: 34px;
  margin-bottom: 15px;
  grid-template-columns: 16.6667% minmax(0, 1fr);
}

.legacy-horizontal-form :deep(.el-form-item__label) {
  display: block;
  width: auto !important;
  height: 34px;
  padding: 7px 15px 0 0;
  color: #333;
  font-weight: 400;
  line-height: 20px;
  text-align: right;
  box-sizing: border-box;
  justify-self: stretch;
}

.legacy-horizontal-form :deep(.el-form-item__content) {
  min-width: 0;
  margin-left: 0 !important;
  line-height: 34px;
}

.legacy-horizontal-form :deep(.el-input),
.legacy-horizontal-form :deep(.el-input-number),
.legacy-horizontal-form :deep(.el-select) {
  width: 100%;
}

.legacy-horizontal-form :deep(.el-input__wrapper),
.legacy-horizontal-form :deep(.el-select__wrapper) {
  min-height: 34px;
  border-radius: 0;
  box-shadow: 0 0 0 1px #d2d6de inset;
}

.legacy-form-actions {
  min-height: 50px !important;
  margin: 0 !important;
  padding: 10px 15px;
  border-top: 1px solid #f4f4f4;
  grid-template-columns: 1fr !important;
}

.legacy-form-actions :deep(.el-form-item__label) {
  display: none;
}

.legacy-form-actions :deep(.el-form-item__content) {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

.legacy-save-button {
  min-width: 54px;
  height: 34px;
  margin-top: 0;
  border-radius: 0;
}

@media (width <= 768px) {
  .legacy-config-balance {
    margin-left: 12px;
  }

  .legacy-horizontal-form :deep(.el-form-item) {
    grid-template-columns: 110px minmax(0, 1fr);
  }
}
</style>
