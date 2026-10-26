const money = (value: number) => Number(value || 0).toFixed(2)

export const cancelSuccessText = 'tui碼成功'

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
  type: string
  status: string
  remark: string
  amount: number
  recordSource?: string
}) => {
  const isRebate = record.type === '返水' || record.recordSource === 'REBATE'
  const isTransfer = !isRebate && (record.type === '上分' || record.type === '下分')
  const isMemberRequest = isTransfer && record.remark !== '后台手动操作'
  return {
    relatedCommandType: isTransfer
      ? record.type === '上分'
        ? 'DEPOSIT_REQUEST'
        : 'WITHDRAW_REQUEST'
      : isRebate
        ? 'FLOW_REBATE'
        : '',
    memberCommand: isMemberRequest
      ? `${record.type === '上分' ? '上' : '下'}${money(record.amount)}`
      : '',
    robotReply: isRebate
      ? record.status === '已通过'
        ? '返水已入账'
        : `返水${record.status}`
      : `${record.type}${isMemberRequest ? '申请' : ''}${record.status}`
  }
}

export const roomReplyTemplates = {
  welcome(roomName: string, memberName: string, balance: number, period: string) {
    return `${roomName}\n会员：${memberName}\n可用积分：${money(balance)}\n当前期号：${period || '等待开盘'}`
  },

  issueTransition(status: string) {
    return status === 'OPEN' ? '^^★★★开始-答题★★★' : '^^★★★停止-上课★★★'
  },

  draw(period: string, numbers: string[], storedDragonTiger = '') {
    const dragonTiger = resolveDragonTiger(numbers, storedDragonTiger)
    return `^^--| ${period.slice(-3)}期开奖结果-${numbers.join('|')}${dragonTiger ? `|${dragonTiger}` : ''}`
  }
}
