/* 16 情绪谱：打卡盘 / 日历热力 / 曲线 / 标签共用同一色源（DS2） */
export interface EmotionMeta {
  code: string
  label: string
  face: string
  varName: string
  /** 效价/强度默认锚点：打卡写回情绪轨迹时派生用 */
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
  { code: 'NUMB', label: '麻木', face: '😶‍🌫️', varName: '--e-numb', valence: -0.2, intensity: 0.25 },
  { code: 'GRIEVANCE', label: '委屈', face: '🥺', varName: '--e-griev', valence: -0.5, intensity: 0.55 },
  { code: 'PRESSURE', label: '压力', face: '😮‍💨', varName: '--e-pressure', valence: -0.4, intensity: 0.7 },
  { code: 'GRATITUDE', label: '感恩', face: '🙏', varName: '--e-grati', valence: 0.7, intensity: 0.45 },
  { code: 'CONFUSED', label: '困惑', face: '🤔', varName: '--e-confuse', valence: -0.15, intensity: 0.4 },
  { code: 'TIRED', label: '疲惫', face: '🥱', varName: '--e-tired', valence: -0.3, intensity: 0.4 },
  { code: 'EXPECT', label: '期待', face: '🤩', varName: '--e-expect', valence: 0.6, intensity: 0.6 },
  { code: 'BORED', label: '无聊', face: '😐', varName: '--e-bored', valence: -0.05, intensity: 0.2 },
]

export const EMOTION_BY_LABEL: Record<string, EmotionMeta> = Object.fromEntries(
  EMOTIONS.map(e => [e.label, e]),
)

/** 后端情绪词（EmotionAgent 输出）到色源的兜底映射 */
export function emotionVar(label: string): string {
  return EMOTION_BY_LABEL[label]?.varName ?? '--e-numb'
}

/** 效价 → 热力色阶（日历热力条/月历共用，随暗色自适应） */
export function valenceColor(v: number): string {
  if (v > 0.3) return 'var(--sv-mint)'
  if (v > 0) return 'color-mix(in srgb, var(--sv-mint) 42%, var(--sv-card))'
  if (v > -0.3) return 'color-mix(in srgb, var(--e-joy) 55%, var(--sv-card))'
  return 'color-mix(in srgb, var(--sv-red) 62%, var(--sv-card))'
}
