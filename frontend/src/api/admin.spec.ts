import { beforeEach, describe, expect, it, vi } from 'vitest'

import http from '@/api/http'
import * as admin from './admin'

vi.mock('@/api/http', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

const get = vi.mocked(http.get)
const post = vi.mocked(http.post)
const put = vi.mocked(http.put)
const del = vi.mocked(http.delete)

/** 封装层只消费响应包的 data.data，故 mock 只需还原该形状 */
function resp<T>(data: T) {
  return { data: { data } } as never
}

describe('api/admin 管理端接口封装', () => {
  beforeEach(() => {
    get.mockReset()
    post.mockReset()
    put.mockReset()
    del.mockReset()
    get.mockResolvedValue(resp({ ok: true }))
    post.mockResolvedValue(resp({ ok: true }))
    put.mockResolvedValue(resp({ ok: true }))
    del.mockResolvedValue(resp({ ok: true }))
  })

  it('O3 指标总览默认 7 天、可指定天数，并解包 data.data', async () => {
    get.mockResolvedValueOnce(resp({ windowDays: 7 }))
    await expect(admin.getOverview()).resolves.toEqual({ windowDays: 7 })
    expect(get).toHaveBeenCalledWith('/admin/metrics/overview', { params: { days: 7 } })

    await admin.getOverview(30)
    expect(get).toHaveBeenLastCalledWith('/admin/metrics/overview', { params: { days: 30 } })
  })

  it('A1 任务监控：带条件列表、按 taskNo 取详情、重跑走 POST', async () => {
    await admin.listTasks({ status: 'FAILED', page: 2, size: 20 })
    expect(get).toHaveBeenCalledWith('/admin/tasks', {
      params: { status: 'FAILED', page: 2, size: 20 },
    })

    await admin.getTask('T-1')
    expect(get).toHaveBeenCalledWith('/admin/tasks/T-1')

    await admin.retryTask('T-1')
    expect(post).toHaveBeenCalledWith('/admin/tasks/T-1/retry', {})
  })

  it('A2 风险复核：队列筛选、二次授权解密、结案联动', async () => {
    await admin.riskQueue({ reviewed: 0, level: 'HIGH' })
    expect(get).toHaveBeenCalledWith('/admin/risk/queue', { params: { reviewed: 0, level: 'HIGH' } })

    await admin.riskReveal(9, 'pwd')
    expect(post).toHaveBeenCalledWith('/admin/risk/9/reveal', { password: 'pwd' })

    await admin.riskReview(9, true)
    expect(post).toHaveBeenCalledWith('/admin/risk/9/review', { closeCrisis: true })
  })

  it('A3 内容管理：KG 列表按 type 过滤、场景与练习列表', async () => {
    await admin.listKg('DISTORTION')
    expect(get).toHaveBeenCalledWith('/admin/content/kg', { params: { type: 'DISTORTION' } })

    await admin.listKg()
    expect(get).toHaveBeenCalledWith('/admin/content/kg', { params: {} })

    await admin.listScenes()
    expect(get).toHaveBeenCalledWith('/admin/content/scenes')

    await admin.listExercises()
    expect(get).toHaveBeenCalledWith('/admin/content/exercises')
  })

  it('A3 三类内容上下架均走 PUT 并携带 status', async () => {
    await admin.upsertKgStatus('cd_1', 0)
    expect(put).toHaveBeenCalledWith('/admin/content/kg/cd_1', { status: 0 })

    await admin.upsertSceneStatus('DORM_CONFLICT', 1)
    expect(put).toHaveBeenCalledWith('/admin/content/scenes/DORM_CONFLICT', { status: 1 })

    await admin.upsertExerciseStatus('ex_breath', 0)
    expect(put).toHaveBeenCalledWith('/admin/content/exercises/ex_breath', { status: 0 })
  })

  it('A3 内容热刷新与提示词预览', async () => {
    await admin.refreshContent()
    expect(post).toHaveBeenCalledWith('/admin/content/refresh', {})

    const body = { template: 'diary', user: 'u1', vars: { mood: 'calm' } }
    await admin.previewPrompt(body)
    expect(post).toHaveBeenCalledWith('/admin/content/preview', body)
  })

  it('A4 用户支持：列表筛选、危机看板、冻结与解冻', async () => {
    await admin.listUsers({ status: 2, q: 'amy' })
    expect(get).toHaveBeenCalledWith('/admin/users', { params: { status: 2, q: 'amy' } })

    await admin.crisisBoard()
    expect(get).toHaveBeenCalledWith('/admin/users/crisis-board')

    await admin.freezeUser(3)
    expect(post).toHaveBeenCalledWith('/admin/users/3/freeze', {})

    await admin.unfreezeUser(3)
    expect(post).toHaveBeenCalledWith('/admin/users/3/unfreeze', {})
  })

  it('A5 审计与密钥：审计查询、链校验、密钥看板、导出记录', async () => {
    await admin.listAudit({ action: 'ADMIN_RETRY', page: 1 })
    expect(get).toHaveBeenCalledWith('/admin/ops/audit', {
      params: { action: 'ADMIN_RETRY', page: 1 },
    })

    await admin.verifyAudit()
    expect(post).toHaveBeenCalledWith('/admin/ops/audit/verify', {})

    await admin.listKeys()
    expect(get).toHaveBeenCalledWith('/admin/ops/keys')

    await admin.listExportRecords({ page: 3 })
    expect(get).toHaveBeenCalledWith('/admin/ops/export-records', { params: { page: 3 } })
  })

  it('M12 群体看板：列表、建群、详情、增删成员、聚合统计', async () => {
    await admin.listGroups()
    expect(get).toHaveBeenCalledWith('/admin/board/groups')

    await admin.createGroup('计科2301')
    expect(post).toHaveBeenCalledWith('/admin/board/groups', { name: '计科2301' })

    await admin.getGroup(5)
    expect(get).toHaveBeenCalledWith('/admin/board/groups/5')

    await admin.addGroupMembers(5, [1, 2])
    expect(post).toHaveBeenCalledWith('/admin/board/groups/5/members', { userIds: [1, 2] })

    await admin.removeGroupMember(5, 2)
    expect(del).toHaveBeenCalledWith('/admin/board/groups/5/members/2')

    await admin.groupStats(5)
    expect(get).toHaveBeenCalledWith('/admin/board/groups/5/stats', { params: { days: 7 } })

    await admin.groupStats(5, 30)
    expect(get).toHaveBeenLastCalledWith('/admin/board/groups/5/stats', { params: { days: 30 } })
  })

  it('所有封装统一解包 data.data，而不是回传整个 ApiResp', async () => {
    get.mockResolvedValueOnce(resp({ items: [], total: 0 }))
    await expect(admin.listScenes()).resolves.toEqual({ items: [], total: 0 })
  })
})
