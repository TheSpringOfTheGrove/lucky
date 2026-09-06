<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { previewRoomBetApi, type RoomCredential } from '@/api/lottery/room'
import QuickPickChoice from './QuickPickChoice.vue'
import {
  buildQuickPickExpression,
  createQuickPickForm,
  QUICK_PICK_DEFINITIONS,
  quickPickBrotherOptions,
  quickPickPositions,
  quickPickRepeatOptions,
  type QuickPickChoice as Choice,
  type QuickPickPosition
} from './quickPick'

const props = defineProps<{
  visible: boolean
  period: string
  balance: number
  credential: RoomCredential
}>()

const emit = defineEmits<{
  close: []
  submit: [content: string]
}>()

const activePlay = ref(QUICK_PICK_DEFINITIONS[0].label)
const definition = computed(
  () => QUICK_PICK_DEFINITIONS.find((item) => item.label === activePlay.value)!
)
const form = reactive(createQuickPickForm(definition.value))
const amount = ref<number | null>(null)
const generated = ref('')
const textSource = ref('')
const generatedNumbers = ref<string[]>([])
const noteCount = ref(0)
const previewTruncated = ref(false)
const previewing = ref(false)
const sourceMode = ref<'form' | 'text'>('form')

interface TextCommandDraft {
  identity: string
  previewContent: string
  amount?: number
  submit: (amount: number) => string
}

const positions = computed(() => quickPickPositions(definition.value))
const repeats = computed(() => quickPickRepeatOptions(definition.value))
const brothers = computed(() => quickPickBrotherOptions(definition.value))
const attributePairs = [
  ['单', '双'],
  ['大', '小']
] as const
const isFixed = computed(() => definition.value.kind !== 'current')
const isFifth = computed(() => definition.value.kind === 'fifth')
const showThreeSum = computed(() => definition.value.count >= 3)
const positionLabel = (position: QuickPickPosition) =>
  ({ 千: '仟', 百: '佰', 十: '拾', 个: '个', 五: '五' })[position]
const total = computed(() =>
  Number((noteCount.value * Math.max(0, Number(amount.value || 0))).toFixed(2))
)

const clearResult = () => {
  generated.value = ''
  generatedNumbers.value = []
  noteCount.value = 0
  previewTruncated.value = false
}

const reset = () => {
  Object.assign(form, createQuickPickForm(definition.value))
  amount.value = null
  textSource.value = ''
  sourceMode.value = 'form'
  clearResult()
}

const setBaseChoice = (field: 'positionAction' | 'rotationAction', value: Choice) => {
  form[field] = value
  if (!value) return
  if (field === 'positionAction') form.rotationAction = ''
  else form.positionAction = ''
}

const togglePosition = (items: QuickPickPosition[], position: QuickPickPosition) => {
  const index = items.indexOf(position)
  if (index >= 0) items.splice(index, 1)
  else items.push(position)
}

const preview = async (expression: string, requestContent = `${expression}各1`) => {
  previewing.value = true
  try {
    const result = await previewRoomBetApi(props.credential, requestContent)
    generated.value = expression
    generatedNumbers.value = result.selections || []
    noteCount.value = Number(result.count || 0)
    previewTruncated.value = Boolean(result.selectionsTruncated)
    return true
  } catch (reason: any) {
    clearResult()
    ElMessage.error(reason?.message || '无法生成对应号码组')
    return false
  } finally {
    previewing.value = false
  }
}

const composeExpression = () => buildQuickPickExpression(definition.value, form)

const parseTextCommand = (content: string): TextCommandDraft => {
  const eachMatch = /^(.*)各(\d+(?:\.\d+)?)$/.exec(content)
  if (eachMatch) {
    const expression = eachMatch[1].trim()
    return {
      identity: expression,
      previewContent: content,
      amount: Number(eachMatch[2]),
      submit: (value) => `${expression}各${value}`
    }
  }
  const inlineMatch = /^(.*)([=/:])(\d+(?:\.\d+)?)$/.exec(content)
  if (inlineMatch) {
    const prefix = inlineMatch[1].trim()
    const separator = inlineMatch[2]
    return {
      identity: `${prefix}${separator}`,
      previewContent: content,
      amount: Number(inlineMatch[3]),
      submit: (value) => `${prefix}${separator}${value}`
    }
  }
  return {
    identity: content,
    previewContent: `${content}各1`,
    submit: (value) => `${content}各${value}`
  }
}

