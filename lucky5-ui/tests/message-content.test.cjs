const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { resolve } = require('node:path')
const ts = require('typescript')
const vue = require('vue')
const { renderToString } = require('vue/server-renderer')
const { parse, compileScript } = require('vue/compiler-sfc')

function load(code, dependencies = {}) {
  const output = ts.transpileModule(code, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  const module = { exports: {} }
  new Function('require', 'module', 'exports', output)(
    (id) => dependencies[id] || require(id), module, module.exports
  )
  return module.exports
}
const replyTemplates = load(readFileSync(resolve(__dirname, '../src/views/lottery/room/replyTemplates.ts'), 'utf8'))
const receipt = load(readFileSync(resolve(__dirname, '../src/views/lottery/utils/receipt.ts'), 'utf8'), {
  '../room/replyTemplates': replyTemplates
})
const source = readFileSync(resolve(__dirname, '../src/views/lottery/messages/MessageContent.vue'), 'utf8')
const roomSource = readFileSync(resolve(__dirname, '../src/views/lottery/room/index.vue'), 'utf8')
const cancelSource = readFileSync(resolve(__dirname, '../src/views/lottery/messages/CancelSuccessReceipt.vue'), 'utf8')
const { descriptor: cancelDescriptor } = parse(cancelSource)
const cancelComponent = load(compileScript(cancelDescriptor, { id: 'cancel-receipt', inlineTemplate: true }).content, {
  '../utils/receipt': receipt,
  '../room/replyTemplates': replyTemplates
}).default
const { descriptor } = parse(source)
const component = load(compileScript(descriptor, { id: 'audit-message', inlineTemplate: true }).content, {
  '../utils/receipt': receipt,
  './CancelSuccessReceipt.vue': cancelComponent
}).default
const render = (row) => renderToString(vue.h(component, { row }))

test('room and audit share receipt punctuation, amounts and action handling', () => {
  const text = '@会员\n[挂牌时间]035\n【户型审核成功】✓✓\n【编号】： 2\n【套内】：49\n【套外】：560\n【面积】：680\n\n点击退码'
  assert.equal(receipt.receiptText(text), '@会员\n【入住时间】035\n【户型审核成功】√√√\n【编号】2\n【室内】:49\n【室外】:560.00\n【面积】:680.00')
  assert.equal(receipt.receiptAction(text), 'cancelable')
  assert.equal(receipt.receiptAction(text + '\n已退码'), 'canceled')
})

test('current customer room wording is consistent for transitions and saved replies', () => {
  assert.equal(replyTemplates.roomReplyTemplates.issueTransition('OPEN'), '★★★★开始———答题★★★★')
  assert.equal(replyTemplates.roomReplyTemplates.issueTransition('CLOSED'), '★★★★停止———答题★★★★')
  assert.equal(replyTemplates.normalizeRoomReply('【状态】:房源已录入成功✓✓'),
    '【状态】：房源已提交成功√√√')
  assert.equal(receipt.receiptText('【户型审核成功】√√\n点击退码'), '【户型审核成功】√√√')
  assert.equal(receipt.receiptText('【户型审核成功】☑️☑️☑️\n点击退码'), '【户型审核成功】√√√')
  assert.equal(replyTemplates.normalizeRoomReply('【状态】：房源已提交成功✅✅✅'), '【状态】：房源已提交成功√√√')
  assert.equal(replyTemplates.normalizeRoomReply('[挂牌时间]035\n【套内】:49\n【套外】:980.00'),
    '【入住时间】035\n【室内】:49\n【室外】:980.00')
  assert.equal(receipt.receiptText('[挂牌时间]035\n【套内】：49\n【套外】：980.00\ntui碼成功'),
    '【入住时间】035\n【室内】：49\n【室外】：980.00')
})

test('robot cancel label is orange and readonly; player text is not an action', async () => {
  const html = await render({ kind: 'robot', content: '@会员\n【面积】:123.00\n\n点击退码' })
  assert.match(html, /class="message-content__cancel">点击退码<\/span>/)
  assert.doesNotMatch(html, /<button|<a\b/)
  assert.match(source, /color: #ffa500/)
  const player = await render({ kind: 'member', content: '点击退码' })
  assert.doesNotMatch(player, /class="message-content__cancel"/)
})

test('canceled receipt retains its body but never restores the cancel label', async () => {
  const html = await render({ kind: 'robot', content: '@会员\n【面积】:123.00\n点击退码\n已退码' })
  assert.match(html, /【面积】:123.00/)
  assert.match(html, /class="cancel-success-receipt__status">tui碼成功<\/div>/)
  assert.doesNotMatch(html, /点击退码/)
})

test('room and audit receipts use regular serif text with one blank line above their action', async () => {
  const { descriptor: roomDescriptor } = parse(roomSource)
  const roomStyle = roomDescriptor.styles.map((style) => style.content).join('\n')
  const auditStyle = descriptor.styles.map((style) => style.content).join('\n')
  const roomBody = roomStyle.match(/\.chat-bubble pre\.reference-receipt\s*\{([^}]+)\}/)[1]
  const roomAction = roomStyle.match(/\.cancel-link\s*\{([^}]+)\}/)[1]
  const auditBody = auditStyle.match(/\.message-content--receipt\s*\{([^}]+)\}/)[1]
  const auditAction = auditStyle.match(/\.message-content__cancel\s*\{([^}]+)\}/)[1]
  const fontFamily = "'Times New Roman', SimSun, '宋体', serif"
  assert.ok(roomBody.includes(`font-family: ${fontFamily};`))
  assert.match(roomBody, /font-weight: 400;/)
  // The preceding pre must not add its own bottom margin to the blank line.
  assert.match(roomBody, /margin-bottom: 0;/)
  assert.match(roomAction, /margin: 18px 6px 6px;/)
  assert.ok(roomAction.includes(`font: 400 16px/18px ${fontFamily};`))
  assert.ok(auditBody.includes(`font-family: ${fontFamily};`))
  assert.match(auditBody, /font-weight: 400;/)
  assert.match(auditAction, /margin-top: 20px;/)
  for (const action of ['点击退码', '已退码']) {
    const html = await render({ kind: 'robot', content: `@会员\n【面积】:123.00\n\n${action}` })
    assert.match(html, /class="message-content message-content--receipt"/)
  }
  const player = await render({ kind: 'member', content: '点击退码' })
  assert.doesNotMatch(player, /message-content--receipt/)
})

test('draw displays the unchanged saved image and escapes untrusted text', async () => {
  const drawImage = 'data:image/svg+xml;base64,PHN2Zy8+'
  const html = await render({ kind: 'robot', period: '20260928215', content: '215期开奖结果-9|9|7|9|6|龙', drawImage })
  assert.match(html, /215期开奖结果-9\|9\|7\|9\|6\|龙/)
  assert.ok(html.includes(`src="${drawImage}"`))
  const unsafe = await render({ kind: 'member', content: '<img src=x onerror=alert(1)>', drawImage: 'javascript:alert(1)' })
  assert.match(unsafe, /&lt;img/)
  assert.doesNotMatch(unsafe, /<img|javascript:/)
})
