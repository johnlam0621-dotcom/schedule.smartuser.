<template>
  <!-- 主布局：左侧菜单由登录返回的菜单配置驱动，右侧显示当前路由页面。 -->
  <div class="app-shell" :class="{ 'is-sidebar-collapsed': sidebarCollapsed, 'is-mobile-menu-open': mobileMenuOpen, 'inspector-layout': isInspector }">
    <aside class="sidebar" aria-label="Main navigation">
      <div class="sidebar-head">
        <span class="brand-logo-frame">
          <img class="brand-logo" src="/smartuser-logo.png" alt="SmartUser" />
        </span>
      </div>
      <nav class="nav" @click="closeMobileMenu">
        <RouterLink v-for="menu in visibleMenus" :key="menu.path" :to="menu.path" :title="menu.name">
          <svg class="nav-icon" viewBox="0 0 24 24" aria-hidden="true">
            <path :d="menuIconPath(menu.path)" />
          </svg>
          <span class="nav-label">{{ menu.name }}</span>
        </RouterLink>
      </nav>
    </aside>
    <button v-if="mobileMenuOpen" class="mobile-menu-backdrop" type="button" aria-label="Close navigation" @click="closeMobileMenu"></button>
    <section class="content">
      <header class="topbar">
        <div class="topbar-context">
          <button class="sidebar-toggle" type="button" :title="mobileMenuOpen ? 'Close menu' : (sidebarCollapsed ? 'Expand menu' : 'Open menu')" :aria-expanded="mobileMenuOpen" aria-label="Toggle navigation" @click="toggleSidebar">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h16" /></svg>
          </button>
          <span class="topbar-home">Home</span>
          <span class="topbar-divider">/</span>
          <strong>{{ routeTitle }}</strong>
        </div>
        <div class="topbar-user">
          <span class="user-avatar" aria-hidden="true">{{ userInitials }}</span>
          <span class="user-name">{{ authState.user?.realName || authState.user?.username }}</span>
          <span class="role-badge">{{ authState.user?.roleCode || 'user' }}</span>
          <button class="topbar-logout" type="button" @click="logout">Logout</button>
        </div>
      </header>
      <RouterView />
    </section>

    <aside v-if="uploadQueueState.items.length" class="background-upload-panel" aria-live="polite">
      <header>
        <div>
          <strong>Background video uploads</strong>
          <span v-if="activeUploadCount">{{ activeUploadCount }} active · keep SmartUser open</span>
          <span v-else>All uploads finished</span>
        </div>
      </header>
      <div class="background-upload-list">
        <article v-for="item in uploadQueueState.items" :key="item.id" :class="`is-${item.status}`">
          <div class="background-upload-name">
            <strong :title="item.name">{{ item.name }}</strong>
            <span>{{ item.description }} · {{ formatUploadSize(item.size) }}</span>
          </div>
          <div v-if="item.status === 'queued'" class="background-upload-status">Waiting…</div>
          <div v-else-if="item.status === 'uploading'" class="background-upload-progress">
            <div><span :style="{ width: `${item.progress}%` }"></span></div>
            <strong>{{ item.progress }}%</strong>
          </div>
          <div v-else-if="item.status === 'complete'" class="background-upload-result success">
            <span>Uploaded</span>
            <button type="button" @click="dismissBackgroundUpload(item.id)">Dismiss</button>
          </div>
          <div v-else class="background-upload-result failed">
            <span :title="item.error">Upload failed</span>
            <button type="button" @click="retryBackgroundUpload(item.id)">Retry</button>
            <button type="button" @click="dismissBackgroundUpload(item.id)">Dismiss</button>
          </div>
        </article>
      </div>
      <p v-if="activeUploadCount">You can use other SmartUser pages. Do not close the browser until every upload says Uploaded.</p>
    </aside>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../api/http'
import { authState, clearSession } from '../store/auth'
import {
  activeUploadCount,
  dismissBackgroundUpload,
  retryBackgroundUpload,
  uploadQueueState
} from '../store/uploadQueue'

