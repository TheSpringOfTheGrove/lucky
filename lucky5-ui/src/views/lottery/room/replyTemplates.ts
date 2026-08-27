const money = (value: number) => Number(value || 0).toFixed(2)

export const resolveDragonTiger = (numbers: string[], storedResult = '') => {
  if (['龙', '虎', '和'].includes(storedResult)) return storedResult
  const settlementNumbers = (numbers || []).slice(0, 4).map(Number)
  if (settlementNumbers.length !== 4 || settlementNumbers.some(Number.isNaN)) return ''
  const first = settlementNumbers[0]
  const last = settlementNumbers[3]
  return first === last ? '和' : first > last ? '龙' : '虎'
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
