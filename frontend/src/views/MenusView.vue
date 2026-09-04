<template>
  <main class="page">
    <section class="panel">
      <h2>Menu Management</h2>
      <form class="form-grid" @submit.prevent="save">
        <label>Name<input v-model.trim="form.name" required /></label>
        <label>Path<input v-model.trim="form.path" required /></label>
        <label>Component<input v-model.trim="form.component" /></label>
        <label>Permission<input v-model.trim="form.permissionCode" /></label>
        <label>Sort order<input v-model.number="form.sortOrder" type="number" /></label>
        <label>
          Visible
          <select v-model.number="form.visible">
            <option :value="1">Visible</option>
            <option :value="0">Hidden</option>
          </select>
        </label>
        <button>{{ form.id ? 'Update menu' : 'Add menu' }}</button>
        <button type="button" class="secondary" @click="resetForm">Clear</button>
      </form>
      <div class="status" :class="{ error: hasError }">{{ status }}</div>
    </section>

    <section class="panel">
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>ID</th>
              <th>Name</th>
              <th>Path</th>
              <th>Component</th>
              <th>Permission</th>
              <th>Sort</th>
              <th>Visible</th>
              <th>Action</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="menu in menus" :key="menu.id">
              <td>{{ menu.id }}</td>
              <td>{{ menu.name }}</td>
              <td>{{ menu.path }}</td>
              <td>{{ menu.component }}</td>
              <td>{{ menu.permissionCode }}</td>
              <td>{{ menu.sortOrder }}</td>
              <td>{{ menu.visible === 1 ? 'Yes' : 'No' }}</td>
              <td><button class="secondary" @click="edit(menu)">Edit</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </main>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import http from '../api/http'

const menus = ref([])
const status = ref('')
const hasError = ref(false)
const form = reactive(blank())

onMounted(load)

function blank() {
  // 新菜单默认挂在根级、排序 100、可见，前端只维护当前系统需要的导航字段。
  return { id: null, parentId: 0, name: '', path: '', component: '', permissionCode: '', sortOrder: 100, visible: 1 }
}

async function load() {
  // 加载菜单配置，主布局会根据这些菜单渲染左侧导航。
  try {
    menus.value = await http.get('/system/menus')
  } catch (error) {
    hasError.value = true
    status.value = error.message
  }
}

function edit(menu) {
  // 点击编辑后把当前行数据复制到表单，提交时按 id 更新。
  Object.assign(form, menu)
}

function resetForm() {
  // 清空表单并回到新增菜单状态。
  Object.assign(form, blank())
  hasError.value = false
  status.value = ''
}

async function save() {
  // id 存在时更新菜单，否则创建新菜单；保存后刷新列表保证页面与数据库一致。
  hasError.value = false
  try {
    if (form.id) {
      await http.put(`/system/menus/${form.id}`, form)
    } else {
      await http.post('/system/menus', form)
    }
    status.value = 'Saved.'
    Object.assign(form, blank())
    await load()
  } catch (error) {
    hasError.value = true
    status.value = error.message
  }
}
</script>
