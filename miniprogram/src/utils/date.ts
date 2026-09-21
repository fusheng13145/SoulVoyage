/** 端侧日期口径：一律 YYYY-MM-DD 字符串，和后端 LocalDate 对齐。
 *  不用 toLocaleDateString：小程序的 JS core 对 locale 支持不全，拼出来的分隔符会漂。 */
function pad(n: number) {
  return n < 10 ? `0${n}` : String(n)
}

export function dateStr(d: Date) {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

export function todayStr() {
  return dateStr(new Date())
}

/** 相对今天偏移 n 天（负数往前）：近两周轨迹带用 */
export function dayOffset(n: number) {
  const d = new Date()
  d.setDate(d.getDate() + n)
  return dateStr(d)
}

/** 某月首日：GET /mood-check-ins?month= 要 YYYY-MM */
export function monthOf(date = todayStr()) {
  return date.slice(0, 7)
}

export function clockGreeting(nickname?: string) {
  const h = new Date().getHours()
  const g =
    h < 5
      ? '还没睡呀'
      : h < 11
        ? '早上好'
        : h < 14
          ? '中午好'
          : h < 18
            ? '下午好'
            : h < 22
              ? '晚上好'
              : '夜深了'
  return nickname ? `${g}，${nickname}` : g
}

/** 幂等键：日记建档重试用同一个 id 只写一条，换一次编辑就换新 id */
export function reqId(prefix = 'mp') {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
}
