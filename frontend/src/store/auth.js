import { reactive } from 'vue'
import http from '../api/http'
import { logError, logInfo } from '../utils/logger'

// 登录态集中存储：内存响应式状态与 localStorage 保持同步。
export const authState = reactive({
  token: localStorage.getItem('schedule_token') || '',
  user: readJson('schedule_user', null),
  menus: readJson('schedule_menus', []),
  permissions: readJson('schedule_permissions', [])
})

export function setSession(data) {
  // 登录或刷新会话成功后，把 token、用户、菜单、权限同时写入响应式状态和 localStorage。
  logInfo('AuthStore', 'Saving login session', { user: data.user?.username, menus: data.menus?.length || 0 })
  if (data.token) {
    authState.token = data.token
    localStorage.setItem('schedule_token', data.token)
  }
  authState.user = data.user
  authState.menus = data.menus || []
  authState.permissions = data.permissions || []
  localStorage.setItem('schedule_user', JSON.stringify(authState.user))
  localStorage.setItem('schedule_menus', JSON.stringify(authState.menus))
  localStorage.setItem('schedule_permissions', JSON.stringify(authState.permissions))
}

export async function refreshSession() {
  // 根据已有 token 向后端确认当前用户，并刷新菜单/权限缓存。
  logInfo('AuthStore', 'Refreshing current user session')
  const data = await http.get('/auth/me')
  setSession(data)
  return data
}

export function clearSession() {
  // 退出登录或 401 失效时清空所有认证缓存，避免旧权限继续影响页面。
  logInfo('AuthStore', 'Clearing current user session')
  authState.token = ''
  authState.user = null
  authState.menus = []
  authState.permissions = []
  localStorage.removeItem('schedule_token')
  localStorage.removeItem('schedule_user')
  localStorage.removeItem('schedule_menus')
  localStorage.removeItem('schedule_permissions')
}

function readJson(key, fallback) {
  // localStorage 中的菜单/权限是 JSON 字符串，解析失败时回退默认值并输出错误日志。
  try {
    const value = localStorage.getItem(key)
    return value ? JSON.parse(value) : fallback
  } catch (error) {
    logError('AuthStore', 'Failed to read local cache', { key, message: error.message })
    return fallback
  }
}
