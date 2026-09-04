<template>
  <main class="page routes-page workbench-page">
    <section class="panel route-filter-panel">
      <h2>Routes map</h2>
      <div class="toolbar routes-toolbar">
        <label>
          Week
          <select v-model="selectedWeek" @change="applyWeekFilter">
            <option value="">All weeks</option>
            <option v-for="week in weekOptions" :key="week.number" :value="week.number">
              Week {{ week.number }} · {{ week.start }} to {{ week.end }}
            </option>
          </select>
        </label>
        <label>
          Day
          <select v-model="selectedDay" :disabled="!selectedWeek" @change="applyDayFilter">
            <option value="">Whole week</option>
            <option v-for="day in dayOptions" :key="day.offset" :value="day.offset">
              {{ day.label }}
            </option>
          </select>
        </label>
        <label>Start date<input v-model="filters.startDate" type="date" @change="clearCalendarSelection" /></label>
        <label>End date<input v-model="filters.endDate" type="date" @change="clearCalendarSelection" /></label>
        <label>
          Inspector
          <select v-model="filters.inspector">
            <option value="">All inspectors</option>
            <option v-for="inspector in inspectorOptions" :key="inspector" :value="inspector">
              {{ inspector }}
            </option>
          </select>
        </label>
        <label>Sales<input v-model.trim="filters.sales" /></label>
        <label>Keyword<input v-model.trim="filters.keyword" placeholder="MAC, phone, name, address" /></label>
        <label>
          Route
          <select v-model="filters.route">
            <option value="">All routes</option>
            <option v-for="route in routes" :key="route.routeName" :value="route.routeName">
              {{ route.routeName }} / {{ route.stopCount }} stops
            </option>
          </select>
        </label>
        <button class="secondary" @click="clearFilters">Clear</button>
        <button @click="searchRoutes">Search</button>
        <button class="secondary" @click="loadGoogleMap">Load Google map</button>
      </div>
    </section>

    <section class="stats">
      <div class="stat">
        <strong>{{ summary.routeGroups || 0 }}</strong>
        <span class="muted">route groups</span>
      </div>
      <div class="stat">
        <strong>{{ summary.scheduledInspections || 0 }}</strong>
        <span class="muted">scheduled inspections</span>
      </div>
      <div class="stat">
        <strong>{{ summary.openSlots || 0 }}</strong>
        <span class="muted">open slots</span>
      </div>
      <div class="stat">
        <strong>{{ summary.startDate || '-' }}</strong>
        <span class="muted">first date</span>
      </div>
    </section>

    <section class="panel route-map-panel">
      <h2>Global route map</h2>
      <div ref="mapElement" class="map-box"></div>
      <div class="error map-error" v-if="mapError">{{ mapError }}</div>
      <div class="muted">{{ mapNotice }}</div>
    </section>

    <section class="panel route-records-panel">
      <div class="panel-title-row">
        <h2>Route Records</h2>
        <div class="record-actions">
          <button class="secondary" :disabled="exporting || !total" @click="exportAllRecords">Export all</button>
          <button class="secondary" :disabled="exporting || !selectedCount" @click="exportSelectedRecords">
            Export selected ({{ selectedCount }})
          </button>
          <button class="danger" :disabled="!selectedCount" @click="batchDeleteRecords">
            Delete selected ({{ selectedCount }})
          </button>
        </div>
      </div>
      <div class="error" v-if="exportError">{{ exportError }}</div>
      <div class="table-wrap">
        <table class="records-table route-records-table">
          <thead>
            <tr>
              <th class="select-col">
                <input type="checkbox" :checked="allVisibleSelected" :disabled="!records.length" @change="toggleSelectAll" />
              </th>
              <th>Customer name</th>
              <th>Sales</th>
              <th>MACID</th>
              <th>Phone</th>
              <th>Address</th>
              <th>Area</th>
              <th>Inspector</th>
              <th>Date</th>
              <th>Time</th>
              <th>Product</th>
              <th>Created at</th>
              <th>Updated at</th>
              <th class="note-col">Note</th>
              <th class="actions-col">Action</th>
            </tr>
          </thead>
          <tbody>
            <tr class="route-row schedule-row" v-for="record in records" :key="record.id">
              <td class="select-col">
                <input type="checkbox" :checked="isRecordSelected(record.id)" @change="toggleRecordSelection(record.id, $event)" />
              </td>
              <td>{{ record.customerName }}</td>
              <td>{{ record.sales }}</td>
              <td>{{ record.macId }}</td>
              <td>{{ record.phoneNumber }}</td>
              <td>{{ record.address }}</td>
              <td>{{ record.suburb || record.cityCouncil }}</td>
              <td class="schedule-edit-cell inspector-cell">
                <select class="schedule-select inspector-select" :value="record.inspector || ''" :disabled="isRowSaving(record)" @change="markInlineField(record, 'inspector', $event.target.value)">
                  <option value="">Unassigned</option>
                  <option v-for="inspector in inspectorOptions" :key="inspector" :value="inspector">{{ inspector }}</option>
                </select>
                <span class="inline-save-state" :class="inlineStateClass(record, 'inspector')">{{ inlineStateText(record, 'inspector') }}</span>
              </td>
              <td class="schedule-edit-cell schedule-active-cell">
                <input class="schedule-input date-input" type="date" :value="record.inspectionDate || ''" :disabled="isRowSaving(record)" @change="markInlineField(record, 'inspectionDate', $event.target.value)" />
                <span class="inline-save-state" :class="inlineStateClass(record, 'inspectionDate')">{{ inlineStateText(record, 'inspectionDate') }}</span>
              </td>
              <td class="schedule-edit-cell schedule-active-cell">
                <select class="schedule-select time-select" :value="record.inspectionTime || ''" :disabled="isRowSaving(record)" @change="markInlineField(record, 'inspectionTime', $event.target.value)">
                  <option value="">No time</option>
                  <option v-for="time in timeOptions" :key="time" :value="time">{{ time }}</option>
                </select>
                <span class="inline-save-state" :class="inlineStateClass(record, 'inspectionTime')">{{ inlineStateText(record, 'inspectionTime') }}</span>
              </td>
              <td class="schedule-edit-cell product-cell" :class="{ battery: record.projects === 'Battery only' }">
                <select class="schedule-select product-select" :value="record.projects || ''" :disabled="isRowSaving(record)" @change="markInlineField(record, 'projects', $event.target.value)">
                  <option value="">No product</option>
                  <option v-for="product in productOptions" :key="product" :value="product">{{ product }}</option>
                </select>
                <span class="inline-save-state" :class="inlineStateClass(record, 'projects')">{{ inlineStateText(record, 'projects') }}</span>
              </td>
              <td>{{ displayValue(record.createdAt) }}</td>
              <td>{{ displayValue(record.updatedAt) }}</td>
              <td class="note-cell note-col">
                <textarea :value="noteValue(record)" rows="2" placeholder="Add note update" :disabled="isRowSaving(record)" @input="markInlineField(record, 'schedulerRemarks', $event.target.value)"></textarea>
                <span class="inline-save-state" :class="inlineStateClass(record, 'schedulerRemarks')">{{ inlineStateText(record, 'schedulerRemarks') }}</span>
              </td>
              <td class="actions-col">
                <div class="row-actions row-actions-fixed">
                  <button class="save" :disabled="!isRowDirty(record) || isRowSaving(record)" @click="saveRouteRecord(record)">
                    {{ isRowSaving(record) ? 'Saving...' : 'Save' }}
                  </button>
                  <button class="secondary" @click="viewRecord(record.id)">Detail</button>
                  <button class="secondary" @click="editRecord(record.id)">Edit</button>
                  <button class="secondary" :disabled="record.status === 'Cancelled'" @click="cancelRecord(record)">Cancel</button>
                  <button class="danger" @click="deleteRecord(record)">Delete</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pagination">
        <span>Showing {{ records.length }} of {{ total }} records loaded from MySQL.</span>
        <button class="secondary" :disabled="pagination.page <= 1" @click="changePage(pagination.page - 1)">Previous</button>
        <label class="inline-control">
          Page
          <input v-model.number="pageInput" type="number" min="1" :max="totalPages" @keyup.enter="jumpPage" />
        </label>
        <span>/ {{ totalPages }}</span>
        <button class="secondary" :disabled="pagination.page >= totalPages" @click="changePage(pagination.page + 1)">Next</button>
        <label class="inline-control">
          Page size
          <select v-model.number="pagination.size" @change="changePageSize">
            <option :value="10">10</option>
            <option :value="20">20</option>
            <option :value="50">50</option>
            <option :value="100">100</option>
          </select>
        </label>
      </div>
    </section>

    <div v-if="dialogMode" class="modal-backdrop">
      <div class="modal">
        <div class="modal-header">
          <h3>{{ modalTitle }}</h3>
          <button class="secondary" @click="closeDialog">Close</button>
        </div>

        <div v-if="dialogMode === 'detail' && activeRecord" class="detail-grid">
          <div v-for="field in detailFields" :key="field.key" :class="{ wide: field.wide }">
            <span class="muted">{{ field.label }}</span>
            <strong>{{ displayValue(activeRecord[field.key]) }}</strong>
          </div>
        </div>

        <form v-if="dialogMode === 'edit'" class="edit-grid" @submit.prevent="saveRecord">
          <label v-for="field in editFields" :key="field.key" :class="{ wide: field.wide }">
            {{ field.label }}
            <textarea v-if="field.type === 'textarea'" v-model="editForm[field.key]" rows="3"></textarea>
            <input v-else v-model="editForm[field.key]" :type="field.type || 'text'" />
          </label>
          <div class="modal-actions wide">
            <button :disabled="saving">{{ saving ? 'Saving...' : 'Save' }}</button>
            <button type="button" class="secondary" @click="closeDialog">Cancel</button>
          </div>
          <div class="error wide" v-if="dialogError">{{ dialogError }}</div>
        </form>
      </div>
    </div>
  </main>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import http from '../api/http'
