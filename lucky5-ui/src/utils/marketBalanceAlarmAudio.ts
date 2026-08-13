type AlarmAudioWindow = typeof window & {
  webkitAudioContext?: typeof AudioContext
  __lucky5MarketAlarmAudioContext?: AudioContext
}

let activeOscillators: OscillatorNode[] = []
let soundTimer: ReturnType<typeof setTimeout> | undefined

const getAudioContext = () => {
  const alarmWindow = window as AlarmAudioWindow
  const AudioContextClass = window.AudioContext || alarmWindow.webkitAudioContext
  if (!AudioContextClass) return undefined
  alarmWindow.__lucky5MarketAlarmAudioContext ||= new AudioContextClass()
  return alarmWindow.__lucky5MarketAlarmAudioContext
}

export const stopMarketBalanceAlarmAudio = () => {
  if (soundTimer) {
    clearTimeout(soundTimer)
    soundTimer = undefined
  }
  activeOscillators.forEach((oscillator) => {
    try {
      oscillator.stop()
    } catch {
      // 已按计划停止的节点无需重复处理。
    }
  })
  activeOscillators = []
}

export const unlockMarketBalanceAlarmAudio = async () => {
  const audioContext = getAudioContext()
  if (!audioContext) return false
  try {
    if (audioContext.state === 'suspended') await audioContext.resume()
    return audioContext.state === 'running'
  } catch {
    return false
  }
}

export const playMarketBalanceAlarmAudio = async () => {
  const unlocked = await unlockMarketBalanceAlarmAudio()
  const audioContext = getAudioContext()
  if (!unlocked || !audioContext) throw new Error('Audio playback is blocked')

  stopMarketBalanceAlarmAudio()
  const startAt = audioContext.currentTime + 0.02
  const duration = 0.24
  const interval = 0.36
  for (let index = 0; index < 7; index += 1) {
    const oscillator = audioContext.createOscillator()
    const gain = audioContext.createGain()
    const beepStart = startAt + index * interval
    const beepEnd = beepStart + duration
    oscillator.type = 'square'
    oscillator.frequency.setValueAtTime(index % 2 === 0 ? 880 : 660, beepStart)
    gain.gain.setValueAtTime(0, beepStart)
    gain.gain.linearRampToValueAtTime(0.16, beepStart + 0.02)
    gain.gain.setValueAtTime(0.16, beepEnd - 0.04)
    gain.gain.linearRampToValueAtTime(0, beepEnd)
    oscillator.connect(gain)
    gain.connect(audioContext.destination)
    oscillator.start(beepStart)
    oscillator.stop(beepEnd)
    activeOscillators.push(oscillator)
  }
  soundTimer = setTimeout(() => {
    activeOscillators = []
    soundTimer = undefined
  }, 3_000)
}
