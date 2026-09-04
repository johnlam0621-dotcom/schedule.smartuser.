<template>
  <main class="page import-page workbench-page">
    <section class="panel">
      <h2>Inspection Import</h2>
      <form class="form-grid" @submit.prevent="upload">
        <label>
          CSV / Excel file
          <input type="file" accept=".csv,.xls,.xlsx" @change="file = $event.target.files[0]" />
        </label>
        <button :disabled="uploading || !file">{{ uploading ? 'Uploading...' : 'Import file' }}</button>
      </form>
      <div class="sheet-import-card">
        <div class="sheet-import-grid">
          <label>
            Google Sheet week
            <select v-model="selectedSheetGid" :disabled="loadingSheetTabs || importingSheetWeek" @change="previewSelectedSheet">
              <option value="">Choose a Sheet tab</option>
              <option v-for="sheet in googleSheetTabs" :key="sheet.gid" :value="String(sheet.gid)">
                {{ sheet.name }} · {{ sheet.rowCount }} source rows
              </option>
            </select>
          </label>
          <button class="secondary" :disabled="loadingSheetTabs || importingSheetWeek" @click="loadGoogleSheetTabs">
            {{ loadingSheetTabs ? 'Loading tabs...' : 'Refresh tabs' }}
          </button>
          <button class="secondary" :disabled="!selectedSheetGid || previewingSheet || importingSheetWeek" @click="previewSelectedSheet">
            {{ previewingSheet ? 'Reading...' : 'Preview week' }}
          </button>
          <label class="sheet-replace-option">
            <input v-model="replaceExistingWeek" type="checkbox" />
            Replace existing database rows for this week
          </label>
          <button :disabled="!selectedSheetGid || importingSheetWeek" @click="importSelectedSheet">
            {{ importingSheetWeek ? 'Importing week...' : 'Import selected week' }}
          </button>
        </div>
        <div v-if="selectedSheetPreview" class="status">
          {{ selectedSheetPreview.sheetName }}: {{ selectedSheetPreview.businessRows }} business rows found
          across {{ selectedSheetPreview.scannedRows }} scanned rows.
          Dates: {{ (selectedSheetPreview.dates || []).join(', ') || 'No scheduled dates' }}.
        </div>
        <div v-if="sheetImportStatus" class="status">{{ sheetImportStatus }}</div>
        <div v-if="sheetImportError" class="error">{{ sheetImportError }}</div>
      </div>
      <div class="record-actions">
        <button class="secondary" @click="checkGoogleSheetStatus">Check Google Sheet</button>
        <span class="muted">The source Sheet is read-only here. Booking only updates the selected existing open slot.</span>
      </div>
      <div class="status" v-if="googleSheetCheck?.status === 'success'">
        Google Sheet status: {{ googleSheetCheck.message }}
      </div>
      <div class="status" v-else-if="googleSheetCheck?.status === 'skipped'">
        Google Sheet status: {{ googleSheetCheck.message }}
      </div>
      <div class="error" v-else-if="googleSheetCheck?.status === 'failed'">
        Google Sheet status: {{ googleSheetCheck.message }}
      </div>
      <div class="status" v-if="result">
        Import completed. Batch {{ result.batchId }}: {{ result.successRows }} saved, {{ result.failedRows }} failed, {{ result.totalRows }} rows scanned.
      </div>
      <div class="status" v-if="result?.googleSheetStatus === 'success'">
        Google Sheet sync completed: {{ result.googleSheetRows }} rows synced.
      </div>
      <div class="status" v-else-if="result?.googleSheetStatus === 'skipped'">
        Google Sheet sync skipped: {{ result.googleSheetMessage }}
      </div>
      <div class="error" v-else-if="result?.googleSheetStatus === 'failed'">
        Google Sheet sync failed: {{ result.googleSheetMessage }}
      </div>
      <div class="error" v-if="error">Import failed: {{ error }}</div>
    </section>

    <section class="panel">
      <h2>Imported Inspection Records</h2>
      <div class="toolbar import-toolbar">
        <label>Start date<input v-model="filters.startDate" type="date" /></label>
        <label>End date<input v-model="filters.endDate" type="date" /></label>
        <label>Inspector<input v-model.trim="filters.inspector" /></label>
        <label>Sales<input v-model.trim="filters.sales" /></label>
        <label>Keyword<input v-model.trim="filters.keyword" placeholder="MAC, phone, name, address" /></label>
        <button class="secondary" @click="clearFilters">Clear</button>
        <button @click="searchRecords">Search</button>
        <button class="danger" :disabled="!selectedCount" @click="batchDeleteRecords">
          Delete selected ({{ selectedCount }})
        </button>
      </div>
      <div class="record-actions">
        <button class="secondary" :disabled="exporting || !total" @click="exportAllRecords">Export all</button>
        <button class="secondary" :disabled="exporting || !selectedCount" @click="exportSelectedRecords">
          Export selected ({{ selectedCount }})
        </button>
      </div>
      <div class="error" v-if="exportError">{{ exportError }}</div>
      <div class="table-wrap">
        <table class="records-table import-records-table">
          <thead>
            <tr>
              <th class="select-col">
                <input type="checkbox" :checked="allVisibleSelected" :disabled="!records.length" @change="toggleSelectAll" />
              </th>
              <th>Customer</th>
              <th>Sales</th>
              <th>MAC ID</th>
              <th>Phone</th>
              <th>Address</th>
              <th>Inspector</th>
              <th>Date</th>
              <th>Time</th>
              <th>Product</th>
              <th>Created at</th>
              <th>Updated at</th>
              <th class="actions-col">Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="record in records" :key="record.id">
              <td class="select-col">
                <input type="checkbox" :checked="isRecordSelected(record.id)" @change="toggleRecordSelection(record.id, $event)" />
              </td>
              <td>{{ record.customerName }}</td>
              <td>{{ record.sales }}</td>
              <td>{{ record.macId }}</td>
              <td>{{ record.phoneNumber }}</td>
              <td>{{ record.address }}</td>
              <td>{{ record.inspector || 'Unassigned' }}</td>
              <td>{{ record.inspectionDate }}</td>
              <td>{{ record.inspectionTime }}</td>
              <td>{{ record.projects }}</td>
              <td>{{ displayValue(record.createdAt) }}</td>
              <td>{{ displayValue(record.updatedAt) }}</td>
              <td class="actions-col">
                <div class="row-actions row-actions-fixed">
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
        <span>Total: {{ total }}</span>
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
import { computed, onMounted, reactive, ref } from 'vue'
import http from '../api/http'
import { formatDisplayValue } from '../utils/date'
import { logError, logInfo } from '../utils/logger'

