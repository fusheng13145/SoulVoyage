import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * API domain 层契约测试：只验证 URL/参数/返回值透传，不重复测后端语义。
 * 这些模块是页面与 http 实例之间的薄封装，0% 覆盖会直接拖垮覆盖率门禁。
 */
const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
}))

vi.mock('@/api/http', () => ({ default: httpMock }))

import { accountApi } from './account'
import { achievementApi } from './achievement'
import { archiveApi } from './archive'
import { authApi } from './auth'
import { checkinApi } from './checkin'
import { companionApi } from './companion'
import { contentApi } from './content'
import { diaryApi } from './diary'
import { emotionApi } from './emotion'
import { exerciseApi } from './exercise'
import { letterApi } from './letter'
import { notificationApi } from './notification'
import { planApi } from './plan'
import { readingApi } from './reading'
import { reportApi } from './report'
import { riskApi } from './risk'
import { simulationApi } from './simulation'
import { taskApi } from './task'
import { voiceApi } from './voice'

const ok = { data: { data: { fileId: 'f1', taskNo: 't1', favorited: true, starred: true } } }

beforeEach(() => {
  vi.clearAllMocks()
  httpMock.get.mockResolvedValue(ok)
  httpMock.post.mockResolvedValue(ok)
  httpMock.put.mockResolvedValue(ok)
  httpMock.delete.mockResolvedValue(ok)
})

describe('account/achievement/archive/auth', () => {
  it('透传账号、成就、档案与认证端点', async () => {
    await accountApi.requestDeletion('pw')
    await accountApi.cancelDeletion()
    await accountApi.changePassword('old', 'new')
    await expect(accountApi.createDataExport()).resolves.toMatchObject({ fileId: 'f1' })
    await accountApi.consumeDataExport('f1')
    await achievementApi.wall()
    await archiveApi.summary()
    await archiveApi.createExport()
    await archiveApi.claimExport('f1')
    await authApi.login('u', 'p')
    await authApi.register('u', 'p', 'n')
    await authApi.me()
    await authApi.logout()

    expect(httpMock.post).toHaveBeenCalledWith('/users/me/delete', { password: 'pw' })
    expect(httpMock.put).toHaveBeenCalledWith('/users/me/password', {
      oldPassword: 'old',
      newPassword: 'new',
    })
    expect(httpMock.get).toHaveBeenCalledWith('/users/me/data-export')
    expect(httpMock.post).toHaveBeenCalledWith('/archive/export', {})
    expect(httpMock.get).toHaveBeenCalledWith('/archive/export/f1')
    expect(httpMock.post).toHaveBeenCalledWith('/auth/login', { username: 'u', password: 'p' })
    expect(httpMock.post).toHaveBeenCalledWith('/auth/logout', {})
  })
})

describe('checkin/companion/content/diary', () => {
  it('透传打卡、陪伴、内容与日记端点', async () => {
    await checkinApi.month({ month: '2026-11' })
    await checkinApi.streak()
    await checkinApi.create({ emotion: 'CALM', rating: 4 })
    await checkinApi.makeup({ emotion: 'CALM' }, { date: '2026-11-12' })

    await companionApi.active()
    await companionApi.open()
    await companionApi.history({ page: 1, size: 10 })
    await companionApi.transcript(1)
    await companionApi.noAnalyze(2)
    await companionApi.end(3)

    await contentApi.scenes()
    await contentApi.exercises()

    await diaryApi.list({ q: 'x', page: 1, size: 10 })
    await diaryApi.detail(1)
    await diaryApi.update(1, { content: 'c', moodSelfRating: 3 })
    await diaryApi.remove(1)
    await diaryApi.reanalyze(1)
    await diaryApi.getDraft()
    await diaryApi.saveDraft('draft')
    await diaryApi.deleteDraft()

    expect(httpMock.get).toHaveBeenCalledWith('/mood-check-ins', { params: { month: '2026-11' } })
    expect(httpMock.post).toHaveBeenCalledWith(
      '/mood-check-ins/makeup',
      { emotion: 'CALM' },
      {
        params: { date: '2026-11-12' },
      },
    )
    expect(httpMock.post).toHaveBeenCalledWith('/companion/turns/2/no-analyze', {})
    expect(httpMock.get).toHaveBeenCalledWith('/diaries/draft')
    expect(httpMock.put).toHaveBeenCalledWith('/diaries/draft', { content: 'draft' })
    expect(httpMock.delete).toHaveBeenCalledWith('/diaries/draft')
  })
})

