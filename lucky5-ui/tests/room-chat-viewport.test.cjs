const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { resolve } = require('node:path')
const { runInNewContext } = require('node:vm')
const ts = require('typescript')
const vue = require('vue')

const source = readFileSync(
  resolve(__dirname, '../src/views/lottery/room/useRoomChatViewport.ts'),
  'utf8'
)
const script = ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
}).outputText

class EventTarget {
  listeners = new Map()
  addEventListener(type, callback) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set())
    this.listeners.get(type).add(callback)
  }
  removeEventListener(type, callback) {
    this.listeners.get(type)?.delete(callback)
  }
  emit(type, event = {}) {
    for (const callback of this.listeners.get(type) || []) callback(event)
  }
}

function fixture(t, withVisualViewport = true) {
  const frames = new Map()
  const observers = []
  const mounted = []
  const unmounted = []
  let frameId = 0
  const window = Object.assign(new EventTarget(), {
    innerHeight: 760,
    visualViewport: withVisualViewport
      ? Object.assign(new EventTarget(), { height: 760, offsetTop: 0 })
      : undefined,
    requestAnimationFrame(callback) {
      frames.set(++frameId, callback)
      return frameId
    },
    cancelAnimationFrame(id) { frames.delete(id) }
  })
  const exports = {}
  runInNewContext(script, {
    exports,
    require: () => ({
      ...vue,
      onMounted(callback) { mounted.push(callback) },
      onBeforeUnmount(callback) { unmounted.push(callback) }
    }),
    window,
    ResizeObserver: class {
      disconnected = false
      constructor(callback) { this.callback = callback; observers.push(this) }
      observe() {}
      disconnect() { this.disconnected = true }
    }
  })
  const stream = Object.assign(new EventTarget(), {
    scrollTop: 0,
    scrollHeight: 2400,
    clientHeight: 704
  })
  const chat = vue.shallowRef()
  const content = vue.shallowRef()
  const composer = vue.shallowRef()
  const autoFollow = vue.ref(true)
  const scope = vue.effectScope()
  const controller = scope.run(() =>
    exports.useRoomChatViewport(chat, content, composer, autoFollow, () => {})
  )
  mounted.forEach(callback => callback())
  // The room only renders these refs after its initial session request completes.
  chat.value = stream
  content.value = {}
  composer.value = { getBoundingClientRect: () => ({ height: 56 }) }
  const resize = () => {
    for (const observer of observers) {
      if (!observer.disconnected) observer.callback()
    }
  }
  const flush = async () => {
    await vue.nextTick()
    for (let pass = 0; frames.size; pass++) {
      assert.ok(pass < 10, 'layout following must settle without a perpetual animation loop')
      const callbacks = [...frames.values()]
      frames.clear()
      callbacks.forEach(callback => callback())
      await vue.nextTick()
    }
  }
  const dispose = () => { unmounted.forEach(callback => callback()); scope.stop() }
  t.after(dispose)
  return { controller, autoFollow, stream, window, frames, observers, resize, flush, dispose }
}

test('follows replies and images that grow after the optimistic message was rendered', async t => {
  const f = fixture(t)
  await f.flush()
  assert.equal(f.stream.scrollTop, 1696)
  await f.controller.scrollToBottom()
  f.stream.scrollHeight += 800
  // A scroll event between DOM growth and ResizeObserver must not disable following.
  f.controller.handleScroll()
  assert.equal(f.autoFollow.value, true)
  f.resize()
  await f.flush()
  assert.equal(f.stream.scrollTop, 2496)
  f.stream.scrollHeight += 400
  f.resize()
  await f.flush()
  assert.equal(f.stream.scrollTop, 2896)
})

test('keeps the viewport and bottom aligned through keyboard resize, pan and dismissal', async t => {
  const f = fixture(t)
  await f.flush()
  f.window.visualViewport.height = 350
  f.window.visualViewport.offsetTop = 120
  f.window.visualViewport.emit('resize')
  f.stream.clientHeight = 294
  f.controller.handleScroll()
  f.resize()
  await f.flush()
  assert.equal(f.controller.viewportStyle.value.height, '350px')
  assert.equal(f.controller.viewportStyle.value.top, '120px')
  assert.equal(f.stream.scrollTop, 2106)
  f.window.visualViewport.offsetTop = 80
  f.window.visualViewport.emit('scroll')
  assert.equal(f.controller.viewportStyle.value.top, '80px')
  f.window.visualViewport.height = 760
  f.window.visualViewport.offsetTop = 0
  f.window.visualViewport.emit('resize')
  f.stream.clientHeight = 704
  f.stream.scrollTop = 1696
  f.controller.handleScroll()
  await f.flush()
  assert.equal(f.autoFollow.value, true)
  assert.equal(f.controller.viewportStyle.value.height, '760px')
  assert.equal(f.stream.scrollTop, 1696)
})

test('reading history cancels queued following and survives later replies and keyboard changes', async t => {
  const f = fixture(t)
  await f.flush()
  await f.controller.scrollToBottom()
  f.stream.emit('touchstart', { touches: [{ clientY: 200 }] })
  f.stream.emit('touchmove', { touches: [{ clientY: 340 }] })
  f.stream.scrollTop = 1200
  f.controller.handleScroll()
  f.stream.scrollHeight += 800
  f.window.visualViewport.height = 400
  f.window.visualViewport.emit('resize')
  f.resize()
  await f.flush()
  assert.equal(f.autoFollow.value, false)
  assert.equal(f.stream.scrollTop, 1200)
  // Sending or explicitly returning to the latest message opts back in.
  await f.controller.scrollToBottom()
  await f.flush()
  assert.equal(f.autoFollow.value, true)
  assert.equal(f.stream.scrollTop, 2496)
})

test('wheel and scrollbar navigation pause following and reaching the bottom resumes it', async t => {
  const f = fixture(t)
  await f.flush()
  f.stream.emit('wheel', { deltaY: -50 })
  f.stream.scrollTop -= 50
  f.controller.handleScroll()
  f.resize()
  await f.flush()
  assert.equal(f.autoFollow.value, false)
  assert.equal(f.stream.scrollTop, 1646)
  f.stream.scrollTop = 1696
  f.controller.handleScroll()
  await f.flush()
  assert.equal(f.autoFollow.value, true)
  f.stream.scrollTop = 1000
  f.controller.handleScroll()
  assert.equal(f.autoFollow.value, false)
})

test('falls back to window resizing without VisualViewport and cleans up scheduled work', async t => {
  const f = fixture(t, false)
  await f.flush()
  f.window.innerHeight = 600
  f.window.emit('resize')
  assert.equal(f.controller.viewportStyle.value.height, '600px')
  assert.equal(f.controller.viewportStyle.value.top, '0px')
  assert.ok(f.frames.size > 0)
  f.dispose()
  assert.equal(f.frames.size, 0)
  assert.equal(f.window.listeners.get('resize').size, 0)
  assert.equal(f.stream.listeners.get('touchmove').size, 0)
  assert.ok(f.observers.every(observer => observer.disconnected))
})