// Inspection Import 页面负责 CSV/Excel 导入、导入记录查询、批量操作和导出。
const file = ref(null)
const uploading = ref(false)
const result = ref(null)
const error = ref('')
const googleSheetCheck = ref(null)
const googleSheetTabs = ref([])
const selectedSheetGid = ref('')
const selectedSheetPreview = ref(null)
const loadingSheetTabs = ref(false)
const previewingSheet = ref(false)
const importingSheetWeek = ref(false)
const replaceExistingWeek = ref(true)
const sheetImportStatus = ref('')
const sheetImportError = ref('')
const records = ref([])
const total = ref(0)
const selectedIds = ref([])
const selectedAllRecords = ref(false)
const exporting = ref(false)
const exportError = ref('')
const dialogMode = ref('')
const activeRecord = ref(null)
const saving = ref(false)
const dialogError = ref('')
const filters = reactive({ startDate: '', endDate: '', inspector: '', sales: '', keyword: '' })
const pagination = reactive({ page: 1, size: 10 })
const pageInput = ref(1)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pagination.size)))
const visibleIds = computed(() => records.value.map((record) => record.id))
const allVisibleSelected = computed(() => visibleIds.value.length > 0 && (selectedAllRecords.value || visibleIds.value.every((id) => selectedIds.value.includes(id))))
const selectedCount = computed(() => selectedAllRecords.value ? total.value : selectedIds.value.length)
const modalTitle = computed(() => (dialogMode.value === 'edit' ? 'Edit inspection record' : 'Inspection record detail'))
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
  // 详情弹窗字段：覆盖数据库保存的导入字段，便于核对原始客户、排班和备注信息。
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
  // 编辑弹窗字段：与后端 updateRecord 接口可保存的业务字段保持一致。
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

