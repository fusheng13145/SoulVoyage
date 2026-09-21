<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { CRISIS_FALLBACK, crisisResources, type CrisisResource } from '@/api/app'
import { applyTheme, useTheme } from '@/composables/theme'
import { toast } from '@/utils/feedback'

/** 求助资源：这一页不做登录守卫，也不假设网络一定在——
 *  接口挂了就用兜底号码，人不能对着一片空白离开。 */
const { themeClass } = useTheme()
const boundary = ref(CRISIS_FALLBACK.boundary)
const list = ref<CrisisResource[]>(CRISIS_FALLBACK.resources)
const offline = ref(false)

onLoad(() => {
  applyTheme()
  void load()
})

async function load() {
  try {
    const r = await crisisResources()
    boundary.value = r.boundary || CRISIS_FALLBACK.boundary
    if (r.resources?.length) list.value = r.resources
    offline.value = false
  } catch {
    offline.value = true // 兜底不是失败提示，是"至少这几个号码还能打"
  }
}

function call(value: string) {
  const num = value.replace(/[^\d+]/g, '')
  if (!num) {
    toast('这一条不是电话，长按复制即可')
    return
  }
  uni.makePhoneCall({ phoneNumber: num, fail: () => copy(num) })
}

function copy(value: string) {
  uni.setClipboardData({ data: value, success: () => toast('已复制') })
}
</script>

<template>
  <view class="sv-page resources" :class="themeClass">
    <text class="sv-h1">需要立刻有人接住</text>
    <text class="sv-muted">{{ boundary }}</text>
    <text v-if="offline" class="sv-cap hint">
      网络这会儿不配合，下面这几个是常年有效的那几个号，先打出去。
    </text>

    <view v-for="r in list" :key="r.value" class="sv-surface item">
      <view class="item-text">
        <text class="name">{{ r.name }}</text>
        <text class="value">{{ r.value }}</text>
        <text v-if="r.note" class="sv-cap">{{ r.note }}</text>
      </view>
      <view class="item-acts">
        <button class="sv-btn call" @tap="call(r.value)">拨打</button>
        <text class="link" @tap="copy(r.value)">复制</text>
      </view>
    </view>

    <view class="sv-surface tips">
      <text class="sv-h2">如果你现在很危险</text>
      <text class="sv-muted">先打 120 或 110，或者离让你害怕的人和环境远一点。</text>
      <text class="sv-muted">心屿里的记录不能代替医生，也不会有人替你做决定——它只是把你的感受接住。</text>
    </view>
    <text class="sv-cap hint">这一页不需要登录也能打开。把号码存到通讯录里，比存在这里更靠得住。</text>
  </view>
</template>

<style scoped>
.resources {
  padding-top: calc(var(--sv-s4) + var(--sv-safe-t));
  display: flex;
  flex-direction: column;
  gap: var(--sv-s3);
}
.sv-h1 {
  margin-bottom: var(--sv-s1);
}
.item {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: var(--sv-s3);
}
.item-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
}
.name {
  font-size: var(--sv-fs-subhead);
  font-weight: 600;
}
.value {
  font-size: var(--sv-fs-title3);
  font-weight: 700;
  letter-spacing: 0.5px;
}
.item-acts {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--sv-s1);
}
.call {
  width: 88px;
  min-height: 40px;
  padding: 8px 0;
}
.link {
  color: var(--sv-indigo);
  font-size: var(--sv-fs-caption1);
}
.tips {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s2);
}
.hint {
  line-height: 1.5;
}
</style>
