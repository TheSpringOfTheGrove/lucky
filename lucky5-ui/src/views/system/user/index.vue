<template>
  <el-row :gutter="20">
    <el-col :span="24">
      <!-- 搜索 -->
      <ContentWrap>
        <el-form
          class="-mb-15px"
          :model="queryParams"
          ref="queryFormRef"
          :inline="true"
          label-width="68px"
        >
          <el-form-item label="用户名称" prop="username">
            <el-input
              v-model="queryParams.username"
              placeholder="请输入用户名称"
              clearable
              @keyup.enter="handleQuery"
              class="!w-240px"
            />
          </el-form-item>
          <el-form-item label="手机号码" prop="mobile">
            <el-input
              v-model="queryParams.mobile"
              placeholder="请输入手机号码"
              clearable
              @keyup.enter="handleQuery"
              class="!w-240px"
            />
          </el-form-item>
          <el-form-item v-if="isAdminAccount" label="角色" prop="roleId">
            <el-select
              v-model="queryParams.roleId"
              placeholder="请选择角色"
              clearable
              class="!w-240px"
            >
              <el-option
                v-for="role in roleList"
                :key="role.id"
                :label="role.name"
                :value="role.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="状态" prop="status">
            <el-select
              v-model="queryParams.status"
              placeholder="请选择用户状态"
              clearable
              class="!w-240px"
            >
              <el-option
                v-for="dict in getIntDictOptions(DICT_TYPE.COMMON_STATUS)"
                :key="dict.value"
                :label="dict.label"
                :value="dict.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="创建时间" prop="createTime">
            <el-date-picker
              v-model="queryParams.createTime"
              value-format="YYYY-MM-DD HH:mm:ss"
              type="datetimerange"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              class="!w-240px"
            />
          </el-form-item>
          <el-form-item>
            <el-button @click="handleQuery"><Icon icon="ep:search" />搜索</el-button>
            <el-button @click="resetQuery"><Icon icon="ep:refresh" />重置</el-button>
            <el-button
              type="primary"
              plain
              @click="openForm('create')"
              v-hasPermi="['system:user:create']"
            >
              <Icon icon="ep:plus" /> 新增
            </el-button>
            <el-button
              type="warning"
              plain
              @click="handleImport"
              v-hasPermi="['system:user:import']"
            >
              <Icon icon="ep:upload" /> 导入
            </el-button>
            <el-button
              type="success"
              plain
              @click="handleExport"
              :loading="exportLoading"
              v-hasPermi="['system:user:export']"
            >
              <Icon icon="ep:download" />导出
            </el-button>
            <el-button
              type="danger"
              plain
              :disabled="checkedIds.length === 0"
              @click="handleDeleteBatch"
              v-hasPermi="['system:user:delete']"
            >
              <Icon icon="ep:delete" />批量删除
            </el-button>
          </el-form-item>
        </el-form>
      </ContentWrap>
      <div v-if="isAgentAccount" class="mb-15px pl-10px text-14px">
        当前可用金币：<strong>{{ agentAvailableGold ?? '加载中' }}</strong>
      </div>
      <ContentWrap>
        <el-table v-loading="loading" :data="list" @selection-change="handleRowCheckboxChange">
          <el-table-column type="selection" width="55" />
          <el-table-column label="用户编号" align="center" key="id" prop="id" />
          <el-table-column
            label="登录账号"
            align="center"
            prop="username"
            :show-overflow-tooltip="true"
          />
          <el-table-column
            label="用户昵称"
            align="center"
            prop="nickname"
            :show-overflow-tooltip="true"
          />
          <el-table-column label="角色" align="center" min-width="120">
            <template #default="scope">
              {{ scope.row.roleNames?.join('、') || '-' }}
            </template>
          </el-table-column>
          <el-table-column label="金额" align="left" min-width="90">
            <template #default="scope">
              {{ scope.row.accountType === 'ADMIN' ? '无限' : (scope.row.goldBalance ?? 0) }}
            </template>
          </el-table-column>
          <el-table-column label="状态" key="status">
            <template #default="scope">
              <el-switch
                v-model="scope.row.status"
                :active-value="0"
                :inactive-value="1"
                @change="handleStatusChange(scope.row)"
                :disabled="!checkPermi(['system:user:update'])"
              />
            </template>
          </el-table-column>
          <el-table-column
            label="到期时间"
            align="left"
            prop="expireTime"
            :formatter="dateFormatter"
            width="180"
          />
          <el-table-column
            label="创建时间"
            align="center"
            prop="createTime"
            :formatter="dateFormatter"
            width="180"
          />
          <el-table-column label="操作" align="center" width="160">
            <template #default="scope">
              <div class="flex items-center justify-center">
                <el-button
                  type="primary"
                  link
                  @click="openForm('update', scope.row.id)"
                  v-hasPermi="['system:user:update']"
                >
                  <Icon icon="ep:edit" />修改
                </el-button>
                <el-dropdown
                  trigger="click"
                  @command="(command) => handleCommand(command, scope.row)"
                  v-hasPermi="[
                    'system:user:delete',
                    'system:user:update-password',
                    'system:permission:assign-user-role',
                    'system:user:create'
                  ]"
                >
                  <el-button type="primary" link><Icon icon="ep:d-arrow-right" /> 更多</el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item
                        command="handleGold"
                        v-if="
                          (scope.row.accountType === 'AGENT' && checkRole(['super_admin'])) ||
                          (scope.row.accountType === 'OWNER' &&
                            (checkRole(['super_admin']) ||
                              (scope.row.parentAgentUserId === userStore.getUser.id &&
                                checkRole(['lottery_agent']))))
                        "
                      >
                        <Icon icon="ep:coin" />{{
                          scope.row.accountType === 'AGENT' || isAdminAccount ? '充值金币' : '分配金币'
                        }}
                      </el-dropdown-item>
                      <el-dropdown-item
                        command="handleDelete"
                        v-if="checkPermi(['system:user:delete'])"
                      >
                        <Icon icon="ep:delete" />删除
                      </el-dropdown-item>
                      <el-dropdown-item
                        command="handleResetPwd"
                        v-if="checkPermi(['system:user:update-password'])"
                      >
                        <Icon icon="ep:key" />重置密码
                      </el-dropdown-item>
                      <el-dropdown-item
                        command="handleRole"
                        v-if="isAdminAccount && checkPermi(['system:permission:assign-user-role'])"
                      >
                        <Icon icon="ep:circle-check" />分配角色
                      </el-dropdown-item>
                      <el-dropdown-item
                        command="handleLotteryInitialize"
                        v-if="canInitializeUser(scope.row)"
                      >
                        <Icon icon="ep:setting" />初始化老板配置
                      </el-dropdown-item>
                      <el-dropdown-item
                        command="handleAutoProxyData"
                        v-if="canInitializeUser(scope.row)"
                        :disabled="autoProxyInitializing"
                      >
                        <Icon icon="ep:user-filled" />自动托数据
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <Pagination
          :total="total"
          v-model:page="queryParams.pageNo"
          v-model:limit="queryParams.pageSize"
          @pagination="getList"
        />
      </ContentWrap>
    </el-col>
  </el-row>

  <!-- 添加或修改用户对话框 -->
  <UserForm ref="formRef" @success="handleUserSaved" />
  <el-dialog v-model="goldDialogVisible" :title="goldTarget?.accountType === 'AGENT' ? '代理金币' : isAdminAccount ? '老板金币' : '分配金币'" width="420px" class="max-w-[calc(100vw-24px)]">
    <div class="mb-12px">账号：{{ goldTarget?.username }}</div>
    <div class="mb-12px">当前金币：{{ goldTarget?.goldBalance ?? 0 }}</div>
    <el-form label-width="72px">
      <el-form-item label="操作">
        <el-radio-group v-model="goldDirection">
          <el-radio value="increase">增加</el-radio>
          <el-radio value="decrease">减少</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="金币数量">
        <el-input v-model.trim="goldAmount" inputmode="numeric" placeholder="请输入正整数" />
      </el-form-item>
    </el-form>
    <div class="text-12px text-#909399">1 金币 = 24 小时；减少老板金币会缩短期限。代理收回自己分配的金币时返还代理余额；管理员直接充值不改变代理余额。</div>
    <template #footer>
      <el-button @click="goldDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="goldSubmitting" @click="submitGold">确认</el-button>
    </template>
  </el-dialog>
  <!-- 用户导入对话框 -->
  <UserImportForm ref="importFormRef" @success="getList" />
  <!-- 分配角色 -->
  <UserAssignRoleForm ref="assignRoleFormRef" @success="getList" />
