import { createRouter, createWebHistory } from 'vue-router'
import { authState } from '../store/auth'
import LoginView from '../views/LoginView.vue'
import MainLayout from '../views/MainLayout.vue'
import DashboardHome from '../views/DashboardHome.vue'
import UsersView from '../views/UsersView.vue'
import MenusView from '../views/MenusView.vue'
import PermissionsView from '../views/PermissionsView.vue'
import ImportView from '../views/ImportView.vue'
import RoutesMapView from '../views/RoutesMapView.vue'
import SearchView from '../views/SearchView.vue'
import PayrollView from '../views/PayrollView.vue'
import BookView from '../views/BookView.vue'
import InspectorWorkspaceView from '../views/InspectorWorkspaceView.vue'
import InspectorPhotosView from '../views/InspectorPhotosView.vue'
import InspectionConfirmationView from '../views/InspectionConfirmationView.vue'
import { logInfo } from '../utils/logger'

// 路由表集中定义页面入口，菜单权限由后端登录返回的数据控制显示。
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView },
    {
      path: '/',
      component: MainLayout,
      redirect: '/dashboard',
      children: [
        { path: 'dashboard', component: DashboardHome },
        { path: 'system/users', component: UsersView },
        { path: 'system/menus', component: MenusView, meta: { adminOnly: true } },
        { path: 'system/permissions', component: PermissionsView, meta: { adminOnly: true } },
        { path: 'inspections/import', component: ImportView },
        { path: 'inspections/search', component: SearchView },
        { path: 'payroll', component: PayrollView },
        { path: 'routes/map', component: RoutesMapView },
        { path: 'routes/book', component: BookView, meta: { permission: 'route:book' } },
        { path: 'inspector', component: InspectorWorkspaceView, meta: { permission: 'inspector:workspace' } },
        { path: 'inspector/photos', component: InspectorPhotosView, meta: { managerOnly: true } },
        { path: 'inspections/confirm', component: InspectionConfirmationView, meta: { managerOnly: true } }
      ]
    }
  ]
})

router.beforeEach((to) => {
  // 全局路由守卫：未登录只能访问登录页，已登录访问登录页时自动回到仪表盘。
  logInfo('Router', 'Validating route navigation', { path: to.path, hasToken: Boolean(authState.token) })
  if (to.path !== '/login' && !authState.token) {
    return '/login'
  }
  if (to.path === '/login' && authState.token) {
    if (authState.user?.roleCode === 'inspector') return '/inspector'
    return ['scheduler', 'sales'].includes(authState.user?.roleCode) ? '/inspections/search' : '/dashboard'
  }
  if (to.meta.adminOnly && authState.user?.roleCode !== 'admin') {
    return ['scheduler', 'sales'].includes(authState.user?.roleCode) ? '/inspections/search' : '/dashboard'
  }
  if (to.meta.managerOnly && !['admin', 'manager'].includes(authState.user?.roleCode)) {
    return ['scheduler', 'sales'].includes(authState.user?.roleCode) ? '/inspections/search' : '/dashboard'
  }
  if (authState.user?.roleCode === 'scheduler' &&
      !['/inspections/search', '/routes/book', '/inspector'].includes(to.path)) {
    return '/inspections/search'
  }
  if (authState.user?.roleCode === 'sales' &&
      !['/inspections/search', '/routes/book'].includes(to.path)) {
    return '/inspections/search'
  }
  if (authState.user?.roleCode === 'inspector' && to.path !== '/inspector') {
    return '/inspector'
  }
  const schedulerInspectorWorkspace = authState.user?.roleCode === 'scheduler' && to.path === '/inspector'
  if (to.meta.permission && !authState.permissions.includes(to.meta.permission) && !schedulerInspectorWorkspace) {
    return '/dashboard'
  }
  return true
})

export default router
