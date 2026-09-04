<template>
  <main class="confirmation-page">
    <section class="confirmation-header">
      <div>
        <h2>Confirm Inspection</h2>
        <p>Check each inspector's weekly results and uploaded evidence before manager sign-off.</p>
      </div>
      <div class="week-controls">
        <button type="button" class="secondary" @click="moveWeek(-1)">Previous week</button>
        <label>Week
          <input v-model="weekInput" type="date" @change="loadWeek" />
        </label>
        <button type="button" class="secondary" @click="useCurrentWeek">Current week</button>
        <button type="button" class="secondary" @click="moveWeek(1)">Next week</button>
      </div>
    </section>

    <p v-if="error" class="confirmation-message error">{{ error }}</p>
    <p v-if="notice" class="confirmation-message success">{{ notice }}</p>

    <section class="confirmation-summary">
      <div><strong>{{ data.jobCount }}</strong><span>Total jobs</span></div>
      <div class="done"><strong>{{ data.doneCount }}</strong><span>Done</span></div>
      <div class="unavailable"><strong>{{ data.unavailableCount }}</strong><span>Unavailable</span></div>
      <div class="pending"><strong>{{ data.pendingCount }}</strong><span>Pending</span></div>
      <div class="confirmed"><strong>{{ data.confirmedCount }}</strong><span>Confirmed</span></div>
    </section>

    <form class="confirmation-search" @submit.prevent="applySearch">
      <label>Search MACID
        <input v-model.trim="searchMacId" type="search" inputmode="numeric" placeholder="Enter MACID" />
      </label>
      <label>Inspector
        <select v-model="searchInspector">
          <option value="">Choose inspector</option>
          <option v-for="name in inspectorNames" :key="name" :value="name">{{ name }}</option>
        </select>
      </label>
      <button type="submit">Search</button>
      <button v-if="searchApplied" type="button" class="secondary" @click="clearSearch">Clear</button>
    </form>

    <section class="week-bar">
      <div><strong>{{ formattedWeek }}</strong><span>{{ searchApplied ? `${resultCount} matching inspection(s)` : `${data.inspectors.length} inspector(s)` }}</span></div>
      <div v-if="searchApplied" class="filter-tabs" aria-label="Inspection filter">
        <button v-for="item in filters" :key="item.value" type="button" :class="{ active: filter === item.value }" @click="filter = item.value">{{ item.label }}</button>
      </div>
    </section>

    <div v-if="loading" class="confirmation-empty">Loading weekly inspections…</div>
    <div v-else-if="!searchApplied" class="confirmation-empty">Enter a MACID or choose an inspector, then click Search.</div>
    <div v-else-if="!filteredGroups.length" class="confirmation-empty">No matching inspections were found for this week.</div>

    <section v-for="group in filteredGroups" :key="group.name" class="inspector-group">
      <header class="inspector-heading">
        <div><h3>{{ group.name }}</h3><span>{{ group.jobs.length }} inspection(s)</span></div>
        <span>{{ group.jobs.filter((job) => job.confirmed).length }}/{{ group.jobs.length }} confirmed</span>
      </header>

      <article v-for="job in group.jobs" :key="job.id" class="confirmation-card" :class="{ confirmed: job.confirmed }">
        <header class="job-heading">
          <div>
            <div class="job-date">{{ formatDate(job.date) }} · {{ job.start || 'Time not set' }}</div>
            <h4>{{ job.customerName || 'Unnamed customer' }}</h4>
            <p>MACID: {{ job.macId || '—' }} · {{ job.projects || 'No product recorded' }}</p>
            <p>{{ job.address || 'No address recorded' }}</p>
          </div>
          <div class="job-state">
            <span class="status-pill" :class="job.fieldStatus">{{ statusLabel(job.fieldStatus) }}</span>
            <span v-if="job.confirmed" class="confirmed-pill">✓ Manager confirmed</span>
          </div>
        </header>

        <div v-if="job.schedulerRemarks || job.inspectorRemark" class="job-notes">
          <p v-if="job.schedulerRemarks"><strong>Scheduler:</strong> {{ job.schedulerRemarks }}</p>
          <p v-if="job.inspectorRemark"><strong>Inspector:</strong> {{ job.inspectorRemark }}</p>
        </div>

        <div class="review-row">
          <button type="button" class="secondary" @click="toggleMedia(job)">
            {{ expanded[job.id] ? 'Hide media' : `Review media (${fileCount(job)})` }}
          </button>
          <div v-if="job.confirmed" class="confirmation-audit">
            Confirmed by {{ job.confirmedBy || 'manager' }}<span v-if="job.confirmedAt"> · {{ formatDateTime(job.confirmedAt) }}</span>
          </div>
          <button v-if="job.confirmed" type="button" class="secondary reopen" :disabled="saving[job.id]" @click="setConfirmed(job, false)">Reopen review</button>
          <button v-else type="button" :disabled="saving[job.id] || !isFinal(job)" :title="isFinal(job) ? '' : 'Inspector must mark the job Done or Unavailable first'" @click="setConfirmed(job, true)">
            {{ saving[job.id] ? 'Saving…' : 'Confirm inspection' }}
          </button>
        </div>

        <section v-if="expanded[job.id]" class="job-media">
          <div v-if="!fileCount(job)" class="media-empty">No photos or videos have been uploaded.</div>
          <div v-else class="media-grid">
            <button v-for="item in photoFiles(job)" :key="item.file.id" type="button" class="media-card" @click="openMedia(item, false)">
              <span class="media-thumb"><span v-if="!previews[item.file.id]">Loading…</span><img v-else :src="previews[item.file.id]" :alt="item.label" /></span>
              <strong>{{ item.label }}</strong><small>{{ item.file.name }}</small>
              <em v-if="item.file.remark">{{ item.file.remark }}</em>
            </button>
            <button v-for="item in videoFiles(job)" :key="item.file.id" type="button" class="media-card video" @click="openMedia(item, true)">
              <span class="media-thumb">▶</span><strong>{{ item.label }}</strong><small>{{ item.file.name }}</small>
              <em v-if="item.file.remark">{{ item.file.remark }}</em>
            </button>
          </div>
        </section>
      </article>
    </section>

    <div v-if="activeMedia" class="confirmation-modal" role="dialog" aria-modal="true" @click.self="closeMedia">
      <section>
        <button type="button" class="modal-close" aria-label="Close" @click="closeMedia">×</button>
        <p v-if="mediaLoading">Loading media…</p>
        <video v-else-if="activeMedia.video" :src="activeMedia.url" controls autoplay></video>
        <img v-else :src="activeMedia.url" :alt="activeMedia.label" />
        <footer><strong>{{ activeMedia.label }}</strong><span>{{ activeMedia.name }}</span></footer>
      </section>
    </div>
  </main>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import http from '../api/http'
