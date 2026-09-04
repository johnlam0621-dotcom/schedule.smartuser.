import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import './styles.css'

// 前端应用入口：创建 Vue 实例，挂载根组件，并注入路由能力。
// 所有页面都通过 App.vue 的 RouterView 渲染，接口调用统一走 src/api/http.js。
createApp(App).use(router).mount('#app')
