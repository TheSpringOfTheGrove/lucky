<script setup lang="ts">
import { reactive, watch } from 'vue'
import { useLucky5Store } from '@/store/modules/lottery'

type AccessMode = 'GROUP' | 'PRIVATE' | 'BOTH'
type RoomMode = 'GROUP' | 'PRIVATE'

const store = useLucky5Store()
const form = reactive({ accessMode: 'BOTH' as AccessMode, defaultRoomMode: 'GROUP' as RoomMode })

watch(
  () => store.links,
  (value) => {
    const groupEnabled = value.groupLinkEnabled !== false
    const privateEnabled = value.privateLinkEnabled !== false
    form.accessMode = groupEnabled && privateEnabled ? 'BOTH' : groupEnabled ? 'GROUP' : 'PRIVATE'
    form.defaultRoomMode = value.defaultRoomMode === 'PRIVATE' ? 'PRIVATE' : 'GROUP'
    if (form.accessMode !== 'BOTH') form.defaultRoomMode = form.accessMode
  },
  { deep: true, immediate: true }
)

const save = async () => {
  const groupLinkEnabled = form.accessMode === 'GROUP' || form.accessMode === 'BOTH'
  const privateLinkEnabled = form.accessMode === 'PRIVATE' || form.accessMode === 'BOTH'
  const defaultRoomMode = form.accessMode === 'BOTH' ? form.defaultRoomMode : form.accessMode
  await store.saveLinks({ groupLinkEnabled, privateLinkEnabled, defaultRoomMode })
}
</script>

<template>
  <div class="lucky-page">
    <el-card class="lucky-card" shadow="never">
      <template #header>
        <strong>玩家房间入口</strong>
      </template>
      <el-form class="lucky-original-form" label-width="120px">
        <el-form-item label="开放方式">
          <el-radio-group v-model="form.accessMode" class="room-mode-options">
            <el-radio-button value="GROUP">仅群聊</el-radio-button>
            <el-radio-button value="PRIVATE">仅私聊</el-radio-button>
            <el-radio-button value="BOTH">群聊和私聊</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.accessMode === 'BOTH'" label="默认入口">
          <el-radio-group v-model="form.defaultRoomMode">
            <el-radio value="GROUP">群聊</el-radio>
            <el-radio value="PRIVATE">私聊</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item>
          <div class="room-mode-help">
            <p><strong>群聊：</strong>同一老板的玩家可看到彼此的聊天和下注内容，也能看到自动托下注。</p>
            <p><strong>私聊：</strong>玩家只看到自己的消息、订单和机器人回复，不显示其他玩家或自动托。</p>
            <p>余额、上下分、退码和结算明细始终只对当前玩家显示。</p>
          </div>
        </el-form-item>
        <el-form-item>
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
}

.room-mode-help {
  max-width: 720px;
  padding: 12px 16px;
  color: #606266;
  background: #f7f9fc;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  line-height: 1.7;
}

.room-mode-help p {
  margin: 0;
}
</style>