import { authState } from '../store/auth'

const weekInput = ref(mondayText(new Date()))
const loading = ref(false)
const filter = ref('needs-review')
const searchMacId = ref('')
const searchInspector = ref('')
const appliedMacId = ref('')
const appliedInspector = ref('')
const searchApplied = ref(false)
const error = ref('')
const notice = ref('')
const data = reactive({ weekStart: '', weekEnd: '', jobCount: 0, doneCount: 0, unavailableCount: 0, pendingCount: 0, confirmedCount: 0, inspectors: [] })
const expanded = reactive({})
const saving = reactive({})
const previews = reactive({})
const activeMedia = ref(null)
const mediaLoading = ref(false)
const filters = [
  { value: 'all', label: 'All' }, { value: 'needs-review', label: 'Needs review' },
  { value: 'confirmed', label: 'Confirmed' }, { value: 'pending', label: 'Pending' }
]

const formattedWeek = computed(() => data.weekStart ? `${formatDate(data.weekStart)} – ${formatDate(data.weekEnd)}` : '—')
const inspectorNames = computed(() => (data.inspectors || []).map((group) => group.name).filter(Boolean).sort((a, b) => a.localeCompare(b)))
const filteredGroups = computed(() => (data.inspectors || []).map((group) => ({
  ...group,
  jobs: (group.jobs || []).filter((job) => {
    if (!searchApplied.value) return false
    if (appliedInspector.value && group.name !== appliedInspector.value) return false
    if (appliedMacId.value && !String(job.macId || '').toLowerCase().includes(appliedMacId.value.toLowerCase())) return false
    if (filter.value === 'needs-review') return isFinal(job) && !job.confirmed
    if (filter.value === 'confirmed') return job.confirmed
    if (filter.value === 'pending') return !isFinal(job)
    return true
  })
})).filter((group) => group.jobs.length))
const resultCount = computed(() => filteredGroups.value.reduce((total, group) => total + group.jobs.length, 0))

