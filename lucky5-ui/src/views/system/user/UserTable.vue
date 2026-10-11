<template>
  <el-table :data="rows" v-loading="loading" @selection-change="(items) => emit('selection-change', items)">
    <el-table-column type="selection" width="55" />
    <el-table-column label="登录账号" align="center" min-width="120" show-overflow-tooltip>
      <template #default="scope">
        <el-button v-if="showOwnerCount" type="primary" link @click="emit('show-owners', scope.row)">
          {{ scope.row.username }}
        </el-button>
        <span v-else>{{ scope.row.username }}</span>
      </template>
    </el-table-column>
    <el-table-column label="用户昵称" align="center" min-width="120" show-overflow-tooltip>
      <template #default="scope">
        <el-button v-if="showOwnerCount" type="primary" link @click="emit('show-owners', scope.row)">
          {{ scope.row.nickname || '-' }}
        </el-button>
        <span v-else>{{ scope.row.nickname || '-' }}</span>
      </template>
    </el-table-column>
    <el-table-column v-if="showRole" label="角色" align="center" min-width="120">
      <template #default="scope">{{ scope.row.roleNames?.join('、') || '-' }}</template>
    </el-table-column>
    <el-table-column label="金额" align="left" min-width="90">
      <template #default="scope">
        {{ scope.row.accountType === 'ADMIN' ? '无限' : (scope.row.goldBalance ?? 0) }}
      </template>
    </el-table-column>
    <el-table-column v-if="showOwnerCount" label="老板账号数" align="center" min-width="110">
      <template #default="scope">
        <el-button type="primary" link @click="emit('show-owners', scope.row)">
          {{ scope.row.ownerCount ?? 0 }}
        </el-button>
      </template>
    </el-table-column>
    <el-table-column label="状态" min-width="85">
      <template #default="scope">
        <el-switch
          v-model="scope.row.status"
          :active-value="0"
          :inactive-value="1"
          :disabled="!checkPermi(['system:user:update'])"
          @change="emit('status-change', scope.row)"
        />
      </template>
    </el-table-column>
    <el-table-column v-if="showDates" label="到期时间" prop="expireTime" :formatter="dateFormatter" width="180" />
    <el-table-column v-if="showDates" label="创建时间" prop="createTime" :formatter="dateFormatter" width="180" />
    <el-table-column label="操作" align="center" width="160" fixed="right">
      <template #default="scope">
        <div class="flex items-center justify-center">
          <el-button type="primary" link @click="emit('update', scope.row.id)" v-hasPermi="['system:user:update']">
            <Icon icon="ep:edit" />修改
          </el-button>
          <el-dropdown
            trigger="click"
            @command="(command) => emit('command', command, scope.row)"
            v-hasPermi="['system:user:delete', 'system:user:update-password', 'system:permission:assign-user-role', 'system:user:create']"
          >
            <el-button type="primary" link><Icon icon="ep:d-arrow-right" /> 更多</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                  v-if="
                    (scope.row.accountType === 'AGENT' && isAdminAccount) ||
                    (scope.row.accountType === 'OWNER' &&
                      (isAdminAccount || (isAgentAccount && scope.row.parentAgentUserId === currentUserId)))
                  "
                  command="handleGold"
                >
                  <Icon icon="ep:coin" />{{ isAdminAccount ? '充值金币' : '分配金币' }}
                </el-dropdown-item>
                <el-dropdown-item v-if="checkPermi(['system:user:delete'])" command="handleDelete">
                  <Icon icon="ep:delete" />删除
                </el-dropdown-item>
                <el-dropdown-item v-if="checkPermi(['system:user:update-password'])" command="handleResetPwd">
                  <Icon icon="ep:key" />重置密码
                </el-dropdown-item>
                <el-dropdown-item v-if="isAdminAccount && showOwnerCount && checkPermi(['system:permission:assign-user-role'])" command="handleRole">
                  <Icon icon="ep:circle-check" />分配角色
                </el-dropdown-item>
                <el-dropdown-item v-if="canInitializeUser(scope.row)" command="handleLotteryInitialize">
                  <Icon icon="ep:setting" />初始化老板配置
                </el-dropdown-item>
                <el-dropdown-item v-if="canInitializeUser(scope.row)" command="handleAutoProxyData" :disabled="autoProxyInitializing">
                  <Icon icon="ep:user-filled" />自动托数据
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </template>
    </el-table-column>
  </el-table>
</template>

<script lang="ts" setup>
import { checkPermi } from '@/utils/permission'
import { dateFormatter } from '@/utils/formatTime'
import type { UserVO } from '@/api/system/user'

defineOptions({ name: 'SystemUserTable' })

const props = defineProps<{
  rows: UserVO[]
  loading: boolean
  showOwnerCount: boolean
  showRole: boolean
  showDates: boolean
  isAdminAccount: boolean
  isAgentAccount: boolean
  currentUserId: number
  autoProxyInitializing: boolean
}>()

const emit = defineEmits<{
  (event: 'selection-change', rows: UserVO[]): void
  (event: 'status-change', row: UserVO): void
  (event: 'update', id: number): void
  (event: 'command', command: string, row: UserVO): void
  (event: 'show-owners', row: UserVO): void
}>()

const canInitializeUser = (row: UserVO) =>
  row.accountType === 'OWNER' &&
  ((props.isAdminAccount && row.id !== props.currentUserId) ||
    (props.isAgentAccount && row.parentAgentUserId === props.currentUserId))
</script>
