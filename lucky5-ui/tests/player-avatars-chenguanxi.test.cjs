const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync, readdirSync, statSync } = require('node:fs')
const { resolve } = require('node:path')

const avatarDir = resolve(__dirname, '../src/assets/lottery/player-avatars-chenguanxi')
const accountAvatarDir = resolve(__dirname, '../public/chenguanxi/account-avatars')

test('current customer has eighty compact, numbered avatar assets', () => {
  const files = readdirSync(avatarDir).sort()
  const expected = Array.from({ length: 80 }, (_, index) =>
    `avatar_${String(index + 1).padStart(2, '0')}.jpg`
  )
  assert.deepEqual(files, expected)
  for (const file of files) {
    const path = resolve(avatarDir, file)
    assert.ok(statSync(path).size < 100_000, `${file} is too large for a room avatar`)
    const signature = readFileSync(path).subarray(0, 3)
    assert.deepEqual([...signature], [0xff, 0xd8, 0xff])
  }
})

test('account avatars include the dedicated admin portrait and the same eighty choices', () => {
  const files = readdirSync(accountAvatarDir).sort()
  const expected = [
    'admin-ceo.jpg',
    ...Array.from({ length: 80 }, (_, index) =>
      `avatar_${String(index + 1).padStart(2, '0')}.jpg`
    )
  ]
  assert.deepEqual(files, expected)
  for (const file of files.slice(1)) {
    assert.ok(readFileSync(resolve(accountAvatarDir, file)).equals(readFileSync(resolve(avatarDir, file))))
  }
  assert.ok(statSync(resolve(accountAvatarDir, 'admin-ceo.jpg')).size < 100_000)
})
