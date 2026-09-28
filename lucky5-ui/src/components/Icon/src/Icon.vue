<script lang="ts" setup>
import { propTypes } from '@/utils/propTypes'
import { Icon as IconifyIcon } from '@iconify/vue'
import { legacyFontGlyphs, legacyIcons } from './legacyIcons'
import { useDesign } from '@/hooks/web/useDesign'

defineOptions({ name: 'Icon' })

const { getPrefixCls } = useDesign()

const prefixCls = getPrefixCls('icon')

const props = defineProps({
  // icon name
  icon: propTypes.string,
  // icon color
  color: propTypes.string,
  // icon size
  size: propTypes.number.def(16),
  // icon svg class
  svgClass: propTypes.string.def('')
})

const isLocal = computed(() => props.icon?.startsWith('svg-icon:'))

const symbolId = computed(() => {
  return unref(isLocal) ? `#icon-${props.icon.split('svg-icon:')[1]}` : props.icon
})

const getSvgClass = computed(() => {
  const { svgClass } = props
  return `iconify ${svgClass}`
})

const legacyGlyph = computed(() => legacyFontGlyphs[props.icon as keyof typeof legacyFontGlyphs])
</script>

<template>
  <ElIcon :class="prefixCls" :color="color" :size="size">
    <svg v-if="isLocal" :class="getSvgClass">
      <use :xlink:href="symbolId" />
    </svg>

    <template v-else>
      <i
        v-if="legacyGlyph"
        class="legacy-icon-glyph"
        :class="icon.startsWith('ion:') ? 'legacy-icon-glyph--ion' : 'legacy-icon-glyph--fa'"
        aria-hidden="true"
        >{{ legacyGlyph }}</i
      >
      <IconifyIcon
        :icon="legacyIcons[symbolId as keyof typeof legacyIcons] || symbolId"
        :class="[getSvgClass, { 'legacy-icon-fallback': Boolean(legacyGlyph) }]"
        :style="{ fontSize: `${size}px`, color }"
      />
    </template>
  </ElIcon>
</template>

<style lang="scss">
@font-face {
  font-family: 'LuckyFontAwesome4';
  font-style: normal;
  font-weight: normal;
  src: url('./fonts/fontawesome-4.7.woff2') format('woff2');
  font-display: block;
}

@font-face {
  font-family: 'LuckyIonicons2';
  font-style: normal;
  font-weight: normal;
  src: url('./fonts/ionicons-2.0.1.woff') format('woff');
  font-display: block;
}

.legacy-icon-glyph {
  display: none;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  font-style: normal;
  font-weight: normal;
  line-height: 1;
}

body.lucky-admin-theme .legacy-icon-glyph {
  display: inline-block;
}

body.lucky-admin-theme .legacy-icon-fallback {
  display: none;
}

.legacy-icon-glyph--fa {
  font-family: 'LuckyFontAwesome4';
}

.legacy-icon-glyph--ion {
  font-family: 'LuckyIonicons2';
}
</style>
