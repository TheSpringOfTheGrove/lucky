const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { resolve } = require('node:path')
const { runInNewContext } = require('node:vm')
const ts = require('typescript')
const source = readFileSync(resolve(__dirname, '../src/views/lottery/room/index.vue'), 'utf8')
const ast = ts.createSourceFile('room.ts', source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1], ts.ScriptTarget.Latest, true)
const functions = {}
function visit(node) {
  if (ts.isVariableDeclaration(node) && ['scheduleBetReplyRefresh', 'scheduleSessionRefresh'].includes(node.name.getText(ast))) {
    functions[node.name.getText(ast)] = node.initializer.getText(ast)
  }
  ts.forEachChild(node, visit)
}
visit(ast)
function fixture() {
  const jobs = []
  const context = {
    window: { clearTimeout() {}, setTimeout(callback, delay) { jobs.push({ callback, delay }); return jobs.length } },
    betReplyTimer: undefined, refreshTimer: undefined, betReplyRequestSequence: 0, sessionRequestSequence: 0,
    trackedBetMessageIds: { value: [1] }, betReplyFastPollUntilMs: { value: Date.now() + 30000 },
    autoFollowMessages: { value: false }, credential: { value: { tenantId: 1, openId: 'member' } },
    session: { value: { messages: [{ id: 1, reply: '提交中' }], orders: [{ id: 'o1', processing: true }] } },
    getRoomBetRepliesApi: async () => [{ id: 1, orderId: 'o1', status: '已受理', reply: '标准回执\n点击退码', processing: false }],
    nextTick: async () => {}, collectUnreadMessages() {}, loadSession: async () => {}, scheduleDrawStateRefresh() {}
  }
  const script = Object.entries(functions).map(([name, value]) => `${name} = ${value}`).join('\n')
  runInNewContext(ts.transpileModule(script, { compilerOptions: { target: ts.ScriptTarget.ES2020 } }).outputText, context)
  return { context, jobs }
}
test('polls only lightweight replies at 500ms and keeps full snapshots at five seconds', async () => {
  const { context, jobs } = fixture()
  context.scheduleBetReplyRefresh()
  context.scheduleSessionRefresh()
  assert.deepEqual(jobs.map(job => job.delay), [500, 5000])
  await jobs[0].callback()
  assert.equal(context.session.value.messages[0].reply, '标准回执\n点击退码')
  assert.equal(context.session.value.orders[0].processing, false)
  assert.equal(context.trackedBetMessageIds.value.length, 0)
  assert.equal(context.sessionRequestSequence, 1)
})
test('ends fast polling after its bounded window', () => {
  const { context, jobs } = fixture()
  context.betReplyFastPollUntilMs.value = 0
  context.scheduleBetReplyRefresh()
  assert.equal(jobs.length, 0)
})
test('ignores a reply from an earlier room or an unmounted component', async () => {
  const { context, jobs } = fixture()
  context.getRoomBetRepliesApi = async () => {
    context.betReplyRequestSequence++
    return [{ id: 1, reply: 'old room', processing: false }]
  }
  context.scheduleBetReplyRefresh()
  await jobs[0].callback()
  assert.equal(context.session.value.messages[0].reply, '提交中')
})
