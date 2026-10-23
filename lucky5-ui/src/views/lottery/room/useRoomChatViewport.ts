import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue'

export const useRoomChatViewport = (
  chatRef: Ref<HTMLElement | undefined>,
  contentRef: Ref<HTMLElement | undefined>,
  composerRef: Ref<HTMLElement | undefined>,
  autoFollow: Ref<boolean>,
  onReachBottom: () => void
) => {
  const viewportHeight = ref(window.visualViewport?.height || window.innerHeight)
  const viewportTop = ref(window.visualViewport?.offsetTop || 0)
  const composerHeight = ref(49)
  const viewportStyle = computed(() => ({
    height: `${viewportHeight.value}px`,
    top: `${viewportTop.value}px`,
    '--composer-height': `${composerHeight.value}px`
  }))
  let disposed = false
  let followFrame: number | undefined
  let remainingPasses = 0
  let previousTop = 0
  let previousHeight = 0
  let previousClientHeight = 0
  let touchY: number | undefined

  const rememberScrollPosition = (stream: HTMLElement) => {
    previousTop = stream.scrollTop
    previousHeight = stream.scrollHeight
    previousClientHeight = stream.clientHeight
  }

  // 回执、图片和 iOS 键盘可能分多次改变布局。每次按最新尺寸贴底，
  // 不使用会被后续布局打断、并产生中间 scroll 事件的平滑滚动。
  const followAfterLayout = () => {
    if (disposed || !autoFollow.value || !chatRef.value) return
    remainingPasses = 2
    if (followFrame !== undefined) return
    const align = () => {
      followFrame = undefined
      const stream = chatRef.value
      if (disposed || !autoFollow.value || !stream) return
      stream.scrollTop = Math.max(0, stream.scrollHeight - stream.clientHeight)
      rememberScrollPosition(stream)
      if (--remainingPasses > 0) followFrame = window.requestAnimationFrame(align)
    }
    followFrame = window.requestAnimationFrame(align)
  }

  const scrollToBottom = async () => {
    autoFollow.value = true
    onReachBottom()
    await nextTick()
    followAfterLayout()
  }

  const handleScroll = () => {
    const stream = chatRef.value
    if (!stream) return
    const distance = stream.scrollHeight - stream.scrollTop - stream.clientHeight
    const layoutUnchanged =
      stream.scrollHeight === previousHeight && stream.clientHeight === previousClientHeight
    if (distance <= 4) {
      autoFollow.value = true
      onReachBottom()
    } else if (layoutUnchanged && stream.scrollTop < previousTop - 1) {
      // 也支持拖动桌面滚动条或用键盘向上阅读；布局变化不代表用户离开底部。
      autoFollow.value = false
    }
    rememberScrollPosition(stream)
    if (autoFollow.value) followAfterLayout()
  }

  const handleWheel = (event: WheelEvent) => {
    if (event.deltaY < 0) autoFollow.value = false
  }
  const handleTouchStart = (event: TouchEvent) => {
    touchY = event.touches[0]?.clientY
  }
  const handleTouchMove = (event: TouchEvent) => {
    const nextY = event.touches[0]?.clientY
    if (nextY !== undefined && touchY !== undefined && nextY > touchY + 1) {
      autoFollow.value = false
    }
    touchY = nextY
  }

  const stopObserving = watch(
    [chatRef, contentRef, composerRef],
    ([stream, content, composer], _previous, onCleanup) => {
      const measureLayout = () => {
        if (composer) composerHeight.value = Math.ceil(composer.getBoundingClientRect().height)
        followAfterLayout()
      }
      const observer = new ResizeObserver(measureLayout)
      for (const element of [stream, content, composer]) {
        if (element) observer.observe(element)
      }
      stream?.addEventListener('wheel', handleWheel, { passive: true })
      stream?.addEventListener('touchstart', handleTouchStart, { passive: true })
      stream?.addEventListener('touchmove', handleTouchMove, { passive: true })
      if (stream) rememberScrollPosition(stream)
      measureLayout()
      onCleanup(() => {
        observer.disconnect()
        stream?.removeEventListener('wheel', handleWheel)
        stream?.removeEventListener('touchstart', handleTouchStart)
        stream?.removeEventListener('touchmove', handleTouchMove)
      })
    },
    { flush: 'post', immediate: true }
  )

  const syncViewport = () => {
    const viewport = window.visualViewport
    viewportHeight.value = viewport?.height || window.innerHeight
    viewportTop.value = Math.max(0, viewport?.offsetTop || 0)
    followAfterLayout()
  }
  onMounted(() => {
    syncViewport()
    window.addEventListener('resize', syncViewport)
    window.visualViewport?.addEventListener('resize', syncViewport)
    window.visualViewport?.addEventListener('scroll', syncViewport)
  })
  onBeforeUnmount(() => {
    disposed = true
    stopObserving()
    if (followFrame !== undefined) window.cancelAnimationFrame(followFrame)
    window.removeEventListener('resize', syncViewport)
    window.visualViewport?.removeEventListener('resize', syncViewport)
    window.visualViewport?.removeEventListener('scroll', syncViewport)
  })

  return { composerHeight, viewportHeight, viewportStyle, scrollToBottom, handleScroll }
}