import { formatDisplayValue } from '../utils/date'
import { logError, logInfo } from '../utils/logger'

// Routes map 页面负责路线筛选、地图展示、路线记录内联编辑和批量操作。
const googleMapsKey = import.meta.env.VITE_GOOGLE_MAPS_KEY || ''
const currentRoute = useRoute()
const routes = ref([])
const records = ref([])
const summary = ref({})
const mapElement = ref(null)
const mapNotice = ref('Google map is not loaded. Route records are available below.')
const mapError = ref('')
const selectedIds = ref([])
const selectedAllRecords = ref(false)
const exporting = ref(false)
const exportError = ref('')
const inlineState = reactive({})
const noteDrafts = reactive({})
const dirtyRows = reactive({})
const rowSaving = reactive({})
const dialogMode = ref('')
const activeRecord = ref(null)
const saving = ref(false)
const dialogError = ref('')
const filters = reactive({ startDate: '', endDate: '', inspector: '', sales: '', keyword: '', route: '' })
const selectedWeek = ref('')
const selectedDay = ref('')
const dayOptions = [
  { label: 'Monday', offset: 0 },
  { label: 'Tuesday', offset: 1 },
  { label: 'Wednesday', offset: 2 },
  { label: 'Thursday', offset: 3 },
  { label: 'Friday', offset: 4 },
  { label: 'Saturday', offset: 5 },
  { label: 'Sunday', offset: 6 }
]
const weekOptions = Array.from({ length: 117 }, (_, index) => {
  const number = 410 - index
  const start = businessWeekDate(number, 0)
  return { number, start, end: businessWeekDate(number, 6) }
})
const total = ref(0)
const pagination = reactive({ page: 1, size: 10 })
const pageInput = ref(1)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pagination.size)))
const visibleIds = computed(() => records.value.map((record) => record.id))
const allVisibleSelected = computed(() => visibleIds.value.length > 0 && (selectedAllRecords.value || visibleIds.value.every((id) => selectedIds.value.includes(id))))
const selectedCount = computed(() => selectedAllRecords.value ? total.value : selectedIds.value.length)
const modalTitle = computed(() => (dialogMode.value === 'edit' ? 'Edit inspection record' : 'Inspection record detail'))
const defaultTimeOptions = [
  // 路线页手动选择时间的默认枚举；后续也会合并数据库中已有的非空时间。
  '09:00', '09:30', '10:00', '10:30', '11:00', '11:30',
  '12:00 PM', '12:30 PM', '1:00 PM', '1:30 PM', '2:00 PM',
  '2:30 PM', '3:00 PM', '3:30 PM', '4:00 PM', '4:30 PM',
  '5:00 PM'
]
const defaultProductOptions = [
  // 产品枚举来源于业务给定的 15 个 products，供 Product 列在线选择后点击 Save 保存。
  'MAC',
  'MAC + DAC',
  'MAC + HP + Battery',
  'MAC + Battery',
  'MAC + HP',
  'MAC + DAC + Battery',
  'DAC',
  'DAC + Battery',
  'DAC + HP + Battery',
  'HP + Battery',
  'HP only',
  'Battery only',
  'ALL PRODUCTS',
  'SP + Battery + MAC',
  'SP + Battery'
]
const defaultInspectorOptions = [
  // Inspector 枚举来源于业务配置中的 inspectors.name，供路线分配下拉框使用。
  'Alex',
  'Mia',
  'Ajay',
  'Ajay K',
  'Andreas',
  'Dylan',
  'Jeff Li',
  'Lakshay',
  'Rohien',
  'Ronit',
  'Tarun'
]
const inspectorOptions = computed(() => uniqueValues([
  // 下拉选项 = 固定业务枚举 + 当前页已有值，避免老数据中的人员无法继续显示。
  ...defaultInspectorOptions,
  ...records.value.map((record) => record.inspector)
]).filter((name) => name && name !== 'Unassigned'))
const timeOptions = computed(() => uniqueValues([
  // 时间选项 = 默认时间段 + 当前页已有值，兼容历史导入格式。
  ...defaultTimeOptions,
  ...records.value.map((record) => record.inspectionTime)
]))
const productOptions = computed(() => uniqueValues([
  // 产品选项 = 固定产品枚举 + 当前页已有值，兼容历史导入文本。
  ...defaultProductOptions,
  ...records.value.map((record) => record.projects)
]))
const editForm = reactive({
  id: null,
  rowNumber: '',
  dayName: '',
  status: '',
  macId: '',
  sales: '',
  firstName: '',
  lastName: '',
  customerName: '',
  phoneNumber: '',
  address: '',
  suburb: '',
  cityCouncil: '',
  inspector: '',
  inspectionDate: '',
  inspectionTime: '',
  projects: '',
  schedulerRemarks: '',
  quotationTeamReport: ''
})
const detailFields = [
  // 详情弹窗字段与 Inspection Import 保持一致，方便两边核对同一条记录。
  { label: 'ID', key: 'id' },
  { label: 'Batch ID', key: 'importBatchId' },
  { label: 'Row', key: 'rowNumber' },
  { label: 'Day', key: 'dayName' },
  { label: 'Status', key: 'status' },
  { label: 'MAC ID', key: 'macId' },
  { label: 'Sales', key: 'sales' },
  { label: 'First name', key: 'firstName' },
  { label: 'Last name', key: 'lastName' },
  { label: 'Customer', key: 'customerName' },
  { label: 'Phone', key: 'phoneNumber' },
  { label: 'Inspector', key: 'inspector' },
  { label: 'Date', key: 'inspectionDate' },
  { label: 'Time', key: 'inspectionTime' },
  { label: 'Product', key: 'projects' },
  { label: 'Suburb', key: 'suburb' },
  { label: 'City council', key: 'cityCouncil' },
  { label: 'Address', key: 'address', wide: true },
  { label: 'Scheduler remarks', key: 'schedulerRemarks', wide: true },
  { label: 'Quotation team report', key: 'quotationTeamReport', wide: true },
  { label: 'Created at', key: 'createdAt', wide: true },
  { label: 'Updated at', key: 'updatedAt', wide: true }
]
const editFields = [
  // 编辑字段用于弹窗编辑和路线行 Save payload 构造，需与后端可更新字段保持一致。
  { label: 'Row', key: 'rowNumber', type: 'number' },
  { label: 'Day', key: 'dayName' },
  { label: 'Status', key: 'status' },
  { label: 'MAC ID', key: 'macId' },
  { label: 'Sales', key: 'sales' },
  { label: 'First name', key: 'firstName' },
  { label: 'Last name', key: 'lastName' },
  { label: 'Customer', key: 'customerName' },
  { label: 'Phone', key: 'phoneNumber' },
  { label: 'Inspector', key: 'inspector' },
  { label: 'Date', key: 'inspectionDate', type: 'date' },
  { label: 'Time', key: 'inspectionTime' },
  { label: 'Product', key: 'projects' },
  { label: 'Suburb', key: 'suburb' },
  { label: 'City council', key: 'cityCouncil' },
  { label: 'Address', key: 'address', type: 'textarea', wide: true },
  { label: 'Scheduler remarks', key: 'schedulerRemarks', type: 'textarea', wide: true },
  { label: 'Quotation team report', key: 'quotationTeamReport', type: 'textarea', wide: true }
]

