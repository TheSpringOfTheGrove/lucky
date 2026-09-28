<script lang="ts" setup>
import { useAppStore } from '@/store/modules/app'
import { propTypes } from '@/utils/propTypes'
import { useDesign } from '@/hooks/web/useDesign'

defineOptions({ name: 'Collapse' })

const { getPrefixCls } = useDesign()

const prefixCls = getPrefixCls('collapse')

defineProps({
  color: propTypes.string.def('')
})

const appStore = useAppStore()

const toggleCollapse = () => {
  appStore.setCollapse(!appStore.getCollapse)
}
</script>

<template>
  <div :class="prefixCls" role="button" tabindex="0" aria-label="切换菜单" :aria-expanded="!appStore.getCollapse" @click="toggleCollapse" @keydown.enter.prevent="toggleCollapse" @keydown.space.prevent="toggleCollapse">
    <span class="lucky-admin-hamburger" aria-hidden="true"></span>
  </div>
</template>
