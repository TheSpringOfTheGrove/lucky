<script setup lang="ts">
import { reactive } from 'vue'
import { useLucky5Store } from '@/store/modules/lottery'

type ShortLinkMode = 'CLOSED' | 'SHORT_2' | 'SHORT_3'

const store = useLucky5Store()
const form = reactive({ shortLinkMode: 'SHORT_2' as ShortLinkMode })

const save = async () => {
  // 短链接选项仅复刻旧后台的展示，所有实际玩家链接始终固定为群聊。
  await store.saveLinks({ groupLinkEnabled: true, privateLinkEnabled: false, defaultRoomMode: 'GROUP' })
}
</script>

<template>
  <div class="lucky-page">
    <el-card class="lucky-card" shadow="never">
      <template #header>
        <strong>配置信息</strong>
      </template>
      <el-form class="lucky-original-form lucky-link-form" label-width="120px">
        <el-form-item label="开启短链接">
          <el-radio-group v-model="form.shortLinkMode" class="room-mode-options">
            <el-radio value="CLOSED">关闭</el-radio>
            <el-radio value="SHORT_2">短链接2</el-radio>
            <el-radio value="SHORT_3">短链接3</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item class="lucky-link-form__action">
          <el-button type="primary" :loading="store.saving" @click="save">保存</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.room-mode-options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.lucky-link-form__action {
  min-height: 70px;
  margin-top: 22px;
  justify-content: flex-end;
  padding-top: 10px;
  border-top: 1px solid #f4f4f4;
}

.lucky-link-form__action :deep(.el-form-item__content) {
  display: flex;
  width: 100%;
  margin-left: 0 !important;
  justify-content: flex-end;
}

@media (width <= 768px) {
  .room-mode-options {
    gap: 4px 10px;
  }
}
</style>