let scriptPromise = null
let googleMap = null
let googleMapMarkers = []
let googleMapLines = []
let mapLoadGeneration = 0
let lastGeocodeFailure = ''

onMounted(() => {
  Object.assign(filters, {
    startDate: String(currentRoute.query.startDate || ''),
    endDate: String(currentRoute.query.endDate || ''),
    inspector: String(currentRoute.query.inspector || ''),
    keyword: String(currentRoute.query.keyword || '')
  })
  loadAll()
})
onBeforeUnmount(clearGoogleRouteMap)

async function loadAll() {
  // 路线页加载顺序：先加载路线下拉选项，再加载汇总和分页记录。
  logInfo('RoutesMap', 'Loading all Routes Map data', { ...filters, page: pagination.page, size: pagination.size })
  await loadRoutes()
  await loadRecords()
}

async function searchRoutes() {
  // 按日期、人员、销售、关键字、路线刷新地图数据，切换筛选条件时清空旧选择。
  logInfo('RoutesMap', 'Refreshing route map with filters', { ...filters })
  clearSelection()
  pagination.page = 1
  await loadAll()
}

async function applyWeekFilter() {
  selectedDay.value = ''
  if (!selectedWeek.value) {
    filters.startDate = ''
    filters.endDate = ''
  } else {
    filters.startDate = businessWeekDate(Number(selectedWeek.value), 0)
    filters.endDate = businessWeekDate(Number(selectedWeek.value), 6)
  }
  await searchRoutes()
}

