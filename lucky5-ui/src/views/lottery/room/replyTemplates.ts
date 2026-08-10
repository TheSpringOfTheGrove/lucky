import type { RoomOrder } from '@/api/lottery/room'

const money = (value: number) => Number(value || 0).toFixed(2)

export const roomReplyTemplates = {
  welcome(roomName: string, memberName: string, balance: number, period: string) {
    return `${roomName}\n会员：${memberName}\n可用积分：${money(balance)}\n当前期号：${period || '等待开盘'}`
  },

  issueTransition(status: string) {
    return status === 'OPEN' ? '^^★★★开始-答题★★★' : '^^★★★停止-上课★★★'
  },

  periodSummary(memberName: string, orders: RoomOrder[]) {
    if (!orders.length) return ''
    const lines = orders.map((order) => `[${memberName}]${order.content}`)
    return `本期成功订单\n${lines.join('\n')}\n------------`
  },

  draw(period: string, numbers: string[]) {
    return `^^--| ${period.slice(-3)}期开奖结果-${numbers.join('|')}`
  }
}
