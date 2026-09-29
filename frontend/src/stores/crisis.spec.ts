import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import http from '@/api/http'
import { useCrisisStore } from './crisis'

vi.mock('@/api/http', () => ({ default: { get: vi.fn() } }))

const get = vi.mocked(http.get)

/** 构造后端统一响应包（ApiResp<T>） */
function resp<T>(data: T) {
  return { data: { code: 0, msg: 'ok', data, traceId: 't' } } as never
}

describe('stores/crisis 危机资源与画像', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    get.mockReset()
  })

  it('ensure 成功后写入服务端转介资源', async () => {
    get.mockResolvedValueOnce(
      resp({
        boundary: '服务端边界文案',
        resources: [{ name: '热线A', value: '12356', type: 'PHONE', note: '24 小时' }],
      }),
    )

    const store = useCrisisStore()
    await store.ensure()

    expect(get).toHaveBeenCalledTimes(1)
    expect(get).toHaveBeenCalledWith('/risk/resources')
    expect(store.referral?.boundary).toBe('服务端边界文案')
    expect(store.referral?.resources).toHaveLength(1)
  })

  it('ensure 失败时降级为内置兜底资源：危机功能不允许空白', async () => {
    get.mockRejectedValueOnce(new Error('network down'))

    const store = useCrisisStore()
    await store.ensure()

    const values = store.referral?.resources.map(r => r.value) ?? []
    expect(values).toContain('12356')
    expect(store.referral?.boundary).toContain('不做诊断')
  })

  it('ensure 幂等：已有 referral 时不再发起请求', async () => {
    get.mockResolvedValue(resp({ boundary: 'b', resources: [] }))

    const store = useCrisisStore()
    await store.ensure()
    await store.ensure()

    expect(get).toHaveBeenCalledTimes(1)
  })

  it('并发 ensure 共享同一次在途请求（inflight 去重）', async () => {
    get.mockResolvedValue(resp({ boundary: 'b', resources: [] }))

    const store = useCrisisStore()
    await Promise.all([store.ensure(), store.ensure(), store.ensure()])

    expect(get).toHaveBeenCalledTimes(1)
  })

  it('resources 在 referral 为空时先 ensure，且保证返回非空列表', async () => {
    get.mockRejectedValueOnce(new Error('boom'))

    const store = useCrisisStore()
    const list = await store.resources()

    expect(list.length).toBeGreaterThan(0)
    expect(list.map(r => r.value)).toContain('12356')
  })

  it('refreshProfile 写入 crisisMode 与周画像', async () => {
    get.mockResolvedValueOnce(
      resp({
        crisisMode: true,
        weeks: [{ statWeek: '2026-W39', avgValence: '-0.40', riskLevel: 'HIGH' }],
      }),
    )

    const store = useCrisisStore()
    await store.refreshProfile()

    expect(get).toHaveBeenCalledWith('/emotions/profile')
    expect(store.crisisMode).toBe(true)
    expect(store.weeks).toHaveLength(1)
    expect(store.weeks[0]?.riskLevel).toBe('HIGH')
  })

  it('refreshProfile 缺省 weeks 时回落为空数组', async () => {
    get.mockResolvedValueOnce(resp({ crisisMode: false }))

    const store = useCrisisStore()
    await store.refreshProfile()

    expect(store.crisisMode).toBe(false)
    expect(store.weeks).toEqual([])
  })
})