onMounted(loadWeek)
onBeforeUnmount(clearObjectUrls)

async function loadWeek() {
  loading.value = true; error.value = ''; notice.value = ''; clearObjectUrls()
  Object.keys(expanded).forEach((key) => delete expanded[key])
  try {
    const response = await http.get('/inspector/weekly-confirmations', { params: { weekStart: weekInput.value } })
    Object.assign(data, response)
    weekInput.value = response.weekStart
  } catch (requestError) {
    error.value = requestError.message || 'Weekly inspections could not be loaded.'
  } finally { loading.value = false }
}

function moveWeek(amount) { const date = new Date(`${weekInput.value}T12:00:00`); date.setDate(date.getDate() + amount * 7); weekInput.value = localText(date); loadWeek() }
function useCurrentWeek() { weekInput.value = mondayText(new Date()); loadWeek() }
function applySearch() {
  error.value = ''
  if (!searchMacId.value && !searchInspector.value) {
    searchApplied.value = false
    error.value = 'Enter a MACID or choose an inspector before searching.'
    return
  }
  appliedMacId.value = searchMacId.value
  appliedInspector.value = searchInspector.value
  filter.value = 'all'
  searchApplied.value = true
}
function clearSearch() {
  searchMacId.value = ''; searchInspector.value = ''; appliedMacId.value = ''; appliedInspector.value = ''
  searchApplied.value = false; filter.value = 'needs-review'; error.value = ''
}

async function setConfirmed(job, confirmed) {
  saving[job.id] = true; error.value = ''; notice.value = ''
  try {
    await http.put(`/inspector/weekly-confirmations/${job.id}`, { confirmed })
    job.confirmed = confirmed
    job.confirmedBy = confirmed ? (authState.user?.realName || authState.user?.username || 'Manager') : ''
    job.confirmedAt = confirmed ? new Date().toISOString() : null
    data.confirmedCount += confirmed ? 1 : -1
    notice.value = confirmed ? 'Inspection confirmed.' : 'Inspection reopened for review.'
  } catch (requestError) { error.value = requestError.message || 'The inspection confirmation could not be saved.' }
  finally { delete saving[job.id] }
}

async function toggleMedia(job) {
  expanded[job.id] = !expanded[job.id]
  if (!expanded[job.id]) return
  await Promise.all(photoFiles(job).map(async ({ file }) => {
    if (previews[file.id]) return
    try { const response = await fetch(file.url, { headers: { Authorization: `Bearer ${authState.token}` } }); if (response.ok) previews[file.id] = URL.createObjectURL(await response.blob()) } catch (_) {}
  }))
}

async function openMedia(item, video) {
  mediaLoading.value = video
  activeMedia.value = { label: item.label, name: item.file.name, video, url: video ? '' : previews[item.file.id] }
  if (!video) return
  try {
    const response = await fetch(item.file.url, { headers: { Authorization: `Bearer ${authState.token}` } })
    if (!response.ok) throw new Error('Video could not be loaded.')
    activeMedia.value.url = URL.createObjectURL(await response.blob())
  } catch (requestError) { error.value = requestError.message; closeMedia() }
  finally { mediaLoading.value = false }
}

