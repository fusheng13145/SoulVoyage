import { defineStore } from 'pinia'
import { ref } from 'vue'
import http, { type ApiResp } from '@/api/http'

export interface RefResource { name: string; value: string; type: string; note: string }
export interface Referral { boundary: string; resources: RefResource[] }

/** 危机资源全站一份（CrisisCard/危机页/转介卡共用），失败时降级静态兜底 */
export const useCrisisStore = defineStore('crisis', () => {
  const referral = ref<Referral | null>(null)
  /** S1 关怀模式：/emotions/profile 的 crisisMode + 周画像缓存 */
  const crisisMode = ref(false)
  const weeks = ref<{ statWeek: string; avgValence: string | number; riskLevel: string; stressorTop?: { name: string; count: number }[]; distortionTop?: { name: string; count: number }[] }[]>([])
  let inflight: Promise<void> | null = null

  const FALLBACK: Referral = {
    boundary: '心屿漫行是自助工具，不做诊断，也不能替代专业帮助。转介即我们能陪你走的最远一步。紧急情况请拨打 110/120。',
    resources: [
      { name: '全国 24 小时心理援助热线', value: '12356', type: 'PHONE', note: '免费 · 24 小时' },
      { name: '希望 24 热线', value: '400-161-9995', type: 'PHONE', note: '24 小时' },
    ],
  }

  async function ensure() {
    if (referral.value) return
    inflight ??= http.get<ApiResp<Referral>>('/risk/resources')
      .then(({ data }) => { referral.value = data.data })
      .catch(() => { referral.value = FALLBACK })
      .finally(() => { inflight = null })
    return inflight
  }

  /** 热线拨号统一走这里：数据没到时也有兜底（危机功能不允许空白） */
  async function resources(): Promise<RefResource[]> {
    if (!referral.value) await ensure()
    return referral.value?.resources ?? FALLBACK.resources
  }

  /** 画像一次拉取全站共享（危机横幅 + 洞察周画像） */
  async function refreshProfile() {
    const { data } = await http.get<ApiResp<{ crisisMode: boolean; weeks: typeof weeks.value }>>('/emotions/profile')
    crisisMode.value = !!data.data.crisisMode
    weeks.value = data.data.weeks ?? []
  }

  return { referral, crisisMode, weeks, ensure, resources, refreshProfile }
})
