const avatarAssets = import.meta.glob('/src/assets/lottery/player-avatars-chenguanxi/avatar_*.jpg', {
  eager: true,
  import: 'default',
  query: '?url'
}) as Record<string, string>

// Keep these indices aligned with LotteryMemberAvatar and the current customer's avatar migration.
const playerAvatars = Array.from({ length: 80 }, (_, index) => {
  const number = String(index + 1).padStart(2, '0')
  const source = avatarAssets[`/src/assets/lottery/player-avatars-chenguanxi/avatar_${number}.jpg`]
  if (!source) throw new Error(`缺少玩家头像 ${number}`)
  return source
})

export function lotteryPlayerAvatarSrc(avatar?: number | string | null) {
  const value = Number(avatar || 1)
  const index = Number.isFinite(value)
    ? Math.min(playerAvatars.length - 1, Math.max(0, Math.floor(value) - 1))
    : 0
  return playerAvatars[index]
}
