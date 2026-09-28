const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { resolve } = require('node:path')
const { runInNewContext } = require('node:vm')
const ts = require('typescript')

const source = readFileSync(
  resolve(__dirname, '../src/layout/components/Menu/src/Menu.vue'),
  'utf8'
)
const script = source.match(/<script lang="tsx">([\s\S]*?)<\/script>/)[1]
const ast = ts.createSourceFile('Menu.tsx', script, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX)
let handler
function visit(node) {
  if (ts.isVariableDeclaration(node) && node.name.getText(ast) === 'menuSelect') {
    handler = node.initializer.getText(ast)
  }
  ts.forEachChild(node, visit)
}
visit(ast)
assert.ok(handler, 'the tests must execute the actual menu selection handler')
const code = ts.transpileModule(`exports.select = ${handler}`, {
  compilerOptions: { target: ts.ScriptTarget.ES2020 }
}).outputText

function menu(mobile) {
  const events = []
  const context = {
    exports: {},
    appStore: { getMobile: mobile, setCollapse: (value) => events.push(['collapse', value]) },
    horizontalOverflowOpened: { value: true },
    props: {},
    isUrl: (path) => path.startsWith('https://'),
    window: { open: (path) => events.push(['open', path]) },
    permissionStore: { getRouters: [] },
    findRouteByPath: () => undefined,
    createRouteLocation: (path) => ({ path }),
    resolve: ({ path }) => ({ fullPath: path }),
    unref: (value) => value.value,
    currentRoute: { value: { fullPath: '/current' } },
    push: (location) => events.push(['navigate', location.path])
  }
  runInNewContext(code, context)
  return { select: context.exports.select, events, context }
}

test('mobile selection closes the menu and navigates normally', () => {
  const instance = menu(true)
  instance.select('/next')
  assert.deepEqual(instance.events, [
    ['collapse', true],
    ['navigate', '/next']
  ])
  assert.equal(instance.context.horizontalOverflowOpened.value, false)
})

test('selecting the current page still closes the mobile menu without navigating', () => {
  const instance = menu(true)
  instance.select('/current')
  assert.deepEqual(instance.events, [['collapse', true]])
})

test('desktop selection does not change sidebar collapse state', () => {
  const instance = menu(false)
  instance.select('/next')
  instance.select('/current')
  assert.deepEqual(instance.events, [['navigate', '/next']])
})

test('mobile external links also close the menu before opening', () => {
  const instance = menu(true)
  instance.select('https://example.com')
  assert.deepEqual(instance.events, [
    ['collapse', true],
    ['open', 'https://example.com']
  ])
})
