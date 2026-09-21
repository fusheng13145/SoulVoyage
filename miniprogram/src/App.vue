<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'
import { initTheme } from '@/composables/theme'
import { useAuthStore } from '@/stores/auth'

onLaunch(() => {
  initTheme()
  // 冷启动只认一次人，不拦启动：没有令牌就是登录页，有令牌但过期由网络层统一登出
  void useAuthStore().bootstrap()
})
</script>

<style lang="scss">
/* 全局样式必须走 App.vue：uni-app 把它编进 app.wxss，页面才拿得到 CSS 变量 */
@import './styles/tokens.scss';
@import './styles/base.scss';
</style>