const build = async () => {
  try {
    const expression = composeExpression()
    sourceMode.value = 'form'
    textSource.value = expression
    await preview(expression)
  } catch (reason: any) {
    ElMessage.warning(reason?.message || '请完整填写生成条件')
  }
}

const buildFromText = async () => {
  const content = textSource.value.trim()
  if (!content) return ElMessage.warning('请输入要生成的下注文字')
  const draft = parseTextCommand(content)
  if (draft.amount) amount.value = draft.amount
  sourceMode.value = 'text'
  await preview(draft.identity, draft.previewContent)
}

const submitGenerated = async () => {
  let expression = ''
  let contentFactory = (value: number) => `${expression}各${value}`
  try {
    if (sourceMode.value === 'text') {
      const text = textSource.value.trim()
      if (!text) throw new Error('请输入要生成的下注文字')
      const draft = parseTextCommand(text)
      expression = draft.identity
      contentFactory = draft.submit
    } else {
      expression = composeExpression()
      contentFactory = (value) => `${expression}各${value}`
    }
  } catch (reason: any) {
    return ElMessage.warning(reason?.message || '请完整填写生成条件')
  }
  const numericAmount = Number(amount.value || 0)
  if (!Number.isFinite(numericAmount) || numericAmount <= 0) {
    return ElMessage.warning('请输入正确金额')
  }
  const content = contentFactory(numericAmount)
  if (expression !== generated.value && !(await preview(expression, content))) return
  emit('submit', content)
}

const useTextSource = () => {
  sourceMode.value = 'text'
  clearResult()
}

watch(activePlay, () => reset())
watch(
  () => props.visible,
  (visible) => {
    if (visible) clearResult()
  }
)
</script>

