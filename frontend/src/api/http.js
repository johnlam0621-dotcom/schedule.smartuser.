import axios from 'axios'
import { logError, logInfo } from '../utils/logger'

// API 客户端统一配置：所有前端接口都从这里带 token、处理响应和记录日志。
const http = axios.create({
  baseURL: '/api',
  timeout: 120000
})

http.interceptors.request.use((config) => {
  // 请求前自动读取登录 token，并写入 Authorization 头。
  const token = localStorage.getItem('schedule_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  config.metadata = { startAt: Date.now() }
  logInfo('HTTP', 'Request started', {
    method: (config.method || 'get').toUpperCase(),
    url: config.url,
    params: config.params || {}
  })
  return config
})

http.interceptors.response.use(
  (response) => {
    // 后端统一返回 ApiResponse；这里拆出 data，让页面只关心业务数据。
    const cost = Date.now() - (response.config.metadata?.startAt || Date.now())
    logInfo('HTTP', 'Request completed', {
      method: (response.config.method || 'get').toUpperCase(),
      url: response.config.url,
      status: response.status,
      cost
    })
    const body = response.data
    if (body && body.success === false) {
      return Promise.reject(new Error(body.message || 'Request failed'))
    }
    if (body && Object.prototype.hasOwnProperty.call(body, 'data')) {
      return body.data
    }
    return body
  },
  (error) => {
    // 401 表示登录态失效，清空本地缓存后跳回登录页。
    const config = error.config || {}
    const cost = Date.now() - (config.metadata?.startAt || Date.now())
    const message = error.response?.data?.message || error.message || 'Request failed'
    logError('HTTP', 'Request failed', {
      method: (config.method || 'get').toUpperCase(),
      url: config.url,
      status: error.response?.status,
      cost,
      message
    })
    if (error.response?.status === 401) {
      localStorage.removeItem('schedule_token')
      localStorage.removeItem('schedule_user')
      localStorage.removeItem('schedule_menus')
      localStorage.removeItem('schedule_permissions')
      if (location.pathname !== '/login') {
        location.href = '/login'
      }
    }
    return Promise.reject(new Error(message))
  }
)

export default http
