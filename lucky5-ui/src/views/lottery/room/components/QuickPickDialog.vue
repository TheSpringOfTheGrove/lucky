<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { previewRoomBetApi, type RoomCredential } from '@/api/lottery/room'

const props = defineProps<{
  visible: boolean
  period: string
  balance: number
  credential: RoomCredential
}>()
const emit = defineEmits<{
  close: []
  use: [content: string]
  submit: [content: string]
}>()

const plays = ['二字定', '三字定', '四字定', '二字现', '三字现', '四字现', '五位二定']
const activePlay = ref('二字定')
const amount = ref(1)
const generated = ref('')
const textSource = ref('')
const generatedNumbers = ref<string[]>([])
const previewing = ref(false)
const form = reactive({
  千: '',
  百: '',
  十: '',
  个: '',
  定位: '取',
  配数: '除',
  两数合: '',
  三数合: '',
  含: '',
  全转: '',
  上奖: '',
  排除: '',
  取值最小: '',
  取值最大: '',
  双重: '',
  二兄弟: '',
  三兄弟: '',
  四兄弟: '',
  对数: '',
  单位置: [] as string[],
  双位置: [] as string[],
  大位置: [] as string[],
  小位置: [] as string[]
})

const positions = ['千', '百', '十', '个'] as const
const noteCount = computed(() => generatedNumbers.value.length)
const total = ref(0)

const normalizeDigits = (value: string) => [...new Set(value.replace(/\D/g, ''))].join('')

const reset = () => {
  for (const position of positions) form[position] = ''
  Object.assign(form, {
    定位: '取',
    配数: '除',
    两数合: '',
    三数合: '',
    含: '',
    全转: '',
    上奖: '',
    排除: '',
    取值最小: '',
    取值最大: '',
    双重: '',
    二兄弟: '',
    三兄弟: '',
    四兄弟: '',
    对数: '',
    单位置: [],
    双位置: [],
    大位置: [],
    小位置: []
  })
  generated.value = ''
  textSource.value = ''
  generatedNumbers.value = []
  total.value = 0
}

const preview = async (content: string) => {
  if (!content) return
  previewing.value = true
  try {
    const result = await previewRoomBetApi(props.credential, content)
    generated.value = content
    generatedNumbers.value = result.selections
    total.value = result.total
  } catch (reason: any) {
    generated.value = ''
    generatedNumbers.value = []
    total.value = 0
    ElMessage.error(reason?.message || '无法生成对应号码组')
  } finally {
    previewing.value = false
  }
}

const build = async () => {
  const fixedCount = Number(
    activePlay.value[0] === '五'
      ? 2
      : activePlay.value[0] === '四'
        ? 4
        : activePlay.value[0] === '三'
          ? 3
          : 2
  )
  const groups = positions
    .map((position) => ({ position, digits: normalizeDigits(form[position]) }))
    .filter((item) => item.digits)
  let value = ''

  if (form.全转) {
    value = `${normalizeDigits(form.全转)}全倒${activePlay.value.includes('三') ? '三' : activePlay.value.includes('四') ? '四' : '二'}定`
  } else if (activePlay.value.includes('现')) {
    const digits = normalizeDigits(groups.map((item) => item.digits).join(''))
    if (!digits) return ElMessage.warning('请填写要生成的数字')
    value = `${activePlay.value}${digits}`
  } else {
    if (groups.length !== fixedCount) {
      return ElMessage.warning(`${activePlay.value}需要选择 ${fixedCount} 个位置`)
    }
    value = groups.map((item) => `${item.position}${item.digits}`).join('')
    if (activePlay.value === '五位二定') value += '五位二定'
  }

  if (form.含) value += `。含${normalizeDigits(form.含)}`
  if (form.两数合) value += `两数合${normalizeDigits(form.两数合)}`
  if (form.三数合) value += `三数合${normalizeDigits(form.三数合)}`
  if (form.上奖) value += `上奖${normalizeDigits(form.上奖)}`
  if (form.双重) value += `${form.双重}双重`
  if (form.二兄弟) value += `${form.二兄弟}二兄弟`
  if (form.三兄弟) value += `${form.三兄弟}三兄弟`
  if (form.四兄弟) value += `${form.四兄弟}四兄弟`
  if (form.取值最小 && form.取值最大) value += `取值${form.取值最小}值${form.取值最大}`
  value += `各${Number(amount.value || 0)}`
  textSource.value = value
  await preview(value)
}

