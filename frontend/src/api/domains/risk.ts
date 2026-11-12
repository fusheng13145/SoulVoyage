import http, { type ApiResp } from '@/api/http'

export interface RefResource {
  name: string
  value: string
  type: string
  note: string
}

export interface Referral {
  boundary: string
  resources: RefResource[]
}

export const riskApi = {
  resources: () => http.get<ApiResp<Referral>>('/risk/resources').then(r => r.data.data),
}
