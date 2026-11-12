import { describe, expect, it } from 'vitest'
import { EMOTIONS, emotionByCode, emotionOf } from './emotions'

describe('小程序 utils/emotions', () => {
  it('16 情绪闭集且 code 唯一', () => {
    expect(EMOTIONS).toHaveLength(16)
    expect(new Set(EMOTIONS.map(e => e.code)).size).toBe(16)
  })

  it('emotionByCode 大小写不敏感，未收录返回 undefined', () => {
    expect(emotionByCode('joy')?.label).toBe('喜悦')
    expect(emotionByCode('JOY')?.code).toBe('JOY')
    expect(emotionByCode('NOPE')).toBeUndefined()
    expect(emotionByCode(null)).toBeUndefined()
  })

  it('emotionOf 优先 code，其次中文标签，兜底麻木', () => {
    expect(emotionOf('悲伤', 'JOY').code).toBe('JOY')
    expect(emotionOf('焦虑').code).toBe('ANXIETY')
    expect(emotionOf(null, null).code).toBe('NUMB')
  })
})
