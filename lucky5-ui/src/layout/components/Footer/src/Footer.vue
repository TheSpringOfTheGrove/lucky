<script lang="ts" setup>
import { useDesign } from '@/hooks/web/useDesign'

defineOptions({ name: 'Footer' })

const { getPrefixCls } = useDesign()

const prefixCls = getPrefixCls('footer')
</script>

<template>
  <footer :class="[prefixCls, 'lucky-admin-footer']">
    <strong>Copyright © 2014-2025 .</strong> All rights reserved.
  </footer>
</template>