</template>
<script lang="ts" setup>
import { DICT_TYPE, getIntDictOptions } from '@/utils/dict'
import { checkPermi, checkRole } from '@/utils/permission'
import { dateFormatter } from '@/utils/formatTime'
import download from '@/utils/download'
import { CommonStatusEnum } from '@/utils/constants'
import * as UserApi from '@/api/system/user'
import * as RoleApi from '@/api/system/role'
import * as AgentStatisticsApi from '@/api/system/agentStatistics'
import { initializeLotteryAutoProxyDataApi, initializeLotteryOwnerApi } from '@/api/lottery'
import { useUserStore } from '@/store/modules/user'
import UserForm from './UserForm.vue'
import UserImportForm from './UserImportForm.vue'
import UserAssignRoleForm from './UserAssignRoleForm.vue'

defineOptions({ name: 'SystemUser' })

const message = useMessage() // 消息弹窗
const { t } = useI18n() // 国际化
const userStore = useUserStore()
const isAdminAccount = computed(() => userStore.getRoles.includes('super_admin'))
const isAgentAccount = computed(() => userStore.getRoles.includes('lottery_agent') && !isAdminAccount.value)
const canInitializeUser = (row: UserApi.UserVO) =>
  (isAdminAccount.value && row.id !== userStore.getUser.id) ||
  (isAgentAccount.value &&
    row.accountType === 'OWNER' &&
    row.parentAgentUserId === userStore.getUser.id)