async function applyDayFilter() {
  if (!selectedWeek.value) return
  if (selectedDay.value === '') {
    filters.startDate = businessWeekDate(Number(selectedWeek.value), 0)
    filters.endDate = businessWeekDate(Number(selectedWeek.value), 6)
  } else {
    const date = businessWeekDate(Number(selectedWeek.value), Number(selectedDay.value))
    filters.startDate = date
    filters.endDate = date
  }
  await searchRoutes()
}

function businessWeekDate(weekNumber, dayOffset) {
  // Business calendar anchor supplied by the scheduling Sheet: Week 388 starts Monday 27-Jul-2026.
  const anchor = Date.UTC(2026, 6, 27)
  const milliseconds = anchor + ((weekNumber - 388) * 7 + dayOffset) * 86400000
  return new Date(milliseconds).toISOString().slice(0, 10)
}

function clearCalendarSelection() {
  selectedWeek.value = ''
  selectedDay.value = ''
}

async function clearFilters() {
  // 清空路线页全部筛选条件后重新加载第一页。
  logInfo('RoutesMap', 'Clearing route filters')
  selectedWeek.value = ''
  selectedDay.value = ''
  Object.assign(filters, { startDate: '', endDate: '', inspector: '', sales: '', keyword: '', route: '' })
  clearSelection()
  pagination.page = 1
  await loadAll()
}

async function loadRoutes() {
  // 查询当前筛选范围内可选路线，路线目前按 Inspector 聚合。
  logInfo('RoutesMap', 'Loading route options', { ...filters })
  const data = await http.get('/routes/options', {
    params: {
      startDate: filters.startDate,
      endDate: filters.endDate,
      inspector: filters.inspector,
      sales: filters.sales,
      keyword: filters.keyword
    }
  })
  routes.value = data.routes || []
}

async function loadRecords() {
  // 加载路线汇总和分页记录，响应中的 summary 用于顶部统计卡片。
  logInfo('RoutesMap', 'Loading route records', {
    startDate: filters.startDate,
    endDate: filters.endDate,
    inspector: filters.inspector,
    sales: filters.sales,
    keyword: filters.keyword,
    route: filters.route,
    page: pagination.page,
    size: pagination.size
  })
  const data = await http.get('/routes/map', {
    params: {
      startDate: filters.startDate,
      endDate: filters.endDate,
      inspector: filters.inspector,
      sales: filters.sales,
      keyword: filters.keyword,
      route: filters.route,
      page: pagination.page,
      size: pagination.size
    }
  })
  summary.value = normalizeSummary(data.summary || {})
  records.value = data.records || []
  prepareNoteDrafts(records.value)
  resetRouteEditState(records.value)
  total.value = data.total || 0
  pagination.page = data.page || pagination.page
  pagination.size = data.size || pagination.size
  pageInput.value = pagination.page
  selectedIds.value = selectedAllRecords.value ? [...visibleIds.value] : selectedIds.value.filter((id) => visibleIds.value.includes(id))
  mapNotice.value = records.value.length
    ? 'Route boxes are available below. Click Load Google map to render address markers.'
    : 'No route records found. Import a CSV/Excel file first or change filters.'
}

