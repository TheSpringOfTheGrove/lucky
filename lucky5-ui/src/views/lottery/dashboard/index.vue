<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { IntegrationKey } from '@/api/lottery'
import { useLucky5Store, type SwitchKey } from '@/store/modules/lottery'

const store = useLucky5Store()
const router = useRouter()
let dashboardRefreshTimer: number | undefined

const integrationKeys: IntegrationKey[] = ['blueWhale', 'wechat', 'fish']
const integrationStyles: Record<IntegrationKey, { iconClass: string }> = {
  blueWhale: { iconClass: 'dashboard-service-card__icon--orange' },
  wechat: { iconClass: 'dashboard-service-card__icon--green' },
  fish: { iconClass: 'dashboard-service-card__icon--aqua' }
}
const integrationDialogVisible = ref(false)
const selectedIntegrationKey = ref<IntegrationKey>('blueWhale')
const integrationForm = reactive({ account: '', group: '' })
const selectedIntegration = computed(() => store.integrations[selectedIntegrationKey.value])
const integrationCards = computed(() =>
  integrationKeys.map((key) => ({
    key,
    ...store.integrations[key],
    ...integrationStyles[key]
  }))
)

const openIntegrationConfig = (key: IntegrationKey) => {
  const integration = store.integrations[key]
  selectedIntegrationKey.value = key
  integrationForm.account = integration?.account || ''
  integrationForm.group = integration?.group || ''
  integrationDialogVisible.value = true
}

const saveIntegration = async () => {
  const saved = await store.bindIntegration(selectedIntegrationKey.value, integrationForm)
  if (saved) integrationDialogVisible.value = false
}

onMounted(() => {
  void store.refreshMembers()
  dashboardRefreshTimer = window.setInterval(() => void store.refreshMembers(), 5_000)
})

onBeforeUnmount(() => {
  if (dashboardRefreshTimer) window.clearInterval(dashboardRefreshTimer)
})
</script>

