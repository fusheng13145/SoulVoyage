import { describe, expect, it } from 'vitest'

import { EMOTION_BY_LABEL, EMOTIONS, emotionVar, valenceColor } from './emotions'

describe('utils/emotions 情绪谱', () => {
  it('恰好 16 条，code 与 varName 均唯一', () => {
    expect(EMOTIONS).toHaveLength(16)
    expect(new Set(EMOTIONS.map(e => e.code)).size).toBe(16)
    expect(new Set(EMOTIONS.map(e => e.varName)).size).toBe(16)
  })

  it('varName 全部落在 --e-* 色源变量命名空间内', () => {
    for (const e of EMOTIONS) expect(e.varName).toMatch(/^--e-[a-z]+$/)
  })

  it('valence 落在 [-1,1]、intensity 落在 [0,1]', () => {
    for (const e of EMOTIONS) {
      expect(e.valence).toBeGreaterThanOrEqual(-1)
      expect(e.valence).toBeLessThanOrEqual(1)
      expect(e.intensity).toBeGreaterThanOrEqual(0)
      expect(e.intensity).toBeLessThanOrEqual(1)
    }
  })

  it('EMOTION_BY_LABEL 以中文 label 为键，覆盖全部 16 条', () => {
    expect(Object.keys(EMOTION_BY_LABEL)).toHaveLength(16)
    expect(EMOTION_BY_LABEL['喜悦']?.code).toBe('JOY')
    expect(EMOTION_BY_LABEL['麻木']?.varName).toBe('--e-numb')
  })
})

describe('emotionVar 标签到色源的映射', () => {
  it('已知中文情绪返回其专属色源', () => {
    expect(emotionVar('喜悦')).toBe('--e-joy')
    expect(emotionVar('焦虑')).toBe('--e-anx')
  })

  it('未知或空标签统一兜底到 --e-numb', () => {
    expect(emotionVar('不存在的情绪')).toBe('--e-numb')
    expect(emotionVar('')).toBe('--e-numb')
  })

  it('英文 code 不命中：口径为后端输出的中文 label', () => {
    expect(emotionVar('JOY')).toBe('--e-numb')
  })
})

describe('valenceColor 效价色阶', () => {
  const MINT = 'var(--sv-mint)'
  const MINT_SOFT = 'color-mix(in srgb, var(--sv-mint) 42%, var(--sv-card))'
  const NEUTRAL = 'color-mix(in srgb, var(--e-joy) 55%, var(--sv-card))'
  const RED = 'color-mix(in srgb, var(--sv-red) 62%, var(--sv-card))'

  it('v > 0.3 用实心 mint', () => {
    expect(valenceColor(0.31)).toBe(MINT)
    expect(valenceColor(1)).toBe(MINT)
  })

  it('0 < v <= 0.3 用 mint 42% 混色', () => {
    expect(valenceColor(0.3)).toBe(MINT_SOFT)
    expect(valenceColor(0.01)).toBe(MINT_SOFT)
  })

  it('-0.3 < v <= 0 用中性带混色', () => {
    expect(valenceColor(0)).toBe(NEUTRAL)
    expect(valenceColor(-0.29)).toBe(NEUTRAL)
  })

  it('v <= -0.3 用红色警示混色（-0.3 本身即入红档）', () => {
    expect(valenceColor(-0.3)).toBe(RED)
    expect(valenceColor(-0.31)).toBe(RED)
    expect(valenceColor(-1)).toBe(RED)
  })
})