function closeMedia() { if (activeMedia.value?.video && activeMedia.value.url) URL.revokeObjectURL(activeMedia.value.url); activeMedia.value = null; mediaLoading.value = false }
function clearObjectUrls() { closeMedia(); Object.values(previews).forEach(URL.revokeObjectURL); Object.keys(previews).forEach((key) => delete previews[key]) }
function allFiles(job) { return (job.photoSlots || []).flatMap((slot) => (slot.files || []).map((file) => ({ file, label: slot.label, video: String(slot.group).includes('video') || String(slot.key).includes('video') }))) }
function photoFiles(job) { return allFiles(job).filter((item) => !item.video) }
function videoFiles(job) { return allFiles(job).filter((item) => item.video) }
function fileCount(job) { return allFiles(job).length }
function isFinal(job) { return ['done', 'customer_unavailable'].includes(job.fieldStatus) }
function statusLabel(status) { return status === 'done' ? 'Done' : status === 'customer_unavailable' ? 'Unavailable' : 'Pending' }
function formatDate(value) { if (!value) return '—'; return new Intl.DateTimeFormat('en-AU', { day: '2-digit', month: 'short', year: 'numeric' }).format(new Date(`${String(value).slice(0, 10)}T12:00:00`)) }
function formatDateTime(value) { if (!value) return ''; return new Intl.DateTimeFormat('en-AU', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) }
function localText(date) { const y = date.getFullYear(); const m = String(date.getMonth() + 1).padStart(2, '0'); const d = String(date.getDate()).padStart(2, '0'); return `${y}-${m}-${d}` }
function mondayText(date) { const copy = new Date(date); const day = copy.getDay() || 7; copy.setDate(copy.getDate() - day + 1); return localText(copy) }
</script>

