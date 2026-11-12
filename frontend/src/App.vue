<script setup lang="ts">
import { computed, provide, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import SvTabBar from '@/components/ui/SvTabBar.vue'
import SvToastHost from '@/components/ui/SvToastHost.vue'
import SvDialogHost from '@/components/ui/SvDialogHost.vue'
import SvCrisisBanner from '@/components/SvCrisisBanner.vue'
import { useAuthStore } from '@/stores/auth'
import { TOKEN_KEY } from '@/api/http'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const scrollRoot = ref<HTMLElement | null>(null)
provide('svScrollRoot', scrollRoot)

const showTab = computed(() => !!route.meta.tab)
const isAdmin = computed(() => !!route.meta.admin)
const isAuthed = computed(() => !!localStorage.getItem(TOKEN_KEY))

/* 转场：统一 200ms 透明度 + 6px 上移（Spec §5.8） */
const transitionName = ref('fade')
router.afterEach((to, from) => {
  const depth = (r: typeof to) => (r.matched[r.matched.length - 1]?.meta.depth as number) ?? 0
  const fade = to.meta.tab && from.meta.tab
  if (fade) transitionName.value = 'fade'
  else transitionName.value = depth(to) >= depth(from) ? 'push' : 'pop'
  scrollRoot.value?.scrollTo({ top: 0 })
})

if (isAuthed.value && !auth.me)
  auth.fetchMe().catch(() => {
    /* 401 由拦截器处理 */
  })
watch(isAuthed, v => {
  if (v && !auth.me) auth.fetchMe().catch(() => {})
})
</script>

<template>
  <div class="stage">
    <div class="canvas" :class="{ wide: isAdmin }">
      <div id="sv-layer" />
      <SvCrisisBanner v-if="isAuthed && !isAdmin && route.path !== '/crisis'" />
      <main ref="scrollRoot" class="screen sv-scroll">
        <router-view v-slot="{ Component }">
          <transition :name="transitionName" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
      <SvTabBar v-if="showTab" />
      <SvToastHost />
      <SvDialogHost />
    </div>
  </div>
</template>

<style>
/* 移动优先：暖纸满屏；≥1024px 收敛为 760px 纸页列（不做手机壳，Spec §5.5） */
.stage {
  min-height: 100dvh;
  background: var(--sv-bg);
  display: flex;
  align-items: stretch;
  justify-content: center;
}
.canvas {
  position: relative;
  width: 100%;
  height: 100dvh;
  background: var(--sv-bg);
  color: var(--sv-label);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
@media (min-width: 1024px) {
  body {
    overflow: hidden;
  }
  .stage {
    align-items: center;
    padding: var(--sv-s7) 0;
  }
  .canvas {
    width: min(760px, 92vw);
    height: min(900px, calc(100dvh - 64px));
    min-height: 0;
    background: var(--sv-card);
    border: 1px solid var(--sv-sep);
    border-radius: var(--sv-r-card);
  }
  /* 管理端宽屏变体：同一暖纸体系，仅放开宽度与密度 */
  .canvas.wide {
    width: min(1200px, 96vw);
  }
}
.screen {
  flex: 1;
  min-height: 0;
  padding-bottom: 20px;
}
#sv-layer {
  position: absolute;
  inset: 0;
  z-index: 100;
  pointer-events: none;
}
#sv-layer > * {
  pointer-events: auto;
}

/* 路由转场：200ms 透明度 + 6px 上移（Spec §5.8） */
.fade-enter-active,
.push-enter-active,
.pop-enter-active {
  transition:
    opacity 0.2s ease,
    transform 0.2s ease;
}
.fade-leave-active,
.push-leave-active,
.pop-leave-active {
  transition: opacity 0.18s ease;
}
.fade-enter-from,
.fade-leave-to,
.push-enter-from,
.pop-enter-from {
  opacity: 0;
}
.push-enter-from,
.pop-enter-from {
  transform: translateY(6px);
}
.fade-leave-to,
.push-leave-to,
.pop-leave-to {
  opacity: 0;
}
</style>