<template>
  <div v-if="visible" class="quick-picker-mask" @click.self="emit('close')">
    <section class="quick-picker">
      <header>
        <strong>幸运五</strong>
        <span>{{ period }}期　可用余额:{{ Number(balance).toFixed(2) }}</span>
        <button type="button" aria-label="关闭" @click="emit('close')">×</button>
      </header>

      <div class="play-tabs">
        <button
          v-for="play in QUICK_PICK_DEFINITIONS"
          :key="play.label"
          type="button"
          :class="{ active: activePlay === play.label }"
          @click="activePlay = play.label"
        >
          {{ play.label }}
        </button>
        <span
          v-for="index in (3 - (QUICK_PICK_DEFINITIONS.length % 3)) % 3"
          :key="`empty-play-${index}`"
          class="play-tab-placeholder"
          aria-hidden="true"
        ></span>
      </div>

      <template v-if="isFixed">
        <div class="rule-row split base-actions">
          <label>
            <b>定位置</b>
            <QuickPickChoice
              :model-value="form.positionAction"
              @update:model-value="setBaseChoice('positionAction', $event)"
            />
          </label>
          <label>
            <b>配数全转</b>
            <QuickPickChoice
              :model-value="form.rotationAction"
              @update:model-value="setBaseChoice('rotationAction', $event)"
            />
          </label>
        </div>

        <div v-if="form.positionAction" class="position-grid" :class="{ fifth: isFifth }">
          <template v-for="position in positions" :key="position">
            <label>{{ positionLabel(position) }}</label>
            <input v-model="form.positions[position]" inputmode="numeric" maxlength="20" />
          </template>
        </div>

        <div v-else-if="form.rotationAction" class="pool-grid">
          <template v-for="poolIndex in definition.count" :key="poolIndex">
            <input v-model="form.pools[poolIndex - 1]" inputmode="numeric" maxlength="20" />
            <span v-if="poolIndex < definition.count"
              >配<span v-if="poolIndex < definition.count - 1">，</span></span
            >
          </template>
        </div>

        <div class="rule-row sum-title">
          <b>合　分</b>
          <QuickPickChoice v-model="form.sumAction" />
        </div>
        <div class="sum-rules">
          <div v-for="(rule, ruleIndex) in form.sumRules" :key="ruleIndex" class="sum-rule">
            <span>{{ ruleIndex + 1 }}.</span>
            <template v-for="(position, positionIndex) in positions" :key="position">
              <span v-if="isFifth && positionIndex < 4 && positionIndex !== ruleIndex">X</span>
              <input
                v-else
                type="checkbox"
                :checked="rule.positions.includes(position)"
                :aria-label="`第${ruleIndex + 1}组合${position}位`"
                @change="togglePosition(rule.positions, position)"
              />
            </template>
            <input v-model="rule.digits" class="sum-value" inputmode="numeric" maxlength="10" />
          </div>
        </div>
      </template>

      <template v-else>
        <div class="rule-row current-action">
          <b>配数</b>
          <QuickPickChoice
            :model-value="form.rotationAction"
            @update:model-value="setBaseChoice('rotationAction', $event)"
          />
        </div>
        <div class="pool-grid">
          <template v-for="poolIndex in definition.count" :key="poolIndex">
            <input v-model="form.pools[poolIndex - 1]" inputmode="numeric" maxlength="20" />
            <span v-if="poolIndex < definition.count"
              >配<span v-if="poolIndex < definition.count - 1">，</span></span
            >
          </template>
        </div>
      </template>

      <div class="rule-row unpositioned-sum">
        <b>不定位胆分</b>
        <label><input v-model="form.twoSumEnabled" type="checkbox" />两数合</label>
        <label v-if="showThreeSum"
          ><input v-model="form.threeSumEnabled" type="checkbox" />三数合</label
        >
        <input v-model="form.unpositionedSum" inputmode="numeric" aria-label="不定位胆合分" />
      </div>

      <div v-if="activePlay === '四字定'" class="rule-row range-row">
        <b>值 范 围</b>
        <span>从</span><input v-model="form.rangeMin" inputmode="numeric" /> <span>值 至</span
        ><input v-model="form.rangeMax" inputmode="numeric" /><span>值</span>
      </div>

      <div v-if="isFixed" class="rule-row fixed-tools">
        <label><b>全转</b><input v-model="form.allTurn" inputmode="numeric" /></label>
        <label><b>上奖</b><input v-model="form.prize" inputmode="numeric" /></label>
        <label><b>排除</b><input v-model="form.exclude" inputmode="numeric" /></label>
        <label class="multiplier-label">
          <b>乘号位置</b>
          <span v-for="position in positions" :key="position" class="position-choice">
            <input
              type="checkbox"
              :checked="form.multiplierPositions.includes(position)"
              :aria-label="`乘号${position}位`"
              @change="togglePosition(form.multiplierPositions, position)"
            />
          </span>
        </label>
      </div>

      <div class="rule-row include-row">
        <QuickPickChoice v-model="form.includeAction" />
        <b>{{ definition.label }}含</b>
        <input v-model="form.include" inputmode="numeric" />
        <label
          ><b>{{ definition.label }}复式</b><input v-model="form.complex" inputmode="numeric"
        /></label>
      </div>

      <div class="rule-row shape-row">
        <label v-for="option in repeats" :key="option">
          <QuickPickChoice v-model="form.repeats[option]" />
          <b>（{{ option }}）</b>
        </label>
      </div>

      <div class="rule-row shape-row">
        <label v-for="option in brothers" :key="option">
          <QuickPickChoice v-model="form.brothers[option]" />
          <b>（{{ option }}）</b>
        </label>
      </div>

      <div class="rule-row opposite-row">
        <QuickPickChoice v-model="form.oppositeAction" />
        <b>（对数）</b>
        <input
          v-for="(_, index) in form.opposites"
          :key="index"
          v-model="form.opposites[index]"
          inputmode="numeric"
          maxlength="4"
        />
      </div>

      <div
        v-for="pair in attributePairs"
        :key="pair[0]"
        class="rule-row attribute-row"
        :class="{ 'attribute-row-fifth': isFifth }"
      >
        <label v-for="attribute in pair" :key="attribute">
          <QuickPickChoice v-model="form.attributes[attribute].action" />
          <b>（{{ attribute }}）</b>
          <span v-for="position in positions" :key="position" class="position-choice">
            <input
              type="checkbox"
              :checked="form.attributes[attribute].positions.includes(position)"
              :aria-label="`${attribute}${position}位`"
              @change="togglePosition(form.attributes[attribute].positions, position)"
            />
          </span>
        </label>
      </div>

      <textarea
        v-model="textSource"
        rows="2"
        placeholder="输入要生成的文字"
        @input="useTextSource"
      ></textarea>
      <div class="actions">
        <button type="button" :disabled="previewing" @click="build">生成</button>
        <button type="button" @click="reset">复位</button>
        <button type="button" :disabled="previewing" @click="buildFromText">根据文字生成</button>
      </div>

      <p v-if="generated" class="generated-expression">生成的文字为：{{ generated }}</p>

      <label class="box-title">生成号码框</label>
      <div class="number-grid">
        <span v-for="(number, index) in generatedNumbers" :key="`${number}-${index}`">
          {{ number.replaceAll('X', '×') }}
        </span>
        <div v-if="!generatedNumbers.length" class="number-empty">
          {{ previewing ? '正在生成…' : '' }}
        </div>
      </div>
      <p v-if="previewTruncated" class="preview-tip">号码较多，仅展示前 500 笔</p>

      <label class="box-title">发送框</label>
      <div class="send-box">
        <div class="amount-cell">
          <label>金额 <input v-model.number="amount" type="number" min="0.1" step="0.1" /></label>
          <button type="button" :disabled="previewing" @click="submitGenerated">下注</button>
        </div>
        <div class="summary-cell">
          <span>笔数: {{ noteCount }}</span>
          <span>金额：{{ total }}元</span>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.quick-picker-mask {
  position: fixed;
  z-index: 50;
  display: flex;
  overflow: auto;
  background: rgb(0 0 0 / 75%);
  align-items: flex-start;
  justify-content: center;
  inset: 0;
}