const route = useRoute()
const router = useRouter()
const sidebarCollapsed = ref(localStorage.getItem('schedule_sidebar_collapsed') === '1')
const mobileMenuOpen = ref(false)
const isInspector = computed(() => authState.user?.roleCode === 'inspector')
// 兜底菜单：当后端菜单接口不可用或本地缓存为空时，仍可进入核心页面调试。
const fallbackMenus = [
  { name: 'Dashboard', path: '/dashboard', visible: 1 },
  { name: 'User Management', path: '/system/users', visible: 1 },
  { name: 'Menu Management', path: '/system/menus', visible: 1 },
  { name: 'Permission Management', path: '/system/permissions', visible: 1 },
  { name: 'Inspection Import', path: '/inspections/import', visible: 1 },
  { name: 'Search', path: '/inspections/search', permissionCode: 'inspection:list', visible: 1 },
  { name: 'Payroll / Shifts', path: '/payroll', permissionCode: 'route:map', visible: 1 },
  { name: 'Routes map', path: '/routes/map', visible: 1 },
  { name: 'Book', path: '/routes/book', permissionCode: 'route:book', visible: 1 },
  { name: 'Inspector Photos', path: '/inspector/photos', visible: 1 },
  { name: 'Confirm Inspection', path: '/inspections/confirm', visible: 1 }
]

const visibleMenus = computed(() => {
  // 菜单 visible=0 时隐藏；未登录菜单缓存为空时使用 fallbackMenus。
  const menus = authState.menus?.length ? [...authState.menus] : [...fallbackMenus]
  if (!menus.some((menu) => menu.path === '/inspections/search')) {
    const routeMapIndex = menus.findIndex((menu) => menu.path === '/routes/map')
    menus.splice(routeMapIndex < 0 ? menus.length : routeMapIndex, 0, { name: 'Search', path: '/inspections/search', permissionCode: 'inspection:list', visible: 1 })
  }
  if (!menus.some((menu) => menu.path === '/payroll')) {
    const routeMapIndex = menus.findIndex((menu) => menu.path === '/routes/map')
    menus.splice(routeMapIndex < 0 ? menus.length : routeMapIndex, 0, { name: 'Payroll / Shifts', path: '/payroll', permissionCode: 'route:map', visible: 1 })
  }
  if (!menus.some((menu) => menu.path === '/routes/book')) {
    const routeMapIndex = menus.findIndex((menu) => menu.path === '/routes/map')
    menus.splice(routeMapIndex < 0 ? menus.length : routeMapIndex + 1, 0, { name: 'Book', path: '/routes/book', permissionCode: 'route:book', visible: 1 })
  }
  if (!menus.some((menu) => menu.path === '/inspector')) {
    menus.push({ name: 'My Route / Shift', path: '/inspector', permissionCode: 'inspector:workspace', visible: 1 })
  }
  if (!menus.some((menu) => menu.path === '/inspector/photos')) {
    menus.push({ name: 'Inspector Photos', path: '/inspector/photos', visible: 1 })
  }
  if (!menus.some((menu) => menu.path === '/inspections/confirm')) {
    menus.push({ name: 'Confirm Inspection', path: '/inspections/confirm', visible: 1 })
  }
  const permissions = new Set(authState.permissions || [])
  const permittedMenus = menus.filter((menu) => menu.visible !== 0 && (
    !menu.permissionCode || permissions.has(menu.permissionCode) ||
    (authState.user?.roleCode === 'scheduler' && menu.path === '/inspector')
  ))
  if (authState.user?.roleCode === 'inspector') {
    return [{ name: 'Inspector workspace', path: '/inspector', permissionCode: 'inspector:workspace', visible: 1 }]
  }
  if (['scheduler', 'sales'].includes(authState.user?.roleCode)) {
    const allowed = authState.user?.roleCode === 'scheduler'
      ? ['/inspections/search', '/routes/book', '/inspector']
      : ['/inspections/search', '/routes/book']
    return permittedMenus.filter((menu) => allowed.includes(menu.path))
  }
  if (!['admin', 'manager'].includes(authState.user?.roleCode)) {
    return permittedMenus.filter((menu) => !['/inspector/photos', '/inspections/confirm', '/system/menus', '/system/permissions'].includes(menu.path))
  }
  if (authState.user?.roleCode !== 'admin') {
    return permittedMenus.filter((menu) => !['/system/menus', '/system/permissions'].includes(menu.path))
  }
  return permittedMenus
})

