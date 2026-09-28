<script setup lang="ts">
import { useMediaQuery } from '@vueuse/core'
import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  reactive,
  ref
} from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import QRCode from 'qrcode'
import { useLucky5Store } from '@/store/modules/lottery'
import { legacyFooterHeaders } from '@/views/lottery/utils/legacyTable'
import { lotteryPlayerAvatarSrc } from '@/views/lottery/utils/playerAvatar'

const store = useLucky5Store()
const timeType = ref(0)
const puller = ref('')
const memberSearchInput = ref('')
const memberSearchKeyword = ref('')
const memberVisible = ref(false)
const transferVisible = ref(false)
const clearAllVisible = ref(false)
const clearPassword = ref('')
const linkVisible = ref(false)
const linkData = ref<Record<string, string | number>>({})
const linkQrs = ref<Record<string, string>>({})
const linkMember = ref('')
const detailsVisible = ref(false)
const details = ref<any>(null)
const isMobile = useMediaQuery('(max-width: 768px)')
const memberForm = reactive({
  id: '',
  avatar: 1,
  name: '',
  balance: 0,
  status: '离线',
  partner: '无',
  normalRate: 0,
  lhhRate: 0,
  tag: '普通',
  autoProxy: false,
  searchable: true,
  privateChat: false,
  webOnly: false,
  blueWhalePassword: ''
})
const transferForm = reactive({ id: '', type: '上分' as '上分' | '下分', amount: 0 })

const pullers = computed(() => [
  ...new Set(store.members.map((item) => item.partner).filter((item) => item && item !== '无'))
])
const rows = computed(() => {
  const keyword = memberSearchKeyword.value.trim().toLocaleLowerCase()
  return store.members.filter((item) => {
    if (puller.value && item.partner !== puller.value) return false
    if (!keyword) return true
    return [item.name, item.externalNickname].some((value) =>
      String(value || '').toLocaleLowerCase().includes(keyword)
    )
  })
})
const linkEntries = computed(() => {
  // 玩家端只开放群聊。链接 3 沿用接口短链接，链接 5 使用浏览器可识别的
  // 百分号编码形式，供封号严重时复制到浏览器打开。
  const groupUrl = String(
    linkData.value.groupUrl || linkData.value.roomUrl || linkData.value.longUrl || ''
  )
  const link3 = String(linkData.value.shortUrl || groupUrl)
  const toBrowserOnlyLink = (value: string) => {
    try {
      const parsed = new URL(value)
      const encodedHost = Array.from(parsed.hostname)
        .map((character) => `%${character.charCodeAt(0).toString(16).padStart(2, '0')}`)
        .join('')
      return `${parsed.protocol}//${encodedHost}${parsed.port ? `:${parsed.port}` : ''}${parsed.pathname}${parsed.search}${parsed.hash}`
    } catch {
      return value
    }
  }
  const entries = [
    { key: 'original', label: '原始', url: groupUrl, withQr: true },
    { key: 'link3', label: '链接3', url: link3, withQr: true },
    { key: 'link5', label: '链接5', url: toBrowserOnlyLink(groupUrl), withQr: false }
  ]
  return entries.filter((item) => item.url)
})
const realMembers = computed(() =>
  store.members.filter((item) => item.memberType !== 'BOT' && !item.autoProxy)
)
const proxyMembers = computed(() =>
  store.members.filter((item) => item.memberType === 'BOT' || item.autoProxy)
)
const totalBalance = computed(() =>
  store.members.reduce((sum, item) => sum + Number(item.balance || 0), 0)
)
const proxyBalance = computed(() =>
  proxyMembers.value.reduce((sum, item) => sum + Number(item.balance || 0), 0)
)
const realBalance = computed(() =>
  realMembers.value.reduce((sum, item) => sum + Number(item.balance || 0), 0)
)
const totalBet = computed(() =>
  store.members.reduce((sum, item) => sum + Number(item.totalBet || 0), 0)
)
const proxyBet = computed(() =>
  proxyMembers.value.reduce((sum, item) => sum + Number(item.totalBet || 0), 0)
)
const realBet = computed(() =>
  realMembers.value.reduce((sum, item) => sum + Number(item.totalBet || 0), 0)
)
const totalProfit = computed(() =>
  store.members.reduce((sum, item) => sum + Number(item.profitLoss || 0), 0)
)
const proxyProfit = computed(() =>
  proxyMembers.value.reduce((sum, item) => sum + Number(item.profitLoss || 0), 0)
)
const realProfit = computed(() =>
  realMembers.value.reduce((sum, item) => sum + Number(item.profitLoss || 0), 0)
)

