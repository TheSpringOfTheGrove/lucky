import router from './router'
import type { RouteRecordRaw } from 'vue-router'
import { isRelogin } from '@/config/axios/service'
import { getAccessToken } from '@/utils/auth'
import { useTitle } from '@/hooks/web/useTitle'
import { useNProgress } from '@/hooks/web/useNProgress'
import { useDictStoreWithOut } from '@/store/modules/dict'
import { useUserStoreWithOut } from '@/store/modules/user'
import { usePermissionStoreWithOut } from '@/store/modules/permission'
import { parseRouteLocation } from '@/utils/routeParams'

const { start, done } = useNProgress()

// 路由不重定向白名单
const whiteList = [
  '/login',
  '/social-login',
  '/auth-redirect',
  '/bind',
  '/register',
  '/room',
  '/oauthLogin/gitee'
]

const isPublicRoomPath = (path: string) =>
  path === '/room' ||
  path.startsWith('/room/') ||
  path.startsWith('/r/') ||
  path.startsWith('/g/') ||
  path.startsWith('/p/')

const agentHomeRedirect = (path: string, roles: string[]) =>
  (path === '/' || path === '/index') &&
  roles.includes('lottery_agent') && !roles.includes('super_admin')

// 路由加载前
router.beforeEach(async (to, from, next) => {
  start()
  // 玩家房间不属于后台登录体系。即使浏览器残留后台令牌，也不能触发后台用户初始化和重新登录弹窗。
  if (isPublicRoomPath(to.path)) {
    next()
    return
  }
  if (getAccessToken()) {
    if (to.path === '/login') {
      next({ path: '/' })
    } else {
      const dictStore = useDictStoreWithOut()
      const userStore = useUserStoreWithOut()
      const permissionStore = usePermissionStoreWithOut()
      // 异步加载字典
      // 另外，间接 issue：https://gitee.com/lucky5code/lucky5-ui-admin-vue3/issues/ID9FLI
      if (!dictStore.getIsSetDict) {
        dictStore.setDictMap().then()
      }
      if (!userStore.getIsSetUser) {
        isRelogin.show = true
        await userStore.setUserInfoAction()
        isRelogin.show = false
        // 后端过滤菜单
        await permissionStore.generateRoutes()
        permissionStore.getAddRouters.forEach((route) => {
          router.addRoute(route as unknown as RouteRecordRaw) // 动态添加可访问路由表
        })
        const redirectPath = from.query.redirect
        // 修复跳转时不带参数的问题
        const redirect = typeof redirectPath === 'string' ? redirectPath : to.fullPath
        const redirectLocation = parseRouteLocation(redirect)
        const nextData =
          to.fullPath === redirect
            ? { ...to, replace: true }
            : { ...redirectLocation, replace: true }
        next(agentHomeRedirect(nextData.path, userStore.getRoles)
          ? { path: '/system/agent-statistics', replace: true }
          : nextData)
      } else {
        if (agentHomeRedirect(to.path, userStore.getRoles)) {
          next({ path: '/system/agent-statistics', replace: true })
        } else {
          next()
        }
      }
    }
  } else {
    if (whiteList.includes(to.path)) {
      next()
    } else {
      next(`/login?redirect=${encodeURIComponent(to.fullPath)}`) // 否则全部重定向到登录页
    }
  }
})

router.afterEach((to) => {
  useTitle(to?.meta?.title as string)
  done() // 结束Progress
})
