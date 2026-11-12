import { onBeforeUnmount, watch, type Ref } from 'vue'

const FOCUSABLE =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), ' +
  'select:not([disabled]), [tabindex]:not([tabindex="-1"])'

/** 收集容器内可聚焦元素；排除 aria-hidden/disabled。 */
export function focusableIn(root: HTMLElement | null): HTMLElement[] {
  if (!root) return []
  return Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(el => {
    if (el.hasAttribute('hidden')) return false
    return !el.closest('[aria-hidden="true"]')
  })
}

/** Tab 环形索引：current < 0 时从首/尾开始。 */
export function nextFocusIndex(current: number, length: number, shift: boolean): number {
  if (length <= 0) return -1
  if (current < 0) return shift ? length - 1 : 0
  return shift ? (current - 1 + length) % length : (current + 1) % length
}

/**
 * 浮层焦点陷阱：激活时优先聚焦 [data-autofocus]，否则首项；Tab 不逃逸；
 * 失活时把焦点还给打开前的元素。组件卸载时兜底移除监听。
 */
export function useFocusTrap(target: Ref<HTMLElement | null>, active: Ref<boolean>) {
  let previous: HTMLElement | null = null
  let onKey: ((e: KeyboardEvent) => void) | null = null

  watch(
    active,
    v => {
      if (v) {
        previous = document.activeElement as HTMLElement | null
        requestAnimationFrame(() => {
          const root = target.value
          if (!root) return
          const preferred = root.querySelector<HTMLElement>('[data-autofocus]')
          ;(preferred ?? focusableIn(root)[0] ?? root).focus({ preventScroll: true })
        })
        onKey = e => {
          if (e.key !== 'Tab') return
          const root = target.value
          const items = focusableIn(root)
          if (!items.length) {
            e.preventDefault()
            return
          }
          const idx = items.indexOf(document.activeElement as HTMLElement)
          e.preventDefault()
          items[nextFocusIndex(idx, items.length, e.shiftKey)]?.focus()
        }
        document.addEventListener('keydown', onKey, true)
      } else {
        if (onKey) document.removeEventListener('keydown', onKey, true)
        onKey = null
        previous?.focus?.({ preventScroll: true })
        previous = null
      }
    },
    { flush: 'post', immediate: true },
  )

  onBeforeUnmount(() => {
    if (onKey) document.removeEventListener('keydown', onKey, true)
  })
}
