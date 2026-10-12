import { watch, ref } from 'vue'
import { isString } from '@/utils/is'

export const useTitle = (newTitle?: string) => {
  const { t } = useI18n()
  const title = ref(newTitle ? t(newTitle) : '')

  watch(
    title,
    (n, o) => {
      if (isString(n) && n !== o && document) {
        document.title = n
      }
    },
    { immediate: true }
  )

  return title
}
