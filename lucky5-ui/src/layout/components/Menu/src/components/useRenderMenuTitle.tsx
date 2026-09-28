import type { RouteMeta } from 'vue-router'
import { Icon } from '@/components/Icon'
import { useI18n } from '@/hooks/web/useI18n'

export const useRenderMenuTitle = () => {
  const renderMenuTitle = (meta: RouteMeta) => {
    const { t } = useI18n()
    const { title = 'Please set title', icon } = meta

    const legacyIcon: Record<string, string> = {
      首页: 'dashboard', 配置管理: 'cog', 赔率设置: 'cog', 飞鱼蓝鲸信息: 'dashboard',
      链接配置: 'link', 预设订单管理: 'cog', 跟单列表: 'clone', 会员管理: 'users',
      会员操作管理: 'users', 上下分审核: 'cny', 订单查询: 'shopping-cart',
      历史记录: 'history', 开奖历史记录: 'database', 返水管理: 'backward',
      吃码额度设定: 'cog', 吃码盈亏: 'balance-scale', 消息记录: 'wrench'
    }
    const menuIcon = legacyIcon[t(title as string)] ? `fa:${legacyIcon[t(title as string)]}` : icon
    return menuIcon ? (
      <>
        <Icon icon={menuIcon} size={14}></Icon>
        <span class="v-menu__title overflow-hidden overflow-ellipsis whitespace-nowrap">
          {t(title as string)}
        </span>
      </>
    ) : (
      <span class="v-menu__title overflow-hidden overflow-ellipsis whitespace-nowrap">
        {t(title as string)}
      </span>
    )
  }

  return {
    renderMenuTitle
  }
}
