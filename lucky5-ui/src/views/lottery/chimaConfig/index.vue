<script setup lang="ts">
import { reactive, watch } from 'vue'
import { useLucky5Store } from '@/store/modules/lottery'
import { chimaConfigPayload, quotaInputValue } from '@/views/lottery/utils/chimaConfig'

const store = useLucky5Store()
const form = reactive({
  siZiXian: '0.0',
  sanZiXian: '0.0',
  erZiXian: '0.0',
  siDingWei: '0.0',
  sanDingWei: '0.0',
  erDingWei: '0.0',
  yiDingWei: '0.0',
  yinKuiMax: 0,
  yinKuiMin: 0
})

watch(
  () => store.chimaConfig,
  (value) => {
    // Only the seven play quotas use a decimal zero; profit/loss limits stay numeric.
    Object.assign(
      form,
      Object.fromEntries(
        Object.entries(value).map(([key, amount]) => [
          key,
          key === 'yinKuiMax' || key === 'yinKuiMin' ? Number(amount) || 0 : quotaInputValue(amount)
        ])
      )
    )
  },
  { deep: true, immediate: true }
)
</script>

<template>
  <div class="lucky-page">
    <el-card class="lucky-card" shadow="never">
      <template #header>
        <strong>吃码额度设定</strong>
      </template>
      <el-form :model="form" class="lucky-original-form chima-config-form" label-width="150px">
        <el-form-item label="四字现"
          ><el-input v-model="form.siZiXian" type="number" step="any" class="chima-quota-input"
        /></el-form-item>
        <el-form-item label="三字现"
          ><el-input v-model="form.sanZiXian" type="number" step="any" class="chima-quota-input"
        /></el-form-item>
        <el-form-item label="二字现"
          ><el-input v-model="form.erZiXian" type="number" step="any" class="chima-quota-input"
        /></el-form-item>
        <el-form-item label="四定位"
          ><el-input v-model="form.siDingWei" type="number" step="any" class="chima-quota-input"
        /></el-form-item>
        <el-form-item label="三定位"
          ><el-input v-model="form.sanDingWei" type="number" step="any" class="chima-quota-input"
        /></el-form-item>
        <el-form-item label="二定位"
          ><el-input v-model="form.erDingWei" type="number" step="any" class="chima-quota-input"
        /></el-form-item>
        <el-form-item label="一定位"
          ><el-input v-model="form.yiDingWei" type="number" step="any" class="chima-quota-input"
        /></el-form-item>
        <el-form-item label="盈亏上限"
          ><el-input-number v-model="form.yinKuiMax" :controls="false"
        /></el-form-item>
        <el-form-item label="盈亏下限"
          ><el-input-number v-model="form.yinKuiMin" :controls="false"
        /></el-form-item>
        <el-form-item class="chima-config-form__action">
          <el-button type="primary" :loading="store.saving" @click="store.saveChimaConfig(chimaConfigPayload(form))"
            >保存</el-button
          >
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.chima-config-form :deep(.el-input-number),
.chima-config-form .chima-quota-input {
  width: 190px;
}

.chima-config-form :deep(.el-input__inner) {
  text-align: left !important;
}

.chima-quota-input :deep(input) {
  appearance: textfield;
}

.chima-quota-input :deep(input::-webkit-inner-spin-button),
.chima-quota-input :deep(input::-webkit-outer-spin-button) {
  margin: 0;
  appearance: none;
}

.chima-config-form__action {
  margin-top: 20px;
}

.chima-config-form__action :deep(.el-form-item__content) {
  justify-content: flex-end;
}
</style>
