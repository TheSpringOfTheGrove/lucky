const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { resolve } = require('node:path')
const { runInNewContext } = require('node:vm')
const ts = require('typescript')

const source = readFileSync(resolve(__dirname, '../src/views/lottery/member/index.vue'), 'utf8')
const names = ['openTransfer', 'captureTransferAmount', 'parseTransferAmount', 'submitTransfer']
const functions = {}
function extract(text, filename, selected) {
  const ast = ts.createSourceFile(filename, text, ts.ScriptTarget.Latest, true)
  function visit(node) {
    if (ts.isVariableDeclaration(node) && selected.includes(node.name.getText(ast))) {
      functions[node.name.getText(ast)] = node.initializer.getText(ast)
    }
    ts.forEachChild(node, visit)
  }
  visit(ast)
}
extract(source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1], 'member.ts', names)
extract(readFileSync(resolve(__dirname, '../src/api/lottery/index.ts'), 'utf8'), 'api.ts', ['transferMemberApi'])

function fixture(response = () => Promise.resolve(true)) {
  const requests = []
  const warnings = []
  class AmountInput {
    constructor(value) { this.value = value }
  }
  const context = {
    transferForm: { id: '', type: '上分', amount: '' },
    transferSubmitting: { value: false }, transferVisible: { value: false },
    HTMLInputElement: AmountInput,
    ElMessage: { warning: (value) => warnings.push(value) },
    base: '/lottery',
    request: { post: (payload) => { requests.push(payload); return response() } },
    store: { saving: false, transferMember: (...args) => context.transferMemberApi(...args) }
  }
  runInNewContext(ts.transpileModule(
    Object.entries(functions).map(([name, value]) => `${name} = ${value}`).join('\n'),
    { compilerOptions: { target: ts.ScriptTarget.ES2020 } }
  ).outputText, context)
  context.openTransfer({ id: 'test-member' }, '上分')
  const input = (value) => context.captureTransferAmount({ target: new AmountInput(value) })
  return { context, requests, warnings, input }
}

test('transfer opens empty and never silently supplies 0.01', async () => {
  const { context, requests, warnings } = fixture()
  assert.equal(context.transferForm.amount, '')
  assert.match(source, /amount: ''/)
  assert.doesNotMatch(source, /el-input-number v-model="transferForm.amount"/)
  await context.submitTransfer()
  assert.equal(requests.length, 0)
  assert.equal(warnings.length, 1)
  context.transferForm.amount = '1000'
  context.openTransfer({ id: 'another-member' }, '下分')
  assert.equal(context.transferForm.amount, '')
})

test('mobile native input is captured before the IME completes and sends exactly 1000', async () => {
  const { context, requests, input } = fixture()
  assert.match(source, /@input.capture="captureTransferAmount"/)
  assert.match(source, /inputmode="decimal"/)
  // No component v-model or compositionend event: capture the native input directly.
  input('1000')
  assert.equal(context.transferForm.amount, '1000')
  await context.submitTransfer()
  assert.equal(requests.length, 1)
  assert.equal(requests[0].url, '/lottery/members/test-member/transfer')
  assert.equal(requests[0].data.amount, 1000)
  assert.equal(requests[0].data.type, '上分')
  assert.equal(context.transferVisible.value, false)
})

test('decimal amounts remain exact, including 0.1 and 0.01', async () => {
  for (const [text, amount] of [['0.1', 0.1], ['.01', 0.01], ['1000.25', 1000.25], ['1000.', 1000]]) {
    const { context, requests, input } = fixture()
    input(text)
    await context.submitTransfer()
    assert.equal(requests[0].data.amount, amount)
  }
})

test('empty, invalid, excessive precision and unsafe amounts never issue a request', async () => {
  for (const value of ['', ' ', '0', '-1000', '1000元', '1e3', '0.001', 'NaN', 'Infinity', '90071992547409.92']) {
    const { context, requests, warnings, input } = fixture()
    input(value)
    await context.submitTransfer()
    assert.equal(requests.length, 0, value)
    assert.equal(warnings.length, 1, value)
    assert.equal(context.transferForm.amount, value)
    assert.equal(context.transferVisible.value, true)
  }
})

test('clearing stays empty and the field has no automatic step controls', () => {
  const { context, input } = fixture()
  input('1000')
  input('')
  assert.equal(context.transferForm.amount, '')
  const dialog = source.slice(source.indexOf('v-model="transferVisible"'))
    .split('</el-dialog>')[0]
  assert.match(dialog, /<el-input\s/)
  assert.match(dialog, /placeholder="分数"/)
  assert.doesNotMatch(dialog, /el-input-number|adjustTransferAmount|keydown\.(?:up|down)/)
})

test('repeated confirmation cannot create duplicate requests or switch the active member', async () => {
  let complete
  const { context, requests, input } = fixture(() => new Promise((resolve) => { complete = resolve }))
  input('1000')
  const first = context.submitTransfer()
  await context.submitTransfer()
  context.openTransfer({ id: 'another-member' }, '下分')
  assert.equal(requests.length, 1)
  assert.equal(context.transferForm.id, 'test-member')
  assert.equal(context.transferSubmitting.value, true)
  complete(true)
  await first
  assert.equal(context.transferSubmitting.value, false)
})

test('failed submission preserves the original amount and permits retry', async () => {
  const { context, requests, input } = fixture(() => Promise.resolve(false))
  input('1000')
  await context.submitTransfer()
  assert.equal(context.transferForm.amount, '1000')
  assert.equal(context.transferVisible.value, true)
  assert.equal(context.transferSubmitting.value, false)
  context.openTransfer({ id: 'test-member' }, '下分')
  input('1000')
  await context.submitTransfer()
  assert.equal(requests[1].data.type, '下分')
  assert.equal(requests[1].data.amount, 1000)
})
