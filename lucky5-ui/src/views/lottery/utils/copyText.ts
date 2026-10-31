/** Clipboard API requires a secure context; the selection fallback also works on HTTP IP pages. */
export const copyText = async (value: string): Promise<boolean> => {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(value)
      return true
    } catch {
      // Permissions can reject the modern API even when it exists.
    }
  }

  const input = document.createElement('textarea')
  input.value = value
  input.style.position = 'fixed'
  input.style.top = '0'
  input.style.opacity = '0'
  input.style.pointerEvents = 'none'
  const previousFocus = document.activeElement as HTMLElement | null
  const container = previousFocus?.closest('.el-dialog') || document.body
  container.appendChild(input)
  try {
    input.focus()
    input.select()
    input.setSelectionRange(0, value.length)
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    input.remove()
    previousFocus?.focus()
  }
}
