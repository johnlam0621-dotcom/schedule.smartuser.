<template>
  <main class="page inspection-search-page">
    <section class="panel search-filter-panel">
      <h2>Search inspections</h2>
      <p class="muted">Search imported inspections by MAC ID, mobile, customer, address, sales, or inspector.</p>
      <form class="search-form" @submit.prevent="search">
        <label class="search-wide">
          MAC ID / mobile / address
          <input v-model.trim="filters.keyword" placeholder="MAC ID, 04..., customer, or street/suburb" />
        </label>
        <label>
          Inspector route
          <input v-model.trim="filters.inspector" placeholder="Inspector name" />
        </label>
        <label>
          Sales
          <input v-model.trim="filters.sales" placeholder="Sales name" />
        </label>
        <label>
          Start date
          <input v-model="filters.startDate" type="date" />
        </label>
        <label>
          End date
          <input v-model="filters.endDate" type="date" />
        </label>
        <div class="search-actions search-wide">
          <button :disabled="loading">{{ loading ? 'Searching...' : 'Search' }}</button>
          <button type="button" class="secondary" @click="showRoute">Show route</button>
          <button type="button" class="secondary" @click="clearSearch">Clear</button>
        </div>
      </form>
      <div class="error" v-if="error">{{ error }}</div>
    </section>

    <section class="search-workspace">
      <div class="panel search-results-panel">
        <div class="panel-title-row">
          <h2>Results</h2>
          <span class="muted">{{ searched ? `${total} inspections found` : 'Enter search details above' }}</span>
        </div>
        <div v-if="searched && !records.length && !loading" class="search-empty">No matching inspections found.</div>
        <button
          v-for="record in records"
          :key="record.id"
          type="button"
          class="search-result-card"
          :class="{ active: activeRecord?.id === record.id }"
          @click="selectRecord(record)"
        >
          <strong>{{ record.customerName || record.macId || `Inspection ${record.id}` }}</strong>
          <span>{{ record.macId || 'No MAC ID' }} · {{ record.phoneNumber || 'No mobile' }}</span>
          <span>{{ record.address || 'No address' }}</span>
          <span>{{ record.inspector || 'Unassigned' }} · {{ record.inspectionDate || 'No date' }} {{ record.inspectionTime || '' }}</span>
        </button>
        <div class="pagination" v-if="totalPages > 1">
          <button class="secondary" :disabled="pagination.page <= 1" @click="changePage(pagination.page - 1)">Previous</button>
          <span>Page {{ pagination.page }} / {{ totalPages }}</span>
          <button class="secondary" :disabled="pagination.page >= totalPages" @click="changePage(pagination.page + 1)">Next</button>
        </div>
      </div>

      <aside class="panel search-detail-panel">
        <h2>Inspection details</h2>
        <div v-if="detailLoading" class="muted">Loading details...</div>
        <div v-else-if="!activeRecord" class="search-empty">Select a result to show its complete details here.</div>
        <div v-else class="search-detail-grid">
          <div v-for="field in detailFields" :key="field.key" :class="{ wide: field.wide }">
            <span class="muted">{{ field.label }}</span>
            <strong>{{ displayValue(activeRecord[field.key]) }}</strong>
          </div>
        </div>
      </aside>
    </section>
  </main>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import http from '../api/http'
import { formatDisplayValue } from '../utils/date'

// 搜索页复用 Inspection Import 的查询接口，并在右侧保留当前选中记录的完整详情。
const router = useRouter()
const filters = reactive({ keyword: '', inspector: '', sales: '', startDate: '', endDate: '' })
const records = ref([])
const activeRecord = ref(null)
const total = ref(0)
const loading = ref(false)
const detailLoading = ref(false)
const searched = ref(false)
const error = ref('')
const pagination = reactive({ page: 1, size: 10 })
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pagination.size)))

// 详情字段与导入页、路线页保持一致，确保三个入口展示相同的业务数据。
const detailFields = [
  { label: 'Customer', key: 'customerName' },
  { label: 'MAC ID', key: 'macId' },
  { label: 'Phone', key: 'phoneNumber' },
  { label: 'Sales', key: 'sales' },
  { label: 'Inspector', key: 'inspector' },
  { label: 'Status', key: 'status' },
  { label: 'Inspection date', key: 'inspectionDate' },
  { label: 'Inspection time', key: 'inspectionTime' },
  { label: 'Product', key: 'projects' },
  { label: 'Suburb', key: 'suburb' },
  { label: 'City council', key: 'cityCouncil' },
  { label: 'Address', key: 'address', wide: true },
  { label: 'Scheduler remarks', key: 'schedulerRemarks', wide: true },
  { label: 'Quotation team report', key: 'quotationTeamReport', wide: true },
  { label: 'Created at', key: 'createdAt' },
  { label: 'Updated at', key: 'updatedAt' }
]

async function search() {
  // 用户提交新条件时从第一页开始查询，避免沿用上一次搜索的页码。
  pagination.page = 1
  await loadResults()
}

async function loadResults() {
  // 将关键词、检查员、销售和日期范围一次传给后端分页接口。
  loading.value = true
  error.value = ''
  searched.value = true
  try {
    const data = await http.get('/inspections/records', {
      params: { ...filters, page: pagination.page, size: pagination.size }
    })
    records.value = data.records || []
    total.value = data.total || 0
    pagination.page = data.page || pagination.page
    if (records.value.length) {
      // 默认选中当前页第一条记录，让右侧详情区域立即显示有效内容。
      await selectRecord(records.value[0])
    } else {
      activeRecord.value = null
    }
  } catch (requestError) {
    error.value = requestError.message
  } finally {
    loading.value = false
  }
}

async function selectRecord(record) {
  // 点击结果后按主键重新读取最新记录，避免详情使用过期的列表快照。
  activeRecord.value = record
  detailLoading.value = true
  try {
    activeRecord.value = await http.get(`/inspections/records/${record.id}`)
  } catch (requestError) {
    error.value = requestError.message
  } finally {
    detailLoading.value = false
  }
}

async function changePage(page) {
  // 页码始终限制在有效范围内，再使用当前搜索条件重新查询。
  pagination.page = Math.min(Math.max(1, page), totalPages.value)
  await loadResults()
}

function clearSearch() {
  // 清空条件、结果和详情，使页面恢复到首次进入时的状态。
  Object.assign(filters, { keyword: '', inspector: '', sales: '', startDate: '', endDate: '' })
  records.value = []
  activeRecord.value = null
  total.value = 0
  searched.value = false
  error.value = ''
  pagination.page = 1
}

function showRoute() {
  // 把当前搜索条件通过 URL 查询参数传给 Routes Map，实现页面间条件联动。
  router.push({
    path: '/routes/map',
    query: {
      inspector: filters.inspector || undefined,
      startDate: filters.startDate || undefined,
      endDate: filters.endDate || undefined,
      keyword: filters.keyword || undefined
    }
  })
}

function displayValue(value) {
  // 空值统一显示短横线，日期时间值复用项目的格式化工具。
  return value === null || value === undefined || value === '' ? '-' : formatDisplayValue(value)
}
</script>