const buildFromText = async () => {
  const content = textSource.value.trim()
  if (!content) return ElMessage.warning('请输入要生成的下注文字')
  await preview(content)
}

const useGenerated = async (submit = false) => {
  if (!generated.value) await build()
  if (!generated.value) return
  emit(submit ? 'submit' : 'use', generated.value)
}

watch(
  () => props.visible,
  (visible) => {
    if (visible) {
      generated.value = ''
      generatedNumbers.value = []
      total.value = 0
    }
  }
)
</script>

<template>
  <div v-if="visible" class="quick-picker-mask">
    <section class="quick-picker">
      <header>
        <strong>幸运五</strong>
        <span>{{ period }}期　可用余额：{{ Number(balance).toFixed(2) }}</span>
        <button type="button" @click="emit('close')">×</button>
      </header>

      <div class="play-tabs">
        <button
          v-for="play in plays"
          :key="play"
          type="button"
          :class="{ active: activePlay === play }"
          @click="activePlay = play"
          >{{ play }}</button
        >
      </div>

      <div class="rule-row split">
        <label
          ><b>定位覆</b>
          <input v-model="form.定位" type="checkbox" true-value="除" false-value="" />除
          <input v-model="form.定位" type="checkbox" true-value="取" false-value="" />取</label
        >
        <label
          ><b>配数全转</b>
          <input v-model="form.配数" type="checkbox" true-value="除" false-value="" />除
          <input v-model="form.配数" type="checkbox" true-value="取" false-value="" />取</label
        >
      </div>

      <div class="position-grid">
        <template v-for="position in positions" :key="position">
          <label>{{ position }}</label
          ><input v-model="form[position]" inputmode="numeric" />
        </template>
      </div>

      <div class="rule-row"
        ><b>合　分</b><label>两数合 <input v-model="form.两数合" /></label
        ><label>三数合 <input v-model="form.三数合" /></label
      ></div>
      <div class="rule-row"
        ><b>包含</b><input v-model="form.含" /><b>全转</b><input v-model="form.全转" /><b>上奖</b
        ><input v-model="form.上奖"
      /></div>
      <div class="rule-row"
        ><b>排除</b><input v-model="form.排除" /><b>取值</b
        ><input v-model="form.取值最小" class="short" /> 至
        <input v-model="form.取值最大" class="short"
      /></div>
      <div class="rule-row"
        ><label
          ><input v-model="form.双重" type="checkbox" true-value="除" false-value="" />除
          <input v-model="form.双重" type="checkbox" true-value="取" false-value="" />取
          <b>（双重）</b></label
        ></div
      >
      <div class="rule-row"
        ><label
          ><input v-model="form.二兄弟" type="checkbox" true-value="除" false-value="" />除
          <input v-model="form.二兄弟" type="checkbox" true-value="取" false-value="" />取
          <b>（二兄弟）</b></label
        ></div
      >
      <div class="rule-row"
        ><label
          ><input v-model="form.三兄弟" type="checkbox" true-value="除" false-value="" />除
          <input v-model="form.三兄弟" type="checkbox" true-value="取" false-value="" />取
          <b>（三兄弟）</b></label
        ></div
      >
      <div class="rule-row"
        ><label
          ><input v-model="form.四兄弟" type="checkbox" true-value="除" false-value="" />除
          <input v-model="form.四兄弟" type="checkbox" true-value="取" false-value="" />取
          <b>（四兄弟）</b></label
        ></div
      >
      <div class="rule-row"><b>对数</b><input v-model="form.对数" placeholder="05-16-27" /></div>

      <textarea v-model="textSource" rows="2" placeholder="输入要生成的文字"></textarea>
      <div class="actions"
        ><button type="button" :disabled="previewing" @click="build">生成</button
        ><button type="button" @click="reset">复位</button
        ><button type="button" :disabled="previewing" @click="buildFromText"
          >根据文字生成</button
        ></div
      >
      <p v-if="generated" class="generated-command">生成的文字为：{{ generated }}</p>
      <label class="box-title">生成号码框</label>
      <div class="number-grid">
        <span v-for="(number, index) in generatedNumbers" :key="`${number}-${index}`">{{
          number
        }}</span>
        <div v-if="!generatedNumbers.length" class="number-empty">
          {{ previewing ? '正在生成…' : '请先生成号码' }}
        </div>
      </div>
      <label class="box-title">发送框</label>
      <div class="send-box">
        <label>金额 <input v-model.number="amount" type="number" min="0.1" step="0.1" /></label>
        <button type="button" @click="useGenerated(true)">下注</button>
        <span>笔数：{{ noteCount }}</span
        ><span>金额：{{ total }} 元</span>
      </div>
      <button class="use-button" type="button" @click="useGenerated(false)">填入聊天框</button>
    </section>
  </div>