describe('emotion/exercise/letter/notification', () => {
  it('透传情绪、练习、信件与通知端点', async () => {
    await emotionApi.trajectory({ from: '2026-11-01', to: '2026-11-12' })
    await emotionApi.profile()
    await emotionApi.insight({ weeks: 4 })
    await emotionApi.selfRating({
      emotion: 'CALM',
      valence: 0.2,
      intensity: 0.5,
      date: '2026-11-12',
    })

    await exerciseApi.list()
    await exerciseApi.records({ limit: 3 })
    await exerciseApi.record({ exerciseId: 'ex_1' })
    await exerciseApi.cognitiveWriting({ content: 'x' })

    await letterApi.list()

    await notificationApi.list({ page: 1, size: 10, unreadOnly: true })
    await notificationApi.read(1)
    await notificationApi.readAll()
    await notificationApi.getPrefs()
    await notificationApi.putPrefs({ theme: 'dark' })

    expect(httpMock.get).toHaveBeenCalledWith('/emotions/insights', { params: { weeks: 4 } })
    expect(httpMock.post).toHaveBeenCalledWith('/emotions/self-rating', {
      emotion: 'CALM',
      valence: 0.2,
      intensity: 0.5,
      date: '2026-11-12',
    })
    expect(httpMock.get).toHaveBeenCalledWith('/exercise-records', { params: { limit: 3 } })
    expect(httpMock.put).toHaveBeenCalledWith('/preferences', { theme: 'dark' })
  })
})

describe('plan/reading/report/risk/simulation/task/voice', () => {
  it('透传计划、阅读、报告、风险、模拟、任务与语音端点', async () => {
    await planApi.active()
    await planApi.list()
    await planApi.detail(73)
    await planApi.adapt(73, 'REDUCE')
    await planApi.skipToday(73)

    await readingApi.today()
    await readingApi.favorites()
    await readingApi.toggleFavorite({ type: 'article', refCode: 'r1' })

    await reportApi.list({ page: 1, size: 10, type: 'TRACE', starred: true })
    await reportApi.detail(1)
    await reportApi.star(1, true)
    await reportApi.feedback(1, { rating: 'USEFUL' })

    await riskApi.resources()

    await simulationApi.list({ page: 1, size: 10 })
    await simulationApi.stats()
    await simulationApi.detail(1)
    await simulationApi.open({ sceneCode: 's1', difficulty: 'NORMAL' })
    await simulationApi.interrupt(1)
    await simulationApi.finish(1)

    await taskApi.submit({ pipelineCode: 'DIARY_PIPELINE', payload: { diaryText: 'x' } })
    await taskApi.list({ page: 1, size: 10 })
    await taskApi.get('t1')
    await taskApi.steps('t1')
    await taskApi.retry('t1')

    await voiceApi.transcribe(new FormData())

    expect(httpMock.post).toHaveBeenCalledWith('/plans/73/adapt', { action: 'REDUCE' })
    expect(httpMock.post).toHaveBeenCalledWith('/plans/73/skip-today')
    expect(httpMock.get).toHaveBeenCalledWith('/reports', {
      params: { page: 1, size: 10, type: 'TRACE', starred: true },
    })
    expect(httpMock.post).toHaveBeenCalledWith('/reports/1/star', { starred: true })
    expect(httpMock.post).toHaveBeenCalledWith('/tasks/t1/retry')
    expect(httpMock.post).toHaveBeenCalledWith('/diaries/voice-transcriptions', expect.any(FormData))
  })
})
