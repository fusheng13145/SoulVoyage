import { createRouter, createWebHistory } from 'vue-router'
import { TOKEN_KEY } from '@/api/http'

/* 路由分层（下篇六）：tab 深度 0，push 页面深度 1/2；转场由 App.vue 按 depth 判定 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue') },
    { path: '/', redirect: '/today' },

    // —— 一级 Tab ——
    { path: '/today', name: 'today', component: () => import('@/views/TodayView.vue'), meta: { auth: true, tab: true, depth: 0 } },
    { path: '/diaries', name: 'diaries', component: () => import('@/views/DiaryListView.vue'), meta: { auth: true, tab: true, depth: 0 } },
    { path: '/practice', name: 'practice', component: () => import('@/views/PracticeView.vue'), meta: { auth: true, tab: true, depth: 0 } },
    { path: '/insights', name: 'insights', component: () => import('@/views/InsightsView.vue'), meta: { auth: true, tab: true, depth: 0 } },
    { path: '/me', name: 'me', component: () => import('@/views/MeView.vue'), meta: { auth: true, tab: true, depth: 0 } },

    // —— 二级 / 三级页 ——
    { path: '/diaries/write', name: 'diary-write', component: () => import('@/views/DiaryWriteView.vue'), meta: { auth: true, depth: 1 } },
    { path: '/diaries/analysis', name: 'diary-analysis', component: () => import('@/views/AnalysisView.vue'), meta: { auth: true, depth: 1 } },
    { path: '/diaries/:id', name: 'diary-detail', component: () => import('@/views/DiaryDetailView.vue'), meta: { auth: true, depth: 1 } },
    { path: '/companion', name: 'companion', component: () => import('@/views/CompanionView.vue'), meta: { auth: true, depth: 1 } },
    { path: '/practice/sim', name: 'sim', component: () => import('@/views/SimulationView.vue'), meta: { auth: true, depth: 1 } },
    { path: '/practice/room/:id', name: 'practice-room', component: () => import('@/views/PracticeRoomView.vue'), meta: { auth: true, depth: 2 } },
    { path: '/archive', name: 'archive', component: () => import('@/views/ArchiveView.vue'), meta: { auth: true, depth: 1 } },
    { path: '/archive/report/:id', name: 'report-detail', component: () => import('@/views/ReportDetailView.vue'), meta: { auth: true, depth: 2 } },
    { path: '/letters', name: 'letters', component: () => import('@/views/LettersView.vue'), meta: { auth: true, depth: 1 } },
    { path: '/achievements', name: 'achievements', component: () => import('@/views/AchievementsView.vue'), meta: { auth: true, depth: 1 } },
    { path: '/notifications', name: 'notifications', component: () => import('@/views/NotificationsView.vue'), meta: { auth: true, depth: 1 } },
    { path: '/account', name: 'account', component: () => import('@/views/AccountView.vue'), meta: { auth: true, depth: 1 } },

    // —— 公开危机页（无需登录可达） ——
    { path: '/crisis', name: 'crisis', component: () => import('@/views/CrisisView.vue'), meta: { depth: 1 } },

    // —— 旧路径兼容 ——
    { path: '/diary', redirect: '/diaries/write' },
    { path: '/plan', redirect: '/practice' },
    { path: '/simulation', redirect: '/practice/sim' },
    { path: '/:pathMatch(.*)*', redirect: '/today' },
  ],
})

router.beforeEach((to) => {
  if (to.meta.auth && !localStorage.getItem(TOKEN_KEY)) return '/login'
})

export default router
