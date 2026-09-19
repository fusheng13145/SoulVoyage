import { createRouter, createWebHistory } from 'vue-router'
import { TOKEN_KEY } from '../api/http'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('../views/LoginView.vue') },
    { path: '/', component: () => import('../views/HomeView.vue'), meta: { auth: true } },
    { path: '/diary', component: () => import('../views/DiaryView.vue'), meta: { auth: true } },
    { path: '/simulation', component: () => import('../views/SimulationView.vue'), meta: { auth: true } },
  ],
})

router.beforeEach((to) => {
  if (to.meta.auth && !localStorage.getItem(TOKEN_KEY)) return '/login'
})

export default router
