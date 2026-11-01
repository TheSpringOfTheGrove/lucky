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
const { roomAmountRecordPresentation, normalizeRoomReply, roomReplyTemplates } = templateExports

test('room status bubbles do not show the old prefix', () => {
  assert.equal(roomReplyTemplates.issueTransition('OPEN'), '★★★★开始———答题★★★★')
  assert.equal(roomReplyTemplates.issueTransition('CLOSED'), '★★★★停止———答题★★★★')
})

test('old approved transfer replies render with the new wording', () => {
  assert.equal(normalizeRoomReply('@小吴\n审批通过[加油100]\n现在:10054'),
    '@小吴\n加分：100\n现在:10054')
  assert.equal(normalizeRoomReply('@测试1\n考核成功下100\n现在:9954'),
    '@测试1\n减分：100\n现在:9954')
  assert.equal(normalizeRoomReply('@测试1\n减分 1000\n现在:9254'),
    '@测试1\n减分：1000\n现在:9254')
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

test('actual member withdrawal keeps its existing request bubbles', () => {
  const result = roomAmountRecordPresentation({
    member: '小吴',
    type: '下分',
    status: '已通过',
    remark: '网页群消息申请',
    amount: 191.52,
    recordSource: 'PLAYER'
  })
  assert.equal(result.memberCommand, '下191.52')
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
