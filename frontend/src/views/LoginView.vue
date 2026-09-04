<template>
  <main class="login-page">
    <section class="login-panel">
      <div class="login-brand">
        <img src="/smartuser-logo.png" alt="SmartUser" />
        <span>Inspection scheduling</span>
      </div>
      <h1>Schedule inspections smarter</h1>
      <p class="login-intro">Plan routes, manage inspectors and book the right time—all in one place.</p>
      <form class="stack" @submit.prevent="login">
        <label>
          Username
          <input v-model.trim="form.username" autocomplete="username" placeholder="Enter username" />
        </label>
        <label>
          Password
          <span class="password-field">
            <input
              v-model="form.password"
              :type="showPassword ? 'text' : 'password'"
              autocomplete="current-password"
              placeholder="Enter password"
            />
            <button
              class="password-toggle"
              type="button"
              :aria-label="showPassword ? 'Hide password' : 'Show password'"
              :title="showPassword ? 'Hide password' : 'Show password'"
              @click="showPassword = !showPassword"
            >
              <svg v-if="!showPassword" viewBox="0 0 24 24" aria-hidden="true">
                <path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12Z" />
                <circle cx="12" cy="12" r="3" />
              </svg>
              <svg v-else viewBox="0 0 24 24" aria-hidden="true">
                <path d="m3 3 18 18M10.6 6.2A9.8 9.8 0 0 1 12 6c6.5 0 10 6 10 6a17 17 0 0 1-2.1 2.8M6.5 6.5C3.6 8.3 2 12 2 12s3.5 6 10 6c1.5 0 2.8-.3 4-.8M9.9 9.9a3 3 0 0 0 4.2 4.2" />
              </svg>
            </button>
          </span>
        </label>
        <button :disabled="loading">{{ loading ? 'Signing in...' : 'Login' }}</button>
        <button class="forgot-password-link" type="button" @click="forgotOpen = !forgotOpen">
          Forgot password?
        </button>
        <div v-if="forgotOpen" class="password-help" role="status">
          <strong>Reset your password</strong>
          <p>For account security, please ask the system administrator to reset your password.</p>
          <a :href="supportEmailLink">Email support</a>
        </div>
        <div class="error" v-if="error">{{ error }}</div>
      </form>
    </section>
    <p class="login-footer">© 2026 SmartUser · Schedule with confidence</p>
  </main>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import http from '../api/http'
import { setSession } from '../store/auth'
import { logError, logInfo } from '../utils/logger'

const router = useRouter()
const loading = ref(false)
const error = ref('')
const showPassword = ref(false)
const forgotOpen = ref(false)
const form = reactive({
  // 登录页不预填任何账号或密码，避免默认凭据泄漏及误提交。
  username: '',
  password: ''
})
const supportEmailLink = computed(() => {
  const username = form.username || 'Not entered'
  return `mailto:it@siliconlighting.com.au?subject=${encodeURIComponent('Schedule SmartUser password reset')}&body=${encodeURIComponent(`Please reset my Schedule SmartUser password.\n\nUsername: ${username}`)}`
})

async function login() {
  // 登录成功后保存 token、菜单、权限，并进入系统首页；失败时展示后端错误信息。
  error.value = ''
  loading.value = true
  logInfo('Login', 'Login started', { username: form.username })
  try {
    const data = await http.post('/auth/login', form)
    setSession(data)
    logInfo('Login', 'Login successful', { username: data.user?.username })
    router.push('/dashboard')
  } catch (err) {
    logError('Login', 'Login failed', err)
    error.value = err.message
  } finally {
    loading.value = false
  }
}
</script>
