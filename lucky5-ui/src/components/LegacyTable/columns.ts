import { Fragment, isVNode, type VNode, type VNodeChild } from 'vue'

// Reuse the existing Element column definitions and scoped cell slots. No
// second data source, requests, pagination or business conditions are created.
export function collectLegacyColumns(children: VNodeChild[]): VNode[] {
  return children.flatMap((child) => {
    if (Array.isArray(child)) return collectLegacyColumns(child)
    if (!isVNode(child)) return []
    if (child.type === Fragment) {
      return collectLegacyColumns((child.children || []) as VNodeChild[])
    }
    const type = child.type as { name?: string }
    return type?.name === 'ElTableColumn' ? [child] : []
  })
}

export function legacyField(row: Record<string, any>, path?: string) {
  return path ? path.split('.').reduce((value, key) => value?.[key], row) : ''
}