const agentAvailableGold = ref<number | null>(null)
const refreshAgentGold = async () => {
  if (isAgentAccount.value) {
    agentAvailableGold.value = (await AgentStatisticsApi.getSummary()).availableGold
  }
}

const loading = ref(true) // 列表的加载中
const total = ref(0) // 列表的总页数
const list = ref([]) // 列表的数
const roleList = ref<RoleApi.RoleVO[]>([])
const queryParams = reactive({
  pageNo: 1,
  pageSize: 10,
  username: undefined,
  mobile: undefined,
  roleId: undefined as number | undefined,
  status: undefined,
  createTime: []
})
const queryFormRef = ref() // 搜索的表单

/** 查询列表 */
const getList = async () => {
  loading.value = true
  try {
    const data = await UserApi.getUserPage(queryParams)
    list.value = data.list
    total.value = data.total
    await refreshAgentGold()
  } finally {
    loading.value = false
  }
}

/** 搜索按钮操作 */
const handleQuery = () => {
  queryParams.pageNo = 1
  getList()
}

/** 重置按钮操作 */
const resetQuery = () => {
  queryFormRef.value?.resetFields()
  handleQuery()
}

/** 添加/修改操作 */
const formRef = ref()
const openForm = (type: string, id?: number) => {
  formRef.value.open(type, id)
}

/** 用户导入 */
const importFormRef = ref()
const handleImport = () => {
  importFormRef.value.open()
}

/** 修改用户状态 */
const handleStatusChange = async (row: UserApi.UserVO) => {
  try {
    // 修改状态的二次确认
    const text = row.status === CommonStatusEnum.ENABLE ? '启用' : '停用'
    await message.confirm('确认要"' + text + '""' + row.username + '"用户吗?')
    // 发起修改状态
    await UserApi.updateUserStatus(row.id, row.status)
    // 刷新列表
    await getList()
  } catch {
    // 取消后，进行恢复按钮
    row.status =
      row.status === CommonStatusEnum.ENABLE ? CommonStatusEnum.DISABLE : CommonStatusEnum.ENABLE
  }
}

/** 导出按钮操作 */
const exportLoading = ref(false)
const handleExport = async () => {
  try {
    // 导出的二次确认
    await message.exportConfirm()
    // 发起导出
    exportLoading.value = true
    const data = await UserApi.exportUser(queryParams)
    download.excel(data, '用户数据.xls')
  } catch {
  } finally {
    exportLoading.value = false
  }
}

/** 操作分发 */
const handleCommand = (command: string, row: UserApi.UserVO) => {
  switch (command) {
    case 'handleGold':
      handleGold(row)
      break
    case 'handleDelete':
      handleDelete(row.id)
      break
    case 'handleResetPwd':
      handleResetPwd(row)
      break
    case 'handleRole':
      handleRole(row)
      break
    case 'handleLotteryInitialize':
      handleLotteryInitialize(row)
      break
    case 'handleAutoProxyData':
      handleAutoProxyData(row)
      break
    default:
      break
  }
}

const goldDialogVisible = ref(false)
const goldTarget = ref<UserApi.UserVO | null>(null)
const goldDirection = ref<'increase' | 'decrease'>('increase')
const goldAmount = ref('')
const goldSubmitting = ref(false)
const goldPendingOperation = ref<{ amount: number; key: string } | null>(null)
const handleGold = (row: UserApi.UserVO) => {
  goldTarget.value = row
  goldDirection.value = 'increase'
  goldAmount.value = ''
  goldPendingOperation.value = null
  goldDialogVisible.value = true
}