const openMember = (row?: any) => {
  Object.assign(memberForm, {
    id: row?.id || '',
    avatar: Number(row?.avatar || 1),
    name: row?.name || '',
    balance: Number(row?.balance || 0),
    status: row?.status || '离线',
    partner: row?.partner || '无',
    normalRate: Number(row?.normalRate || 0),
    lhhRate: Number(row?.lhhRate || 0),
    tag: row?.tag || '普通',
    autoProxy: Boolean(row?.autoProxy),
    searchable: row?.searchable !== false,
    privateChat: Boolean(row?.privateChat),
    webOnly: Boolean(row?.webOnly),
    blueWhalePassword: row?.blueWhalePassword || ''
  })
  memberVisible.value = true
}

const openTransfer = (row: any, type: '上分' | '下分') => {
  Object.assign(transferForm, { id: row.id, type, amount: 0 })
  transferVisible.value = true
}

const toggleEat = (row: any) =>
  store.saveMember({
    ...row,
    eatEnabled: !row.eatEnabled,
    autoBetEnabled: Boolean(row.autoProxy)
  })

const submitMember = async () => {
  const saved = await store.saveMember(memberForm)
  if (saved) memberVisible.value = false
}

const submitTransfer = async () => {
  const saved = await store.transferMember(transferForm.id, transferForm.amount, transferForm.type)
  if (saved) transferVisible.value = false
}

const clearAllFlows = async () => {
  if (!clearPassword.value) {
    ElMessage.warning('请输入管理员密码')
    return
  }
  const saved = await store.clearAllMemberFlows(clearPassword.value)
  if (saved) {
    clearAllVisible.value = false
    clearPassword.value = ''
  }
}

const clearAllFingerprints = async () => {
  try {
    await ElMessageBox.confirm('确认抹除全部会员的登录标识？', '抹除标识', { type: 'warning' })
    await store.clearAllFingerprints()
  } catch {
    // User cancelled.
  }
}

const openLinks = async (row: any) => {
  const data = await store.getMemberLinks(row.id)
  if (!data) return
  linkMember.value = row.name
  await setLinkData(data)
  linkVisible.value = true
}

const setLinkData = async (data: Record<string, string | number>) => {
  linkData.value = data
  linkQrs.value = {}
  await Promise.all(
    linkEntries.value.map(async (item) => {
      linkQrs.value[item.key] = await QRCode.toDataURL(item.url, {
        width: 260,
        margin: 1,
        color: { dark: '#111111', light: '#ffffff' }
      })
    })
  )
}

const rotateLink = async () => {
  try {
    await ElMessageBox.confirm('换链接后，旧的房间链接会立即失效。确认更换？', '换链接', {
      type: 'warning',
      confirmButtonText: '确认更换',
      cancelButtonText: '取消'
    })
    const member = store.members.find((item) => item.name === linkMember.value)
    if (!member) return
    const data = await store.rotateMemberLink(member.id)
    if (data) await setLinkData(data)
  } catch {
    // User cancelled.
  }
}

const copy = async (value: string) => {
  await navigator.clipboard.writeText(value)
  ElMessage.success('已复制')
}

const clearMember = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确认清理 ${row.name} 的投额和盈亏流水？`, '清理流水', {
      type: 'warning'
    })
    await store.clearMember(row.id)
  } catch {
    // User cancelled.
  }
}

const openDetails = async (row: any) => {
  const data = await store.getMemberDetails(row.id)
  if (!data) return
  details.value = data
  detailsVisible.value = true
}

const deleteMember = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确认删除会员 ${row.name}？`, '删除会员', { type: 'warning' })
    await store.remove('members', row.id)
  } catch {
    // User cancelled.
  }
}

const avatarColor = (avatar: number) =>
  ['#30bbbb', '#3d9970', '#e89500', '#dd3224', '#337ab7', '#7a5ea8'][(Number(avatar || 1) - 1) % 6]

const isAutoProxy = (row: any) => row.memberType === 'BOT' || Boolean(row.autoProxy)
const isSearchable = (row: any) => row.searchable !== false
const isBound = (row: any) => Boolean(String(row.fingerprint || '').trim())
const formatStatistic = (value: number) => {
  const amount = Number(value)
  if (!Number.isFinite(amount)) return '0'
  return String(Math.round((Math.abs(amount) < 0.005 ? 0 : amount) * 100) / 100)
}

