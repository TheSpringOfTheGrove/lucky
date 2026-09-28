<script lang="tsx">
import { computed, defineComponent, ref } from 'vue'
import { Breadcrumb } from '@/layout/components/Breadcrumb'
import { Collapse } from '@/layout/components/Collapse'
import { UserInfo } from '@/layout/components/UserInfo'
import { useSetting } from '@/layout/components/Setting'
import { useAppStore } from '@/store/modules/app'
import { useUserStore } from '@/store/modules/user'
import { formatDate } from '@/utils/formatTime'
import { useDesign } from '@/hooks/web/useDesign'
import { Icon } from '@/components/Icon'
import ChangePasswordDialog from './ChangePasswordDialog.vue'
import { isHorizontalMenuLayout, isMixedNavLayout, isTwoColumnLayout } from '@/utils/layout'

const { getPrefixCls, variables } = useDesign()
const prefixCls = getPrefixCls('tool-header')
const appStore = useAppStore()
const userStore = useUserStore()

const breadcrumb = computed(() => appStore.getBreadcrumb)
const hamburger = computed(() => appStore.getHamburger)
const layout = computed(() => appStore.getLayout)

export default defineComponent({
  name: 'ToolHeader',
  setup() {
    const { t } = useI18n()
    const { openSetting } = useSetting()
    const passwordDialogVisible = ref(false)
    const showSidebarControl = computed(
      () => !isHorizontalMenuLayout(layout.value) || isMixedNavLayout(layout.value)
    )
    const showBreadcrumb = computed(() => !isHorizontalMenuLayout(layout.value))

    return () => (
      <div
        id={`${variables.namespace}-tool-header`}
        class={[
          prefixCls,
          'h-[var(--top-tool-height)] relative px-[var(--top-tool-p-x)] flex items-center justify-between',
          'dark:bg-[var(--el-bg-color)]'
        ]}
      >
        {showSidebarControl.value || showBreadcrumb.value ? (
          <div class="h-full flex items-center">
            {showSidebarControl.value && hamburger.value && !isTwoColumnLayout(layout.value) ? (
              <Collapse class="custom-hover" color="var(--top-header-text-color)"></Collapse>
            ) : undefined}
            {showBreadcrumb.value && breadcrumb.value ? (
              <Breadcrumb class="lt-md:hidden"></Breadcrumb>
            ) : undefined}
            <div class="lucky-admin-top-meta">
              {userStore.getIsSetUser ? (
                <>
                  <span>
                    到期时间：
                    {userStore.getUser.expireTime
                      ? formatDate(userStore.getUser.expireTime, 'YYYY-MM-DD HH:mm:ss')
                      : '未设置'}
                  </span>
                  <button type="button" onClick={() => (passwordDialogVisible.value = true)}>
                    修改密码
                  </button>
                </>
              ) : undefined}
            </div>
          </div>
        ) : undefined}
        <div class="h-full flex items-center">
          <div
            class="v-setting custom-hover"
            title={t('setting.projectSetting')}
            onClick={openSetting}
          >
            <Icon color="var(--top-header-text-color)" size={18} icon="ep:setting" />
          </div>
          <UserInfo></UserInfo>
        </div>
        <ChangePasswordDialog
          modelValue={passwordDialogVisible.value}
          onUpdate:modelValue={(value: boolean) => (passwordDialogVisible.value = value)}
        />
      </div>
    )
  }
})
</script>

<style lang="scss" scoped>
$prefix-cls: #{$namespace}-tool-header;

.#{$prefix-cls} {
  transition: left var(--transition-time-02);
}
</style>
