export type AdminUser = {
  id: string
  name: string
  email: string
  role: 'ADMIN' | 'DISPATCHER' | 'MANAGER' | string
  active: boolean
  createdAt: string
}

export type AdminLoginResponse = {
  token: string
  tokenType: string
  expiresInMs: number
  user: AdminUser
}
