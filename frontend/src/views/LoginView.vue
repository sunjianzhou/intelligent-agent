<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-logo">
        <i class="fas fa-robot" />
        <h1>智能体</h1>
      </div>

      <div class="form-group">
        <label>用户名</label>
        <input v-model="form.username" placeholder="请输入用户名"
               @keyup.enter="login" :disabled="loading" />
      </div>
      <div class="form-group">
        <label>密码</label>
        <input v-model="form.password" type="password" placeholder="请输入密码"
               @keyup.enter="login" :disabled="loading" />
      </div>

      <div v-if="error" class="error-tip">
        <i class="fas fa-exclamation-circle" /> {{ error }}
      </div>

      <button class="login-btn" :disabled="loading || !form.username || !form.password"
              @click="login">
        <i v-if="loading" class="fas fa-circle-notch fa-spin" />
        {{ loading ? '登录中...' : '登 录' }}
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router   = useRouter()
const authStore = useAuthStore()

const form    = ref({ username: '', password: '' })
const loading = ref(false)
const error   = ref('')

const login = async () => {
  if (!form.value.username || !form.value.password) return
  loading.value = true
  error.value   = ''
  try {
    const ok = await authStore.login(form.value.username, form.value.password)
    if (ok) {
      router.push('/chat')
    } else {
      error.value = authStore.error || '用户名或密码错误'
    }
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh; display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}
.login-card {
  background: white; border-radius: 16px; padding: 40px 36px;
  width: 360px; box-shadow: 0 20px 60px rgba(0,0,0,0.15);
  display: flex; flex-direction: column; gap: 18px;
}
.login-logo {
  display: flex; flex-direction: column; align-items: center; gap: 10px; margin-bottom: 8px;
}
.login-logo i  { font-size: 2.5rem; color: #667eea; }
.login-logo h1 { font-size: 1.5rem; color: #333; font-weight: 600; margin: 0; }
.form-group { display: flex; flex-direction: column; gap: 6px; }
.form-group label { font-size: 0.88rem; color: #555; font-weight: 500; }
.form-group input {
  padding: 10px 14px; border: 1px solid #e0e3e8; border-radius: 8px;
  font-size: 0.95rem; outline: none; transition: border-color 0.2s;
}
.form-group input:focus { border-color: #667eea; }
.form-group input:disabled { background: #f8f9fa; }
.error-tip {
  background: #fce4e4; color: #c62828; padding: 10px 12px;
  border-radius: 8px; font-size: 0.85rem; display: flex; gap: 8px; align-items: center;
}
.login-btn {
  padding: 12px; border-radius: 8px; border: none;
  background: #667eea; color: white; font-size: 1rem; font-weight: 500;
  cursor: pointer; transition: background 0.2s; display: flex; align-items: center; justify-content: center; gap: 8px;
  margin-top: 4px;
}
.login-btn:hover:not(:disabled) { background: #5a6fd6; }
.login-btn:disabled { opacity: 0.6; cursor: not-allowed; }
</style>