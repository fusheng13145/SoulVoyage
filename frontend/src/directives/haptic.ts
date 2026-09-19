import type { Directive } from 'vue'
import { hapticOn } from '@/stores/ui'

/**
 * SvHaptic：统一按压反馈（DS2 触感规范）。
 * 轻点 = scale(0.97)；navigator.vibrate 可用则同步轻触；危机场景挂 v-haptic="false" 不加戏。
 */
export const vHaptic: Directive<HTMLElement, boolean | undefined> = {
  mounted(el, binding) {
    const enabled = () => binding.value !== false && hapticOn.value
    el.style.transition = 'transform .12s cubic-bezier(0.32,0.72,0,1)'
    const down = () => {
      if (!enabled()) return
      el.style.transform = 'scale(0.97)'
      if (navigator.vibrate) navigator.vibrate(8)
    }
    const up = () => { el.style.transform = '' }
    el.addEventListener('pointerdown', down)
    el.addEventListener('pointerup', up)
    el.addEventListener('pointercancel', up)
    el.addEventListener('pointerleave', up)
    el._svHaptic = { down, up }
  },
  unmounted(el) {
    const h = el._svHaptic
    if (!h) return
    el.removeEventListener('pointerdown', h.down)
    el.removeEventListener('pointerup', h.up)
    el.removeEventListener('pointercancel', h.up)
    el.removeEventListener('pointerleave', h.up)
  },
}

declare global {
  interface HTMLElement {
    _svHaptic?: { down: () => void; up: () => void }
  }
}
