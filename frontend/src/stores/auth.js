import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

const TOKEN_KEY = 'agent_token'
const USER_KEY  = 'agent_user'

export const useAuthStore = defineStore('auth', () => {
  const token    = ref(localStorage.getItem(TOKEN_KEY) || '')
  const username = ref(localStorage.getItem(USER_KEY)  || '')
  const error    = ref('')

  const isLoggedIn = computed(() => !!token.value)

  const login = async (user, pass) => {
    error.value = ''
    try {
      const res = await fetch('/api/auth/login', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ username: user, password: pass }),
      })
      const data = await res.json()
      if (data.success && data.token) {
        token.value    = data.token
        username.value = data.username || user
        localStorage.setItem(TOKEN_KEY, data.token)
        localStorage.setItem(USER_KEY,  username.value)
        return true
      }
      error.value = data.message || '登录失败'
      return false
    } catch (e) {
      error.value = '网络错误，请检查后端是否运行'
      return false
    }
  }

  const logout = () => {
    token.value    = ''
    username.value = ''
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }

  const refreshToken = (newToken) => {
    token.value = newToken
    localStorage.setItem(TOKEN_KEY, newToken)
  }

  return { token, username, isLoggedIn, error, login, logout, refreshToken }
})