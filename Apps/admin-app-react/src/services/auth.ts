import { api } from '@/lib/api'
import type { AdminLoginResponse } from '../types/auth'

type LoginPayload = {
  email: string
  password: string
}

export async function loginAdmin(payload: LoginPayload): Promise<AdminLoginResponse> {
  const { data } = await api.post<AdminLoginResponse>('/auth/admin/login', payload)
  return data
}