.quick-picker {
  width: min(375px, 100vw);
  min-height: 100dvh;
  font-family: Arial, 'Microsoft YaHei', sans-serif;
  font-size: 14px;
  color: #111;
  background: #f5f5f7;
  border: 3px solid #444;
  box-sizing: border-box;
  color-scheme: light;
}

.quick-picker input,
.quick-picker textarea {
  color: #111 !important;
  background: #fff !important;
  border: 1px solid #888;
  border-radius: 0;
  box-sizing: border-box;
  -webkit-text-fill-color: #111 !important;
}

.quick-picker input::placeholder,
.quick-picker textarea::placeholder {
  color: #777 !important;
  opacity: 1;
  -webkit-text-fill-color: #777 !important;
}

.quick-picker input:not([type='checkbox']) {
  height: 20px;
  min-width: 0;
}

.quick-picker input[type='checkbox'] {
  width: 14px;
  height: 14px;
  margin: 0;
  accent-color: #168eea;
}

.quick-picker header {
  position: relative;
  padding: 8px 34px 7px;
  color: #fff;
  text-align: center;
  background: #ff5426;
}

.quick-picker header strong {
  display: block;
  font-size: 20px;
  line-height: 22px;
  color: #fff500;
}

.quick-picker header span {
  display: block;
  font-weight: 700;
  line-height: 18px;
}

.quick-picker header button {
  position: absolute;
  top: 14px;
  right: 13px;
  width: 22px;
  height: 22px;
  padding: 0;
  font-size: 19px;
  line-height: 16px;
  color: #ffe600;
  background: transparent;
  border: 2px solid #ffe600;
  border-radius: 50%;
}

.play-tabs {
  display: grid;
  margin: 40px 6px 0;
  border-top: 1px solid #efc8b8;
  border-left: 1px solid #efc8b8;
  grid-template-columns: repeat(3, 1fr);
}

.play-tabs button,
.play-tab-placeholder {
  height: 40px;
  border: 0;
  border-right: 1px solid #efc8b8;
  border-bottom: 1px solid #efc8b8;
  box-sizing: border-box;
}

.play-tabs button {
  padding: 0;
  font-size: 14px;
  color: #111;
  background: #f5f5f5;
  outline: 0;
}

.play-tabs button.active {
  font-weight: 700;
  color: #fff;
  background: #ff9200;
}

.rule-row,
.position-grid,
.pool-grid,
.sum-rules {
  margin-right: 6px;
  margin-left: 6px;
  border-right: 1px solid #efc8b8;
  border-bottom: 1px solid #efc8b8;
  border-left: 1px solid #efc8b8;
}

.rule-row {
  display: flex;
  min-height: 44px;
  padding: 7px 8px;
  box-sizing: border-box;
  align-items: center;
  gap: 5px;
  flex-wrap: wrap;
}

.rule-row b {
  font-size: 16px;
  color: #b8003a;
}