function toggleSelectAll(event) {
  // 表头复选框用于选择当前查询范围，后端删除/导出时再按模式处理。
  selectedAllRecords.value = event.target.checked
  selectedIds.value = event.target.checked ? [...visibleIds.value] : []
}

function toggleRecordSelection(id, event) {
  // 从“选择全部”取消某一行时，切换成当前页选择模式。
  if (selectedAllRecords.value) {
    selectedAllRecords.value = false
    selectedIds.value = visibleIds.value.filter((visibleId) => visibleId !== id)
    return
  }
  if (event.target.checked) {
    selectedIds.value = selectedIds.value.includes(id) ? selectedIds.value : [...selectedIds.value, id]
  } else {
    selectedIds.value = selectedIds.value.filter((selectedId) => selectedId !== id)
  }
}

function isRecordSelected(id) {
  // 行是否勾选由全选状态或 selectedIds 判断。
  return selectedAllRecords.value || selectedIds.value.includes(id)
}

function clearSelection() {
  // 筛选、分页、删除后统一清空选择状态，避免误操作。
  selectedAllRecords.value = false
  selectedIds.value = []
}

function uniqueValues(values) {
  // 去空、去重、排序，生成稳定的下拉枚举。
  return values
    .map((value) => (value || '').trim())
    .filter((value, index, array) => value && array.indexOf(value) === index)
    .sort((left, right) => left.localeCompare(right))
}

function prepareNoteDrafts(items) {
  // Note 输入使用本地草稿缓存，避免保存前列表刷新丢失输入值。
  items.forEach((record) => {
    if (!Object.prototype.hasOwnProperty.call(noteDrafts, record.id)) {
      noteDrafts[record.id] = record.schedulerRemarks || record.quotationTeamReport || ''
    }
  })
}

function noteValue(record) {
  // Note 优先展示草稿，其次展示 schedulerRemarks/quotationTeamReport。
  if (Object.prototype.hasOwnProperty.call(noteDrafts, record.id)) {
    return noteDrafts[record.id]
  }
  return record.schedulerRemarks || record.quotationTeamReport || ''
}

function markInlineField(record, field, value) {
  // 绿色编辑区字段只更新前端草稿并标记未保存，点击行内 Save 后才持久化到数据库。
  const normalized = normalizeInlineValue(field, value)
  record[field] = normalized
  if (field === 'schedulerRemarks') {
    noteDrafts[record.id] = normalized
  }
  dirtyRows[record.id] = true
  inlineState[inlineKey(record, field)] = 'dirty'
}

function normalizeInlineValue(field, value) {
  // 日期空值传 null，其他字段传空字符串，保持后端字段语义清晰。
  if (field === 'inspectionDate') {
    return value || null
  }
  return value ?? ''
}

async function saveRouteRecord(record) {
  // 保存按钮统一提交当前行绿色区域已编辑内容，成功后清除未保存标记。
  const fields = routeEditableFields()
  rowSaving[record.id] = true
  fields.forEach((field) => {
    if (inlineState[inlineKey(record, field)] === 'dirty') {
      inlineState[inlineKey(record, field)] = 'saving'
    }
  })
  logInfo('RoutesMap', 'Saving route row changes', { id: record.id })
  try {
    const updated = await http.put(`/inspections/records/${record.id}`, buildRecordPayloadFromRecord(record))
    Object.assign(record, updated)
    sortRecordsByLatest()
    noteDrafts[record.id] = updated.schedulerRemarks || updated.quotationTeamReport || noteDrafts[record.id] || ''
    delete dirtyRows[record.id]
    fields.forEach((field) => {
      inlineState[inlineKey(record, field)] = 'saved'
    })
    setTimeout(() => {
      fields.forEach((field) => {
        const key = inlineKey(record, field)
        if (inlineState[key] === 'saved') {
          delete inlineState[key]
        }
      })
    }, 1200)
  } catch (err) {
    logError('RoutesMap', 'Failed to save route row changes', { id: record.id, error: err.message })
    fields.forEach((field) => {
      if (inlineState[inlineKey(record, field)] === 'saving') {
        inlineState[inlineKey(record, field)] = 'error'
      }
    })
  } finally {
    rowSaving[record.id] = false
  }
}

function buildRecordPayloadFromRecord(record) {
  // 从当前行数据组装后端 updateRecord 需要的完整 payload，避免局部保存清空其他字段。
  const payload = {}
  editFields.forEach((field) => {
    let value = record[field.key]
    if (field.key === 'schedulerRemarks' && Object.prototype.hasOwnProperty.call(noteDrafts, record.id)) {
      value = noteDrafts[record.id]
    }
    if (field.type === 'number') {
      value = value === '' || value === null || value === undefined ? null : Number(value)
    } else if (field.type === 'date') {
      value = value || null
    } else {
      value = value ?? ''
    }
    payload[field.key] = value
  })
  return payload
}

function inlineKey(record, field) {
  // 每条记录每个字段独立维护保存状态，避免多个字段同时编辑时互相覆盖提示。
  return `${record.id}:${field}`
}