</template>

<style scoped>
.quick-picker-mask {
  position: fixed;
  z-index: 50;
  background: rgb(0 0 0/0.45);
  inset: 0;
  overflow: auto;
}
.quick-picker {
  width: min(1220px, 100%);
  min-height: 100%;
  margin: auto;
  color: #202124;
  color-scheme: light;
  background: #f8f8fa;
  border: 1px solid #aaa;
}

.quick-picker input,
.quick-picker textarea,
.quick-picker select {
  color: #202124 !important;
  -webkit-text-fill-color: #202124 !important;
  background: #fff !important;
}

.quick-picker input::placeholder,
.quick-picker textarea::placeholder {
  color: #8a8f98 !important;
  opacity: 1;
  -webkit-text-fill-color: #8a8f98 !important;
}
.quick-picker header {
  position: relative;
  padding: 8px;
  text-align: center;
  color: #fff;
  background: #ff5326;
}
.quick-picker header strong {
  display: block;
  font-size: 21px;
  color: #fff500;
}
.quick-picker header button {
  position: absolute;
  top: 8px;
  right: 12px;
  border: 0;
  color: #ffe600;
  background: transparent;
  font-size: 26px;
}
.play-tabs {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  margin: 40px 6px 0;
}
.play-tabs button {
  height: 42px;
  border: 1px solid #efc8b8;
  background: #f5f5f5;
}
.play-tabs .active {
  color: #fff;
  background: #ff9100;
}
.rule-row {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 10px 8px;
  border: 1px solid #efc8b8;
  border-top: 0;
  flex-wrap: wrap;
}
.rule-row b {
  color: #bd1240;
}
.rule-row input:not([type='checkbox']) {
  width: 90px;
  height: 24px;
}
.rule-row .short {
  width: 52px !important;
}
.split {
  justify-content: space-around;
}
.position-grid {
  display: grid;
  grid-template-columns: 28px 1fr 28px 1fr;
  gap: 8px;
  padding: 12px 70px;
}
.position-grid input {
  min-width: 0;
  height: 25px;
}
.quick-picker > textarea {
  display: block;
  box-sizing: border-box;
  width: calc(100% - 12px);
  margin: 6px;
  border: 1px solid #aaa;
  resize: vertical;
}
.actions {
  display: flex;
  justify-content: center;
  gap: 5px;
}
.actions button,
.send-box button,
.use-button {
  padding: 7px 14px;
  border: 0;
  border-radius: 4px;
  color: #fff;
  background: #079fe5;
}
.box-title {
  display: block;
  margin: 6px;
  padding: 6px;
  background: #dff4ff;
}
.generated-command {
  margin: 8px;
  color: #e34b38;
  text-align: center;
  overflow-wrap: anywhere;
}
.number-grid {
  display: grid;
  min-height: 112px;
  margin: 0 6px 6px;
  border-top: 1px solid #777;
  border-left: 1px solid #777;
  grid-template-columns: repeat(7, minmax(72px, 1fr));
}
.number-grid > span {
  min-width: 0;
  padding: 8px 4px;
  border-right: 1px solid #777;
  border-bottom: 1px solid #777;
  font-weight: 700;
  text-align: center;
  overflow-wrap: anywhere;
}
.number-empty {
  display: grid;
  min-height: 110px;
  color: #888;
  border-right: 1px solid #777;
  border-bottom: 1px solid #777;
  place-items: center;
  grid-column: 1 / -1;
}
.send-box {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  gap: 8px;
  align-items: center;
  margin: 6px;
  padding: 10px;
  border: 1px solid #efc8b8;
}
.send-box input {
  width: 65px;
  height: 28px;
}
.send-box span {
  grid-column: 3;
}
.use-button {
  display: block;
  margin: 8px auto 20px;
}
@media (max-width: 420px) {
  .position-grid {
    padding: 12px 30px;
  }
  .rule-row {
    font-size: 14px;
  }
  .number-grid {
    grid-template-columns: repeat(3, minmax(72px, 1fr));
  }
  .send-box {
    grid-template-columns: 1fr auto;
  }
  .send-box span {
    grid-column: auto;
  }
}

@media (min-width: 421px) and (max-width: 760px) {
  .number-grid {
    grid-template-columns: repeat(5, minmax(72px, 1fr));
  }
}
</style>
