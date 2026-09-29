import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { confirmDialog, dialogState, hapticOn, resolveDialog, setHaptic, toast, toastQueue } from './ui'

describe('stores/ui 全局 Toast 与确认弹窗', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    toastQueue.splice(0, toastQueue.length)
    dialogState.value = null
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('toast 入队并在 2.6s 后自动移除', () => {
    toast('已保存')
    expect(toastQueue.map(t => t.msg)).toEqual(['已保存'])

    vi.advanceTimersByTime(2599)
    expect(toastQueue).toHaveLength(1)

    vi.advanceTimersByTime(1)
    expect(toastQueue).toHaveLength(0)
  })

  it('多条 toast 按各自到期时间独立移除', () => {
    toast('a')
    vi.advanceTimersByTime(1000)
    toast('b')

    vi.advanceTimersByTime(1600)
    expect(toastQueue.map(t => t.msg)).toEqual(['b'])

    vi.advanceTimersByTime(1000)
    expect(toastQueue).toHaveLength(0)
  })

  it('toast 的 id 单调递增，同文案重复时不互相误删', () => {
    toast('same')
    toast('same')

    expect(toastQueue).toHaveLength(2)
    expect(toastQueue[0]?.id).not.toBe(toastQueue[1]?.id)
  })

  it('confirmDialog 挂起，直到 resolveDialog 给出结论', async () => {
    const p = confirmDialog({ title: '注销账号', danger: true, confirmText: '确认注销' })

    expect(dialogState.value?.title).toBe('注销账号')
    expect(dialogState.value?.danger).toBe(true)

    resolveDialog(true)

    await expect(p).resolves.toBe(true)
    expect(dialogState.value).toBeNull()
  })

  it('confirmDialog 取消时 resolve 为 false', async () => {
    const p = confirmDialog({ title: '删除记录' })
    resolveDialog(false)

    await expect(p).resolves.toBe(false)
    expect(dialogState.value).toBeNull()
  })

  it('无挂起弹窗时 resolveDialog 不抛异常', () => {
    expect(() => resolveDialog(true)).not.toThrow()
  })

  it('setHaptic 同步内存开关与 localStorage', () => {
    setHaptic(false)
    expect(hapticOn.value).toBe(false)
    expect(localStorage.getItem('sv_haptic')).toBe('off')

    setHaptic(true)
    expect(hapticOn.value).toBe(true)
    expect(localStorage.getItem('sv_haptic')).toBe('on')
  })

  it('toast 项在到期前已被外部清空时，回调不重复删除、不抛错', () => {
    toast('会被提前清掉')
    expect(toastQueue).toHaveLength(1)

    // 模拟登出/全局重置在 2.6s 窗口内清空了队列：到期回调必须容忍 findIndex 未命中
    toastQueue.splice(0, toastQueue.length)

    expect(() => vi.advanceTimersByTime(2600)).not.toThrow()
    expect(toastQueue).toHaveLength(0)
  })
})