onMounted(() => {
  // 页面进入时加载第一页导入记录，并顺带检查 Google Sheet 同步配置状态。
  loadRecords()
  checkGoogleSheetStatus()
  loadGoogleSheetTabs()
})

async function loadGoogleSheetTabs() {
  loadingSheetTabs.value = true
  sheetImportError.value = ''
  try {
    googleSheetTabs.value = await http.get('/inspections/google-sheet/sheets')
    if (!selectedSheetGid.value && googleSheetTabs.value.length) {
      // 默认选择今天所在的业务周，避免未来周排在第一位时误导入错误页签。
      const currentWeek = currentBusinessWeekNumber()
      const currentTab = googleSheetTabs.value.find((tab) => sheetWeekNumber(tab.name) === currentWeek)
      selectedSheetGid.value = String((currentTab || googleSheetTabs.value[0]).gid)
      await previewSelectedSheet()
    }
  } catch (err) {
    sheetImportError.value = err.message
  } finally {
    loadingSheetTabs.value = false
  }
}

function sheetWeekNumber(name) {
  const match = String(name || '').match(/week\s+(\d+)/i)
  return match ? Number(match[1]) : 0
}

function currentBusinessWeekNumber() {
  const base = new Date(2026, 6, 27)
  const today = new Date()
  const localToday = new Date(today.getFullYear(), today.getMonth(), today.getDate())
  return 388 + Math.floor((localToday.getTime() - base.getTime()) / (7 * 24 * 60 * 60 * 1000))
}

async function previewSelectedSheet() {
  selectedSheetPreview.value = null
  sheetImportStatus.value = ''
  sheetImportError.value = ''
  if (!selectedSheetGid.value) return
  previewingSheet.value = true
  try {
    selectedSheetPreview.value = await http.get(
      `/inspections/google-sheet/sheets/${selectedSheetGid.value}/preview`
    )
  } catch (err) {
    sheetImportError.value = err.message
  } finally {
    previewingSheet.value = false
  }
}

async function importSelectedSheet() {
  if (!selectedSheetGid.value) return
  importingSheetWeek.value = true
  sheetImportStatus.value = ''
  sheetImportError.value = ''
  try {
    const response = await http.post(
      `/inspections/google-sheet/sheets/${selectedSheetGid.value}/import`,
      { replaceExisting: replaceExistingWeek.value },
      { timeout: 900000 }
    )
    result.value = response.importResult
    sheetImportStatus.value = `${response.sheetName} imported: ${response.importResult.successRows} saved, `
      + `${response.removedRows} previous rows replaced.`
    filters.startDate = response.weekStart
    filters.endDate = response.weekEnd
    pagination.page = 1
    await loadRecords()
    await previewSelectedSheet()
  } catch (err) {
    sheetImportError.value = err.message
  } finally {
    importingSheetWeek.value = false
  }
}

