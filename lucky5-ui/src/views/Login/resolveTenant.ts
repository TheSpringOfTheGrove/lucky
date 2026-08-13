import * as LoginApi from '@/api/login'
import * as authUtil from '@/utils/auth'

/** Lucky5 当前统一使用的系统租户。 */
export const SYSTEM_TENANT_ID = 1

/**
 * 登录租户解析规则：未选择租户时使用系统租户；未来开放租户选择后使用明确选择的租户。
 */
export const resolveLoginTenant = async (
  selectedTenantName?: string,
  tenantSelectionEnabled = false
) => {
  if (import.meta.env.VITE_APP_TENANT_ENABLE !== 'true') {
    return undefined
  }

  const tenantName = tenantSelectionEnabled ? selectedTenantName?.trim() : ''
  if (!tenantName) {
    authUtil.setTenantId(SYSTEM_TENANT_ID)
    return SYSTEM_TENANT_ID
  }

  const tenantId = await LoginApi.getTenantIdByName(tenantName)
  if (!tenantId) {
    throw new Error('所选租户不存在')
  }
  authUtil.setTenantId(tenantId)
  return tenantId
}