<style scoped>
.confirmation-page{padding:18px;display:grid;gap:16px;color:#152238}.confirmation-header,.week-bar,.inspector-group,.confirmation-card,.confirmation-search{background:#fff;border:1px solid #d9e3ef;border-radius:12px}.confirmation-header{padding:20px;display:flex;justify-content:space-between;gap:20px;align-items:end}.confirmation-header h2{margin:0 0 5px}.confirmation-header p{margin:0;color:#62738b}.week-controls{display:flex;align-items:end;gap:8px;flex-wrap:wrap}.week-controls label{display:grid;gap:4px;font-size:12px;color:#52647c}.week-controls input{padding:9px;border:1px solid #c8d5e5;border-radius:6px}.confirmation-summary{display:grid;grid-template-columns:repeat(5,1fr);gap:10px}.confirmation-summary div{background:#fff;border:1px solid #d9e3ef;border-radius:10px;padding:14px;display:grid;gap:3px}.confirmation-summary strong{font-size:24px}.confirmation-summary span{font-size:12px;color:#687b94}.confirmation-summary .done{border-top:4px solid #17a673}.confirmation-summary .unavailable{border-top:4px solid #e59b1a}.confirmation-summary .pending{border-top:4px solid #7c8da5}.confirmation-summary .confirmed{border-top:4px solid #1677d2}.confirmation-search{padding:14px 16px;display:grid;grid-template-columns:minmax(220px,1fr) minmax(220px,1fr) auto auto;gap:10px;align-items:end}.confirmation-search label{display:grid;gap:5px;font-size:12px;font-weight:700;color:#405671}.confirmation-search input,.confirmation-search select{min-height:40px;padding:8px 10px;border:1px solid #becde0;border-radius:6px;background:#fff;color:#17283d}.week-bar{padding:12px 16px;display:flex;justify-content:space-between;align-items:center}.week-bar div:first-child{display:grid}.week-bar span{font-size:12px;color:#6b7e96}.filter-tabs{display:flex;gap:5px}.filter-tabs button{background:#edf3fa;color:#30455f}.filter-tabs button.active{background:#176fd1;color:#fff}.inspector-group{overflow:hidden}.inspector-heading{background:#edf5ff;padding:13px 16px;display:flex;justify-content:space-between;align-items:center}.inspector-heading h3{margin:0}.inspector-heading div span,.inspector-heading>span{font-size:12px;color:#526b86}.confirmation-card{margin:12px;padding:15px}.confirmation-card.confirmed{border-left:5px solid #168c68}.job-heading{display:flex;justify-content:space-between;gap:16px}.job-heading h4{margin:4px 0;font-size:17px}.job-heading p{margin:3px 0;color:#516780;font-size:13px}.job-date{font-size:12px;font-weight:700;color:#1765b1}.job-state{display:flex;align-items:end;gap:6px;flex-direction:column}.status-pill,.confirmed-pill{padding:5px 9px;border-radius:20px;font-size:12px;font-weight:700}.status-pill.done{background:#daf6e9;color:#08764f}.status-pill.customer_unavailable{background:#fff0d5;color:#985c00}.status-pill.fixed{background:#e9edf2;color:#556477}.confirmed-pill{background:#dcecff;color:#075da8}.job-notes{background:#f4f7fb;border-radius:7px;padding:8px 10px;margin-top:10px}.job-notes p{margin:3px 0;font-size:13px}.review-row{display:flex;align-items:center;gap:8px;margin-top:12px}.review-row button:last-child{margin-left:auto}.confirmation-audit{font-size:12px;color:#4c637c}.job-media{border-top:1px solid #e3eaf2;margin-top:12px;padding-top:12px}.media-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(145px,1fr));gap:9px}.media-card{background:#fff;color:#17283d;border:1px solid #cfdae8;text-align:left;padding:7px;display:grid;gap:5px}.media-thumb{height:105px;background:#edf2f7;border-radius:6px;display:grid;place-items:center;overflow:hidden}.media-thumb img{width:100%;height:100%;object-fit:cover}.media-card.video .media-thumb{font-size:35px;color:#176fd1}.media-card small,.media-card em{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:11px}.media-card em{color:#536a84}.media-empty,.confirmation-empty{padding:30px;text-align:center;background:#fff;border:1px dashed #c9d5e4;border-radius:10px;color:#60738c}.confirmation-message{padding:10px;border-radius:7px;margin:0}.confirmation-message.error{background:#fff0f0;color:#a52020}.confirmation-message.success{background:#e9f8f1;color:#086946}.confirmation-modal{position:fixed;inset:0;background:#0c1728dc;display:grid;place-items:center;z-index:100;padding:25px}.confirmation-modal>section{position:relative;background:#fff;max-width:1100px;max-height:94vh;border-radius:10px;overflow:auto;padding:12px}.confirmation-modal img,.confirmation-modal video{max-width:100%;max-height:78vh;display:block}.confirmation-modal footer{display:grid;padding:10px 4px 2px}.confirmation-modal footer span{font-size:12px;color:#63758b}.modal-close{position:absolute;right:18px;top:18px;border-radius:50%;z-index:2}.secondary{background:#eef3f8;color:#263a52}.reopen{margin-left:auto!important}.confirmation-page button{border:0;border-radius:6px;padding:9px 12px;cursor:pointer;background:#1677d2;color:#fff}.confirmation-page button:disabled{opacity:.5;cursor:not-allowed}@media(max-width:900px){.confirmation-header{align-items:stretch;flex-direction:column}.confirmation-summary{grid-template-columns:repeat(2,1fr)}.confirmation-search{grid-template-columns:1fr}.week-bar{align-items:stretch;gap:10px;flex-direction:column}.job-heading{flex-direction:column}.job-state{align-items:start;flex-direction:row}.review-row{align-items:stretch;flex-direction:column}.review-row button:last-child,.reopen{margin-left:0!important}.filter-tabs{overflow-x:auto}.filter-tabs button{white-space:nowrap}}
</style>
