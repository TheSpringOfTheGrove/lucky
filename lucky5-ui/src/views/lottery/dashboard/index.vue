<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useLucky5Store, type SwitchKey } from '@/store/modules/lottery'

const store = useLucky5Store()
const router = useRouter()
</script>

<template>
  <div class="lucky-page lucky-dashboard-page">
    <h1 class="lucky-page__heading">Dashboard <small>Version 2.0</small></h1>
    <div class="dashboard-layout">
      <div class="dashboard-card-grid">
        <button
          type="button"
          class="dashboard-info-card dashboard-info-card--interactive"
          @click="router.push('/member')"
        >
          <span class="dashboard-info-card__icon dashboard-info-card__icon--teal">
            <span class="dashboard-users-icon">
              <Icon icon="ep:user" :size="23" />
              <Icon icon="ep:user-filled" :size="31" />
              <Icon icon="ep:user" :size="23" />
            </span>
          </span>
          <span class="dashboard-info-card__content">
            <span class="dashboard-info-card__label">会员总数</span>
            <strong class="dashboard-info-card__value">{{ store.stats.totalMembers }}</strong>
          </span>
        </button>

        <article class="dashboard-info-card">
          <span class="dashboard-info-card__icon dashboard-info-card__icon--olive">
            <span class="dashboard-users-icon">
              <Icon icon="ep:user" :size="23" />
              <Icon icon="ep:user-filled" :size="31" />
              <Icon icon="ep:user" :size="23" />
            </span>
          </span>
          <span class="dashboard-info-card__content">
            <span class="dashboard-info-card__label">在线会员总数</span>
            <strong class="dashboard-info-card__value">{{ store.stats.onlineMembers }}</strong>
          </span>
        </article>

        <button
          type="button"
          class="dashboard-info-card dashboard-info-card--interactive"
          @click="router.push('/amount-record')"
        >
          <span class="dashboard-info-card__icon dashboard-info-card__icon--orange">
            <Icon icon="ep:credit-card" :size="42" />
          </span>
          <span class="dashboard-info-card__content">
            <span class="dashboard-info-card__label">未审核上分请求</span>
            <strong class="dashboard-info-card__value">{{ store.stats.pendingDeposits }}</strong>
          </span>
        </button>

        <article class="dashboard-info-card dashboard-platform-card">
          <span class="dashboard-info-card__icon dashboard-info-card__icon--red">
            <Icon icon="ep:switch-button" :size="42" />
          </span>
          <div class="dashboard-info-card__content dashboard-startup">
            <span class="dashboard-info-card__label">启动状态</span>
            <el-checkbox
              :model-value="store.room.open"
              @change="(value: boolean) => store.setRoomOpen(value)"
            />
          </div>
        </article>
      </div>

      <aside class="dashboard-settings-card">
        <div class="dashboard-settings-card__icon">
          <Icon icon="ep:setting" :size="44" />
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
  </div>
</template>

<style scoped lang="less">
.lucky-dashboard-page {
  min-width: 0;
}

.dashboard-layout {
  display: grid;
  grid-template-columns: minmax(0, 3fr) minmax(250px, 1fr);
  gap: 18px;
  align-items: start;
}

.dashboard-card-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
  align-items: start;
}

.dashboard-info-card {
  display: grid;
  min-width: 0;
  min-height: 102px;
  grid-template-columns: 90px minmax(0, 1fr);
  align-items: center;
  overflow: hidden;
  padding: 0;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 3px;
  background: var(--el-bg-color-overlay);
  box-shadow: 0 1px 2px rgb(0 0 0 / 10%);
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
  border-color: var(--el-color-primary);
  box-shadow: 0 3px 8px rgb(0 0 0 / 14%);
}

.dashboard-info-card__icon {
  display: flex;
  width: 90px;
  height: 100%;
  min-height: 100px;
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
  font-size: 15px;
  line-height: 22px;
}

.dashboard-info-card__value {
  margin-top: 2px;
  font-size: 22px;
  line-height: 1.2;
}

.dashboard-platform-card {
  min-height: 116px;
}

.dashboard-platform-card .dashboard-info-card__icon {
  min-height: 114px;
}

.dashboard-platform-card p {
  margin: 0;
  color: var(--el-text-color-regular);
  font-size: 14px;
  line-height: 21px;
  overflow-wrap: anywhere;
}

.dashboard-platform-card .el-button {
  margin-top: 6px;
}

.dashboard-startup {
  gap: 4px;
}

.dashboard-startup :deep(.el-checkbox) {
  height: 22px;
}

.dashboard-settings-card {
  display: grid;
  min-width: 0;
  grid-template-columns: 90px minmax(0, 1fr);
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 3px;
  background: var(--el-bg-color-overlay);
  box-shadow: 0 1px 2px rgb(0 0 0 / 10%);
}

.dashboard-settings-card__icon {
  display: flex;
  min-height: 102px;
  align-items: center;
  justify-content: center;
  align-self: start;
  background: #001f3f;
  color: #fff;
}

.dashboard-settings-card__content {
  display: grid;
  gap: 3px;
  padding: 10px 12px 12px;
}

.dashboard-settings-card__item {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 7px;
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 20px;
  cursor: pointer;
}

.dashboard-settings-card__item :deep(.el-checkbox) {
  height: 22px;
}

@media (max-width: 1199px) {
  .dashboard-layout {
    grid-template-columns: 1fr;
  }

  .dashboard-settings-card__content {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .dashboard-card-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .dashboard-settings-card__content {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 600px) {
  .dashboard-card-grid,
  .dashboard-settings-card__content {
    grid-template-columns: 1fr;
  }

  .dashboard-info-card,
  .dashboard-settings-card {
    grid-template-columns: 78px minmax(0, 1fr);
  }

  .dashboard-info-card__icon {
    width: 78px;
  }
}
</style>


