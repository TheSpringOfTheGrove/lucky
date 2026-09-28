/** Shared room/audit receipt body; action labels are rendered separately and never as HTML. */
export const receiptText = (value: string) =>
  value
    .replace(/【(编号|套内|套外|面积)】：/g, '【$1】:')
    .replace('【户型审核成功】✓✓', '【户型审核成功】√√')
    .replace(/【编号】:\s*(\d+)/g, '【编号】$1')
    .replace(
      /【(套外|面积)】:\s*(\d+(?:\.\d+)?)/g,
      (_, label, amount) => `【${label}】:${Number(amount).toFixed(2)}`
    )
    .split('\n')
    .filter(
      (line) =>
        !['点击退码', '已退码'].includes(line.trim()) &&
        !/^共\s*\d+\s*注\s*合计\s*[\d,.]+$/.test(line.trim())
    )
    .join('\n')
    .trimEnd()

export const receiptAction = (value: string): 'cancelable' | 'canceled' | undefined => {
  const lines = value.split('\n').map((line) => line.trim())
  if (lines.includes('已退码')) return 'canceled'
  if (lines.includes('点击退码')) return 'cancelable'
  return undefined
}