<template>
  <div class="lucky-page lucky-dashboard-page">
    <h1 class="lucky-page__heading">Dashboard <small>Version 2.0</small></h1>
    <div class="dashboard-breadcrumb"><Icon icon="fa:dashboard" :size="12" /> Home <span>&gt;</span> Dashboard</div>
    <div class="dashboard-top-grid">
      <button
        type="button"
        class="dashboard-info-card dashboard-info-card--interactive"
        @click="router.push('/lucky5/members')"
      >
        <span class="dashboard-info-card__icon dashboard-info-card__icon--teal">
          <span class="dashboard-users-icon">
            <Icon icon="ion:ios-people-outline" :size="45" />
          </span>
        </span>
        <span class="dashboard-info-card__content">
          <span class="dashboard-info-card__label">会员总数</span>
          <strong class="dashboard-info-card__value">{{ store.stats.totalMembers }}</strong>
        </span>
      </button>

      <button
        type="button"
        class="dashboard-info-card dashboard-info-card--interactive"
        @click="router.push('/lucky5/members')"
      >
        <span class="dashboard-info-card__icon dashboard-info-card__icon--olive">
          <span class="dashboard-users-icon">
            <Icon icon="ion:ios-people-outline" :size="45" />
          </span>
        </span>
        <span class="dashboard-info-card__content">
          <span class="dashboard-info-card__label">在线会员总数</span>
          <strong class="dashboard-info-card__value">{{ store.stats.onlineMembers }}</strong>
        </span>
      </button>

      <button
        type="button"
        class="dashboard-info-card dashboard-info-card--interactive"
        @click="router.push('/lucky5/amount-records')"
      >
        <span class="dashboard-info-card__icon dashboard-info-card__icon--orange">
          <Icon icon="ion:card" :size="45" />
        </span>
        <span class="dashboard-info-card__content">
          <span class="dashboard-info-card__label">未审核上分请求</span>
          <strong class="dashboard-info-card__value">{{ store.stats.pendingDeposits }}</strong>
        </span>
      </button>

      <aside class="dashboard-settings-card">
        <div class="dashboard-settings-card__icon">
          <Icon icon="ion:ios-gear-outline" :size="45" />
        </div>
        <div class="dashboard-settings-card__content">
          <label
            v-for="item in store.switchList"
            :key="item.key"
            class="dashboard-settings-card__item"
          >
            <el-checkbox
              :model-value="item.value"
              @change="(value: boolean) => store.setSwitch(item.key as SwitchKey, value)"
            />
            <span>{{ item.label }}</span>
          </label>
        </div>
      </aside>
    </div>

    <div class="dashboard-service-grid">
      <article
        v-for="integration in integrationCards"
        :key="integration.key"
        class="dashboard-service-card"
        :class="`dashboard-service-card--${integration.key}`"
      >
        <span class="dashboard-service-card__icon" :class="integration.iconClass">
          <Icon icon="ion:chatbubbles" :size="45" />
        </span>
        <div class="dashboard-service-card__content">
          <template v-if="integration.key === 'wechat'">
            <div>微信</div>
          </template>
          <template v-else>
            <div>{{ integration.name }}{{ integration.key === 'fish' ? '昵称' : '账号' }}: {{ integration.account || '未绑定' }}</div>
            <div>{{ integration.name }}群: {{ integration.group || '未绑定' }}</div>
            <div>状态: {{ integration.status || '未登录' }}</div>
          </template>
          <button
            type="button"
            class="dashboard-service-card__button"
            @click="openIntegrationConfig(integration.key)"
          >
            配置{{ integration.name }}
          </button>
        </div>
      </article>

      <article class="dashboard-service-card dashboard-startup-card">
        <span class="dashboard-service-card__icon dashboard-service-card__icon--red">
          <Icon icon="ion:power" :size="45" />
        </span>
        <div class="dashboard-service-card__content dashboard-startup">
          <span>启动状态</span>
          <el-checkbox
            :model-value="store.room.open"
            :disabled="store.saving"
            aria-label="启动状态"
            @change="(value: boolean) => store.setRoomOpen(value)"
          />
        </div>
      </article>
    </div>

    <el-dialog
      v-model="integrationDialogVisible"
      :title="`配置${selectedIntegration?.name || ''}`"
      width="440px"
      class="lucky-dialog"
    >
      <el-form label-width="90px">
        <el-form-item label="账号">
          <el-input v-model="integrationForm.account" maxlength="100" autocomplete="off" />
        </el-form-item>
        <el-form-item label="群名称">
          <el-input v-model="integrationForm.group" maxlength="100" autocomplete="off" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="integrationDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="store.saving" @click="saveIntegration">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="less">
.lucky-dashboard-page {
  min-width: 0;
}

.dashboard-top-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 30px;
  align-items: start;
}

.dashboard-info-card {
  display: grid;
  min-width: 0;
  min-height: 90px;
  grid-template-columns: 90px minmax(0, 1fr);
  align-items: center;
  overflow: hidden;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: var(--el-bg-color-overlay);
  box-shadow: none;
  color: var(--el-text-color-primary);
  text-align: left;
}

.dashboard-info-card--interactive {
  width: 100%;
  font: inherit;
  cursor: pointer;
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease;
}

.dashboard-info-card--interactive:hover {
  outline: 1px solid var(--el-color-primary);
  box-shadow: none;
}

.dashboard-info-card__icon {
  display: flex;
  width: 90px;
  height: 100%;
  min-height: 90px;
  align-items: center;
  justify-content: center;
  color: #fff;
}

.dashboard-info-card__icon--teal {
  background: #30bbbb;
}

.dashboard-info-card__icon--olive {
  background: #3d9970;
}

.dashboard-info-card__icon--orange {
  background: #e89500;
}

.dashboard-info-card__icon--green {
  background: #00a65a;
}

.dashboard-info-card__icon--red {
  background: #dd3224;
}

