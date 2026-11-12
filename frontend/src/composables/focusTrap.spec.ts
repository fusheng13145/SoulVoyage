import { describe, expect, it } from 'vitest'
import { focusableIn, nextFocusIndex } from './focusTrap'

describe('focusTrap', () => {
  it('nextFocusIndex：正向/反向环形', () => {
    expect(nextFocusIndex(-1, 3, false)).toBe(0)
    expect(nextFocusIndex(-1, 3, true)).toBe(2)
    expect(nextFocusIndex(2, 3, false)).toBe(0)
    expect(nextFocusIndex(0, 3, true)).toBe(2)
    expect(nextFocusIndex(1, 3, false)).toBe(2)
  })

  it('nextFocusIndex：空容器返回 -1', () => {
    expect(nextFocusIndex(0, 0, false)).toBe(-1)
    expect(nextFocusIndex(-1, 0, true)).toBe(-1)
  })

  it('focusableIn：收集可聚焦元素并排除 disabled/aria-hidden', () => {
    const root = document.createElement('div')
    root.innerHTML = `
      <button>ok</button>
      <button disabled>no</button>
      <a href="#x">link</a>
      <span aria-hidden="true"><button>hidden</button></span>
      <input />
      <div tabindex="-1">skip</div>
      <div tabindex="0">focusable</div>
    `
    const items = focusableIn(root)
    expect(items.map(el => el.tagName.toLowerCase())).toEqual(['button', 'a', 'input', 'div'])
    expect(items[0].textContent).toBe('ok')
  })

  it('focusableIn：空容器安全返回', () => {
    expect(focusableIn(null)).toEqual([])
  })
})
