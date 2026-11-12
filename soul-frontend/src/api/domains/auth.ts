import http, { type ApiResp } from '@/api/http'

export interface Me {
  userId: number
  username: string
  nickname: string
  role: string
  status: number
  deletionRequestedAt: string
}

export interface TokenResp {
  accessToken: string
  refreshToken: string
  userId: number
  nickname: string
  role: string
  deletionPending: boolean
}

export const authApi = {
  login: (username: string, password: string) =>
    http.post<ApiResp<TokenResp>>('/auth/login', { username, password }).then(r => r.data.data),
  register: (username: string, password: string, nickname: string) =>
    http.post<ApiResp<TokenResp>>('/auth/register', { username, password, nickname }).then(r => r.data.data),
  me: () => http.get<ApiResp<Me>>('/auth/me').then(r => r.data.data),
  logout: () => http.post('/auth/logout', {}).then(() => undefined),
}
