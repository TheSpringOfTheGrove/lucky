import avatar1039 from '@/assets/lottery/player-avatars/avatar_1039.jpg'
import avatar173 from '@/assets/lottery/player-avatars/avatar_173.jpg'
import avatar175 from '@/assets/lottery/player-avatars/avatar_175.jpg'
import avatar207 from '@/assets/lottery/player-avatars/avatar_207.jpg'
import avatar336 from '@/assets/lottery/player-avatars/avatar_336.jpg'
import avatar339 from '@/assets/lottery/player-avatars/avatar_339.jpg'
import avatar343 from '@/assets/lottery/player-avatars/avatar_343.jpg'
import avatar792 from '@/assets/lottery/player-avatars/avatar_792.jpg'
import avatar939 from '@/assets/lottery/player-avatars/avatar_939.jpg'

const playerAvatars = [
  avatar1039,
  avatar173,
  avatar175,
  avatar207,
  avatar336,
  avatar339,
  avatar343,
  avatar792,
  avatar939
]

const generatedAvatarAssets = import.meta.glob('/src/assets/lottery/player-avatars/avatar_*.png', {
  eager: true,
  import: 'default',
  query: '?url'
}) as Record<string, string>

for (let avatar = 10; avatar <= 35; avatar += 1) {
  const source = generatedAvatarAssets[`/src/assets/lottery/player-avatars/avatar_${avatar}.png`]
  if (source) playerAvatars.push(source)
}

export function lotteryPlayerAvatarSrc(avatar?: number | string | null) {
  const value = Number(avatar || 1)
  const index = Number.isFinite(value) ? Math.max(0, Math.floor(value) - 1) : 0
  return playerAvatars[index % playerAvatars.length]
}
