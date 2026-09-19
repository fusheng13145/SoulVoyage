import { createRouter, createWebHistory } from 'vue-router'
import { TOKEN_KEY } from '../api/http'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('../views/LoginView.vue') },
    { path: '/', component: () => import('../views/HomeView.vue'), meta: { auth: true } },
    { path: '/diary', component: () => import('../views/DiaryView.vue'), meta: { auth: true } },
    { path: '/diaries', component: () => import('../views/DiaryBookView.vue'), meta: { auth: true } },
    { path: '/simulation', component: () => import('../views/SimulationView.vue'), meta: { auth: true } },
    { path: '/plan', component: () => import('../views/PlanView.vue'), meta: { auth: true } },
    { path: '/insights', component: () => import('../views/InsightsView.vue'), meta: { auth: true } },
    { path: '/archive', component: () => import('../views/ArchiveView.vue'), meta: { auth: true } },
    { path: '/account', component: () => import('../views/AccountView.vue'), meta: { auth: true } },
    { path: '/crisis', component: () => import('../views/CrisisView.vue') },
  ],
})

router.beforeEach((to) => {
  if (to.meta.auth && !localStorage.getItem(TOKEN_KEY)) return '/login'
})

export default router
