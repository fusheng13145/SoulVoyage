<script setup lang="ts">
import { inject, onMounted, onUnmounted, ref, computed, type Ref } from 'vue'
import SvIcon from './SvIcon.vue'

const props = withDefaults(
  defineProps<{
    title?: string
    subtitle?: string
    back?: string | boolean // true=router.back()，字符串=返回链接文案（to 由 router 决定）
    backTo?: string
    large?: boolean
  }>(),
  { large: true, title: '', subtitle: '', back: false, backTo: '' },
)

const collapsed = ref(false)
const scrollRoot = inject<Ref<HTMLElement | null> | undefined>('svScrollRoot', undefined)
const pageBody = ref<HTMLElement | null>(null)

function onScroll() {
  if (!scrollRoot?.value) return
  const bodyTop = pageBody.value?.offsetTop ?? 0
  collapsed.value = scrollRoot.value.scrollTop > Math.max(8, bodyTop - 70)
}
onMounted(() => scrollRoot?.value?.addEventListener('scroll', onScroll, { passive: true }))
onUnmounted(() => scrollRoot?.value?.removeEventListener('scroll', onScroll))

const showBack = computed(() => props.back !== undefined && props.back !== false)
</script>

<template>
  <div class="navw">
    <div class="glass" :class="{ show: large && collapsed, flat: !large }">
      <div class="bar">
        <button
          v-if="showBack"
          class="back"
          :aria-label="typeof back === 'string' ? back : '返回'"
          @click="backTo ? $router.push(backTo) : $router.back()"
        >
          <SvIcon name="i-back" :size="20" tone="inherit" /><span v-if="typeof back === 'string'">{{
            back
          }}</span>
        </button>
        <span v-if="large" class="ctitle" :class="{ show: collapsed }">{{ title }}</span>
        <span v-else class="ctitle show">{{ title }}</span>
        <span class="acts"><slot name="actions" /></span>
      </div>
    </div>
    <div v-if="large" ref="pageBody" class="large">
      <slot name="large">
        <h1>
          {{ title }}<small v-if="subtitle"> {{ subtitle }}</small>
        </h1>
      </slot>
    </div>
  </div>
</template>

<style scoped>
.navw {
  position: relative;
}
.glass {
  position: sticky;
  top: 0;
  z-index: 20;
  background: transparent;
  transition:
    background 0.3s,
    border-color 0.3s;
  border-bottom: 1px solid transparent;
}
.glass.show,
.glass.flat {
  background: color-mix(in srgb, var(--sv-bg) 72%, transparent);
  backdrop-filter: var(--sv-blur);
  -webkit-backdrop-filter: var(--sv-blur);
}
.glass.show {
  border-bottom-color: var(--sv-sep);
}
.bar {
  position: relative;
  min-height: 44px;
  padding: var(--sv-safe-t) 6px 0;
  display: flex;
  align-items: center;
  gap: 8px;
}
.large {
  padding: calc(var(--sv-safe-t) + 8px) 6px 6px;
}
.large h1 {
  font-size: var(--sv-fs-largename);
  font-weight: 700;
  letter-spacing: 0.4px;
  transition: opacity 0.3s;
  line-height: 1.25;
}
.large h1 small {
  display: block;
  font-size: var(--sv-fs-footnote);
  font-weight: 400;
  color: var(--sv-label2);
  letter-spacing: 0;
  margin-top: 2px;
}
.back {
  border: none;
  background: transparent;
  cursor: pointer;
  color: var(--sv-indigo);
  font-size: var(--sv-fs-subhead);
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 8px 6px;
  min-height: 44px;
  font-family: inherit;
}
.ctitle {
  flex: 1;
  text-align: center;
  padding: 0 8px;
  font-weight: 600;
  font-size: var(--sv-fs-headline);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.25s;
}
.ctitle.show {
  opacity: 1;
}
.acts {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 10px;
}
</style>
