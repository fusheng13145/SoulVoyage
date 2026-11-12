import { reactive, ref } from 'vue'

/* ---- Toast / Dialog 全局服务（SvToast/SvDialog 宿主消费；消灭各页手写 error div） ---- */
export interface ToastItem {
  id: number
  msg: string
}
export interface DialogReq {
  title: string
  message?: string
  confirmText?: string
  cancelText?: string
  danger?: boolean
}
interface DialogState extends DialogReq {
  resolve: (ok: boolean) => void
}

let seq = 0
export const toastQueue = reactive<ToastItem[]>([])
export const dialogState = ref<DialogState | null>(null)

export function toast(msg: string) {
  const item = { id: ++seq, msg }
  toastQueue.push(item)
  window.setTimeout(() => {
    const i = toastQueue.findIndex(t => t.id === item.id)
    if (i >= 0) toastQueue.splice(i, 1)
  }, 2600)
}

/** 不可逆动作（导出/删除/注销）必须走 Dialog 确认 */
export function confirmDialog(req: DialogReq): Promise<boolean> {
  return new Promise(resolve => {
    dialogState.value = { ...req, resolve }
  })
}

export function resolveDialog(ok: boolean) {
  dialogState.value?.resolve(ok)
  dialogState.value = null
}

/* ---- 弹层深度：sheet/dialog 打开时底层画布 scale(0.96)+dim（DS4 视差） ---- */
export const overlayDepth = ref(0)

/* ---- 触感开关（M7 并入 user_preferences 后由服务端偏好驱动） ---- */
export const hapticOn = ref(localStorage.getItem('sv_haptic') !== 'off')
export function setHaptic(on: boolean) {
  hapticOn.value = on
  localStorage.setItem('sv_haptic', on ? 'on' : 'off')
}
