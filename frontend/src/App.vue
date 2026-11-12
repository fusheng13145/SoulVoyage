<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, provide, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import SvTabBar from '@/components/ui/SvTabBar.vue'
import SvToastHost from '@/components/ui/SvToastHost.vue'
import SvDialogHost from '@/components/ui/SvDialogHost.vue'
import SvSheet from '@/components/ui/SvSheet.vue'
import SvCrisisBanner from '@/components/SvCrisisBanner.vue'
import { useAuthStore } from '@/stores/auth'
import { dialogState, overlayDepth } from '@/stores/ui'
import { TOKEN_KEY } from '@/api/http'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const scrollRoot = ref<HTMLElement | null>(null)
const mainEl = ref<HTMLElement | null>(null)
const pageTitle = ref('')
const helpOpen = ref(false)
provide('svScrollRoot', scrollRoot)

const showTab = computed(() => !!route.meta.tab)
const isAdmin = computed(() => !!route.meta.admin)
const isAuthed = computed(() => !!localStorage.getItem(TOKEN_KEY))

const PAGE_LABELS: Record<string, string> = {
  today: '今日',
  diaries: '日记',
  'diary-write': '写日记',
  'diary-detail': '日记详情',
  'diary-analysis': '日记分析',
  practice: '练习',
  sim: '模拟训练',
  'practice-room': '跟练',
  insights: '洞察',
  me: '我的',
  tasks: '任务中心',
  archive: '成长档案',
  'report-detail': '报告详情',
  letters: '成长来信',
  achievements: '成就',
  notifications: '通知',
  companion: '漫聊',
  account: '账号',
  login: '登录',
  crisis: '支持资源',
  'admin-home': '管理端总览',
  'admin-tasks': '任务监控',
  'admin-risk': '风险复核',
  'admin-content': '内容管理',
  'admin-users': '用户支持',
  'admin-ops': '审计密钥',
  'admin-board': '群体看板',
}

const TAB_ROUTES = ['/today', '/diaries', '/practice', '/insights', '/me']

function focusMain() {
  mainEl.value?.focus({ preventScroll: true })
}

function isTyping(target: EventTarget | null) {
  const el = target as HTMLElement | null
  const tag = el?.tagName?.toLowerCase()
  return tag === 'input' || tag === 'textarea' || tag === 'select' || el?.isContentEditable === true
}

function onGlobalKey(e: KeyboardEvent) {
  if (e.defaultPrevented || e.ctrlKey || e.metaKey) return
  if (dialogState.value || overlayDepth.value > 0) return
  if (isTyping(e.target)) return
  if (!isAdmin.value && e.altKey && !e.shiftKey && /^[1-5]$/.test(e.key)) {
    e.preventDefault()
    router.push(TAB_ROUTES[Number(e.key) - 1])
    return
  }
  if (e.key === '?' && !e.altKey) {
    e.preventDefault()
    helpOpen.value = true
  }
}

/* 转场：统一 200ms 透明度 + 6px 上移（Spec §5.8） */
const transitionName = ref('fade')
router.afterEach((to, from) => {
  const depth = (r: typeof to) => (r.matched[r.matched.length - 1]?.meta.depth as number) ?? 0
  const fade = to.meta.tab && from.meta.tab
  if (fade) transitionName.value = 'fade'
  else transitionName.value = depth(to) >= depth(from) ? 'push' : 'pop'
  scrollRoot.value?.scrollTo({ top: 0 })
  pageTitle.value = PAGE_LABELS[String(to.name || '')] || ''
  nextTick(() => mainEl.value?.focus({ preventScroll: true }))
})

onMounted(() => window.addEventListener('keydown', onGlobalKey))
onUnmounted(() => window.removeEventListener('keydown', onGlobalKey))

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
    <a class="skip-link" href="#sv-main" @click.prevent="focusMain">跳到主要内容</a>
    <div class="canvas" :class="{ wide: isAdmin }">
      <div id="sv-layer" />
      <div class="sr-only" aria-live="polite">{{ pageTitle }}</div>
      <SvCrisisBanner v-if="isAuthed && !isAdmin && route.path !== '/crisis'" />
      <main
        id="sv-main"
        ref="mainEl"
        class="screen sv-scroll"
        tabindex="-1"
        aria-label="页面主要内容"
      >
        <router-view v-slot="{ Component }">
          <transition :name="transitionName" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
      <SvTabBar v-if="showTab" />
      <SvToastHost />
      <SvDialogHost />
      <SvSheet v-model="helpOpen" title="键盘快捷键">
        <ul class="keys">
          <li><kbd>Tab</kbd> 在控件间移动；首站可跳过导航</li>
          <li><kbd>Alt</kbd> + <kbd>1..5</kbd> 切换今日 / 日记 / 练习 / 洞察 / 我的</li>
          <li><kbd>?</kbd> 打开本说明</li>
          <li><kbd>Esc</kbd> 关闭弹窗、抽屉与本说明</li>
        </ul>
        <template #footer>
          <button class="sv-btn sm" @click="helpOpen = false">知道了</button>
        </template>
      </SvSheet>
    </div>
  </div>
</template>

<style>
.skip-link {
  position: absolute;
  left: 8px;
  top: 8px;
  z-index: 300;
  transform: translateY(-200%);
  background: var(--sv-card);
  color: var(--sv-accent);
  border: 1px solid var(--sv-accent);
  border-radius: var(--sv-r-ctl);
  padding: 8px 12px;
  font-size: var(--sv-fs-footnote);
  transition: transform 0.15s ease;
}
.skip-link:focus {
  transform: none;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
.keys {
  display: flex;
  flex-direction: column;
  gap: 8px;
  color: var(--sv-label2);
  font-size: var(--sv-fs-footnote);
  line-height: 1.7;
}
.keys kbd {
  display: inline-block;
  border: 1px solid var(--sv-sep);
  border-radius: 4px;
  padding: 0 5px;
  background: var(--sv-card2);
  color: var(--sv-label);
  font-family: inherit;
  font-size: var(--sv-fs-caption1);
}
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
