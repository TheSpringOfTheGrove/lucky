const money = (value: number) => Number(value || 0).toFixed(2)

export const cancelSuccessText = 'tui碼成功'
export const isRoomAmountCommand = (value: string) => /^(上|下)(?:分)?\s*\d+(?:\.\d+)?$/.test(value.trim())

export const normalizeRoomReply = (value: string) => value
  .replace(/累计流水：([^\n，]+)，普通：([^\n，]+)，龙虎：[^\n]*/g, '累计流水：$1\n普通流水：$2')
  .replace(/可返流水：([^\n，]+)，普通：([^\n，]+)，龙虎：[^\n]*\n返还水：[^\n]*\n目前：/g,
    '可返流水：$1\n普通：$2\n\n目前：')
  .replace(/【目前房源】：\n目前无房源/g, '【今日房源】：')
  .replace(/【目前房源】/g, '【今日房源】')
  .replace(/【您目前】：/g, '【您目前余】')
  .replace(/\[挂牌时间\]|【挂牌时间】/g, '【入住时间】')
  .replace(/【套内】/g, '【室内】')
  .replace(/【套外】/g, '【室外】')
  .replace(/房源已录入成功[√✓]{2}/g, '房源已提交成功√√√')
  .replace(/房源已提交成功✅✅✅/g, '房源已提交成功√√√')
  .replace(/【户型审核成功】(?:[√✓]{2,3}|☑️☑️☑️)/g, '【户型审核成功】√√√')
  .replace(/【状态】:房源已提交成功/g, '【状态】：房源已提交成功')
  .replace(/审批通过\[加油(\d+(?:\.\d+)?)\]/g, '加分：$1')
  .replace(/考核成功下(\d+(?:\.\d+)?)/g, '减分：$1')
  .replace(/(^|\n)(加分|减分)[ \t]*[：:]?[ \t]*(\d+(?:\.\d+)?)/g, '$1$2：$3')
  .replace(/(^|\n)(?:现在|目前)[ \t]*[：:][ \t]*/g, '$1目前：')

export const cancelFailureText = (reason?: string) => {
  const detail = (reason || '当前订单不能退码')
    .trim()
    .replace(/^(?:tui碼失败|退码失败)[：:,，]?\s*/, '')
  return `tui碼失败，${detail || '当前订单不能退码'}`
}

export const resolveDragonTiger = (numbers: string[], storedResult = '') => {
  if (['龙', '虎', '和'].includes(storedResult)) return storedResult
  const settlementNumbers = (numbers || []).slice(0, 4).map(Number)
  if (settlementNumbers.length !== 4 || settlementNumbers.some(Number.isNaN)) return ''
  const first = settlementNumbers[0]
  const last = settlementNumbers[3]
  return first === last ? '和' : first > last ? '龙' : '虎'
}

export const roomAmountRecordPresentation = (record: {
  member?: string
  type: string
  status: string
  remark: string
  amount: number
  recordSource?: string
}) => {
  const isRebate = record.type === '返水' || record.recordSource === 'REBATE'
  const isTransfer = !isRebate && (record.type === '上分' || record.type === '下分')
  const memberPrefix = isTransfer && record.member?.trim() ? `@${record.member.trim()}\n` : ''
  return {
    relatedCommandType: isTransfer
      ? record.type === '上分'
        ? 'DEPOSIT_REQUEST'
        : 'WITHDRAW_REQUEST'
      : isRebate
        ? 'FLOW_REBATE'
        : '',
    memberCommand: '',
    robotReply: memberPrefix + (isRebate
      ? record.status === '已通过'
        ? '返水已入账'
        : `返水${record.status}`
      : isTransfer && record.status === '已通过'
        ? `${record.type === '上分' ? '加分' : '减分'}：${Number(record.amount || 0)}`
        : `${record.type}${record.status}`)
  }
}

export const roomReplyTemplates = {
  welcome(roomName: string, memberName: string, balance: number, period: string) {
    return `${roomName}\n会员：${memberName}\n可用积分：${money(balance)}\n当前期号：${period || '等待开盘'}`
  },

  issueTransition(status: string) {
    return status === 'OPEN' ? '★★★★★  开始——答题  ★★★★★' : '★★★★★  停止——答题  ★★★★★'
  },

  draw(period: string, numbers: string[], storedDragonTiger = '') {
    const dragonTiger = resolveDragonTiger(numbers, storedDragonTiger)
    return `^^--| ${period.slice(-3)}期开奖结果-${numbers.join('|')}${dragonTiger ? `|${dragonTiger}` : ''}`
  }
}
