import { describe, expect, it } from 'vitest'
import { dateStr, dayOffset, monthOf, reqId } from './date'

describe('小程序 utils/date', () => {
  it('dateStr 固定 YYYY-MM-DD（不依赖 locale）', () => {
    expect(dateStr(new Date(2026, 0, 5))).toBe('2026-01-05')
    expect(dateStr(new Date(2026, 11, 31))).toBe('2026-12-31')
  })

  it('monthOf 取月份键，兼容业务日字符串', () => {
    expect(monthOf('2026-01-05')).toBe('2026-01')
    expect(monthOf()).toMatch(/^\d{4}-\d{2}$/)
  })

  it('dayOffset 支持正负偏移', () => {
    const today = dateStr(new Date())
    expect(dayOffset(0)).toBe(today)
    expect(dayOffset(-1) < today || dayOffset(-1) !== today).toBe(true)
  })

  it('reqId 带前缀且两次调用不同（幂等键不重复）', () => {
    const a = reqId('diary')
    const b = reqId('diary')
    expect(a.startsWith('diary_')).toBe(true)
    expect(a).not.toBe(b)
  })
})