.dashboard-users-icon {
  display: flex;
  align-items: flex-end;
  justify-content: center;
}

.dashboard-users-icon :deep(.v-icon + .v-icon) {
  margin-left: -7px;
}

.dashboard-users-icon :deep(.v-icon:first-child),
.dashboard-users-icon :deep(.v-icon:last-child) {
  margin-bottom: 2px;
}

.dashboard-info-card__content {
  display: flex;
  min-width: 0;
  align-items: flex-start;
  flex-direction: column;
  justify-content: center;
  padding: 12px 14px;
}

.dashboard-info-card__label {
  color: var(--el-text-color-regular);
  font-size: 14px;
  line-height: 20px;
}

.dashboard-info-card__value {
  margin-top: 0;
  color: #3c8dbc;
  font-size: 30px;
  font-weight: 400;
  line-height: 36px;
}

.dashboard-settings-card {
  display: grid;
  min-width: 0;
  grid-template-columns: 90px minmax(0, 1fr);
  overflow: hidden;
  min-height: 165px;
  border: 0;
  border-radius: 0;
  background: var(--el-bg-color-overlay);
  box-shadow: none;
}

.dashboard-settings-card__icon {
  display: flex;
  width: 90px;
  height: 90px;
  align-items: center;
  justify-content: center;
  align-self: start;
  background: #001f3f;
  color: #fff;
}

.dashboard-settings-card__content {
  display: grid;
  grid-template-columns: repeat(3, max-content);
  align-content: start;
  gap: 7px 4px;
  padding: 10px;
}

.dashboard-settings-card__item {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 2px;
  color: #222;
  font-size: 14px;
  font-weight: 700;
  line-height: 18px;
  cursor: pointer;
}

.dashboard-settings-card__item :deep(.el-checkbox) {
  height: 22px;
}

.dashboard-service-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 15px 30px;
  margin-top: 15px;
}

.dashboard-service-card {
  display: grid;
  min-width: 0;
  min-height: 90px;
  grid-template-columns: 90px minmax(0, 1fr);
  overflow: hidden;
  background: var(--el-bg-color-overlay);
}

.dashboard-service-card__icon {
  display: flex;
  width: 90px;
  height: 90px;
  align-self: start;
  align-items: center;
  justify-content: center;
  color: #fff;
}

.dashboard-service-card__icon--orange {
  background: #e49300;
}

.dashboard-service-card__icon--green {
  background: #009551;
}

.dashboard-service-card__icon--aqua {
  background: #2fc2c0;
}

.dashboard-service-card__icon--red {
  background: #dd2f20;
}

.dashboard-service-card__content {
  min-width: 0;
  padding: 5px 10px;
  color: #222;
  font-size: 14px;
  line-height: 20px;
  overflow-wrap: anywhere;
}

.dashboard-service-card__button {
  height: 34px;
  margin-top: 0;
  padding: 6px 12px;
  border: 1px solid #367fa9;
  border-radius: 3px;
  color: #fff;
  background: #3c8dbc;
  font-size: 14px;
  cursor: pointer;
}

.dashboard-startup {
  display: flex;
  align-items: flex-start;
  gap: 4px;
}

/* The reference dashboard keeps the red start card in the third slot; the
 * Feiyu card begins the following row. */
.dashboard-startup-card {
  order: 3;
}

.dashboard-service-card--fish {
  order: 4;
}

.dashboard-startup :deep(.el-checkbox) {
  height: 22px;
}

@media (max-width: 1199px) {
  .dashboard-top-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .dashboard-service-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .dashboard-settings-card__content {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 600px) {
  .dashboard-top-grid,
  .dashboard-service-grid,
  .dashboard-settings-card__content {
    grid-template-columns: 1fr;
  }

  .dashboard-info-card,
  .dashboard-settings-card,
  .dashboard-service-card {
    grid-template-columns: 78px minmax(0, 1fr);
  }

  .dashboard-info-card__icon,
  .dashboard-service-card__icon {
    width: 78px;
  }
}
</style>
