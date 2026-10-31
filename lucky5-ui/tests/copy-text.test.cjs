const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { resolve } = require('node:path')
const ts = require('typescript')

const source = readFileSync(resolve(__dirname, '../src/views/lottery/utils/copyText.ts'), 'utf8')
const output = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText
const moduleRef = { exports: {} }
new Function('module', 'exports', output)(moduleRef, moduleRef.exports)
const { copyText } = moduleRef.exports

const mockDocument = (copied) => {
  const input = {
    value: '', style: {}, focus() {}, select() {}, setSelectionRange() {},
    remove() { this.removed = true }
  }
  return {
    input,
    document: {
      createElement: (tag) => {
        assert.equal(tag, 'textarea')
        return input
      },
      body: { appendChild: (element) => assert.equal(element, input) },
      execCommand: (command) => {
        assert.equal(command, 'copy')
        return copied
      }
    }
  }
}

test('secure clipboard copies without creating a fallback input', async () => {
  let copied = ''
  globalThis.navigator = { clipboard: { writeText: async (value) => { copied = value } } }
  globalThis.document = { createElement: () => { throw new Error('unexpected fallback') } }
  assert.equal(await copyText('http://43.135.38.59/g/test'), true)
  assert.equal(copied, 'http://43.135.38.59/g/test')
})

test('HTTP IP page uses selection fallback and reports its result', async () => {
  const mocked = mockDocument(true)
  mocked.document.activeElement = {
    closest: (selector) => {
      assert.equal(selector, '.el-dialog')
      return mocked.document.body
    },
    focus: () => { mocked.focusRestored = true }
  }
  globalThis.navigator = { clipboard: undefined }
  globalThis.document = mocked.document
  assert.equal(await copyText('http://43.135.38.59/g/test'), true)
  assert.equal(mocked.input.value, 'http://43.135.38.59/g/test')
  assert.equal(mocked.input.removed, true)
  assert.equal(mocked.focusRestored, true)
})

test('rejected clipboard falls back and a failed fallback is reported', async () => {
  const mocked = mockDocument(false)
  globalThis.navigator = { clipboard: { writeText: async () => { throw new Error('denied') } } }
  globalThis.document = mocked.document
  assert.equal(await copyText('link'), false)
  assert.equal(mocked.input.removed, true)
})
