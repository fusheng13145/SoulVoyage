/** 16 情绪谱：与 Web 端 utils/emotions.ts、后端 EmotionCatalog 同一张闭集表（手册 DS2）。
 *  三处同名不同所是刻意的：色源在 CSS 变量里，语义在后端枚举里，展示标签在两端各自一份——
 *  真源冲突时以后端 EmotionCatalog 为准（打卡只认 code，标签错了也不会错数据）。 */
export interface EmotionMeta {
  code: string
  label: string
  face: string
  varName: string
  valence: number
  intensity: number
}

export const EMOTIONS: EmotionMeta[] = [
  { code: 'JOY', label: '喜悦', face: '😊', varName: '--e-joy', valence: 0.8, intensity: 0.7 },
  { code: 'CALM', label: '平静', face: '😌', varName: '--e-calm', valence: 0.5, intensity: 0.2 },
  { code: 'ANXIETY', label: '焦虑', face: '😰', varName: '--e-anx', valence: -0.5, intensity: 0.7 },
  { code: 'ANGER', label: '愤怒', face: '😠', varName: '--e-anger', valence: -0.6, intensity: 0.8 },
  { code: 'SADNESS', label: '悲伤', face: '😢', varName: '--e-sad', valence: -0.6, intensity: 0.5 },
  { code: 'FEAR', label: '恐惧', face: '😨', varName: '--e-fear', valence: -0.6, intensity: 0.75 },
  { code: 'SHAME', label: '羞耻', face: '🫣', varName: '--e-shame', valence: -0.7, intensity: 0.65 },
  { code: 'LONELY', label: '孤独', face: '🫂', varName: '--e-lonely', valence: -0.4, intensity: 0.45 },
  { code: 'NUMB', label: '麻木', face: '😶', varName: '--e-numb', valence: -0.2, intensity: 0.25 },
  { code: 'GRIEVANCE', label: '委屈', face: '🥺', varName: '--e-griev', valence: -0.5, intensity: 0.55 },
  { code: 'PRESSURE', label: '压力', face: '😮', varName: '--e-pressure', valence: -0.4, intensity: 0.7 },
  { code: 'GRATITUDE', label: '感恩', face: '🙏', varName: '--e-grati', valence: 0.7, intensity: 0.45 },
  { code: 'CONFUSED', label: '困惑', face: '🤔', varName: '--e-confuse', valence: -0.15, intensity: 0.4 },
  { code: 'TIRED', label: '疲惫', face: '🥱', varName: '--e-tired', valence: -0.3, intensity: 0.4 },
  { code: 'EXPECT', label: '期待', face: '🤩', varName: '--e-expect', valence: 0.6, intensity: 0.6 },
  { code: 'BORED', label: '无聊', face: '😐', varName: '--e-bored', valence: -0.05, intensity: 0.2 },
]

const BY_CODE = new Map(EMOTIONS.map(e => [e.code, e]))
/** 情绪轨迹里存的是中文标签（EmotionAgent 输出），色源按标签回查 */
const BY_LABEL = new Map(EMOTIONS.map(e => [e.label, e]))

export function emotionByCode(code?: string | null): EmotionMeta | undefined {
  return code ? BY_CODE.get(code.toUpperCase()) : undefined
}

export function emotionOf(label?: string | null, code?: string | null): EmotionMeta {
  return (code && BY_CODE.get(code.toUpperCase())) || (label && BY_LABEL.get(label)) || BY_LABEL.get('麻木')!
}
