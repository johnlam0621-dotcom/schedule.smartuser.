<template>
  <main class="page">
    <section class="panel">
      <h2>User Management</h2>
      <form ref="userForm" class="form-grid" @submit.prevent="save">
        <label>Username<input v-model.trim="form.username" required /></label>
        <label>Real name<input v-model.trim="form.realName" required /></label>
        <label>
          Role
          <select v-model="form.roleCode">
            <option value="admin">admin</option>
            <option value="manager">manager</option>
            <option value="scheduler">scheduler</option>
            <option value="sales">sales</option>
            <option value="inspector">inspector</option>
            <option value="viewer">viewer</option>
          </select>
        </label>
        <label>
          Status
          <select v-model.number="form.status">
            <option :value="1">Enabled</option>
            <option :value="0">Disabled</option>
          </select>
        </label>
        <label>Password<input v-model="form.passwordHash" type="password" minlength="8" :required="!form.id" placeholder="Required for new users (8+ characters)" /></label>
        <button>{{ form.id ? 'Update user' : 'Add user' }}</button>
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
              <th>Username</th>
              <th>Real name</th>
              <th>Role</th>
              <th>Status</th>
              <th>Action</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="user in users" :key="user.id">
              <td>{{ user.id }}</td>
              <td>{{ user.username }}</td>
              <td>{{ user.realName }}</td>
              <td>{{ user.roleCode }}</td>
              <td>{{ user.status === 1 ? 'Enabled' : 'Disabled' }}</td>
              <td><button class="secondary" @click="edit(user)">Edit</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </main>
</template>

<script setup>
import { nextTick, onMounted, reactive, ref } from 'vue'
import http from '../api/http'

const users = ref([])
const status = ref('')
const hasError = ref(false)
const form = reactive(blank())
const userForm = ref(null)

onMounted(load)

function blank() {
  // 用户表单默认创建 scheduler 角色且启用，密码仅新增或重置时提交。
  return { id: null, username: '', realName: '', roleCode: 'scheduler', status: 1, passwordHash: '' }
}

async function load() {
  // 加载用户列表，后端已清空 passwordHash，前端不会展示密码信息。
  try {
    users.value = await http.get('/system/users')
  } catch (error) {
    hasError.value = true
    status.value = error.message
  }
}

async function edit(user) {
  // 编辑用户时不回填密码，避免误把空密码或旧哈希提交。
  Object.assign(form, user, { passwordHash: '' })
  status.value = `Editing user #${user.id}: ${user.username}`
  await nextTick()
  userForm.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  const usernameInput = userForm.value?.querySelector('input')
  usernameInput?.focus({ preventScroll: true })
  usernameInput?.select()
}

function resetForm() {
  // 清空查询/编辑状态，恢复新增用户表单。
  Object.assign(form, blank())
  hasError.value = false
  status.value = ''
}

async function save() {
  // 根据 form.id 判断新增或更新；未填写密码时不提交 passwordHash 字段。
  hasError.value = false
  status.value = ''
  const payload = { ...form }
  if (!payload.passwordHash) {
    delete payload.passwordHash
  }
  try {
    if (form.id) {
      await http.put(`/system/users/${form.id}`, payload)
    } else {
      await http.post('/system/users', payload)
    }
    status.value = 'Saved.'
    Object.assign(form, blank())
    await load()
  } catch (error) {
    // 后端会返回重复用户名、角色越权等明确提示；保留表单内容方便用户修正后重试。
    hasError.value = true
    status.value = error.message
  }
}
</script>
