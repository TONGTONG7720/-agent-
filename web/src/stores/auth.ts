import { defineStore } from 'pinia'
import { api } from '../api'
import { clearToken, getToken, setToken } from '../api/http'

const ROLE_KEY = 'magent_role'
const NAME_KEY = 'magent_username'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: getToken(),
    role: localStorage.getItem(ROLE_KEY) ?? '',
    username: localStorage.getItem(NAME_KEY) ?? ''
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
    isAdmin: (s) => s.role === 'admin'
  },
  actions: {
    async login(username: string, password: string) {
      const res = await api.login(username, password)
      this.token = res.token
      this.role = res.role
      this.username = res.username
      setToken(res.token)
      localStorage.setItem(ROLE_KEY, res.role)
      localStorage.setItem(NAME_KEY, res.username)
    },
    logout() {
      this.token = ''
      this.role = ''
      this.username = ''
      clearToken()
      localStorage.removeItem(ROLE_KEY)
      localStorage.removeItem(NAME_KEY)
    }
  }
})