function routeEditableFields() {
  return ['inspector', 'inspectionDate', 'inspectionTime', 'projects', 'schedulerRemarks']
}

function isRowDirty(record) {
  return Boolean(dirtyRows[record.id])
}

function isRowSaving(record) {
  // 保存中禁用当前行绿色编辑区控件，避免并发提交同一行。
  return Boolean(rowSaving[record.id])
}

function inlineStateClass(record, field) {
  // 根据保存状态返回 CSS 类名，用于展示 Saving/Saved/Save failed。
  const state = inlineState[inlineKey(record, field)]
  return state ? `is-${state}` : ''
}

function inlineStateText(record, field) {
  // 内联状态文案只短暂展示，成功状态会在定时器中清除。
  const state = inlineState[inlineKey(record, field)]
  if (state === 'dirty') return 'Unsaved'
  if (state === 'saving') return 'Saving...'
  if (state === 'saved') return 'Saved'
  if (state === 'error') return 'Save failed'
  return ''
}

function resetRouteEditState(items) {
  const visible = new Set(items.map((record) => String(record.id)))
  Object.keys(dirtyRows).forEach((id) => {
    if (!visible.has(id)) delete dirtyRows[id]
  })
  Object.keys(rowSaving).forEach((id) => {
    if (!visible.has(id)) delete rowSaving[id]
  })
  Object.keys(inlineState).forEach((key) => {
    const id = key.split(':')[0]
    if (!visible.has(id)) delete inlineState[key]
  })
}

async function viewRecord(id) {
  // 详情弹窗按 id 读取最新记录，避免使用过期列表缓存。
  dialogError.value = ''
  activeRecord.value = await http.get(`/inspections/records/${id}`)
  dialogMode.value = 'detail'
}

async function editRecord(id) {
  // 弹窗编辑前读取最新记录并填充完整表单。
  dialogError.value = ''
  const record = await http.get(`/inspections/records/${id}`)
  fillEditForm(record)
  dialogMode.value = 'edit'
}

function closeDialog() {
  // 关闭弹窗时清理当前记录和错误状态。
  dialogMode.value = ''
  dialogError.value = ''
  activeRecord.value = null
}

function fillEditForm(record) {
  // 表单统一把 null 转为空字符串，方便输入框展示和编辑。
  activeRecord.value = record
  Object.keys(editForm).forEach((key) => {
    editForm[key] = record[key] ?? ''
  })
}

function displayValue(value) {
  // 空值统一展示为短横线，日期时间统一显示为 YYYY-MM-DD HH:mm:ss。
  return formatDisplayValue(value)
}

function sortRecordsByLatest() {
  // 行内保存成功后按修改时间重新排列当前页，让刚更新的数据靠前展示。
  records.value = [...records.value].sort((left, right) => {
    const timeDiff = latestRecordTime(right) - latestRecordTime(left)
    if (timeDiff !== 0) return timeDiff
    return (right.id || 0) - (left.id || 0)
  })
}

function latestRecordTime(record) {
  return Date.parse(record.updatedAt || record.createdAt || '') || 0
}

function buildEditPayload() {
  // 按字段类型转换弹窗表单值，保证提交给后端的数据格式正确。
  const payload = {}
  editFields.forEach((field) => {
    let value = editForm[field.key]
    if (field.type === 'number') {
      value = value === '' || value === null ? null : Number(value)
    } else if (field.type === 'date') {
      value = value || null
    }
    payload[field.key] = value
  })
  return payload
}

async function saveRecord() {
  // 弹窗保存成功后重新加载路线和列表，刷新汇总统计。
  dialogError.value = ''
  saving.value = true
  logInfo('RoutesMap', 'Saving route dialog changes', { id: editForm.id })
  try {
    await http.put(`/inspections/records/${editForm.id}`, buildEditPayload())
    closeDialog()
    await reloadAfterRecordChange()
  } catch (err) {
    logError('RoutesMap', 'Failed to save route dialog changes', err)
    dialogError.value = err.message
  } finally {
    saving.value = false
  }
}

async function cancelRecord(record) {
  // 取消路线记录只更新状态为 Cancelled，保留历史数据。
  if (!window.confirm(`Cancel record ${record.customerName || record.macId || record.id}?`)) {
    return
  }
  await http.put(`/inspections/records/${record.id}/cancel`)
  await reloadAfterRecordChange()
}

async function deleteRecord(record) {
  // 单条删除会物理删除记录，删除后刷新路线汇总。
  if (!window.confirm(`Delete record ${record.customerName || record.macId || record.id}?`)) {
    return
  }
  await http.delete(`/inspections/records/${record.id}`)
  clearSelection()
  await reloadAfterRecordChange()
}

async function batchDeleteRecords() {
  // 选择全部时按当前路线筛选删除全部，普通选择时按 ids 批量删除。
  if (!selectedCount.value) {
    return
  }
  if (selectedAllRecords.value) {
    if (!window.confirm(`Delete all ${total.value} records matching current route query?`)) {
      return
    }
    logInfo('RoutesMap', 'Deleting all records matching route filters', { total: total.value, ...filters })
    await http.delete('/inspections/records/query', {
      data: {
        startDate: filters.startDate,
        endDate: filters.endDate,
        inspector: filters.inspector,
        sales: filters.sales,
        keyword: filters.keyword,
        route: filters.route
      }
    })
  } else {
    if (!window.confirm(`Delete ${selectedIds.value.length} selected records?`)) {
      return
    }
    logInfo('RoutesMap', 'Deleting selected route records', { ids: selectedIds.value })
    await http.delete('/inspections/records/batch', { data: { ids: selectedIds.value } })
  }
  clearSelection()
  await reloadAfterRecordChange()
}

