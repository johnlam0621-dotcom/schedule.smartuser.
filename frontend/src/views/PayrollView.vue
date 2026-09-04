<template>
  <main class="page payroll-page">
    <section class="panel payroll-controls">
      <h2>Shift overview</h2>
      <p class="muted">Weekly shifts are connected to the same inspection records shown on Routes Map.</p>
      <label>
        Week start
        <input v-model="weekStart" type="date" @change="normalizeWeekAndLoad" />
      </label>
      <label>
        Week number
        <input v-model.number="weekNumber" type="number" min="1" @change="changeWeekNumber" />
      </label>
      <button :disabled="loading" @click="loadPayroll">{{ loading ? 'Updating...' : 'Update shifts' }}</button>
      <button class="secondary" @click="openWeekRoutes">Show week on Routes Map</button>
      <div class="error" v-if="error">{{ error }}</div>
    </section>

    <section class="panel payroll-overview-panel">
      <div class="stats">
        <div class="stat"><strong>{{ records.length }}</strong><span class="muted">weekly inspections</span></div>
        <div class="stat"><strong>{{ rows.length }}</strong><span class="muted">inspector routes</span></div>
        <div class="stat"><strong>{{ totalShifts }}</strong><span class="muted">active shifts</span></div>
      </div>
      <h2>Payroll / shifts</h2>
      <div class="payroll-table-wrap">
        <table class="payroll-table">
          <thead>
            <tr>
              <th class="payroll-header">Week</th>
              <th class="payroll-header">{{ weekNumber }}</th>
              <th v-for="day in weekDays" :key="day" class="payroll-header">{{ dayName(day) }}<br>{{ dateLabel(day) }}</th>
              <th class="payroll-header">Total Shifts</th>
              <th class="payroll-header">Total Hours Worked</th>
            </tr>
            <tr>
              <th class="payroll-cyan" colspan="2">MAC Daily Total</th>
              <th v-for="day in weekDays" :key="`total-${day}`" class="payroll-cyan">{{ dailyTotal(day) }}</th>
              <th class="payroll-total">{{ totalShifts }}</th>
              <th class="payroll-total">{{ totalHours }}</th>
            </tr>
            <tr>
              <th class="payroll-side">Inspector</th>
              <th class="payroll-side">Inspection area</th>
              <th v-for="day in weekDays" :key="`blank-${day}`" class="payroll-side"></th>
              <th class="payroll-side"></th>
              <th class="payroll-side"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.inspector">
              <th class="payroll-side">{{ row.inspector }}</th>
              <td class="payroll-side">{{ row.area }}</td>
              <td
                v-for="shift in row.shifts"
                :key="`${row.inspector}-${shift.date}`"
                :class="shift.working ? 'payroll-work' : 'payroll-na'"
                :title="shift.count ? `${shift.count} inspections — click to open Routes Map` : 'Click to set a local shift override'"
                @click="openShift(row, shift)"
                @contextmenu.prevent="editShift(row, shift)"
              >
                {{ shift.working ? timeLabel(shift.start) : 'N/A' }}
              </td>
              <td class="payroll-total">{{ row.totalShifts }}</td>
              <td class="payroll-cyan">{{ row.totalHours }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="muted payroll-help">Click a populated shift to open its filtered Routes Map. Right-click any shift to set or clear a local manager override.</p>
    </section>
  </main>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import http from '../api/http'

// Payroll / Shifts 页面直接读取 Routes Map 的路线记录，保证周统计与路线数据一致。
const router = useRouter()
// 旧系统以 2026-07-06 为第 384 周，后续周号按七天递增。
const baseWeekStart = '2026-07-06'
const baseWeekNumber = 384
const weekStart = ref(mondayFor(new Date()))
const weekNumber = ref(automaticWeekNumber(weekStart.value))
const records = ref([])
const loading = ref(false)
const error = ref('')
// 巡检员提交和经理调整都保存在 MySQL，所有登录设备看到同一份班次。
const overrides = ref({})
const defaultInspectors = ['Alex', 'Mia', 'Ajay', 'Ajay K', 'Andreas', 'Dylan', 'Jeff Li', 'Lakshay', 'Rohien', 'Ronit', 'Tarun']
const weekDays = computed(() => Array.from({ length: 6 }, (_, index) => addDays(weekStart.value, index)))
const rows = computed(() => {
  const inspectors = [...new Set([...defaultInspectors, ...records.value.map((record) => record.inspector).filter(Boolean)])]
  return inspectors.map((inspector) => {
    const inspectorRecords = records.value.filter((record) => (record.inspector || 'Unassigned') === inspector)
    const shifts = weekDays.value.map((date) => buildShift(inspector, date, inspectorRecords))
    const totalShifts = shifts.filter((shift) => shift.working).length
    return {
      inspector,
      area: inspectorRecords.find((record) => record.suburb || record.cityCouncil)?.suburb || inspectorRecords.find((record) => record.cityCouncil)?.cityCouncil || 'Assigned area',
      shifts,
      totalShifts,
      totalHours: (totalShifts * 8).toFixed(1)
    }
  })
})
const totalShifts = computed(() => rows.value.reduce((sum, row) => sum + row.totalShifts, 0))
const totalHours = computed(() => rows.value.reduce((sum, row) => sum + Number(row.totalHours), 0).toFixed(1))

// 页面打开后立即加载当前周的路线与班次统计。
onMounted(loadPayroll)

async function loadPayroll() {
  // 一次查询周一至周六的路线记录，供每日 MAC 数量和检查员班次共用。
  loading.value = true
  error.value = ''
  try {
    const data = await http.get('/routes/map', {
      params: { startDate: weekDays.value[0], endDate: weekDays.value[5], page: 1, size: 1000 }
    })
    records.value = data.records || []
    const shiftData = await http.get('/inspector/shifts/week', { params: { start: weekDays.value[0] } })
    overrides.value = Object.fromEntries((shiftData.shifts || []).map((shift) => [
      `${shift.inspector_name}|${shift.shift_date}`,
      { working: Number(shift.working) === 1, start: shift.start_time || '09:00', end: shift.end_time || '16:30' }
    ]))
  } catch (requestError) {
    error.value = requestError.message
  } finally {
    loading.value = false
  }
}

function buildShift(inspector, date, inspectorRecords) {
  // 本地经理覆盖优先于路线推导值；没有覆盖时使用当天最早检查时间作为班次开始时间。
  const override = overrides.value[`${inspector}|${date}`]
  const dayRecords = inspectorRecords.filter((record) => record.inspectionDate === date && record.status !== 'Cancelled')
  if (override) return { date, count: dayRecords.length, ...override }
  const times = dayRecords.map((record) => normalizeTime(record.inspectionTime)).filter(Boolean).sort()
  return { date, working: dayRecords.length > 0, start: times[0] || '09:00', count: dayRecords.length }
}

function openShift(row, shift) {
  // 有检查任务的班次直接跳转到 Routes Map，并自动带入检查员和日期过滤条件。
  if (!shift.count) {
    editShift(row, shift)
    return
  }
  router.push({ path: '/routes/map', query: { inspector: row.inspector, startDate: shift.date, endDate: shift.date } })
}

async function editShift(row, shift) {
  // 经理调整写入 MySQL；巡检员再次打开 Shift 页时会看到经理锁定后的结果。
  const value = window.prompt(`Set start time for ${row.inspector} on ${shift.date}. Enter N/A for not working, or HH:mm.`, shift.working ? shift.start : 'N/A')
  if (value === null) return
  const key = `${row.inspector}|${shift.date}`
  let update
  if (value.trim().toUpperCase() === 'N/A') {
    update = { working: false, start: '09:00', end: shift.end || '16:30' }
  } else if (/^\d{1,2}:\d{2}$/.test(value.trim())) {
    update = { working: true, start: normalizeTime(value.trim()), end: shift.end || '16:30' }
  } else {
    window.alert('Use HH:mm, for example 09:00, or enter N/A.')
    return
  }
  try {
    await http.put('/inspector/shifts/manage', { inspectorName: row.inspector, date: shift.date, ...update })
    overrides.value = { ...overrides.value, [key]: update }
  } catch (requestError) {
    window.alert(requestError.message)
  }
}

function normalizeWeekAndLoad() {
  // 任意日期都归一化到所在周的星期一，再重新计算业务周号。
  weekStart.value = mondayFor(new Date(`${weekStart.value}T00:00:00`))
  weekNumber.value = automaticWeekNumber(weekStart.value)
  loadPayroll()
}

function changeWeekNumber() {
  // 根据旧系统的基准周号反向计算目标周的星期一。
  weekStart.value = addDays(baseWeekStart, (Number(weekNumber.value) - baseWeekNumber) * 7)
  loadPayroll()
}

function openWeekRoutes() {
  // 将整周日期范围传给 Routes Map，方便从工资表快速核对路线明细。
  router.push({ path: '/routes/map', query: { startDate: weekDays.value[0], endDate: weekDays.value[5] } })
}

function dailyTotal(date) {
  // MAC Daily Total 只统计未取消且产品字段包含 MAC 的检查记录。
  return records.value.filter((record) => record.inspectionDate === date && record.status !== 'Cancelled' && String(record.projects || '').toUpperCase().includes('MAC')).length
}

function automaticWeekNumber(start) {
  // 周号使用项目约定的业务周，而不是 ISO 自然周。
  return baseWeekNumber + Math.round((new Date(`${start}T00:00:00`) - new Date(`${baseWeekStart}T00:00:00`)) / 604800000)
}

function mondayFor(source) {
  // 将给定日期转换为本地时区下所在周的星期一。
  const date = new Date(source)
  const day = date.getDay()
  date.setDate(date.getDate() + (day === 0 ? -6 : 1 - day))
  return localDate(date)
}

function addDays(source, days) {
  // 使用本地日期运算，避免 UTC 转换造成日期前移或后移。
  const date = new Date(`${source}T00:00:00`)
  date.setDate(date.getDate() + days)
  return localDate(date)
}

function localDate(date) {
  // 生成后端接口需要的 yyyy-MM-dd 日期字符串。
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function dayName(date) {
  // 表头星期名称采用澳大利亚英文缩写。
  return new Date(`${date}T00:00:00`).toLocaleDateString('en-AU', { weekday: 'short' })
}

function dateLabel(date) {
  // 表头日期显示为 27-Jul 等紧凑格式。
  return new Date(`${date}T00:00:00`).toLocaleDateString('en-AU', { day: '2-digit', month: 'short' }).replace(' ', '-')
}

function normalizeTime(value) {
  // 同时兼容导入数据中的 12 小时制和数据库中的 24 小时制。
  if (!value) return ''
  const text = String(value).trim()
  const twelveHour = text.match(/^(\d{1,2}):(\d{2})\s*(AM|PM)$/i)
  if (twelveHour) {
    let hour = Number(twelveHour[1]) % 12
    if (twelveHour[3].toUpperCase() === 'PM') hour += 12
    return `${String(hour).padStart(2, '0')}:${twelveHour[2]}`
  }
  const twentyFourHour = text.match(/^(\d{1,2}):(\d{2})/)
  return twentyFourHour ? `${String(Number(twentyFourHour[1])).padStart(2, '0')}:${twentyFourHour[2]}` : ''
}

function timeLabel(time) {
  // Payroll 表格统一使用带 AM/PM 的 12 小时制展示班次开始时间。
  const normalized = normalizeTime(time)
  if (!normalized) return 'N/A'
  let [hour, minute] = normalized.split(':').map(Number)
  const suffix = hour >= 12 ? 'PM' : 'AM'
  hour = hour % 12 || 12
  return `${hour}:${String(minute).padStart(2, '0')} ${suffix}`
}

</script>