.rule-row label,
.base-actions label,
.attribute-row label {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.split {
  display: grid;
  grid-template-columns: 1fr 1fr;
  padding: 0;
}

.split > label {
  min-height: 45px;
  justify-content: center;
}

.split > label + label {
  border-left: 1px solid #efc8b8;
}

.position-grid {
  display: grid;
  min-height: 70px;
  grid-auto-rows: 35px;
  grid-template-columns: 24px calc(50% - 24px) 24px calc(50% - 24px);
  align-items: center;
}

.position-grid label {
  padding-left: 9px;
}

.position-grid input {
  width: 88px;
}

.position-grid.fifth {
  min-height: 105px;
}

.current-action {
  justify-content: center;
}

.pool-grid {
  display: flex;
  min-height: 68px;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 8px 14px;
  flex-wrap: wrap;
}

.pool-grid input {
  width: 88px;
}

.sum-title {
  min-height: 44px;
  justify-content: center;
}

.sum-rules {
  padding: 0;
}

.sum-rule {
  display: flex;
  height: 33px;
  align-items: center;
  justify-content: center;
  gap: 3px;
}

.sum-rule > span:first-child {
  width: 23px;
  text-align: right;
}

.sum-rule .sum-value {
  width: 70px;
  margin-left: 6px;
}

.unpositioned-sum input:not([type='checkbox']) {
  width: 88px;
}

.range-row input {
  width: 29px;
}

.fixed-tools {
  display: grid;
  grid-template-columns: 1fr 1fr;
  align-items: flex-start;
}

.fixed-tools label {
  min-width: 0;
  white-space: nowrap;
}

.fixed-tools label input:not([type='checkbox']) {
  width: 78px;
}

.multiplier-label {
  grid-column: 2;
}

.position-choice {
  display: inline-flex;
  margin-left: 2px;
}

.include-row input {
  width: 88px;
}

.shape-row {
  min-height: 47px;
}

.shape-row label {
  white-space: nowrap;
}

.opposite-row input {
  width: 61px;
}

.attribute-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
}

.attribute-row-fifth {
  grid-template-columns: 1fr;
  row-gap: 7px;
}

.attribute-row label {
  gap: 1px;
  min-width: 0;
  white-space: nowrap;
}

.attribute-row .position-choice {
  margin-left: 0;
}

.attribute-row .position-choice input {
  width: 12px;
  height: 12px;
}

.quick-picker > textarea {
  display: block;
  width: calc(100% - 4px);
  min-height: 48px;
  padding: 3px;
  margin: 2px;
  resize: vertical;
}

.actions {
  display: flex;
  min-height: 47px;
  align-items: center;
  justify-content: center;
  gap: 5px;
}

.actions button,
.send-box button {
  padding: 6px 10px;
  font-weight: 700;
  color: #fff;
  background: #079fe5;
  border: 0;
  border-radius: 5px;
}

.actions button:disabled,
.send-box button:disabled {
  opacity: 0.55;
}

.generated-expression {
  margin: -2px 8px 8px;
  font-size: 16px;
  color: #ff3b30;
  overflow-wrap: anywhere;
}

.box-title {
  display: block;
  min-height: 29px;
  padding: 5px 7px;
  margin-right: 6px;
  margin-left: 6px;
  font-size: 16px;
  background: #ddf4ff;
  border-right: 1px solid #efc8b8;
  border-left: 1px solid #efc8b8;
  box-sizing: border-box;
}

.number-grid {
  display: grid;
  height: 130px;
  min-height: 130px;
  margin: 0 6px 8px;
  overflow-y: auto;
  border-top: 1px solid #efc8b8;
  border-left: 1px solid #efc8b8;
  grid-template-columns: repeat(3, 1fr);
  align-content: start;
}

.number-grid > span {
  min-width: 0;
  padding: 5px 3px;
  text-align: center;
  border-right: 1px solid #efc8b8;
  border-bottom: 1px solid #efc8b8;
  overflow-wrap: anywhere;
}

.number-empty {
  min-height: 128px;
  border-right: 1px solid #efc8b8;
  border-bottom: 1px solid #efc8b8;
  grid-column: 1 / -1;
}

.preview-tip {
  margin: -4px 8px 6px;
  font-size: 12px;
  color: #d46a00;
}

.send-box {
  display: grid;
  min-height: 91px;
  margin: 0 6px 220px;
  border: 1px solid #efc8b8;
  grid-template-columns: 55% 45%;
}

.amount-cell,
.summary-cell {
  display: flex;
  box-sizing: border-box;
}

.amount-cell {
  align-items: center;
  gap: 4px;
  padding: 8px;
  border-right: 1px solid #efc8b8;
}

.amount-cell label {
  display: flex;
  align-items: center;
  gap: 3px;
  font-size: 16px;
}

.amount-cell input {
  width: 60px;
  height: 29px !important;
}

.summary-cell {
  flex-direction: column;
}

.summary-cell span {
  display: flex;
  min-height: 45px;
  padding: 7px;
  box-sizing: border-box;
  align-items: center;
}

.summary-cell span + span {
  border-top: 1px solid #efc8b8;
}

@media (width <= 374px) {
  .pool-grid input {
    width: 76px;
  }

  .rule-row,
  .quick-picker {
    font-size: 13px;
  }

  .rule-row b {
    font-size: 15px;
  }
}
</style>