async function exportAllRecords() {
  // 导出当前路线筛选条件下的全部记录。
  await exportRecords(false)
}

async function exportSelectedRecords() {
  // 导出前端勾选的路线记录；选择全部时按当前筛选条件导出全部。
  if (!selectedCount.value) {
    return
  }
  await exportRecords(true)
}

async function exportRecords(selectedOnly) {
  // 调用路线 Excel 导出接口，并在浏览器端触发文件下载。
  exportError.value = ''
  exporting.value = true
  try {
    const payload = {
      startDate: filters.startDate,
      endDate: filters.endDate,
      inspector: filters.inspector,
      sales: filters.sales,
      keyword: filters.keyword,
      route: filters.route
    }
    if (selectedOnly && !selectedAllRecords.value) {
      payload.ids = selectedIds.value
    }
    logInfo('RoutesMap', selectedOnly ? 'Exporting selected route records' : 'Exporting all route records', payload)
    const blob = await http.post('/routes/map/export', payload, { responseType: 'blob' })
    downloadBlob(blob, selectedOnly ? 'route-records-selected.xlsx' : 'route-records-all.xlsx')
  } catch (err) {
    logError('RoutesMap', 'Failed to export route records', err)
    exportError.value = err.message
  } finally {
    exporting.value = false
  }
}

function downloadBlob(blob, fileName) {
  // 将后端返回的 Excel Blob 转为临时下载链接。
  const url = window.URL.createObjectURL(new Blob([blob], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }))
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.URL.revokeObjectURL(url)
}

async function reloadAfterRecordChange() {
  // 删除或编辑后重新加载；当前页为空且还有数据时自动回退一页。
  await loadAll()
  if (!records.value.length && total.value > 0 && pagination.page > 1) {
    pagination.page -= 1
    await loadAll()
  }
}

function changePage(page) {
  // 页码限制在合法范围内，避免请求不存在页码。
  pagination.page = Math.min(Math.max(1, page), totalPages.value)
  loadRecords()
}

function jumpPage() {
  // 输入页码后回车跳转，非法输入按第一页处理。
  changePage(Number(pageInput.value) || 1)
}

function changePageSize() {
  // 修改每页条数后回第一页，并清除勾选状态。
  clearSelection()
  pagination.page = 1
  loadRecords()
}

async function loadGoogleMap() {
  // 按需加载 Google Maps JS，并按检查员/日期连接当前页地址。
  logInfo('RoutesMap', 'Starting Google Maps load', { recordCount: records.value.length })
  mapError.value = ''
  mapNotice.value = 'Loading Google map...'
  if (!records.value.length) {
    await loadRecords()
  }
  if (!records.value.length) {
    return
  }
  if (!googleMapsKey) {
    mapError.value = 'Google Maps key is not configured. Set VITE_GOOGLE_MAPS_KEY and restart the frontend.'
    mapNotice.value = 'Route records remain available below.'
    return
  }
  try {
    await ensureGoogleScript()
    logInfo('RoutesMap', 'Google Maps script loaded')
    await drawGoogleRouteMap(records.value.slice(0, 50))
  } catch (error) {
    logError('RoutesMap', 'Google Maps failed to load', error)
    mapError.value = error.message || 'Google Maps failed to load.'
    mapNotice.value = 'Route records remain available below.'
  }
}

function ensureGoogleScript() {
  // 地图脚本按需加载，避免页面初始化时就依赖 Google Maps 网络。
  if (window.google?.maps) {
    return Promise.resolve()
  }
  if (scriptPromise) {
    return scriptPromise
  }
  scriptPromise = new Promise((resolve, reject) => {
    window.gm_authFailure = () => {
      const allowedReferrer = `${window.location.origin}/*`
      const error = new Error(`Google Maps authorization failed. Add ${allowedReferrer} to the API key website restrictions and confirm Maps JavaScript API billing is enabled.`)
      mapError.value = error.message
      mapNotice.value = 'Google returned RefererNotAllowedMapError. Route records remain available below.'
      reject(error)
    }
    const script = document.createElement('script')
    script.dataset.googleMaps = 'true'
    script.src = `https://maps.googleapis.com/maps/api/js?key=${encodeURIComponent(googleMapsKey)}&libraries=places&language=en&region=AU`
    script.async = true
    script.defer = true
    script.onload = () => {
      if (window.google?.maps?.Map) {
        resolve()
      } else {
        reject(new Error('Google Maps loaded without the required Maps JavaScript API.'))
      }
    }
    script.onerror = () => reject(new Error('Google Maps script failed to load.'))
    document.head.appendChild(script)
  }).catch((error) => {
    scriptPromise = null
    throw error
  })
  return scriptPromise
}

