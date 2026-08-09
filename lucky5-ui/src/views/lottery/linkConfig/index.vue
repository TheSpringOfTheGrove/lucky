<script setup lang="ts">
import { reactive, watch } from 'vue'
import { useLucky5Store } from '@/store/modules/lottery'

const store = useLucky5Store()
const form = reactive({ shortUrlMode: 2 })

watch(
  () => store.links,
  (value) => {
    form.shortUrlMode = Number(value.shortUrlMode ?? 2)
  },
  { deep: true, immediate: true }
)
</script>

<template>
  <div class="lucky-page">
    <el-card class="lucky-card" shadow="never">
      <template #header>
        <strong>配置信息</strong>
      </template>
      <el-form class="lucky-original-form" label-width="150px">
        <el-form-item label="开启短链接">
          <el-radio-group v-model="form.shortUrlMode">
            <el-radio :value="0">关闭</el-radio>
            <el-radio :value="2">短链接2</el-radio>
            <el-radio :value="3">短链接3</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="store.saving" @click="store.saveLinks(form)"
            >保存</el-button
          >
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>


