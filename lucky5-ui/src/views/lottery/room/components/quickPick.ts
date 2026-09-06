export const QUICK_PICK_POSITIONS = ['千', '百', '十', '个', '五'] as const

export type QuickPickPosition = (typeof QUICK_PICK_POSITIONS)[number]
export type QuickPickChoice = '' | '取' | '除'
export type QuickPickKind = 'fixed' | 'current' | 'fifth'

export interface QuickPickDefinition {
  label: string
  command: string
  kind: QuickPickKind
  count: number
  positionCount: number
}

export interface QuickPickSumRule {
  positions: QuickPickPosition[]
  digits: string
}

export interface QuickPickAttributeRule {
  action: QuickPickChoice
  positions: QuickPickPosition[]
}

export interface QuickPickForm {
  positions: Record<QuickPickPosition, string>
  pools: string[]
  positionAction: QuickPickChoice
  rotationAction: QuickPickChoice
  sumAction: QuickPickChoice
  sumRules: QuickPickSumRule[]
  twoSumEnabled: boolean
  threeSumEnabled: boolean
  unpositionedSum: string
  allTurn: string
  prize: string
  exclude: string
  multiplierPositions: QuickPickPosition[]
  includeAction: QuickPickChoice
  include: string
  complex: string
  rangeMin: string
  rangeMax: string
  repeats: Record<string, QuickPickChoice>
  brothers: Record<string, QuickPickChoice>
  oppositeAction: QuickPickChoice
  opposites: string[]
  attributes: Record<'单' | '双' | '大' | '小', QuickPickAttributeRule>
}

export const QUICK_PICK_DEFINITIONS: QuickPickDefinition[] = [
  { label: '二字定', command: '二定', kind: 'fixed', count: 2, positionCount: 4 },
  { label: '三字定', command: '三定', kind: 'fixed', count: 3, positionCount: 4 },
  { label: '四字定', command: '四定', kind: 'fixed', count: 4, positionCount: 4 },
  { label: '二字现', command: '二现', kind: 'current', count: 2, positionCount: 2 },
  { label: '三字现', command: '三现', kind: 'current', count: 3, positionCount: 3 },
  { label: '四字现', command: '四现', kind: 'current', count: 4, positionCount: 4 },
  { label: '五位二定', command: '五位二定', kind: 'fifth', count: 2, positionCount: 5 }
]

export const createQuickPickForm = (definition: QuickPickDefinition): QuickPickForm => ({
  positions: { 千: '', 百: '', 十: '', 个: '', 五: '' },
  pools: ['', '', '', ''],
  positionAction: definition.kind === 'current' ? '' : '取',
  rotationAction: definition.kind === 'current' ? '取' : '',
  sumAction: '取',
  sumRules: Array.from({ length: 4 }, () => ({ positions: [], digits: '' })),
  twoSumEnabled: false,
  threeSumEnabled: false,
  unpositionedSum: '',
  allTurn: '',
  prize: '',
  exclude: '',
  multiplierPositions: [],
  includeAction: '取',
  include: '',
  complex: '',
  rangeMin: '',
  rangeMax: '',
  repeats: { 双重: '', 双双重: '', 三重: '', 四重: '' },
  brothers: { 二兄弟: '', 三兄弟: '', 四兄弟: '' },
  oppositeAction: '',
  opposites: ['', '', ''],
  attributes: {
    单: { action: '', positions: [] },
    双: { action: '', positions: [] },
    大: { action: '', positions: [] },
    小: { action: '', positions: [] }
  }
})

export const quickPickPositions = (definition: QuickPickDefinition): QuickPickPosition[] =>
  QUICK_PICK_POSITIONS.slice(0, definition.positionCount)

export const quickPickRepeatOptions = (definition: QuickPickDefinition): string[] => {
  if (definition.kind === 'fifth' || definition.count === 2) return ['双重']
  if (definition.count === 3) return ['双重', '三重']
  if (definition.kind === 'current') return ['双重', '三重', '四重']
  return ['双重', '双双重', '三重', '四重']
}

export const quickPickBrotherOptions = (definition: QuickPickDefinition): string[] => {
  if (definition.kind === 'fifth' || definition.count === 2) return ['二兄弟']
  if (definition.count === 3) return ['二兄弟', '三兄弟']
  return ['二兄弟', '三兄弟', '四兄弟']
}

const uniqueDigits = (value: string) => [...new Set(value.replace(/\D/g, ''))].join('')
const rawDigits = (value: string) => value.replace(/\D/g, '')

const hasFilters = (form: QuickPickForm) =>
  form.sumRules.some((item) => item.positions.length >= 2 && uniqueDigits(item.digits)) ||
  ((form.twoSumEnabled || form.threeSumEnabled) && uniqueDigits(form.unpositionedSum)) ||
  uniqueDigits(form.prize) ||
  uniqueDigits(form.exclude) ||
  uniqueDigits(form.include) ||
  Object.values(form.repeats).some(Boolean) ||
  Object.values(form.brothers).some(Boolean) ||
  (form.oppositeAction && form.opposites.some((item) => uniqueDigits(item))) ||
  Object.values(form.attributes).some((item) => item.action && item.positions.length) ||
  form.multiplierPositions.length > 0 ||
  (form.rangeMin && form.rangeMax)

