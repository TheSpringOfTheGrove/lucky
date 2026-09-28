<script lang="ts" setup>
import { ElMessageBox } from 'element-plus'

import defaultAvatar from '@/assets/imgs/adminlte-user2-160x160.jpg'
import { useDesign } from '@/hooks/web/useDesign'
import { useTagsViewStore } from '@/store/modules/tagsView'
import { useUserStore } from '@/store/modules/user'

defineOptions({ name: 'UserInfo' })

const { t } = useI18n()

const { replace } = useRouter()

const userStore = useUserStore()

const tagsViewStore = useTagsViewStore()

const { getPrefixCls } = useDesign()

const prefixCls = getPrefixCls('user-info')

// The legacy backend uses this fixed AdminLTE portrait as its default rather
// than the generic avatar returned by the framework profile endpoint.
const avatar = computed(() => defaultAvatar)
const userName = computed(() => userStore.user.username || userStore.user.nickname || 'Admin')

const loginOut = async () => {
  try {
    await ElMessageBox.confirm(t('common.loginOutMessage'), t('common.reminder'), {
      confirmButtonText: t('common.ok'),
      cancelButtonText: t('common.cancel'),
      type: 'warning'
    })
    await userStore.loginOut()
    tagsViewStore.delAllViews()
    replace('/login?redirect=/index')
  } catch {}
}
const toProfile = async () => {
  push('/user/profile')
}
</script>

<template>
  <ElDropdown
    class="custom-hover"
    :class="[prefixCls, 'lucky-admin-user-info']"
    placement="bottom-end"
    popper-class="lucky-admin-profile-popper"
    :popper-options="{ modifiers: [{ name: 'offset', options: { offset: [0, 0] } }] }"
    trigger="click"
  >
    <button type="button" class="lucky-admin-user-trigger">
      <ElAvatar :src="avatar" alt="" class="lucky-admin-user-trigger__avatar" />
      <span class="lucky-admin-user-trigger__name">{{ userName }}</span>
    </button>
    <template #dropdown>
      <div class="lucky-admin-profile-menu">
        <div class="lucky-admin-profile-menu__header">
          <ElAvatar :src="avatar" alt="" class="lucky-admin-profile-menu__avatar" />
          <p>{{ userName }}</p>
        </div>
        <div class="lucky-admin-profile-menu__footer">
          <button type="button" @click="loginOut">登出</button>
        </div>
      </div>
    </template>
  </ElDropdown>
</template>
