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
const { roomAmountRecordPresentation } = templateExports

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
    type: '下分',
    status: '已通过',
    remark: '网页群消息申请',
    amount: 191.52,
    recordSource: 'PLAYER'
  })
  assert.equal(result.memberCommand, '下191.52')
  assert.equal(result.relatedCommandType, 'WITHDRAW_REQUEST')
  assert.equal(result.robotReply, '下分申请已通过')
})

test('manual transfer does not fabricate a member command', () => {
  const result = roomAmountRecordPresentation({
    type: '上分',
    status: '已通过',
    remark: '后台手动操作',
    amount: 10,
    recordSource: 'PLAYER'
  })
  assert.equal(result.memberCommand, '')
  assert.equal(result.relatedCommandType, 'DEPOSIT_REQUEST')
  assert.equal(result.robotReply, '上分已通过')
})
