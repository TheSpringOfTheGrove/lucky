<script lang="ts" setup>
import { computed, onMounted, ref, unref, watch } from 'vue'
import { useAppStore } from '@/store/modules/app'
import { useDesign } from '@/hooks/web/useDesign'
import { getLayoutRenderMode, isHeaderNavLayout } from '@/utils/layout'

defineOptions({ name: 'Logo' })

const { getPrefixCls } = useDesign()

const prefixCls = getPrefixCls('logo')

const appStore = useAppStore()

const show = ref(true)

const title = computed(() => appStore.getTitle)

const layout = computed(() => appStore.getLayout)

const collapse = computed(() => appStore.getCollapse)

const mobile = computed(() => appStore.getMobile)

onMounted(() => {
  if (unref(collapse) && !unref(mobile)) show.value = false
})

watch(
  () => collapse.value,
  (collapse: boolean) => {
    if (unref(mobile)) {
      show.value = true
      return
    }
    if (
      getLayoutRenderMode(unref(layout)) === 'topLeft' ||
      getLayoutRenderMode(unref(layout)) === 'cutMenu'
    ) {
      show.value = true
      return
    }
    if (!collapse) {
      setTimeout(() => {
        show.value = !collapse
      }, 400)
    } else {
      show.value = !collapse
    }
  }
)

watch(
  () => mobile.value,
  (isMobile) => {
    if (isMobile) {
      show.value = true
    }
  },
  { immediate: true }
)

watch(
  () => layout.value,
  (layout) => {
    const renderMode = getLayoutRenderMode(layout)
    if (renderMode === 'top' || renderMode === 'cutMenu') {
      show.value = true
    } else {
      if (unref(collapse)) {
        show.value = false
      } else {
        show.value = true
      }
    }
  }
)
</script>

<template>
  <div>
    <router-link
      :class="[
        prefixCls,
        'luck-logo',
        getLayoutRenderMode(layout) !== 'classic' ? `${prefixCls}__Top` : '',
        'flex !h-[var(--logo-height)] items-center cursor-pointer pl-8px relative decoration-none overflow-hidden'
      ]"
      to="/"
    >
      <img
        class="h-[calc(var(--logo-height)-10px)] w-[calc(var(--logo-height)-10px)]"
        src="@/assets/imgs/logo.png"
      />
      <div
        v-if="show"
        :class="[
          'ml-10px text-16px font-700',
          {
            'text-[var(--logo-title-text-color)]': getLayoutRenderMode(layout) === 'classic',
            'text-[var(--top-header-text-color)]':
              getLayoutRenderMode(layout) === 'topLeft' ||
              isHeaderNavLayout(layout) ||
              getLayoutRenderMode(layout) === 'cutMenu'
          }
        ]"
      >
        <span class="luck-logo__title">{{ title }}</span>
        <span class="luck-logo__subtitle">管理后台</span>
        <span class="luck-logo__legacy-title">请仔细辨别 谨防假冒</span>
        <span class="luck-logo__legacy-subtitle">防伪查询中心17t.xyz</span>
      </div>
    </router-link>
  </div>
</template>
