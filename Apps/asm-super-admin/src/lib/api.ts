import axios from 'axios'

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/',
  withCredentials: true, // sends httpOnly cookie with every request
})

api.interceptors.response.use(
  (r) => r,
  (err) => {
    if (err.response?.status === 401) {
      window.location.href = '/sign-in'
    }
    return Promise.reject(err)
  }
)
