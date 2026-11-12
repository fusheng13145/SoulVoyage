import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import http from '@/api/http'
import { useContentStore } from './content'

vi.mock('@/api/http', () => ({ default: { get: vi.fn() } }))

const get = vi.mocked(http.get)

function resp<T>(data: T) {
  return { data: { code: 0, msg: 'ok', data, traceId: 't' } } as never
}

describe('stores/content 低频内容目录缓存', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    get.mockReset()
  })

  it('ensureScenes 首次拉取并缓存，二次命中缓存不再请求', async () => {
    get.mockResolvedValueOnce(resp([{ code: 'DORM_CONFLICT', title: '宿舍冲突' }]))

    const store = useContentStore()
    const first = await store.ensureScenes()
    await store.ensureScenes()

    expect(first).toHaveLength(1)
    expect(get).toHaveBeenCalledTimes(1)
    expect(get).toHaveBeenCalledWith('/scenes')
  })

  it('ensureScenes(force) 绕过缓存强制重拉', async () => {
    get.mockResolvedValue(resp([]))

    const store = useContentStore()
    await store.ensureScenes()
    await store.ensureScenes(true)

    expect(get).toHaveBeenCalledTimes(2)
  })

  it('ensureExercises 与场景各自独立缓存，互不串扰', async () => {
    get.mockResolvedValueOnce(resp([{ id: 'ex_breath', name: '呼吸' }]))
    get.mockResolvedValueOnce(resp([{ code: 'DORM_CONFLICT' }]))

    const store = useContentStore()
    const ex = await store.ensureExercises()
    const sc = await store.ensureScenes()

    expect(ex).toHaveLength(1)
    expect(sc).toHaveLength(1)
    expect(get).toHaveBeenNthCalledWith(1, '/exercises')
    expect(get).toHaveBeenNthCalledWith(2, '/scenes')
  })

  it('ensureExercises 二次调用命中缓存', async () => {
    get.mockResolvedValueOnce(resp([]))

    const store = useContentStore()
    await store.ensureExercises()
    await store.ensureExercises()

    expect(get).toHaveBeenCalledTimes(1)
  })

  it('初始状态未加载时为 null，拉取后写入响应数据', async () => {
    const store = useContentStore()
    expect(store.scenes).toBeNull()
    expect(store.exercises).toBeNull()

    get.mockResolvedValue(resp([{ code: 'X' }]))
    await store.ensureScenes()

    expect(store.scenes).toEqual([{ code: 'X' }])
  })
})