async function upload() {
  // 上传流程：构造 multipart/form-data，导入成功后刷新第一页列表并展示 Google Sheet 同步结果。
  error.value = ''
  result.value = null
  uploading.value = true
  const formData = new FormData()
  formData.append('file', file.value)
  logInfo('InspectionImport', 'Starting file import', { fileName: file.value?.name, size: file.value?.size })
  try {
    result.value = await http.post('/inspections/import', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    logInfo('InspectionImport', 'File import completed', {
      batchId: result.value.batchId,
      successRows: result.value.successRows,
      failedRows: result.value.failedRows,
      googleSheetStatus: result.value.googleSheetStatus
    })
    pagination.page = 1
    await loadRecords()
    await checkGoogleSheetStatus()
  } catch (err) {
    logError('InspectionImport', 'File import failed', err)
    error.value = err.message
  } finally {
    uploading.value = false
  }
}

async function checkGoogleSheetStatus() {
  // 手动检查 Google Sheet 状态，不写入数据，只用于确认 Web App/服务账号是否可连通。
  logInfo('InspectionImport', 'Checking Google Sheet status')
  try {
    googleSheetCheck.value = await http.get('/inspections/google-sheet/status')
    logInfo('InspectionImport', 'Google Sheet status check completed', googleSheetCheck.value)
  } catch (err) {
    logError('InspectionImport', 'Google Sheet status check failed', err)
    googleSheetCheck.value = { status: 'failed', message: err.message }
  }
}

function searchRecords() {
  // 查询会重置到第一页，并清空旧选择，避免跨条件误删或误导出。
  logInfo('InspectionImport', 'Running record search', { ...filters })
  clearSelection()
  pagination.page = 1
  loadRecords()
}

function clearFilters() {
  // 清空日期、Inspector、Sales、关键字条件后重新加载第一页。
  logInfo('InspectionImport', 'Clearing record search filters')
  Object.assign(filters, { startDate: '', endDate: '', inspector: '', sales: '', keyword: '' })
  clearSelection()
  pagination.page = 1
  loadRecords()
}

function changePage(page) {
  // 页码限制在 1 到 totalPages 范围内，避免请求不存在的页。
  pagination.page = Math.min(Math.max(1, page), totalPages.value)
  loadRecords()
}

function jumpPage() {
  // 输入页码后回车跳转，非法输入按第一页处理。
  changePage(Number(pageInput.value) || 1)
}

function changePageSize() {
  // 修改每页条数后回到第一页，并清空已选数据。
  clearSelection()
  pagination.page = 1
  loadRecords()
}

async function loadRecords() {
  // 从后端分页接口读取导入记录，筛选条件与分页参数一起传递。
  logInfo('InspectionImport', 'Loading imported inspection records', { ...filters, page: pagination.page, size: pagination.size })
  const data = await http.get('/inspections/records', { params: { ...filters, page: pagination.page, size: pagination.size } })
  records.value = data.records || []
  total.value = data.total || 0
  pagination.page = data.page || pagination.page
  pagination.size = data.size || pagination.size
  pageInput.value = pagination.page
  selectedIds.value = selectedAllRecords.value ? [...visibleIds.value] : selectedIds.value.filter((id) => visibleIds.value.includes(id))
}

function toggleSelectAll(event) {
  // 表头复选框表示选择当前查询范围；前端用 selectedAllRecords 区分“全部”和“当前页 ids”。
  selectedAllRecords.value = event.target.checked
  selectedIds.value = event.target.checked ? [...visibleIds.value] : []
}

function toggleRecordSelection(id, event) {
  // 在“选择全部”状态下取消某一行，会切回当前页选择模式。
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
  // 行复选框状态同时受“选择全部”和 selectedIds 控制。
  return selectedAllRecords.value || selectedIds.value.includes(id)
}

function clearSelection() {
  // 清空所有选择状态，避免筛选、分页、删除后保留旧勾选。
  selectedAllRecords.value = false
  selectedIds.value = []
}

async function viewRecord(id) {
  // 查看详情时按 id 重新读取最新数据，避免列表缓存不是最新状态。
  dialogError.value = ''
  activeRecord.value = await http.get(`/inspections/records/${id}`)
  dialogMode.value = 'detail'
}

async function editRecord(id) {
  // 编辑前按 id 读取最新数据并填充表单。
  dialogError.value = ''
  const record = await http.get(`/inspections/records/${id}`)
  fillEditForm(record)
  dialogMode.value = 'edit'
}

function closeDialog() {
  // 关闭弹窗时同时清理错误信息和当前记录引用。
  dialogMode.value = ''
  dialogError.value = ''
  activeRecord.value = null
}

function fillEditForm(record) {
  // 后端返回字段可能为 null，表单统一转换为空字符串便于输入框展示。
  activeRecord.value = record
  Object.keys(editForm).forEach((key) => {
    editForm[key] = record[key] ?? ''
  })
}

function displayValue(value) {
  // 空值统一显示短横线，日期时间统一显示为 YYYY-MM-DD HH:mm:ss。
  return formatDisplayValue(value)
}

function buildEditPayload() {
  // 根据字段类型把表单值转换回后端需要的 number/date/null/string。
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
  // 保存编辑后刷新当前页，保证列表展示与数据库一致。
  dialogError.value = ''
  saving.value = true
  logInfo('InspectionImport', 'Saving imported record changes', { id: editForm.id })
  try {
    await http.put(`/inspections/records/${editForm.id}`, buildEditPayload())
    closeDialog()
    await loadRecords()
  } catch (err) {
    logError('InspectionImport', 'Failed to save imported record', err)
    dialogError.value = err.message
  } finally {
    saving.value = false
  }
}

async function cancelRecord(record) {
  // 取消操作只更新状态为 Cancelled，不删除记录；操作前二次确认。
  if (!window.confirm(`Cancel record ${record.customerName || record.macId || record.id}?`)) {
    return
  }
  await http.put(`/inspections/records/${record.id}/cancel`)
  await loadRecords()
}

async function deleteRecord(record) {
  // 单条删除是物理删除，操作前二次确认并在删除后调整页码。
  if (!window.confirm(`Delete record ${record.customerName || record.macId || record.id}?`)) {
    return
  }
  await http.delete(`/inspections/records/${record.id}`)
  clearSelection()
  await reloadAfterDelete()
}

async function batchDeleteRecords() {
  // 批量删除分两种：选择全部时按当前查询条件删，普通选择时按 ids 删。
  if (!selectedCount.value) {
    return
  }
  if (selectedAllRecords.value) {
    if (!window.confirm(`Delete all ${total.value} records matching current query?`)) {
      return
    }
    logInfo('InspectionImport', 'Deleting all records matching current filters', { total: total.value, ...filters })
    await http.delete('/inspections/records/query', { data: { ...filters } })
  } else {
    if (!window.confirm(`Delete ${selectedIds.value.length} selected records?`)) {
      return
    }
    logInfo('InspectionImport', 'Deleting selected records', { ids: selectedIds.value })
    await http.delete('/inspections/records/batch', { data: { ids: selectedIds.value } })
  }
  clearSelection()
  await reloadAfterDelete()
}

async function exportAllRecords() {
  // 导出当前筛选条件下的全部记录。
  await exportRecords(false)
}

async function exportSelectedRecords() {
  // 导出前端勾选的记录；若处于选择全部状态，则按当前筛选条件导出全部。
  if (!selectedCount.value) {
    return
  }
  await exportRecords(true)
}

async function exportRecords(selectedOnly) {
  // 调用后端 Excel 导出接口，并根据导出模式生成不同文件名。
  exportError.value = ''
  exporting.value = true
  try {
    const payload = { ...filters }
    if (selectedOnly && !selectedAllRecords.value) {
      payload.ids = selectedIds.value
    }
    logInfo('InspectionImport', selectedOnly ? 'Exporting selected records' : 'Exporting all matching records', payload)
    const blob = await http.post('/inspections/records/export', payload, { responseType: 'blob' })
    downloadBlob(blob, selectedOnly ? 'inspection-records-selected.xlsx' : 'inspection-records-all.xlsx')
  } catch (err) {
    logError('InspectionImport', 'Failed to export records', err)
    exportError.value = err.message
  } finally {
    exporting.value = false
  }
}

function downloadBlob(blob, fileName) {
  // 浏览器端下载 Excel Blob，下载完成后立即释放 object URL。
  const url = window.URL.createObjectURL(new Blob([blob], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }))
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.URL.revokeObjectURL(url)
}

async function reloadAfterDelete() {
  // 删除当前页最后一条后自动回退一页，避免停留在空页。
  await loadRecords()
  if (!records.value.length && total.value > 0 && pagination.page > 1) {
    pagination.page -= 1
    await loadRecords()
  }
}
</script>
