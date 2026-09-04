<template>
  <main class="page">
    <section class="panel">
      <h2>Permission Management</h2>
      <form class="form-grid" @submit.prevent="save">
        <label>Code<input v-model.trim="form.code" placeholder="route:map" required /></label>
        <label>Name<input v-model.trim="form.name" required /></label>
        <label>Description<input v-model.trim="form.description" /></label>
        <button>{{ form.id ? 'Update permission' : 'Add permission' }}</button>
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
              <th>Code</th>
              <th>Name</th>
              <th>Description</th>
              <th>Action</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="permission in permissions" :key="permission.id">
              <td>{{ permission.id }}</td>
              <td>{{ permission.code }}</td>
              <td>{{ permission.name }}</td>
              <td>{{ permission.description }}</td>
              <td><button class="secondary" @click="edit(permission)">Edit</button></td>
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

const permissions = ref([])
const status = ref('')
const hasError = ref(false)
const form = reactive(blank())

onMounted(load)

function blank() {
  // 权限表单只维护权限编码、名称和说明，角色授权关系由后端数据表保存。
  return { id: null, code: '', name: '', description: '' }
}

async function load() {
  // 加载权限编码列表，登录后端会根据角色返回对应权限编码给前端。
  try {
    permissions.value = await http.get('/system/permissions')
  } catch (error) {
    hasError.value = true
    status.value = error.message
  }
}

function edit(permission) {
  // 点击编辑后把当前权限复制到表单，提交时按 id 更新。
  Object.assign(form, permission)
}

function resetForm() {
  // 清空表单并回到新增权限状态。
  Object.assign(form, blank())
  hasError.value = false
  status.value = ''
}

async function save() {
  // id 存在时更新权限，否则创建权限；保存后刷新列表。
  hasError.value = false
  try {
    if (form.id) {
      await http.put(`/system/permissions/${form.id}`, form)
    } else {
      await http.post('/system/permissions', form)
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