const applyMemberSearch = () => {
  memberSearchKeyword.value = memberSearchInput.value.trim()
}

let memberRefreshTimer: number | undefined
const startMemberRefresh = () => {
  if (memberRefreshTimer) return
  void store.refreshMembers()
  memberRefreshTimer = window.setInterval(() => void store.refreshMembers(), 2_000)
}
const stopMemberRefresh = () => {
  if (!memberRefreshTimer) return
  window.clearInterval(memberRefreshTimer)
  memberRefreshTimer = undefined
}

onMounted(startMemberRefresh)
onActivated(startMemberRefresh)
onDeactivated(stopMemberRefresh)
onBeforeUnmount(stopMemberRefresh)
</script>

<template>
  <div class="lucky-page">
    <h1 class="lucky-page__heading">会员列表 <small>添加/编辑/删除会员</small></h1>
    <el-card class="legacy-list-box" shadow="never">
      <div class="lucky-toolbar member-toolbar">
        <div class="lucky-toolbar__filters member-toolbar__actions">
          <el-tooltip content="添加会员">
            <el-button class="member-toolbar__add" @click="openMember()"
              ><Icon icon="fa:user-plus" :size="14"
            /></el-button>
          </el-tooltip>
          <el-tooltip content="清理数据">
            <el-button class="member-toolbar__delete" @click="clearAllVisible = true"
              ><Icon icon="fa:trash" :size="14"
            /></el-button>
          </el-tooltip>
          <el-button class="member-toolbar__delete" @click="clearAllFingerprints">抹除标识</el-button>
        </div>
        <div class="member-summary">
          <span class="lucky-member-total">总余分：{{ formatStatistic(totalBalance) }}，托总余分：{{ formatStatistic(proxyBalance) }}，</span>
          <span class="lucky-danger">真实会员总余分：{{ formatStatistic(realBalance) }}</span>
          <span class="member-summary__separator"> || </span>
          <span class="lucky-member-total">总投额：{{ formatStatistic(totalBet) }}，托总投额：{{ formatStatistic(proxyBet) }}，</span>
          <span class="lucky-danger">真实会员总投额：{{ formatStatistic(realBet) }}</span>
          <span class="member-summary__separator"> || </span>
          <span class="lucky-member-total">总盈亏：{{ formatStatistic(totalProfit) }}，</span>
          <span class="lucky-danger">真实会员盈亏：{{ formatStatistic(realProfit) }}，托盈亏：{{ formatStatistic(proxyProfit) }}</span>
        </div>
      </div>

      <div class="lucky-toolbar__filters mb-16px">
        <el-select v-model="timeType">
          <el-option label="全部" :value="0" />
          <el-option label="今天" :value="1" />
          <el-option label="昨天" :value="2" />
          <el-option label="本周" :value="3" />
        </el-select>
        <el-select v-model="puller" clearable placeholder="选择拉手">
          <el-option v-for="item in pullers" :key="item" :label="item" :value="item" />
        </el-select>
        <label class="member-search" for="member-nickname-search">搜索：</label>
        <el-input
          id="member-nickname-search"
          v-model="memberSearchInput"
          clearable
          placeholder="输入昵称"
          @clear="applyMemberSearch"
          @keyup.enter="applyMemberSearch"
        />
      </div>

      <PaginatedTable
        mobile-layout="member-scroll"
        :data="rows"
        :expand-row-keys="rows.map((row) => row.id)"
        row-key="id"
        border
        class="member-list-table"
        show-summary
        :summary-method="legacyFooterHeaders"
      >
        <template #mobile="{ row }">
          <div class="lucky-mobile-card__title">
            <div class="member-identity member-identity--mobile">
              <el-avatar :size="32" :style="{ backgroundColor: avatarColor(row.avatar) }">
                {{ String(row.name || '?').slice(0, 1) }}
              </el-avatar>
              <span>{{ row.externalNickname || row.name }}</span>
            </div>
            <span>{{ row.balance }} 分</span>
          </div>
          <div class="lucky-mobile-card__meta">
            <span>昵称：{{ row.name }}</span>
            <span>投分：{{ row.totalBet || 0 }}</span>
            <span>盈亏：{{ row.profitLoss || 0 }}</span>
            <span>托 {{ isAutoProxy(row) ? '✓' : '×' }}</span>
            <span>吃 {{ row.eatEnabled ? '✓' : '×' }}</span>
            <span>查 {{ isSearchable(row) ? '✓' : '×' }}</span>
            <span>绑定 {{ isBound(row) ? '✓' : '×' }}</span>
          </div>
          <div class="lucky-mobile-card__actions member-mobile-actions">
            <el-button size="small" @click="openMember(row)">编辑</el-button>
            <el-button size="small" @click="openLinks(row)">链接</el-button>
            <el-button size="small" @click="openTransfer(row, '上分')">上分</el-button>
            <el-button size="small" @click="openTransfer(row, '下分')">下分</el-button>
            <el-button size="small" @click="toggleEat(row)">吃码</el-button>
            <el-button size="small" @click="clearMember(row)">清流水</el-button>
            <el-button size="small" @click="openDetails(row)">查询</el-button>
            <el-button size="small" @click="store.changeMemberAvatar(row.id)">换头像</el-button>
            <el-button size="small" type="danger" @click="deleteMember(row)">删除</el-button>
          </div>
        </template>
        <el-table-column type="expand" width="1">
          <template #default="{ row }">
            <div class="member-row-actions">
              <el-tooltip content="编辑"
                ><el-button size="small" circle @click="openMember(row)"
                  ><Icon icon="fa:edit" :size="14" /></el-button
              ></el-tooltip>
              <el-button size="small" @click="openLinks(row)">链接</el-button>
              <el-button size="small" @click="openTransfer(row, '上分')">上</el-button>
              <el-button size="small" @click="openTransfer(row, '下分')">下</el-button>
              <el-button size="small" @click="toggleEat(row)">吃</el-button>
              <el-button size="small" @click="clearMember(row)">清</el-button>
              <el-button size="small" @click="openDetails(row)">查</el-button>
              <el-button size="small" @click="store.changeMemberAvatar(row.id)">换</el-button>
              <el-tooltip content="删除"
                ><el-button size="small" type="danger" circle @click="deleteMember(row)"
                  ><Icon icon="fa:trash" :size="14" /></el-button
              ></el-tooltip>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="微信/飞鱼/蓝鲸昵称" min-width="170">
          <template #default="{ row }">
            <div class="member-identity">
              <span>{{ row.externalNickname || '//' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="昵称" min-width="110" />
        <el-table-column prop="balance" label="积分" min-width="90" />
        <el-table-column label="投分" min-width="90"
          ><template #default="{ row }">{{ row.totalBet || 0 }}</template></el-table-column
        >
        <el-table-column label="盈亏" min-width="90"
          ><template #default="{ row }">{{ row.profitLoss || 0 }}</template></el-table-column
        >
        <el-table-column label="自动托" width="80" align="center">
          <template #default="{ row }">
            <span
              class="member-boolean"
              :class="isAutoProxy(row) ? 'member-boolean--yes' : 'member-boolean--no'"
              :title="isAutoProxy(row) ? '已开启自动托' : '未开启自动托'"
              >{{ isAutoProxy(row) ? '√' : '×' }}</span
            >
          </template>
        </el-table-column>
        <el-table-column label="吃" width="60" align="center">
          <template #default="{ row }">
            <span
              class="member-boolean"
              :class="row.eatEnabled ? 'member-boolean--yes' : 'member-boolean--no'"
              :title="row.eatEnabled ? '已开启吃码' : '未开启吃码'"
              >{{ row.eatEnabled ? '√' : '×' }}</span
            >
          </template>
        </el-table-column>
        <el-table-column label="查" width="60" align="center">
          <template #default="{ row }">
            <span
              class="member-boolean"
              :class="isSearchable(row) ? 'member-boolean--yes' : 'member-boolean--no'"
              :title="isSearchable(row) ? '允许查询流水' : '禁止查询流水'"
              >{{ isSearchable(row) ? '√' : '×' }}</span
            >
          </template>
        </el-table-column>
        <el-table-column label="绑定" width="70" align="center">
          <template #default="{ row }">
            <span
              class="member-boolean"
              :class="isBound(row) ? 'member-boolean--yes' : 'member-boolean--no'"
              :title="isBound(row) ? '已绑定设备标识' : '未绑定设备标识'"
              >{{ isBound(row) ? '√' : '×' }}</span
            >
          </template>
        </el-table-column>
      </PaginatedTable>
    </el-card>

    <el-dialog
      v-model="memberVisible"
      :title="memberForm.id ? '编辑会员' : '添加会员'"
      width="560px"
      class="lucky-dialog"
    >
      <el-form :model="memberForm" label-width="110px">
        <el-form-item label="头像">
          <el-avatar :size="40" :src="lotteryPlayerAvatarSrc(memberForm.avatar)" />
        </el-form-item>
        <el-form-item label="昵称"
          ><el-input v-model="memberForm.name" placeholder="NickName"
        /></el-form-item>
        <el-form-item label="自动托"
          ><el-select v-model="memberForm.autoProxy"
            ><el-option label="否" :value="false" /><el-option
              label="是"
              :value="true" /></el-select
        ></el-form-item>
        <el-form-item label="可查流水"
          ><el-select v-model="memberForm.searchable"
            ><el-option label="否" :value="false" /><el-option
              label="是"
              :value="true" /></el-select
        ></el-form-item>
        <el-form-item label="是否私聊"
          ><el-select v-model="memberForm.privateChat"
            ><el-option label="否" :value="false" /><el-option
              label="是"
              :value="true" /></el-select
        ></el-form-item>
        <el-form-item label="只进网页群"
          ><el-select v-model="memberForm.webOnly"
            ><el-option label="否" :value="false" /><el-option
              label="是"
              :value="true" /></el-select
        ></el-form-item>
        <el-form-item v-if="memberForm.webOnly" label="蓝鲸密码"
          ><el-input v-model="memberForm.blueWhalePassword" placeholder="蓝鲸密码"
        /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="memberVisible = false">取消</el-button>
        <el-button type="primary" :loading="store.saving" @click="submitMember">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="transferVisible"
      :title="transferForm.type"
      width="420px"
      class="lucky-dialog"
    >
      <el-form :model="transferForm" label-width="80px">
        <el-form-item label="分数"
          ><el-input-number v-model="transferForm.amount" :min="0.01"
        /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transferVisible = false">取消</el-button>
        <el-button type="primary" :loading="store.saving" @click="submitTransfer">确认</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="clearAllVisible"
      title="清理全部会员流水"
      width="440px"
      class="lucky-dialog"
    >
      <el-form label-width="80px">
        <el-form-item label="密码">
          <el-input v-model="clearPassword" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="clearAllVisible = false">取消</el-button>
        <el-button type="danger" :loading="store.saving" @click="clearAllFlows">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="linkVisible"
      width="1200px"
      class="lucky-dialog member-link-dialog"
    >
      <p class="member-link-dialog__warning">封号严重的用链接5。链接5只能浏览器打开</p>
      <div class="member-link-dialog__member">
        <strong>{{ linkMember }}</strong>
        <el-button type="primary" @click="rotateLink"
          ><Icon icon="ep:copy-document" />换链接</el-button
        >
      </div>
      <div v-for="item in linkEntries" :key="item.key" class="member-link-row">
        <span class="member-link-row__label">{{ item.label }}</span>
        <div class="member-link-row__content">
          <span>{{ item.url }}</span>
          <img
            v-if="item.withQr && linkQrs[item.key]"
            :src="linkQrs[item.key]"
            :alt="`${item.label}二维码`"
          />
        </div>
        <el-button type="primary" @click="copy(item.url)"
          ><Icon icon="ep:copy-document" />复制</el-button
        >
      </div>
      <el-empty v-if="!linkEntries.length" description="当前未开启玩家房间入口" :image-size="64" />
    </el-dialog>

    <el-dialog
      v-model="detailsVisible"
      :title="`${details?.name || ''} 会员查询`"
      width="860px"
      class="lucky-dialog member-details-dialog"
    >
      <el-descriptions v-if="details" :column="isMobile ? 1 : 3" border class="mb-16px">
        <el-descriptions-item label="积分">{{ details.balance }}</el-descriptions-item>
        <el-descriptions-item label="投分">{{ details.totalBet }}</el-descriptions-item>
        <el-descriptions-item label="盈亏">{{ details.profitLoss }}</el-descriptions-item>
      </el-descriptions>
      <h3 class="member-detail-title">上下分记录</h3>
      <PaginatedTable
        :data="details?.amountRecords || []"
        :default-page-size="10"
        border
        show-summary
        :summary-method="legacyFooterHeaders"
        max-height="220"
      >
        <template #mobile="{ row }">
          <div class="lucky-mobile-card__title">
            <span>{{ row.type }}</span>
            <span>{{ row.amount }} 分</span>
          </div>
          <div class="lucky-mobile-card__meta">
            <span>{{ row.status }}</span>
            <span>{{ row.createdAt }}</span>
          </div>
        </template>
        <el-table-column prop="type" label="类型" />
        <el-table-column prop="amount" label="分数" />
        <el-table-column prop="status" label="状态" />
        <el-table-column prop="createdAt" label="时间" min-width="180" />
      </PaginatedTable>
      <h3 class="member-detail-title">订单记录</h3>
      <PaginatedTable :data="details?.orders || []" :default-page-size="10" border max-height="260" show-summary :summary-method="legacyFooterHeaders">
        <template #mobile="{ row }">
          <div class="lucky-mobile-card__title">
            <span>{{ row.period }}</span>
            <span>{{ row.amount }} 分</span>
          </div>
          <div class="lucky-mobile-card__content">{{ row.content }}</div>
          <div class="lucky-mobile-card__meta">
            <span>中奖：{{ row.win || 0 }}</span>
            <span>{{ row.status }}</span>
          </div>
        </template>
        <el-table-column prop="period" label="期号" min-width="140" />
        <el-table-column prop="content" label="内容" min-width="180" />
        <el-table-column prop="amount" label="投额" />
        <el-table-column prop="win" label="中奖" />
        <el-table-column prop="status" label="状态" />
      </PaginatedTable>
    </el-dialog>
  </div>
</template>

<style scoped>
.lucky-member-total {
  color: #00f;
}

.legacy-list-box :deep(.el-card__body) {
  padding: 10px;
}

.member-toolbar {
  display: flex !important;
  justify-content: flex-start !important;
  flex-wrap: nowrap;
  gap: 10px !important;
  align-items: center;
}

.member-toolbar__actions {
  flex-wrap: nowrap;
}

.member-toolbar__add,
.member-toolbar__delete {
  min-width: 42px;
  min-height: 32px;
  padding: 5px 10px;
  color: #fff !important;
  font-size: 14px;
  border-width: 1px;
  border-style: solid;
  border-radius: 4px;
  box-shadow: inset 0 -3px 0 rgb(0 0 0 / 22%);
  -webkit-text-fill-color: #fff;
}

.member-toolbar__add {
  background: #00c0ef !important;
  border-color: #00acd6 !important;
}

.member-toolbar__delete {
  background: #dd4b39 !important;
  border-color: #d73925 !important;
}

.member-toolbar__add:hover,
.member-toolbar__add:focus-visible {
  background: #00acd6 !important;
}

.member-toolbar__delete:hover,
.member-toolbar__delete:focus-visible {
  background: #d73925 !important;
}

.member-summary {
  display: flex;
  flex: 0 1 auto;
  min-width: 0;
  overflow-x: visible;
  color: #00f;
  font-size: 14px;
  line-height: 34px;
  white-space: nowrap;
  align-items: center;
}

.member-summary__separator {
  color: #333;
}

.member-search {
  color: #333;
  font-size: 22px;
  font-weight: 600;
  line-height: 34px;
}

.member-boolean {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  color: #333;
  font-size: 22px;
  font-weight: 400;
  line-height: 1;
}

.member-boolean--yes {
  color: #333;
}

.member-boolean--no {
  color: #333;
}

.member-identity,
.member-link {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.member-list-table :deep(.el-table__expand-column),
.member-list-table :deep(.el-table__expand-column .cell) {
  width: 1px !important;
  min-width: 1px !important;
  padding: 0 !important;
}

.member-list-table :deep(.el-table__expand-icon) {
  display: none;
}

.member-list-table :deep(.el-table__expanded-cell) {
  padding: 9px 14px !important;
  background: #fff !important;
}

.member-row-actions {
  display: flex;
  min-height: 40px;
  align-items: center;
  gap: 6px;
}

.member-row-actions :deep(.el-button) {
  min-width: 48px;
  min-height: 40px;
  padding: 5px 11px;
  margin: 0;
  color: #fff !important;
  font-size: 16px;
  background: #3c8dbc !important;
  border-color: #367fa9 !important;
  border-radius: 4px;
  box-shadow: inset 0 -3px 0 rgb(0 0 0 / 28%);
  -webkit-text-fill-color: #fff;
}

.member-row-actions :deep(.el-button.is-circle) {
  min-width: 42px;
  padding: 5px;
}

.member-row-actions :deep(.el-button--danger) {
  background: #dd4b39 !important;
  border-color: #d73925 !important;
}

.member-row-actions :deep(.el-button:hover),
.member-row-actions :deep(.el-button:focus-visible) {
  background: #367fa9 !important;
  border-color: #2e6f8f !important;
}

.member-row-actions :deep(.el-button--danger:hover),
.member-row-actions :deep(.el-button--danger:focus-visible) {
  background: #d73925 !important;
  border-color: #c9301f !important;
}

.member-identity--mobile {
  justify-content: flex-start;
}

.member-link {
  justify-content: space-between;
}

.member-link span {
  min-width: 0;
  overflow-wrap: anywhere;
}

.member-link__actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 10px;
}

/* The legacy backend skin makes Element's link buttons solid. Keep the
 * actions legible when the member-link dialog is teleported outside layout. */
.member-link__actions :deep(.el-button) {
  min-height: 30px;
  padding: 4px 10px;
  color: #fff !important;
  font-size: 14px;
  font-weight: 400;
  line-height: 20px;
  background: #3c8dbc !important;
  border-color: #367fa9 !important;
  -webkit-text-fill-color: #fff;
}

.member-link__actions :deep(.el-button > span),
.member-link__actions :deep(.el-button .el-icon),
.member-link__actions :deep(.el-button svg) {
  color: #fff !important;
}

.member-link__actions :deep(.el-button:hover),
.member-link__actions :deep(.el-button:focus-visible) {
  color: #fff !important;
  background: #367fa9 !important;
  border-color: #2e6f8f !important;
}

.member-link-dialog__warning {
  margin: 0 0 24px;
  color: #f00;
  font-size: 24px;
  line-height: 32px;
}

.member-link-dialog__member {
  display: flex;
  min-height: 58px;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #eee;
}

.member-link-dialog__member strong {
  color: #333;
  font-size: 28px;
  font-weight: 600;
}

.member-link-dialog__member :deep(.el-button),
.member-link-row :deep(.el-button) {
  min-height: 54px;
  padding: 10px 18px;
  color: #fff;
  font-size: 20px;
  background: #3c8dbc;
  border-color: #367fa9;
}

.member-link-row {
  display: grid;
  padding: 18px 16px 28px;
  border-bottom: 1px solid #eee;
  /* 原页面的标签是窄列，会自然换行成“原 / 始”、“链 / 接3”。 */
  grid-template-columns: 48px minmax(0, 1fr) max-content;
  gap: 32px;
  align-items: start;
}

.member-link-row__label {
  padding-top: 2px;
  color: #333;
  font-size: 26px;
  line-height: 34px;
  overflow-wrap: anywhere;
}

.member-link-row__content {
  display: flex;
  min-width: 0;
  flex-direction: column;
  align-items: flex-start;
  gap: 26px;
}

.member-link-row__content > span {
  color: #333;
  font-size: 24px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.member-link-row__content > img {
  width: 260px;
  height: 260px;
}

@media (width <= 560px) {
  .member-toolbar {
    flex-wrap: wrap;
  }

  .member-toolbar__actions {
    flex-wrap: wrap;
  }

  .member-summary {
    align-items: flex-start;
    flex-direction: column;
    gap: 2px;
    padding-bottom: 4px;
    overflow-x: visible;
    white-space: normal;
  }

  .member-summary__group {
    width: 100%;
    flex-wrap: wrap;
    gap: 2px 10px;
  }

  .member-summary__separator {
    display: none;
  }

  .member-mobile-actions .el-button {
    min-width: 62px;
    flex: 1;
  }

  .member-link-row {
    grid-template-columns: 42px minmax(0, 1fr);
    gap: 10px;
  }

  .member-link-row__label,
  .member-link-row__content > span {
    font-size: 15px;
  }

  .member-link-row__content > img {
    width: 180px;
    height: 180px;
  }

  .member-link-row :deep(.el-button) {
    grid-column: 2;
    justify-self: start;
    min-height: 34px;
    padding: 6px 12px;
    font-size: 14px;
  }
}

.member-detail-title {
  margin: 16px 0 8px;
  font-size: 15px;
}
</style>
