/** Shared room/audit receipt body; action labels are rendered separately and never as HTML. */
import { cancelSuccessText } from '../room/replyTemplates'

export const receiptText = (value: string) => {
  const normalizedValue = value
    .replace(/\[挂牌时间\]|【挂牌时间】/g, '【入住时间】')
    .replace(/【套内】/g, '【室内】')
    .replace(/【套外】/g, '【室外】')
    .replace(/【户型审核成功】(?:[√✓]{2,3}|☑️☑️☑️)/g, '【户型审核成功】√√√')
  const cancelSuccess = value
    .split('\n')
    .some((line) => [cancelSuccessText, '退码成功'].includes(line.trim()))
  const body = cancelSuccess
    ? normalizedValue
    : normalizedValue
        .replace(/【(编号|室内|室外|面积)】：/g, '【$1】:')
        .replace(/【编号】:\s*(\d+)/g, '【编号】$1')
        .replace(
          /【(室外|面积)】:\s*(\d+(?:\.\d+)?)/g,
          (_, label, amount) => `【${label}】:${Number(amount).toFixed(2)}`
        )
  return body
    .split('\n')
    .filter(
      (line) =>
        !['点击退码', '已退码', cancelSuccessText, '退码成功'].includes(line.trim()) &&
        !/^共\s*\d+\s*注\s*合计\s*[\d,.]+$/.test(line.trim())
    )
    .join('\n')
    .trimEnd()
}

export const receiptAction = (value: string): 'cancelable' | 'canceled' | undefined => {
  const lines = value.split('\n').map((line) => line.trim())
  if (lines.includes(cancelSuccessText) || lines.includes('退码成功')) return 'canceled'
  if (lines.includes('已退码')) return 'canceled'
  if (lines.includes('点击退码')) return 'cancelable'
  return undefined
}
