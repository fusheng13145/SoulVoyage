<script setup lang="ts">
import { computed, provide, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import SvTabBar from '@/components/ui/SvTabBar.vue'
import SvToastHost from '@/components/ui/SvToastHost.vue'
import SvDialogHost from '@/components/ui/SvDialogHost.vue'
import SvCrisisBanner from '@/components/SvCrisisBanner.vue'
import { overlayDepth } from '@/stores/ui'
import { useAuthStore } from '@/stores/auth'
import { TOKEN_KEY } from '@/api/http'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const scrollRoot = ref<HTMLElement | null>(null)
provide('svScrollRoot', scrollRoot)

const canvasZoom = computed(() => overlayDepth.value > 0)
const showTab = computed(() => !!route.meta.tab)
const isAdmin = computed(() => !!route.meta.admin)
const isAuthed = computed(() => !!localStorage.getItem(TOKEN_KEY))

/* 转场：tab 淡入；push 右进左出 / pop 左进右出（DS4） */
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
    <div class="canvas" :class="{ zoom: canvasZoom, wide: isAdmin }">
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
/* 移动优先：≥1024px 收敛为居中 430px 画布 + 品牌氛围背景（DS4） */
.stage {
  min-height: 100dvh;
  background: #0b0d1a;
  display: flex;
  align-items: center;
  justify-content: center;
}
.stage::before {
  content: '';
  position: fixed;
  inset: -20%;
  pointer-events: none;
  background:
    radial-gradient(40% 35% at 25% 25%, rgba(91, 108, 255, 0.5), transparent 70%),
    radial-gradient(35% 30% at 78% 70%, rgba(48, 209, 88, 0.25), transparent 70%),
    radial-gradient(30% 25% at 60% 20%, rgba(255, 159, 10, 0.18), transparent 70%);
  filter: blur(60px);
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
.canvas.zoom .screen {
  transform: scale(0.96);
}
@media (min-width: 1024px) {
  body {
    overflow: hidden;
  }
  .canvas {
    width: 430px;
    height: min(908px, 100dvh);
    min-height: 0;
    border-radius: 44px;
    box-shadow:
      0 40px 120px rgba(0, 0, 0, 0.6),
      0 0 0 10px #16181f;
  }
  /* 管理端宽屏变体：同一画布体系，仅放开宽度 */
  .canvas.wide {
    width: min(920px, 94vw);
    border-radius: 28px;
  }
}
.screen {
  flex: 1;
  min-height: 0;
  padding-bottom: 20px;
  transition: transform 0.35s var(--sv-ease);
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

/* 路由转场 */
.fade-enter-active,
.push-enter-active,
.pop-enter-active {
  transition:
    opacity 0.24s ease,
    transform 0.32s var(--sv-ease);
}
.fade-leave-active,
.push-leave-active,
.pop-leave-active {
  transition:
    opacity 0.18s ease,
    transform 0.32s var(--sv-ease);
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
.push-enter-from {
  opacity: 0;
  transform: translateX(24%);
}
.push-leave-to {
  opacity: 0;
  transform: translateX(-18%);
}
.pop-enter-from {
  opacity: 0;
  transform: translateX(-18%);
}
.pop-leave-to {
  opacity: 0;
  transform: translateX(24%);
}
</style>
