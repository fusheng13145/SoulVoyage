/** 端侧轻提示与确认：小程序没有 DOM，toast/modal 只能走 uni 的这两套原生口。
 *  后端回给页面的中文话术原样展示，端侧不再自己编文案（口径与 Web 端 toast 一致）。 */
export function toast(title: string, icon: 'none' | 'success' | 'error' = 'none') {
  uni.showToast({ title, icon, duration: 2200 })
}

export function confirmSheet(title: string, content: string): Promise<boolean> {
  return new Promise(resolve => {
    uni.showModal({
      title,
      content,
      confirmText: '确定',
      cancelText: '再想想',
      success: res => resolve(!!res.confirm),
      fail: () => resolve(false),
    })
  })
}

/** 请求在飞的显式反馈：小程序没有浏览器那圈标签页 loading，靠这里统一收口 */
let pending = 0
export function busy(on: boolean) {
  pending += on ? 1 : -1
  if (pending > 0) uni.showLoading({ title: '稍等…', mask: false })
  else {
    pending = 0
    uni.hideLoading()
  }
}
