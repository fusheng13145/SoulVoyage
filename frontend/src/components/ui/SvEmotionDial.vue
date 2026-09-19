<script setup lang="ts">
import { computed } from 'vue'
import { EMOTIONS } from '@/utils/emotions'
import { vHaptic } from '@/directives/haptic'

export interface CheckinValue {
  emotion: string   // code
  energy: number    // 1-5
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
        v-for="e in EMOTIONS" :key="e.code"
        class="emoc" :class="{ sel: value.emotion === e.code }"
        role="radio" :aria-checked="value.emotion === e.code"
        v-haptic
        @click="pick(e.code)"
      >
        <i
          :style="{
            background: `color-mix(in srgb, var(${e.varName}) 18%, transparent)`,
            borderColor: value.emotion === e.code ? `var(${e.varName})` : 'transparent',
          }"
        >{{ e.face }}</i>
        {{ e.label }}
      </button>
    </div>

    <div class="energy">
      <label for="sv-energy">能量值</label>
      <output id="sv-energy-out" for="sv-energy">{{ value.energy || 3 }} / 5</output>
      <input
        id="sv-energy" type="range" min="1" max="5" :value="value.energy || 3"
        aria-label="能量值，1 到 5"
        @input="setEnergy(Number(($event.target as HTMLInputElement).value))"
      />
    </div>

    <input
      class="note" maxlength="60" :value="value.note || ''" placeholder="一句话（可选）：躲过食堂排队，赢了"
      aria-label="一句话备注（可选）"
      @input="emit('update:modelValue', { ...value, note: ($event.target as HTMLInputElement).value })"
    />
  </div>
</template>

<style scoped>
.grid {
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px;
}
.emoc {
  display: flex; flex-direction: column; align-items: center; gap: 6px;
  font-size: var(--sv-fs-caption1); color: var(--sv-label2);
  padding: 10px 0; border-radius: 14px; border: none; background: transparent;
  cursor: pointer; font-family: inherit; transition: background 0.15s;
}
.emoc:active { background: var(--sv-fill2); }
.emoc i {
  width: 44px; height: 44px; border-radius: 50%;
  display: grid; place-items: center; font-size: 20px; font-style: normal;
  border: 2.5px solid transparent; transition: 0.2s var(--sv-ease);
}
.emoc.sel { color: var(--sv-label); font-weight: 600; }
.emoc.sel i { transform: scale(1.12); }
.energy { margin: 18px 0 4px; display: grid; grid-template-columns: auto auto 1fr; align-items: center; gap: 10px; }
.energy label { color: var(--sv-label2); font-size: var(--sv-fs-footnote); }
.energy output { font-size: var(--sv-fs-footnote); font-weight: 600; }
.energy input { grid-column: 1 / -1; width: 100%; accent-color: var(--sv-indigo); height: 32px; }
.note {
  width: 100%; margin-top: 14px; border: 1px solid var(--sv-sep);
  background: var(--sv-bg); border-radius: 12px; padding: 11px 14px;
  font-size: var(--sv-fs-subhead); color: var(--sv-label); outline: none; font-family: inherit;
}
</style>
