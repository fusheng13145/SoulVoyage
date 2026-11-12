import { describe, expect, it } from 'vitest'
import { addBusinessDays, businessDate, businessHour, displayMonthDay } from './date'

describe('utils/date 业务时区工具', () => {
  it('businessDate 以 Asia/Shanghai 切日（UTC 16:30 已是次日）', () => {
    expect(businessDate(new Date('2026-11-10T16:30:00Z'))).toBe('2026-11-11')
  })

  it('businessDate 在业务日边界前仍算当天', () => {
    expect(businessDate(new Date('2026-11-10T15:59:59Z'))).toBe('2026-11-10')
  })

  it('businessHour 跨时区取业务小时', () => {
    expect(businessHour(new Date('2026-11-10T16:30:00Z'))).toBe(0)
    expect(businessHour(new Date('2026-11-10T15:59:00Z'))).toBe(23)
    expect(businessHour(new Date('2026-11-10T06:00:00Z'))).toBe(14)
  })

  it('addBusinessDays 支持跨月与零/负偏移', () => {
    const base = new Date('2026-11-30T04:00:00Z')
    expect(businessDate(addBusinessDays(base, 0))).toBe('2026-11-30')
    expect(businessDate(addBusinessDays(base, 1))).toBe('2026-12-01')
    expect(businessDate(addBusinessDays(base, -1))).toBe('2026-11-29')
  })

  it('displayMonthDay 输出 MM/DD', () => {
    expect(displayMonthDay('2026-11-01')).toBe('11/01')
  })
})
