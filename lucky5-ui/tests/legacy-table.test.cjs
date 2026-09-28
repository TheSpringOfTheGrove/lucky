const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { resolve } = require('node:path')
const ts = require('typescript')
const vue = require('vue')
const { renderToString } = require('vue/server-renderer')

function loadSource(file, dependencies = {}) {
  const code = ts.transpileModule(readFileSync(resolve(__dirname, '../src', file), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  const module = { exports: {} }
  new Function('require', 'module', 'exports', code)(
    (id) => dependencies[id] || require(id), module, module.exports
  )
  return module.exports
}

const columns = loadSource('components/LegacyTable/columns.ts')
const Column = { name: 'ElTableColumn' }
let width = 375
const LegacyTable = loadSource('components/LegacyTable/index.ts', {
  './columns': columns,
  '@vueuse/core': { useMediaQuery: () => vue.ref(width <= 767) },
  'element-plus': { ElTable: { name: 'ElTable', render: () => vue.h('div', 'desktop-table') } }
}).default
const render = (data, attrs = {}, definitions = [
  vue.h(Column, { prop: 'name', label: '昵称' }),
  vue.h(Column, { prop: 'balance', label: '积分' })
]) => renderToString(vue.h(LegacyTable, { data, ...attrs }, { default: () => definitions }))

test('collects nested fragments and preserves scoped slots, ignoring comments', () => {
  const column = vue.h(Column, { label: '操作' }, { default: () => vue.h('button', '编辑') })
  assert.deepEqual(columns.collectLegacyColumns([
    null, false, vue.createCommentVNode('conditional'), vue.h(vue.Fragment, [column])
  ]), [column])
})

test('nested fields retain zero/false and tolerate missing values', () => {
  assert.equal(columns.legacyField({ child: { value: 0 } }, 'child.value'), 0)
  assert.equal(columns.legacyField({ enabled: false }, 'enabled'), false)
  assert.equal(columns.legacyField({}, 'child.value'), undefined)
})

test('mobile uses matching real header/footer and escapes long content', async () => {
  width = 375
  const html = await render([{ id: 0, name: '<script>长昵称</script>', balance: -123456.78 }], {
    'row-key': 'id', 'show-summary': ''
  })
  assert.match(html, /<thead>/)
  assert.match(html, /<tfoot>/)
  assert.equal((html.match(/scope="col"/g) || []).length, 4)
  assert.match(html, /&lt;script&gt;长昵称&lt;\/script&gt;/)
  assert.match(html, /-123456.78/)
})

test('empty table keeps all footer labels and one colspan empty row', async () => {
  const html = await render([], { showSummary: true })
  assert.match(html, /colspan="2"/)
  assert.match(html, /No data available in table/)
  assert.match(html, /<tfoot>/)
})

test('blank action heading stays blank in header/footer while the action remains visible', async () => {
  width = 375
  const html = await render([{ id: 'order-1', name: '订单内容' }], { showSummary: true }, [
    vue.h(Column, { prop: 'name', label: '文本', align: 'left' }),
    vue.h(Column, { label: '' }, { default: () => vue.h('button', { class: 'order-cancel-link' }, '退') })
  ])
  assert.match(html, /<thead><tr><th scope="col">文本<\/th><th scope="col"><\/th>/)
  assert.match(html, /<tfoot><tr><th scope="col">文本<\/th><th scope="col"><\/th>/)
  assert.match(html, /<button class="order-cancel-link">退<\/button>/)
})

test('explicitly false summary does not create a footer', async () => {
  assert.doesNotMatch(await render([], { 'show-summary': false }), /<tfoot>/)
})

test('index, formatter, action slots and initial member expansion are preserved', async () => {
  const html = await render([{ id: 9, name: '会员' }], {
    'row-key': 'id', 'expand-row-keys': [9]
  }, [
    vue.h(Column, { type: 'expand' }, { default: ({ row }) => vue.h('button', `编辑${row.id}`) }),
    vue.h(Column, { type: 'index', label: '序号' }),
    vue.h(Column, { prop: 'name', label: '昵称', formatter: (row) => `【${row.name}】` })
  ])
  assert.match(html, /aria-expanded="true"/)
  assert.match(html, /colspan="2"/)
  assert.match(html, /编辑9/)
  assert.match(html, /【会员】/)
  assert.match(html, /__cell">1<\/div>/)
})

test('767px remains a native table and 768px restores Element table', async () => {
  width = 767
  assert.match(await render([]), /legacy-mobile-table/)
  width = 768
  assert.match(await render([]), /desktop-table/)
  width = 375
})
