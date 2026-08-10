<script setup lang="ts">
import { reactive, watch } from 'vue'
import { useLucky5Store } from '@/store/modules/lottery'

const store = useLucky5Store()
const form = reactive({
  siZiXian: 0,
  sanZiXian: 0,
  erZiXian: 0,
  danZiXian: 0,
  siDingWei: 0,
  sanDingWei: 0,
  erDingWei: 0,
  yiDingWei: 0,
  yinKuiMax: 0,
  yinKuiMin: 0
})

watch(
  () => store.chimaConfig,
  (value) => Object.assign(form, value),
  { deep: true, immediate: true }
)
</script>

<template>
  <div class="lucky-page">
    <el-card class="lucky-card" shadow="never">
      <template #header>
        <strong>吃码额度设定</strong>
      </template>
      <el-form :model="form" class="lucky-original-form" label-width="150px">
        <el-alert
          class="mb-18px"
          title="模拟网盘模式下，吃码额度按每期、每种玩法累计；额度为 0 表示该玩法全部进入模拟网盘。"
          type="info"
          :closable="false"
          show-icon
        />
        <el-form-item label="四字现"
          ><el-input-number v-model="form.siZiXian" :controls="false"
        /></el-form-item>
        <el-form-item label="三字现"
          ><el-input-number v-model="form.sanZiXian" :controls="false"
        /></el-form-item>
        <el-form-item label="二字现"
          ><el-input-number v-model="form.erZiXian" :controls="false"
        /></el-form-item>
        <el-form-item label="单字现"
          ><el-input-number v-model="form.danZiXian" :controls="false"
        /></el-form-item>
        <el-form-item label="四定位"
          ><el-input-number v-model="form.siDingWei" :controls="false"
        /></el-form-item>
        <el-form-item label="三定位"
          ><el-input-number v-model="form.sanDingWei" :controls="false"
        /></el-form-item>
        <el-form-item label="二定位"
          ><el-input-number v-model="form.erDingWei" :controls="false"
        /></el-form-item>
        <el-form-item label="一定位"
          ><el-input-number v-model="form.yiDingWei" :controls="false"
        /></el-form-item>
        <el-form-item label="盈亏上限"
          ><el-input-number v-model="form.yinKuiMax" :controls="false"
        /></el-form-item>
        <el-form-item label="盈亏下限"
          ><el-input-number v-model="form.yinKuiMin" :controls="false"
        /></el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="store.saving" @click="store.saveChimaConfig(form)"
            >保存</el-button
          >
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

