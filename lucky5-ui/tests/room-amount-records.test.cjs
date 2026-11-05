const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { resolve } = require('node:path')
const { runInNewContext } = require('node:vm')
const ts = require('typescript')

const source = readFileSync(resolve(__dirname, '../src/views/lottery/room/replyTemplates.ts'), 'utf8')
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText
const templateExports = {}
runInNewContext(compiled, { exports: templateExports })
const { isRoomAmountCommand, roomAmountRecordPresentation, normalizeRoomReply, roomReplyTemplates } = templateExports

test('room status bubbles do not show the old prefix', () => {
  assert.equal(roomReplyTemplates.issueTransition('OPEN'), '★★★★★  开始——答题  ★★★★★')
  assert.equal(roomReplyTemplates.issueTransition('CLOSED'), '★★★★★  停止——答题  ★★★★★')
})

test('old approved transfer replies render with the new wording', () => {
  assert.equal(normalizeRoomReply('@小吴\n审批通过[加油100]\n现在:10054'),
    '@小吴\n加分：100\n目前：10054')
  assert.equal(normalizeRoomReply('@测试1\n考核成功下100\n现在:9954'),
    '@测试1\n减分：100\n目前：9954')
  assert.equal(normalizeRoomReply('@测试1\n减分 1000\n现在:9254'),
    '@测试1\n减分：1000\n目前：9254')
  assert.equal(normalizeRoomReply('@测试1\n减分：1000\n现在：8144'),
    '@测试1\n减分：1000\n目前：8144')
})

test('room detects member amount commands without treating a numeric bet as one', () => {
  assert.equal(isRoomAmountCommand('上10000.00'), true)
  assert.equal(isRoomAmountCommand('下分 10000'), true)
  assert.equal(isRoomAmountCommand('1234各1'), false)
})

test('saved flow and balance replies replay in the current customer layout', () => {
  assert.equal(normalizeRoomReply('@玩家\n累计流水：708.00，普通：708.00，龙虎：0.00'),
    '@玩家\n累计流水：708.00\n普通流水：708.00')
  assert.equal(normalizeRoomReply('@玩家\n可返流水：708.00，普通：708.00，龙虎：0.00\n返还水：0.000，普通：0.000，龙虎：0.000\n目前：25835.70'),
    '@玩家\n可返流水：708.00\n普通：708.00\n\n目前：25835.70')
  assert.equal(normalizeRoomReply('@玩家\n【目前房源】：\n目前无房源\n\n【您目前】：\n20031'),
    '@玩家\n【今日房源】：\n\n【您目前余】\n20031')
})

test('rebate credit never creates a fake withdrawal request', () => {
  const result = roomAmountRecordPresentation({
    type: '返水',
    status: '已通过',
    remark: '普通 191.52 / 龙虎 0.00',
    amount: 191.52,
    recordSource: 'REBATE'
  })
  assert.equal(result.memberCommand, '')
  assert.equal(result.relatedCommandType, 'FLOW_REBATE')
  assert.equal(result.robotReply, '返水已入账')
})

test('rebate source takes priority over an inconsistent transfer type', () => {
  const result = roomAmountRecordPresentation({
    member: '小吴',
    type: '下分',
    status: '已通过',
    remark: '系统发放',
    amount: 191.52,
    recordSource: 'REBATE'
  })
  assert.equal(result.memberCommand, '')
  assert.equal(result.relatedCommandType, 'FLOW_REBATE')
  assert.equal(result.robotReply, '返水已入账')
})

test('historical member withdrawal does not recreate a player request bubble', () => {
  const result = roomAmountRecordPresentation({
    member: '小吴',
    type: '下分',
    status: '已通过',
    remark: '网页群消息申请',
    amount: 191.52,
    recordSource: 'PLAYER'
  })
  assert.equal(result.memberCommand, '')
  assert.equal(result.relatedCommandType, 'WITHDRAW_REQUEST')
  assert.equal(result.robotReply, '@小吴\n减分：191.52')
})

test('manual transfer does not fabricate a member command', () => {
  const result = roomAmountRecordPresentation({
    member: '广西一哥',
    type: '上分',
    status: '已通过',
    remark: '后台手动操作',
    amount: 10,
    recordSource: 'PLAYER'
  })
  assert.equal(result.memberCommand, '')
  assert.equal(result.relatedCommandType, 'DEPOSIT_REQUEST')
  assert.equal(result.robotReply, '@广西一哥\n加分：10')
})
