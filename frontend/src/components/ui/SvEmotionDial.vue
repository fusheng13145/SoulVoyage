<script setup lang="ts">
import { computed } from 'vue'
import { EMOTIONS } from '@/utils/emotions'
import { vHaptic } from '@/directives/haptic'

export interface CheckinValue {
  emotion: string // code
  energy: number // 1-5
  note: string
}

const props = defineProps<{ modelValue: Partial<CheckinValue> }>()
const emit = defineEmits<{ 'update:modelValue': [Partial<CheckinValue>] }>()

const value = computed(() => props.modelValue || {})
function pick(code: string) {
  emit('update:modelValue', { ...value.value, emotion: code })
}
function setEnergy(e: number) {
  emit('update:modelValue', { ...value.value, energy: e })
}
</script>

<template>
  <div class="dial">
    <div class="grid" role="radiogroup" aria-label="选择一个最接近的情绪">
      <button
        v-for="e in EMOTIONS"
        :key="e.code"
        v-haptic
        class="emoc"
        :class="{ sel: value.emotion === e.code }"
        role="radio"
        :aria-checked="value.emotion === e.code"
        @click="pick(e.code)"
      >
        <i class="dot" :style="{ background: `var(${e.varName})` }" aria-hidden="true" />
        {{ e.label }}
      </button>
    </div>

    <div class="energy">
      <label for="sv-energy">能量值</label>
      <output id="sv-energy-out" for="sv-energy">{{ value.energy || 3 }} / 5</output>
      <input
        id="sv-energy"
        type="range"
        min="1"
        max="5"
        :value="value.energy || 3"
        aria-label="能量值，1 到 5"
        @input="setEnergy(Number(($event.target as HTMLInputElement).value))"
      />
    </div>

    <input
      class="note"
      maxlength="60"
      :value="value.note || ''"
      placeholder="一句话（可选）：躲过食堂排队，赢了"
      aria-label="一句话备注（可选）"
      @input="emit('update:modelValue', { ...value, note: ($event.target as HTMLInputElement).value })"
    />
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}
.emoc {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 44px;
  font-size: var(--sv-fs-caption1);
  color: var(--sv-label2);
  padding: 6px 0;
  border-radius: var(--sv-r-ctl);
  border: 1px solid transparent;
  background: transparent;
  cursor: pointer;
  font-family: inherit;
  transition:
    background 0.18s,
    border-color 0.18s;
}
.emoc:active {
  background: var(--sv-fill2);
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  display: block;
  font-style: normal;
}
.emoc.sel {
  color: var(--sv-label);
  font-weight: 500;
  border-color: var(--sv-accent);
  background: var(--sv-accent-soft);
}
.energy {
  margin: 18px 0 4px;
  display: grid;
  grid-template-columns: auto auto 1fr;
  align-items: center;
  gap: 10px;
}
.energy label {
  color: var(--sv-label2);
  font-size: var(--sv-fs-footnote);
}
.energy output {
  font-size: var(--sv-fs-footnote);
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}
.energy input {
  grid-column: 1 / -1;
  width: 100%;
  accent-color: var(--sv-accent);
  height: 32px;
}
.note {
  width: 100%;
  margin-top: 14px;
  border: 1px solid var(--sv-line-strong);
  background: var(--sv-card2);
  border-radius: var(--sv-r-ctl);
  padding: 9px 12px;
  font-size: var(--sv-fs-body);
  color: var(--sv-label);
  outline: none;
  font-family: inherit;
}
</style>
