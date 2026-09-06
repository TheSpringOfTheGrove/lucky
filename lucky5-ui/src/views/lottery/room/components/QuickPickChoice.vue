<script setup lang="ts">
import type { QuickPickChoice } from './quickPick'

const props = withDefaults(
  defineProps<{
    modelValue: QuickPickChoice
    options?: QuickPickChoice[]
  }>(),
  { options: () => ['除', '取'] }
)

const emit = defineEmits<{
  'update:modelValue': [value: QuickPickChoice]
}>()

const toggle = (value: QuickPickChoice) => {
  emit('update:modelValue', props.modelValue === value ? '' : value)
}
</script>

<template>
  <span class="choice-checks">
    <label v-for="option in options" :key="option">
      <input type="checkbox" :checked="modelValue === option" @change="toggle(option)" />{{
        option
      }}
    </label>
  </span>
</template>

<style scoped>
.choice-checks,
.choice-checks label {
  display: inline-flex;
  align-items: center;
}

.choice-checks label {
  gap: 1px;
  cursor: pointer;
}

.choice-checks input {
  width: 14px;
  height: 14px;
  margin: 0;
  accent-color: #168eea;
}
</style>