const submitGold = async () => {
  const row = goldTarget.value
  if (!row || goldSubmitting.value) return
  if (!/^[1-9]\d*$/.test(goldAmount.value) || !Number.isSafeInteger(Number(goldAmount.value)) || Number(goldAmount.value) > 36500) {
    message.error('请输入 1 至 36500 的整数金币数')
    return
  }
  const amount = Number(goldAmount.value) * (goldDirection.value === 'increase' ? 1 : -1)
  if (!goldPendingOperation.value || goldPendingOperation.value.amount !== amount) {
    goldPendingOperation.value = { amount, key: crypto.randomUUID() }
  }
  goldSubmitting.value = true
  try {
    if (row.accountType === 'AGENT') {
      await UserApi.grantAgentGold(row.id, amount, goldPendingOperation.value.key)
    } else {
      await UserApi.allocateOwnerGold(row.id, amount, goldPendingOperation.value.key)
    }
    message.success('金币操作成功')
    goldDialogVisible.value = false
    await getList()
  } catch {
    // 请求失败或结果不确定时保留操作号，重试由服务端幂等处理。
  } finally {
    goldSubmitting.value = false
  }
}

/** 删除按钮操作 */
const handleDelete = async (id: number) => {
  try {
    // 删除的二次确认
    await message.delConfirm()
    // 发起删除
    await UserApi.deleteUser(id)
    message.success(t('common.delSuccess'))
    // 刷新列表
    await getList()
  } catch {}
}

/** 批量删除按钮操作 */
const checkedIds = ref<number[]>([])
const handleRowCheckboxChange = (rows: UserApi.UserVO[]) => {
  checkedIds.value = rows.map((row) => row.id)
}

const handleDeleteBatch = async () => {
  try {
    // 删除的二次确认
    await message.delConfirm()
    // 发起批量删除
    await UserApi.deleteUserList(checkedIds.value)
    checkedIds.value = []
    message.success(t('common.delSuccess'))
    // 刷新列表
    await getList()
  } catch {}
}

/** 重置密码 */
const handleResetPwd = async (row: UserApi.UserVO) => {
  try {
    // 重置的二次确认
    const result = await message.prompt(
      '请输入"' + row.username + '"的新密码',
      t('common.reminder')
    )
    const password = result.value
    // 发起重置
    await UserApi.resetUserPassword(row.id, password)
    message.success('修改成功，新密码是：' + password)
  } catch {}
}

/** 分配角色 */
const assignRoleFormRef = ref()
const handleRole = (row: UserApi.UserVO) => {
  assignRoleFormRef.value.open(row)
}

/** 列表与顶部当前账号期限都重新读取服务端，不能保留旧期限。 */
const handleUserSaved = async () => {
  await getList()
  await userStore.setUserInfoAction()
}

/** 手动初始化老板配置 */
const handleLotteryInitialize = async (row: UserApi.UserVO) => {
  try {
    await message.confirm(
      `确认初始化“${row.username}”的老板基础配置吗？本操作只补齐缺失数据，不会覆盖已有会员、订单和配置。`
    )
    const result = await initializeLotteryOwnerApi(row.id)
    message.success(`初始化完成，累计执行 ${result.initializationCount} 次`)
  } catch {}
}

/** 仅补齐老板名下的自动托及 admin 预设，不触碰其它老板配置。 */
const autoProxyInitializing = ref(false)
const handleAutoProxyData = async (row: UserApi.UserVO) => {
  if (autoProxyInitializing.value) return
  try {
    await message.confirm(
      `确认补齐“${row.username}”的自动托数据吗？将自动托补至 10 个，并复制当前 admin 缺失的预设订单；不会修改已有会员、余额和预设内容。`
    )
    autoProxyInitializing.value = true
    const result = await initializeLotteryAutoProxyDataApi(row.id)
    message.success(`已新增 ${result.botsCreated} 个自动托、${result.presetsCreated} 条预设订单`)
    await getList()
  } catch {
  } finally {
    autoProxyInitializing.value = false
  }
}

/** 初始化 */
onMounted(() => {
  getList()
  if (isAdminAccount.value) {
    RoleApi.getSimpleRoleList().then((roles) => {
      const ownerRole =
        roles.find((role) => role.code === 'crm_admin') ??
        roles.find((role) => role.code === 'tenant_admin')
      roleList.value = roles
        .filter(
          (role) =>
            ['super_admin', 'lottery_agent'].includes(role.code) || role.id === ownerRole?.id
        )
        .map((role) => ({
          ...role,
          name:
            role.id === ownerRole?.id
              ? '老板账号'
              : role.code === 'lottery_agent'
                ? '代理账号'
                : '超级管理员'
        }))
    })
  }
})
onActivated(() => {
  if (isAgentAccount.value && agentAvailableGold.value !== null) void refreshAgentGold()
})
</script>
