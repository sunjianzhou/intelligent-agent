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
  background: var(--gradient-brand);
}
.login-card {
  background: var(--color-surface); border-radius: var(--radius-lg); padding: var(--space-7) 36px;
  width: 360px; box-shadow: var(--shadow-overlay);
  display: flex; flex-direction: column; gap: var(--space-4);
}
.login-logo {
  display: flex; flex-direction: column; align-items: center; gap: var(--space-2-5); margin-bottom: var(--space-2);
}
.login-logo i  { font-size: 2.5rem; color: var(--color-primary); }
.login-logo h1 {
  font-size: var(--text-2xl); color: var(--color-text); font-weight: var(--weight-bold); margin: 0;
  letter-spacing: var(--tracking-wider); text-indent: 0.08em; /* 抵消字距造成的视觉偏移，保持居中 */
}
.form-group { display: flex; flex-direction: column; gap: var(--space-1-5); }
.form-group label { font-size: var(--text-sm); color: var(--color-text); font-weight: var(--weight-medium); }
.form-group input {
  padding: var(--space-2-5) var(--space-3-5); border: 1px solid var(--color-border); border-radius: var(--radius-sm);
  font-size: var(--text-base); outline: none; transition: border-color var(--duration-base);
}
.form-group input:focus { border-color: var(--color-primary); }
.form-group input:disabled { background: var(--color-surface-sunken); }
.error-tip {
  background: var(--color-danger-soft); color: var(--color-danger); padding: var(--space-2-5) var(--space-3);
  border-radius: var(--radius-sm); font-size: var(--text-sm); display: flex; gap: var(--space-2); align-items: center;
}
.login-btn {
  padding: var(--space-3); border-radius: var(--radius-sm); border: none;
  background: var(--color-primary); color: var(--color-text-on-primary); font-size: var(--text-md); font-weight: var(--weight-medium);
  cursor: pointer; transition: background var(--duration-base); display: flex; align-items: center; justify-content: center; gap: var(--space-2);
  margin-top: var(--space-1);
}
.login-btn:hover:not(:disabled) { background: var(--color-primary-hover); }
.login-btn:disabled { opacity: 0.6; cursor: not-allowed; }
</style>