async function drawGoogleRouteMap(items) {
  const generation = ++mapLoadGeneration
  clearGoogleRouteMap()
  googleMap = googleMap || new window.google.maps.Map(mapElement.value, {
    center: { lat: -37.8136, lng: 144.9631 },
    zoom: 10,
    mapTypeControl: false,
    streetViewControl: false,
    fullscreenControl: true
  })

  const bounds = new window.google.maps.LatLngBounds()
  const colors = ['#2563eb', '#16a34a', '#dc2626', '#9333ea', '#ea580c', '#0891b2', '#4f46e5', '#be123c']
  const routeGroups = groupMapRoutes(items)
  let markerCount = 0
  let geocodeFailureCount = 0
  lastGeocodeFailure = ''

  for (let routeIndex = 0; routeIndex < routeGroups.length; routeIndex += 1) {
    const route = routeGroups[routeIndex]
    const path = []
    for (let stopIndex = 0; stopIndex < route.stops.length; stopIndex += 1) {
      if (generation !== mapLoadGeneration) return
      const record = route.stops[stopIndex]
      if (!record.address) continue
      const position = await geocodeForMap(record.address)
      if (!position) {
        geocodeFailureCount += 1
        continue
      }
      path.push(position)
      bounds.extend(position)

      const marker = new window.google.maps.Marker({
        map: googleMap,
        position,
        label: {
          text: `${routeIndex + 1}.${stopIndex + 1}`,
          fontSize: '11px',
          fontWeight: '700'
        },
        title: `${record.customerName || record.macId || 'Inspection'} ${record.inspectionTime || ''}`
      })
      const info = new window.google.maps.InfoWindow({
        content: `<strong>${escapeMapHtml(record.customerName || record.macId || 'Inspection')}</strong><br>${escapeMapHtml(record.inspector || 'Unassigned')} ${escapeMapHtml(record.inspectionTime || '')}<br>${escapeMapHtml(record.address)}`
      })
      marker.addListener('click', () => info.open({ anchor: marker, map: googleMap }))
      googleMapMarkers.push(marker)
      markerCount += 1
    }

    if (path.length > 1) {
      googleMapLines.push(new window.google.maps.Polyline({
        map: googleMap,
        path,
        strokeColor: colors[routeIndex % colors.length],
        strokeOpacity: 0.78,
        strokeWeight: 4
      }))
    }
  }

  if (markerCount) {
    googleMap.fitBounds(bounds)
    mapNotice.value = `Google map loaded with ${markerCount} address markers across ${routeGroups.length} routes.`
    if (geocodeFailureCount) {
      mapError.value = `${geocodeFailureCount} address${geocodeFailureCount === 1 ? '' : 'es'} could not be resolved${lastGeocodeFailure ? ` (${lastGeocodeFailure})` : ''}. Check the address text and Geocoding API access.`
    }
  } else {
    mapError.value = `No addresses could be located${lastGeocodeFailure ? ` (${lastGeocodeFailure})` : ''}. Check the imported addresses and Geocoding API access.`
    mapNotice.value = 'Route records remain available below.'
  }
}

function groupMapRoutes(items) {
  const groups = new Map()
  items.forEach((record) => {
    const key = `${record.inspector || 'Unassigned'}|${record.inspectionDate || 'No date'}`
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key).push(record)
  })
  return [...groups.entries()].map(([key, stops]) => ({
    key,
    stops: stops.sort((a, b) => String(a.inspectionTime || '').localeCompare(String(b.inspectionTime || '')) || (a.rowNumber || 0) - (b.rowNumber || 0))
  }))
}

async function geocodeForMap(address) {
  const cacheKey = 'scheduleSmartuserGeocodeCache'
  const cache = readGeocodeCache(cacheKey)
  if (cache[address]) return cache[address]
  const query = /\baustralia\b/i.test(address) ? address : `${address}, Australia`
  const geocoder = new window.google.maps.Geocoder()

  try {
    const results = await Promise.race([
      new Promise((resolve, reject) => {
        geocoder.geocode(
          { address: query, componentRestrictions: { country: 'AU' } },
          (geocodeResults, status) => {
            if (status === 'OK' && geocodeResults?.length) {
              resolve(geocodeResults)
              return
            }
            reject(new Error(`Google Geocoding status: ${status || 'UNKNOWN_ERROR'}`))
          }
        )
      }),
      new Promise((_, reject) => setTimeout(() => reject(new Error('Geocoding timed out')), 10000))
    ])
    const location = results?.[0]?.geometry?.location
    if (!location) return null
    const position = { lat: location.lat(), lng: location.lng() }
    cache[address] = position
    localStorage.setItem(cacheKey, JSON.stringify(cache))
    return position
  } catch (error) {
    lastGeocodeFailure = error.message || 'Geocoding request failed'
    logError('RoutesMap', 'Address geocoding failed', { address, message: error.message })
    return null
  }
}

function readGeocodeCache(cacheKey) {
  try {
    return JSON.parse(localStorage.getItem(cacheKey) || '{}')
  } catch {
    return {}
  }
}

function clearGoogleRouteMap() {
  googleMapMarkers.forEach((marker) => marker.setMap(null))
  googleMapLines.forEach((line) => line.setMap(null))
  googleMapMarkers = []
  googleMapLines = []
}

function escapeMapHtml(value) {
  return String(value || '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;')
}

function normalizeSummary(source) {
  // 后端聚合字段在不同 JDBC 驱动中可能大小写不同，这里统一成前端使用的驼峰字段。
  return {
    routeGroups: source.routeGroups || source.routegroups || 0,
    scheduledInspections: source.scheduledInspections || source.scheduledinspections || 0,
    openSlots: source.openSlots || source.openslots || 0,
    startDate: source.startDate || source.startdate || ''
  }
}
</script>
