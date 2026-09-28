const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { resolve } = require('node:path')
const ts = require('typescript')

const sourceRoot = resolve(__dirname, '../src/components/Icon/src')
const moduleSource = ts.transpileModule(
  readFileSync(resolve(sourceRoot, 'legacyIcons.ts'), 'utf8'),
  {
    compilerOptions: { module: ts.ModuleKind.CommonJS }
  }
).outputText
const iconModule = { exports: {} }
new Function('module', 'exports', moduleSource)(iconModule, iconModule.exports)
const { legacyFontGlyphs, legacyIcons } = iconModule.exports

test('dashboard uses the original Ionicons 2 glyphs rather than modern lookalikes', () => {
  assert.equal(legacyFontGlyphs['ion:ios-people-outline'], '\uf47b')
  assert.equal(legacyFontGlyphs['ion:ios-gear-outline'], '\uf43c')
  assert.equal(legacyFontGlyphs['ion:card'], '\uf119')
  assert.equal(legacyFontGlyphs['ion:chatbubbles'], '\uf11f')
  assert.equal(legacyFontGlyphs['ion:power'], '\uf2a9')
})

test('member controls distinguish add-user, filled trash and edit glyphs', () => {
  assert.equal(legacyFontGlyphs['fa:user-plus'], '\uf234')
  assert.equal(legacyFontGlyphs['fa:trash'], '\uf1f8')
  assert.equal(legacyFontGlyphs['fa:edit'], '\uf044')
  assert.notEqual(legacyFontGlyphs['fa:trash'], legacyFontGlyphs['fa:trash-o'])
  assert.ok(legacyIcons['fa:trash'].body.includes('<path'))
})

test('both reference fonts ship locally in their original webfont formats', () => {
  assert.equal(
    readFileSync(resolve(sourceRoot, 'fonts/fontawesome-4.7.woff2')).subarray(0, 4).toString(),
    'wOF2'
  )
  assert.equal(
    readFileSync(resolve(sourceRoot, 'fonts/ionicons-2.0.1.woff')).subarray(0, 4).toString(),
    'wOFF'
  )
})

test('font rendering is restricted to admin and public-room SVG fallback remains available', () => {
  const component = readFileSync(resolve(sourceRoot, 'Icon.vue'), 'utf8')
  assert.match(component, /body\.lucky-admin-theme \.legacy-icon-glyph/)
  assert.match(component, /body\.lucky-admin-theme \.legacy-icon-fallback/)
  assert.match(component, /<IconifyIcon/)
  assert.match(component, /\.legacy-icon-glyph\s*\{\s*display: none/)
})