const routeTitle = computed(() => visibleMenus.value.find((menu) => route.path === menu.path)?.name || 'Dashboard')
const userInitials = computed(() => {
  const name = authState.user?.realName || authState.user?.username || 'User'
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0]).join('').toUpperCase()
})

const menuIcons = {
  '/dashboard': 'M3 11.5 12 4l9 7.5M5 10v10h5v-6h4v6h5V10',
  '/system/users': 'M16 20v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 10a4 4 0 1 0 0-8 4 4 0 0 0 0 8M22 20v-2a4 4 0 0 0-3-3.87M16 2.13a4 4 0 0 1 0 7.75',
  '/system/menus': 'M4 5h16M4 12h16M4 19h16M8 3v4M14 10v4M10 17v4',
  '/system/permissions': 'M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10ZM9 12l2 2 4-4',
  '/inspections/import': 'M12 3v12M7 10l5 5 5-5M5 21h14',
  '/inspections/search': 'M11 19a8 8 0 1 1 0-16 8 8 0 0 1 0 16Zm10 2-4.35-4.35',
  '/payroll': 'M4 4h16v16H4zM4 9h16M9 4v16M14 13h3M14 17h3',
  '/routes/map': 'm3 6 6-3 6 3 6-3v15l-6 3-6-3-6 3V6Zm6-3v15m6-12v15',
  '/routes/book': 'M5 4h14a2 2 0 0 1 2 2v14H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2ZM8 2v4m8-4v4M3 10h18m-9 3v6m-3-3h6',
  '/inspector': 'M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20Zm0-13a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm-5 9a5 5 0 0 1 10 0',
  '/inspector/photos': 'M4 5h16v14H4zM8 10a2 2 0 1 0 0-4 2 2 0 0 0 0 4Zm-2 7 4-4 3 3 2-2 3 3',
  '/inspections/confirm': 'M4 4h16v16H4zM8 2v4m8-4v4M4 9h16m-11 6 2 2 4-5'
}

function menuIconPath(path) {
  return menuIcons[path] || 'M4 4h16v16H4z'
}

watch(sidebarCollapsed, (collapsed) => {
  localStorage.setItem('schedule_sidebar_collapsed', collapsed ? '1' : '0')
})

watch(() => route.path, () => {
  // 手机端切换页面后自动收起抽屉，避免菜单遮挡新页面。
  mobileMenuOpen.value = false
})

function toggleSidebar() {
  if (window.matchMedia('(max-width: 980px)').matches) {
    mobileMenuOpen.value = !mobileMenuOpen.value
    return
  }
  // 左侧菜单展开/收起状态保存在本地，刷新页面后保持一致。
  sidebarCollapsed.value = !sidebarCollapsed.value
}

function closeMobileMenu() {
  mobileMenuOpen.value = false
}

function formatUploadSize(bytes) {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`
}

function warnAboutActiveUploads(event) {
  if (!activeUploadCount.value) return
  event.preventDefault()
  event.returnValue = ''
}

onMounted(() => window.addEventListener('beforeunload', warnAboutActiveUploads))
onBeforeUnmount(() => window.removeEventListener('beforeunload', warnAboutActiveUploads))

async function logout() {
  // 退出登录先通知后端移除 token，再清理前端缓存并跳转登录页。
  try {
    await http.post('/auth/logout')
  } finally {
    clearSession()
    router.push('/login')
  }
}
</script>
