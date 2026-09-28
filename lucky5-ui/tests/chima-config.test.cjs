const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { resolve } = require('node:path')
const ts = require('typescript')

const source = readFileSync(resolve(__dirname, '../src/views/lottery/utils/chimaConfig.ts'), 'utf8')
const code = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS }
}).outputText
const helper = { exports: {} }
new Function('module', 'exports', code)(helper, helper.exports)
const { quotaInputValue, chimaConfigPayload } = helper.exports

test('quota zero displays 0.0 without rounding nonzero values', () => {
  for (const zero of [0, '0', '0.00', null, undefined]) assert.equal(quotaInputValue(zero), '0.0')
  assert.equal(quotaInputValue(0.01), '0.01')
  assert.equal(quotaInputValue('123.456'), '123.456')
})

test('save payload retains numeric quotas and unchanged profit/loss limits', () => {
  assert.deepEqual(chimaConfigPayload({ siZiXian: '0.0', erDingWei: '0.01', yinKuiMax: 0, yinKuiMin: -10 }), {
    siZiXian: 0, erDingWei: 0.01, yinKuiMax: 0, yinKuiMin: -10
  })
})
