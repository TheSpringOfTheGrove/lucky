const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { resolve } = require('node:path')
const { runInNewContext } = require('node:vm')
const ts = require('typescript')
const dayjs = require('dayjs')
const source = readFileSync(resolve(__dirname, '../src/views/lottery/room/index.vue'), 'utf8')
const ast = ts.createSourceFile('room.ts', source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1], ts.ScriptTarget.Latest, true)
const functions = {}
function visit(node) {
  if (ts.isVariableDeclaration(node) && ['chatMessages', 'persistedDrawFromMessage', 'refreshCompletedDrawMessage'].includes(node.name.getText(ast))) {
    functions[node.name.getText(ast)] = node.initializer.getText(ast)
  }
  ts.forEachChild(node, visit)
}
visit(ast)
function fixture() {
  let refreshes = 0
  const draw = { period: '20260928238', result: '04247', numbers: ['0', '4', '2', '4', '7'], settledAt: '2026-09-28T19:50:06' }
  const context = {
    dayjs, computed: callback => ({ get value() { return callback() } }),
    session: { value: { draws: [draw], messages: [], amountRecords: [], issueTransitions: [], member: {}, room: { mode: 'GROUP' } } },
    visibleRoomMessages: { value: [] }, localMessages: { value: [] }, sessionStartedAt: { value: '2026-09-28T19:50:00' },
    playerSentAtOverrides: { value: {} }, orderById: { value: {} }, requestedDrawMessagePeriod: '',
    roomReplyTemplates: { welcome: () => 'welcome', draw: () => 'formal draw', issueTransition: status => status },
    formatRobotReply: reply => reply, money: value => Number(value || 0).toFixed(2),
    resolveDragonTiger: () => '虎', loadSession: async () => { refreshes++ }
  }
  const script = Object.entries(functions).map(([name, value]) => `globalThis.${name} = ${value}`).join('\n')
  runInNewContext(ts.transpileModule(script, { compilerOptions: { target: ts.ScriptTarget.ES2020 } }).outputText, context)
  return { context, draw, get refreshes() { return refreshes } }
}
function saved(id, image = 'data:image/svg+xml;base64,saved') {
  return { id, commandType: 'DRAW_RESULT', period: '20260928238', content: '04247', reply: 'formal draw', drawImage: image, createdAt: '2026-09-28T19:50:06' }
}

test('a lightweight result or an imageless message cannot create an alternate draw bubble', () => {
  const { context } = fixture()
  assert.equal(context.chatMessages.value.filter(message => message.type === 'draw').length, 0)
  context.visibleRoomMessages.value = [saved(1, '')]
  assert.equal(context.chatMessages.value.filter(message => message.type === 'draw').length, 0)
  assert.equal(context.chatMessages.value.some(message => message.id.includes('member-message-1')), false)
})

test('only the immutable saved image renders, once per period, after payout and before next open', () => {
  const { context } = fixture()
  context.visibleRoomMessages.value = [
    { id: 3, commandType: 'PAYOUT_SUMMARY', period: '20260928238', reply: 'payout', content: '', own: false, createdAt: '2026-09-28T19:50:06' },
    saved(1), saved(2)
  ]
  context.session.value.issueTransitions = [{ id: 1, status: 'OPEN', createdAt: '2026-09-28T19:50:06' }]
  const draws = context.chatMessages.value.filter(message => message.type === 'draw')
  assert.equal(draws.length, 1)
  assert.equal(draws[0].drawImage, 'data:image/svg+xml;base64,saved')
  const messages = context.chatMessages.value
  assert.ok(messages.findIndex(message => message.id === 'robot-message-3') < messages.findIndex(message => message.id === draws[0].id))
  assert.ok(messages.findIndex(message => message.id === draws[0].id) < messages.findIndex(message => message.id === 'issue-transition-1'))
  context.session.value.draws = [{ period: '20260928239', result: '99999', settledAt: '2026-09-28T19:55:00' }]
  assert.equal(context.chatMessages.value.filter(message => message.type === 'draw')[0].drawImage, draws[0].drawImage)
})

test('one completed period triggers one message refresh rather than every hot polling tick', () => {
  const state = fixture()
  state.context.refreshCompletedDrawMessage([{ ...state.draw, settledAt: '' }])
  assert.equal(state.refreshes, 0)
  for (let tick = 0; tick < 50; tick++) state.context.refreshCompletedDrawMessage([state.draw])
  assert.equal(state.refreshes, 1)
  state.context.refreshCompletedDrawMessage([{ ...state.draw, period: '20260928239' }])
  assert.equal(state.refreshes, 2)
})

test('already saved messages need no extra session refresh and no HTML fallback remains', () => {
  const state = fixture()
  state.context.visibleRoomMessages.value = [saved(1)]
  state.context.refreshCompletedDrawMessage([state.draw])
  assert.equal(state.refreshes, 0)
  assert.doesNotMatch(source, /lottery-table|drawSequenceAnchors|drawNumberClass/)
  assert.match(source, /:src="message\.drawImage"/)
  assert.match(source, /refreshCompletedDrawMessage\(nextState\.draws\)/)
})

test('cached automatic funding never renders a fake player request or robot approval', () => {
  const { context } = fixture()
  const createdAt = '2026-09-27T22:15:00'
  context.visibleRoomMessages.value = [{ id: 91, messageType: 'AUTO_PROXY', commandType: 'DEPOSIT_REQUEST',
    own: true, content: '上1000', reply: '自动托虚拟积分不足，系统自动审核', createdAt }]
  context.session.value.amountRecords = [
    { id: 'AUTO-1', recordSource: 'AUTO_PROXY', type: '上分', amount: 1000, remark: '', createdAt },
    { id: 'LEGACY-1', type: '上分', amount: 1000, remark: '自动托虚拟积分不足，系统自动审核', createdAt }
  ]
  assert.equal(context.chatMessages.value.some(message => /91|AUTO-1|LEGACY-1/.test(message.id)), false)
  assert.equal(context.chatMessages.value.some(message => /自动托|上1000/.test(message.content)), false)
})

test('real player funding is still visible with the standard approval', () => {
  const { context } = fixture()
  const createdAt = '2026-09-27T22:15:00'
  context.visibleRoomMessages.value = [{ id: 92, messageType: 'PLAYER', commandType: 'DEPOSIT_REQUEST',
    own: true, content: '上1000', reply: '@玩家\n上分已通过', status: '已通过', createdAt }]
  assert.ok(context.chatMessages.value.some(message => message.content === '上1000'))
  assert.ok(context.chatMessages.value.some(message => message.content === '@玩家\n上分已通过'))
})
