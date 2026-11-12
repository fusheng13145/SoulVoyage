/** 业务时区固定为 Asia/Shanghai：跨时区设备的「今天」与后端业务日保持一致 */
export const BUSINESS_TZ = 'Asia/Shanghai'

const dateFmt = new Intl.DateTimeFormat('en-CA', { timeZone: BUSINESS_TZ })
const hourFmt = new Intl.DateTimeFormat('en-US', {
  timeZone: BUSINESS_TZ,
  hour: 'numeric',
  hourCycle: 'h23',
})

/** 业务日期（YYYY-MM-DD） */
export function businessDate(date: Date = new Date()): string {
  return dateFmt.format(date)
}

/** 业务小时（0–23） */
export function businessHour(date: Date = new Date()): number {
  return Number(hourFmt.format(date))
}

/** 业务日加减（按自然日；Asia/Shanghai 无夏令时） */
export function addBusinessDays(date: Date, days: number): Date {
  return new Date(date.getTime() + days * 86400000)
}

/** ISO 日期（YYYY-MM-DD…）→ MM/DD */
export function displayMonthDay(iso: string): string {
  return iso.slice(5).replace('-', '/')
}