const buildBase = (definition: QuickPickDefinition, form: QuickPickForm) => {
  const complex = uniqueDigits(form.complex)
  const allTurn = rawDigits(form.allTurn)

  if (definition.kind === 'current') {
    const pools = form.pools.slice(0, definition.count).map(uniqueDigits)
    if (complex && pools.some(Boolean)) throw new Error('配数和复式不能同时填写')
    if (complex) return `${definition.command}复式${complex}`
    if (pools.some(Boolean)) {
      if (pools.some((item) => !item))
        throw new Error(`${definition.label}需要填写 ${definition.count} 组配数`)
      return `${form.rotationAction === '除' ? '除' : ''}${pools.join('配')}${definition.command}`
    }
    if (!hasFilters(form)) throw new Error('请填写配数或筛选条件')
    return definition.command
  }

  const positions = quickPickPositions(definition)
  const groups = positions
    .map((position) => ({ position, digits: uniqueDigits(form.positions[position]) }))
    .filter((item) => item.digits)
  const pools = form.pools.slice(0, definition.count).map(uniqueDigits)
  if (complex && (groups.length || pools.some(Boolean) || allTurn))
    throw new Error('定位、配数全转、全转和复式只能选择一种生成方式')
  if (complex) return `${definition.command}复式${complex}`
  if (form.rotationAction) {
    if (pools.some((item) => !item))
      throw new Error(`${definition.label}配数全转需要填写 ${definition.count} 组配数`)
    return `${form.rotationAction === '除' ? '除' : ''}${pools
      .map((item) => `配${item}`)
      .join('')}${definition.command}`
  }
  if (allTurn) {
    if (groups.length) throw new Error('定位号码和全转号码不能同时填写')
    const fixedCommand = definition.kind === 'fifth' ? '二定' : definition.command
    return `${allTurn}全倒${fixedCommand}`
  }
  if (groups.length) {
    if (!form.positionAction) throw new Error('请选择定位置的取或除')
    if (form.positionAction === '除') {
      return `${groups.map((item) => `除${item.position}${item.digits}`).join('')}${definition.command}`
    }
    return `${groups.map((item) => `${item.position}${item.digits}`).join('')}${definition.command}`
  }
  if (!hasFilters(form)) throw new Error('请填写定位号码、配数全转、全转、复式或筛选条件')
  return definition.command
}

const buildAttributeFilters = (definition: QuickPickDefinition, form: QuickPickForm) => {
  const positions = quickPickPositions(definition)
  const claimed = new Map<QuickPickPosition, string>()
  const filters: string[] = []
  for (const action of ['取', '除'] as const) {
    const pattern = Array.from({ length: definition.positionCount }, () => 'X')
    let used = false
    for (const attribute of ['单', '双', '大', '小'] as const) {
      const rule = form.attributes[attribute]
      if (rule.action !== action) continue
      for (const position of rule.positions) {
        const previous = claimed.get(position)
        if (previous && previous !== attribute)
          throw new Error(`${position}位不能同时选择${previous}和${attribute}`)
        claimed.set(position, attribute)
        const index = positions.indexOf(position)
        if (index >= 0) {
          pattern[index] = attribute
          used = true
        }
      }
    }
    if (used) filters.push(`${action}${pattern.join('')}`)
  }
  return filters.join('')
}

export const buildQuickPickExpression = (definition: QuickPickDefinition, form: QuickPickForm) => {
  let value = buildBase(definition, form)
  const positions = quickPickPositions(definition)

  for (const item of form.sumRules) {
    const selected = positions.filter((position) => item.positions.includes(position))
    const digits = uniqueDigits(item.digits)
    if (!digits && selected.length === 0) continue
    if (!digits || selected.length < 2) throw new Error('合分必须选择至少两个位置并填写合数')
    value += `${form.sumAction || '取'}${selected.join('')}合${digits}`
  }
  if (form.twoSumEnabled || form.threeSumEnabled) {
    const digits = uniqueDigits(form.unpositionedSum)
    if (!digits) throw new Error('请填写不定位胆合分')
    if (form.twoSumEnabled) value += `两数合${digits}`
    if (form.threeSumEnabled) value += `三数合${digits}`
  }
  if (form.prize) value += `上奖${uniqueDigits(form.prize)}`
  if (form.exclude) value += `排除${uniqueDigits(form.exclude)}`
  if (form.include) {
    value += `${form.includeAction === '除' ? '除' : ''}含${uniqueDigits(form.include)}`
  }
  if (definition.label === '四字定' && form.rangeMin && form.rangeMax) {
    value += `取值${Number(form.rangeMin)}值${Number(form.rangeMax)}`
  }
  for (const option of quickPickRepeatOptions(definition)) {
    const action = form.repeats[option]
    if (action) value += `${action}${option}`
  }
  for (const option of quickPickBrotherOptions(definition)) {
    const action = form.brothers[option]
    if (action) value += `${action}${option}`
  }
  if (form.oppositeAction) {
    for (const item of form.opposites) {
      const digits = uniqueDigits(item)
      if (digits) value += `${form.oppositeAction}对数${digits}`
    }
  }
  value += buildAttributeFilters(definition, form)
  if (form.multiplierPositions.length) {
    const selected = positions.filter((position) => form.multiplierPositions.includes(position))
    value += `取乘号位置${selected.join('')}`
  }
  return value
}

export const buildQuickPickCommand = (
  definition: QuickPickDefinition,
  form: QuickPickForm,
  amount: number
) => {
  if (!Number.isFinite(amount) || amount <= 0) throw new Error('请输入正确金额')
  return `${buildQuickPickExpression(definition, form)}各${Number(amount)}`
}
