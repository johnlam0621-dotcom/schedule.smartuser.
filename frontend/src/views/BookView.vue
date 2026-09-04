<template>
  <main class="page book-page workbench-page">
    <section class="panel book-entry-panel">
      <div class="book-form">
        <div>
          <h2>New appointment</h2>
          <div v-if="form.rescheduleId" class="reschedule-banner">
            Rescheduling appointment #{{ form.rescheduleId }}
            <button class="secondary compact-button" @click="clearRescheduleSelection">Clear</button>
          </div>
          <label class="wide">
            CRM customer card
            <textarea v-model="form.crmCard" rows="5" placeholder="Paste the CRM customer card here"></textarea>
          </label>
          <div class="inline-actions">
            <button class="secondary" @click="applyCrmCard">Apply</button>
            <button class="secondary" @click="clearCrmCard">Clear</button>
          </div>
          <p class="muted compact-copy">
            Recognises MACID, Name, Phone, Preferred Mobile and Address. QRID, coupon and Dataforce job ID are ignored.
          </p>
        </div>

        <div class="book-fields">
          <label>
            MAC ID / CRM customer ID
            <div class="field-with-button">
              <input v-model.trim="form.macId" placeholder="Enter MACID to auto-fill customer details" @blur="loadCustomerByMacId(true)" @keyup.enter="loadCustomerByMacId(false)" />
              <button class="secondary" @click="loadCustomerByMacId(false)">Load</button>
            </div>
          </label>
          <label>Customer name<input v-model.trim="form.customerName" placeholder="Jane Smith" /></label>
          <label>Phone / landline<input v-model.trim="form.phoneNumber" placeholder="04xx xxx xxx or landline" /></label>
          <label>Secondary phone / preferred mobile<input v-model.trim="form.secondaryPhone" placeholder="Optional secondary contact" /></label>
          <label>Email<input v-model.trim="form.email" placeholder="customer@email.com" /></label>
          <label class="wide">
            Australian address
            <input
              ref="addressInput"
              v-model.trim="form.address"
              placeholder="Start typing an Australian address"
              autocomplete="street-address"
              @focus="retryAddressAutocomplete"
            />
            <span class="muted compact-copy">{{ addressHint }}</span>
          </label>
          <label>
            State
            <select v-model="form.state">
              <option value="">Choose state</option>
              <option v-for="state in stateOptions" :key="state" :value="state">{{ state }}</option>
            </select>
          </label>
          <template v-if="canManageBookingWindow">
            <label>
              Start date
              <input v-model="form.inspectionDate" type="date" :min="today" @change="handleStartDateChange" />
            </label>
            <label>
              End date
              <input v-model="form.inspectionEndDate" type="date" :min="form.inspectionDate || today" :max="managerMaxEndDate" @change="handleEndDateChange" />
            </label>
          </template>
          <div v-else class="wide imported-date-help">
            <strong>Automatic five-day search: {{ bookingDateLabel }}</strong>
            <span>This account receives the earliest route-safe recommendation for each day.</span>
          </div>
          <div v-if="canManageBookingWindow && !quickAvailability.length" class="wide imported-date-help">
            <strong>No Google Sheet spaces are available from {{ displayDate(form.inspectionDate) }} to {{ displayDate(form.inspectionEndDate) }}.</strong>
            <span v-if="availableDates.length">Choose an imported date:</span>
            <span v-else>Refresh the Sheet tabs and import the week covering this date range before booking.</span>
            <div v-if="availableDates.length" class="imported-date-list">
              <button
                v-for="date in availableDates"
                :key="date"
                type="button"
                class="secondary"
                @click="selectAvailableDate(date)"
              >
                {{ displayDate(date) }}
              </button>
            </div>
          </div>
          <label>
            Inspector work duration
            <output class="calculated-duration">
              {{ inspectorWorkMinutes ? `${inspectorWorkMinutes} minutes` : 'Select products' }}
            </output>
            <span class="muted compact-copy">1 product = 30 min · 2 products = 45 min · 3 products = 60 min</span>
          </label>
          <label class="wide">
            Notes
            <textarea v-model.trim="form.notes" rows="3" placeholder="Gate code, preferred contact, special instructions"></textarea>
          </label>
          <div class="wide">
            <div class="field-label">Inspection products</div>
            <div class="product-check-grid">
              <label v-for="product in atomicProducts" :key="product" class="check-card">
                <input v-model="form.products" type="checkbox" :value="product" />
                <span>{{ product }}</span>
              </label>
            </div>
          </div>
          <div class="inline-actions wide">
            <button :disabled="findingSlots" @click="findSlots">{{ findingSlots ? 'Finding...' : 'Find slots' }}</button>
            <button class="secondary" @click="resetForm">Reset</button>
          </div>
        </div>
      </div>
      <div v-if="statusMessage" class="status">{{ statusMessage }}</div>
      <div v-if="warningMessage" class="book-warning">{{ warningMessage }}</div>
      <div v-if="errorMessage" class="error">{{ errorMessage }}</div>
    </section>

    <section class="stats">
      <div class="stat">
        <strong>{{ overview.bookedToday || 0 }}</strong>
        <span class="muted">booked today</span>
      </div>
      <div class="stat">
        <strong>{{ overview.inspectorRoutes || 0 }}</strong>
        <span class="muted">inspector routes</span>
      </div>
      <div class="stat">
        <strong>{{ overview.availableSlots || 0 }}</strong>
        <span class="muted">Google Sheet open spaces</span>
        <small v-if="slotSearchCompleted" class="muted">{{ slots.length }} route-safe</small>
      </div>
      <div class="stat">
        <strong>{{ bookingDateLabel }}</strong>
        <span class="muted">date range</span>
      </div>
    </section>

    <section class="panel book-slots-panel">
      <div class="availability-board">
        <div>
          <h2>Available-time recommendations</h2>
          <p class="muted">Based on the imported inspection schedule for {{ bookingDateLabel }}.</p>
        </div>
        <div v-if="isScheduler" class="empty-state">The system will show the earliest route-safe time for each day in this five-day window.</div>
        <div v-else-if="!quickAvailability.length" class="empty-state">
          {{ slotSearchCompleted
            ? 'No route-safe times are available for this customer in the selected date range.'
            : 'No open times remain in this date range.' }}
        </div>
        <div v-else class="availability-chips">
          <button
            v-for="item in quickAvailability"
            :key="item.timeSlot"
            type="button"
            class="availability-chip"
            :class="{ recommended: item.recommended, selected: slotFilters.timeSlot === item.timeSlot }"
            @click="chooseAvailableTime(item)"
          >
            <strong>{{ item.label }}</strong>
            <span>{{ item.count }} inspector{{ item.count === 1 ? '' : 's' }} available</span>
            <small v-if="item.recommended">Recommended</small>
          </button>
        </div>
      </div>
      <hr class="panel-divider" />
      <div class="panel-title-row">
        <h2>Recommended slots</h2>
        <label v-if="canManageBookingWindow" class="inline-control">
          Area
          <select v-model="slotFilters.area" @change="findSlots">
            <option v-for="area in areaOptions" :key="area" :value="area">{{ area }}</option>
          </select>
        </label>
        <label v-if="canManageBookingWindow" class="inline-control">
          Time slot
          <select v-model="slotFilters.timeSlot" @change="findSlots">
            <option v-for="time in timeSlotOptions" :key="time" :value="time">{{ time }}</option>
          </select>
        </label>
      </div>
      <div v-if="!slots.length" class="empty-state">{{ recommendedSlotsEmptyMessage }}</div>
      <div v-else class="slot-grid">
        <article
          v-for="slot in slots"
          :key="slot.inspector + slot.date + slot.start"
          class="slot-card"
          :class="{ 'best-slot': slot.recommendation === 'Best' || slot.recommendation === 'Earliest for this day' }"
        >
          <div class="slot-heading">
            <strong>{{ displayDate(slot.date) }} · {{ slot.start }} - {{ slot.end }}</strong>
            <span class="route-badge">Route No.{{ slot.routeNumber }}</span>
          </div>
          <div class="slot-inspector">
            <strong>{{ slot.inspector }}</strong>
            <span>{{ slot.area }}</span>
            <span class="job-badge">{{ slot.jobsAfterBooking }}/{{ slot.routeTarget }} jobs</span>
          </div>
          <span class="recommendation-badge">{{ slot.recommendation }}</span>
          <p class="slot-summary">
            {{ slot.product }} needs {{ slot.durationMinutes }} min. Builds this route toward the
            {{ slot.routeTarget }} job target ({{ slot.jobsAfterBooking }}/{{ slot.routeTarget }} jobs).
            Adds about {{ slot.totalTravelMinutes }} min driving. Travel in {{ slot.travelInMinutes }} min,
            travel out {{ slot.travelOutMinutes }} min.
          </p>
          <p class="travel-safe-note">
            Travel checked with a {{ slot.travelBufferMinutes }} min buffer. Route direction checked to avoid backtracking
            <span v-if="slot.routeContinuityMode"> (local detour {{ slot.routeDetourMinutes }} min)</span>.
          </p>
          <a class="map-route-link" :href="slot.googleMapsUrl" target="_blank" rel="noopener">
            View this route in Google Maps
          </a>
          <button :disabled="bookingKey === slotKey(slot)" @click="bookSlot(slot)">
            {{ bookingKey === slotKey(slot) ? 'Booking...' : 'Book this time' }}
          </button>
        </article>
      </div>
    </section>

    <section class="panel">
      <div class="panel-title-row">
        <h2>Reschedule pool</h2>
        <span class="muted">{{ reschedulePool.length }} waiting</span>
      </div>
      <div v-if="!reschedulePool.length" class="empty-state">No appointments are waiting to be rescheduled.</div>
      <div v-else class="table-wrap">
        <table class="records-table compact-table">
          <thead>
            <tr>
              <th>Customer</th>
              <th>MACID</th>
              <th>Phone</th>
              <th>Address</th>
              <th>Previous date/time</th>
              <th>Product</th>
              <th>Reason</th>
              <th>Action</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in reschedulePool" :key="item.id">
              <td>{{ item.customerName }}</td>
              <td>{{ item.macId }}</td>
              <td>{{ item.phoneNumber }}</td>
              <td>{{ item.address }}</td>
              <td>{{ item.inspectionDate }} {{ item.inspectionTime }}</td>
              <td>{{ item.projects }}</td>
              <td>{{ item.schedulerRemarks }}</td>
              <td><button class="secondary" @click="useReschedule(item)">Select</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="panel">
      <h2>Confirmed appointments</h2>
      <div v-if="!confirmedAppointments.length" class="empty-state">No inspections booked in the application today.</div>
      <div v-else class="table-wrap">
        <table class="records-table compact-table">
          <thead>
            <tr>
              <th>Customer</th>
              <th>MACID</th>
              <th>Phone</th>
              <th>Address</th>
              <th>Inspector</th>
              <th>Date</th>
              <th>Time</th>
              <th>Product</th>
              <th>Note</th>
              <th>Status</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in confirmedAppointments" :key="item.id">
              <td>{{ item.customerName }}</td>
              <td>{{ item.macId }}</td>
              <td>{{ item.phoneNumber }}</td>
              <td>{{ item.address }}</td>
              <td>{{ item.inspector }}</td>
              <td>{{ item.inspectionDate }}</td>
              <td>{{ item.inspectionTime }}</td>
              <td>{{ item.projects }}</td>
              <td>{{ item.schedulerRemarks }}</td>
              <td><span class="fixed-badge">{{ item.status }}</span></td>
              <td>
                <div class="appointment-actions">
                  <button class="secondary" :disabled="appointmentActionId === item.id" @click="editAppointmentTime(item)">Move booking</button>
                  <button class="danger" :disabled="appointmentActionId === item.id" @click="cancelAppointment(item)">Cancel appt</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </main>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import http from '../api/http'
import { logError, logInfo } from '../utils/logger'
import { inspectionWorkMinutes } from '../utils/schedule'
import { localDateInputValue } from '../utils/date'
import { authState } from '../store/auth'

const stateOptions = ['NSW', 'VIC', 'QLD', 'SA', 'WA', 'TAS', 'ACT', 'NT']
const atomicProducts = ['MAC', 'DAC', 'Battery', 'HP', 'SP']
const allowedProductCombinations = new Set([
  'MAC', 'MAC + DAC', 'MAC + HP + Battery', 'MAC + Battery', 'MAC + HP',
  'MAC + DAC + Battery', 'DAC', 'DAC + Battery', 'DAC + HP + Battery',
  'HP + Battery', 'HP only', 'Battery only',
  'SP + Battery + MAC', 'SP + Battery'
])
const today = localDateInputValue()
const schedulerEndDate = addDays(today, 4)
const isScheduler = computed(() => ['scheduler', 'sales'].includes(authState.user?.roleCode))
const canManageBookingWindow = computed(() => !isScheduler.value)
// 行程缓冲用于停车、交接和路况波动；前后两段都必须保留这段时间。
const TRAVEL_BUFFER_MINUTES = 10
// 新地址插入局部路线后，同时超过固定分钟和比例阈值才视为明显折返。
const MAX_ROUTE_DETOUR_MINUTES = 15
const MAX_ROUTE_DETOUR_RATIO = 1.35
const googleMapsKey = ref(import.meta.env.VITE_GOOGLE_MAPS_KEY || '')
const addressInput = ref(null)
const addressHint = ref('Loading Google Maps address autocomplete...')

const form = reactive({
  crmCard: '',
  macId: '',
  customerName: '',
  phoneNumber: '',
  secondaryPhone: '',
  email: '',
  address: '',
  state: '',
  products: [],
  notes: '',
  inspectionDate: today,
  inspectionEndDate: schedulerEndDate,
  rescheduleId: null
})
const bookingDateLabel = computed(() => {
  const start = displayDate(form.inspectionDate)
  const end = displayDate(form.inspectionEndDate)
  return start && end && start !== end ? `${start} – ${end}` : (start || '-')
})
const managerMaxEndDate = computed(() => addDays(form.inspectionDate || today, 4))
const inspectorWorkMinutes = computed(() => (
  form.products.length ? inspectionWorkMinutes(selectedProductLabel(form.products)) : 0
))
const slotFilters = reactive({ area: 'Any area', timeSlot: 'Any hour' })
const customerBookingArea = computed(() => (
  slotFilters.area !== 'Any area' ? slotFilters.area : inferBookingArea(form.address)
))
const quickAvailability = computed(() => {
  const eligibleInspectors = (overview.value.inspectors || [])
    .filter((inspector) => inspectorMatchesArea(inspector.area, customerBookingArea.value))
    .map((inspector) => inspector.name)
  // 查找完成后只能展示已经通过 Google 行车时间和路线方向检查的结果。
  // 原始 Sheet 空位只用于填写表单前的预览，不能继续显示为可预约时间。
  const availabilitySource = slotSearchCompleted.value
    ? slots.value.map((slot) => ({ ...slot, inspectionTime: slot.start }))
    : (overview.value.sheetOpenSlots || [])
  return buildQuickAvailability(availabilitySource, eligibleInspectors)
})
const overview = ref({})
const availableDates = computed(() => overview.value.availableDates || [])
const slots = ref([])
const slotSearchSummary = ref({})
const confirmedAppointments = ref([])
const sheetScheduleRecords = ref([])
const reschedulePool = ref([])
const areaOptions = ref(['Any area'])
const timeSlotOptions = ref(['Any hour'])
const findingSlots = ref(false)
const slotSearchCompleted = ref(false)
const bookingKey = ref('')
const appointmentActionId = ref(null)
const statusMessage = ref('')
const warningMessage = ref('')
const errorMessage = ref('')
const recommendedSlotsEmptyMessage = computed(() => {
  if (!slotSearchCompleted.value) return 'Enter the customer details, then click Find slots.'
  const summary = slotSearchSummary.value || {}
  const sheetOpen = Number(summary.sheetOpenSlots || 0)
  const candidates = Number(summary.routeCandidates || 0)
  const googleRejected = Number(summary.googleRejected || 0)
  if (!sheetOpen) return 'No Google Sheet open spaces exist in this date range. Import or refresh the required week.'
  if (candidates && googleRejected >= candidates) {
    return `${sheetOpen} Sheet space(s) exist, but none has enough verified travel time or a forward-moving route for this address.`
  }
  const reasons = []
  if (summary.areaMismatch) reasons.push(`${summary.areaMismatch} outside the customer area`)
  if (summary.stateMismatch) reasons.push(`${summary.stateMismatch} in another state`)
  if (summary.overlap) reasons.push(`${summary.overlap} overlapping an existing job`)
  if (summary.timeFilterMismatch) reasons.push(`${summary.timeFilterMismatch} outside the selected time`)
  if (summary.unknownInspector) reasons.push(`${summary.unknownInspector} assigned to an unavailable inspector`)
  if (summary.invalidTime) reasons.push(`${summary.invalidTime} with an invalid time`)
  return reasons.length
    ? `${sheetOpen} Sheet space(s) were found, but no booking can be offered: ${reasons.join(', ')}.`
    : `${sheetOpen} Sheet space(s) were found, but none passed all booking checks.`
})

let bookGoogleScriptPromise = null
let bookConfigScriptPromise = null
let addressAutocomplete = null

watch(
  () => [form.address, form.state, form.inspectionDate, form.inspectionEndDate, form.products.join('|')],
  () => {
    // 客户地址、产品或日期改变后，旧的路线安全结果立即失效。
    slotSearchCompleted.value = false
  }
)

onMounted(async () => {
  await loadOverview()
  initializeAddressAutocomplete()
})

async function loadOverview() {
  slotSearchCompleted.value = false
  logInfo('Book', 'Loading Book page overview', { startDate: form.inspectionDate, endDate: form.inspectionEndDate })
  try {
    const data = await http.get('/routes/book/overview', { params: { date: form.inspectionDate } })
    overview.value = data || {}
    if (isScheduler.value) {
      // Use the server-authoritative range so mobile/device clock differences cannot hide searched days.
      form.inspectionDate = data.startDate || data.date || form.inspectionDate
      form.inspectionEndDate = data.endDate || addDays(form.inspectionDate, 4)
    }
    areaOptions.value = data.areas || ['Any area']
    timeSlotOptions.value = data.timeSlots || ['Any hour']
    confirmedAppointments.value = data.confirmedAppointments || []
    reschedulePool.value = data.reschedulePool || []
    await loadSheetSchedule()
  } catch (err) {
    logError('Book', 'Failed to load Book page overview', err)
    errorMessage.value = err.message
  }
}

async function selectAvailableDate(date) {
  // 只允许快捷选择数据库中由 Google Sheet 导入、并且仍有空白行的日期。
  form.inspectionDate = date
  if (!form.inspectionEndDate || form.inspectionEndDate < date) {
    form.inspectionEndDate = date
  }
  slots.value = []
  statusMessage.value = `Selected imported schedule date ${displayDate(date)}.`
  await loadOverview()
}

function displayDate(value) {
  const parts = String(value || '').split('-')
  return parts.length === 3 ? `${parts[2]}/${parts[1]}/${parts[0]}` : value
}

function addDays(value, days) {
  const date = new Date(`${value}T00:00:00`)
  if (Number.isNaN(date.getTime())) return value
  date.setDate(date.getDate() + days)
  return localDateInputValue(date)
}

async function handleStartDateChange() {
  if (!form.inspectionEndDate || form.inspectionEndDate < form.inspectionDate) {
    form.inspectionEndDate = form.inspectionDate
  }
  if (form.inspectionEndDate > managerMaxEndDate.value) {
    form.inspectionEndDate = managerMaxEndDate.value
  }
  slots.value = []
  await loadOverview()
}

async function handleEndDateChange() {
  if (form.inspectionEndDate < form.inspectionDate) {
    form.inspectionEndDate = form.inspectionDate
  }
  slots.value = []
  await loadOverview()
}

async function loadSheetSchedule() {
  const records = []
  let page = 1
  let total = 0
  do {
    const data = await http.get('/routes/map', {
      params: {
        startDate: form.inspectionDate,
        endDate: form.inspectionEndDate || form.inspectionDate,
        page,
        size: 200
      }
    })
    records.push(...(data.records || []))
    total = Number(data.total || records.length)
    page += 1
  } while (records.length < total)
  sheetScheduleRecords.value = records.filter((item) => {
    const status = String(item.status || '').toLowerCase()
    return status !== 'cancelled' && status !== 'reschedule'
  })
  const rangeOpenSlots = sheetScheduleRecords.value.filter((item) => String(item.status || '').toLowerCase() === 'open slot')
  overview.value = {
    ...overview.value,
    sheetOpenSlots: rangeOpenSlots,
    availableSlots: rangeOpenSlots.length
  }
}

function chooseAvailableTime(item) {
  slotFilters.timeSlot = item.timeSlot
  statusMessage.value = `${item.label} selected: ${item.count} inspector${item.count === 1 ? '' : 's'} currently available. Complete the customer details, then click Find slots.`
  if (!validateBookingForm()) {
    findSlots()
  }
}

function buildQuickAvailability(openSlots, eligibleInspectors) {
  const eligible = new Set(eligibleInspectors.map((name) => String(name).toLowerCase()))
  const grouped = new Map()
  openSlots.forEach((slot) => {
    if (!eligible.has(String(slot.inspector || '').toLowerCase())) return
    const minute = timeToMinutes(slot.inspectionTime)
    if (minute < 0) return
    const label = `${String(Math.floor(minute / 60)).padStart(2, '0')}:${String(minute % 60).padStart(2, '0')}`
    if (!grouped.has(label)) grouped.set(label, new Set())
    grouped.get(label).add(slot.inspector)
  })
  const results = [...grouped.entries()].map(([label, inspectors]) => ({
    label,
    timeSlot: `${label} - ${String(Math.floor((timeToMinutes(label) + 60) / 60)).padStart(2, '0')}:${String((timeToMinutes(label) + 60) % 60).padStart(2, '0')}`,
    count: inspectors.size,
    recommended: false
  })).sort((left, right) => left.label.localeCompare(right.label))
  const best = [...results].sort((left, right) => right.count - left.count || left.label.localeCompare(right.label))[0]
  if (best) best.recommended = true
  return results
}

function inferBookingArea(address) {
  const value = String(address || '').toLowerCase()
  const includesAny = (items) => items.some((item) => value.includes(item))
  if (includesAny(['williamstown', 'point cook', 'tarneit', 'truganina', 'werribee', 'hoppers crossing',
    'altona', 'newport', 'footscray', 'yarraville', 'sunshine', 'st albans', 'laverton', 'wyndham vale',
    'manor lakes', 'melton'])) return 'West'
  if (includesAny(['springvale', 'clayton', 'glen waverley', 'oakleigh', 'mulgrave', 'dandenong',
    'noble park', 'keysborough', 'chadstone', 'carnegie', 'murrumbeena', 'cheltenham', 'mentone',
    'moorabbin', 'berwick', 'narre warren', 'cranbourne', 'pakenham'])) return 'South East'
  if (includesAny(['bayswater', 'boronia', 'ringwood', 'croydon', 'mitcham', 'nunawading', 'box hill',
    'doncaster', 'templestowe', 'vermont', 'wantirna', 'ferntree gully', 'rowville', 'lilydale',
    'mooroolbark', 'camberwell', 'hawthorn'])) return 'East'
  const postcode = Number(value.match(/\b(\d{4})\b/)?.[1] || 0)
  if ((postcode >= 3011 && postcode <= 3038) || (postcode >= 3335 && postcode <= 3340)) return 'West'
  if ((postcode >= 3160 && postcode <= 3207) || (postcode >= 3802 && postcode <= 3810)
    || postcode === 3145 || (postcode >= 3147 && postcode <= 3152)) return 'South East'
  if (postcode >= 3101 && postcode <= 3156) return 'East'
  return 'Any area'
}

function inspectorMatchesArea(inspectorArea, selectedArea) {
  if (!selectedArea || selectedArea === 'Any area') return true
  const area = String(inspectorArea || '').toLowerCase().replace('/', ' ').replace(/\s+/g, ' ').trim()
  const selected = String(selectedArea).toLowerCase().replace('/', ' ').replace(/\s+/g, ' ').trim()
  if (selected === 'east south east') return area === 'east' || area === 'south east'
  if (selected === 'north west') return area === 'west'
  return area === selected
}

async function findSlots(showResultStatus = true) {
  errorMessage.value = ''
  statusMessage.value = ''
  warningMessage.value = ''
  const validationMessage = validateBookingForm()
  if (validationMessage) {
    errorMessage.value = validationMessage
    return
  }
  findingSlots.value = true
  slotSearchCompleted.value = false
  try {
    const payload = buildBookingPayload()
    payload.area = slotFilters.area
    payload.timeSlot = slotFilters.timeSlot
    logInfo('Book', 'Searching recommended slots', payload)
    const data = await http.post('/routes/book/slots', payload)
    slots.value = data.slots || []
    slotSearchSummary.value = { ...(data.searchSummary || {}) }
    confirmedAppointments.value = data.confirmedAppointments || confirmedAppointments.value
    warningMessage.value = data.duplicateWarning || ''
    addRouteContext(slots.value, confirmedAppointments.value)
    const travelCheck = await enrichSlotsWithDrivingTimes(slots.value)
    slotSearchSummary.value.googleRejected = travelCheck.rejectedCount || 0
    if (isScheduler.value && slots.value.length) {
      // Scheduler 只看到五天范围内时间最早的一项；同一时间由路线绕行和行车时间决定最佳项。
      slots.value.sort((left, right) =>
        String(left.date || '').localeCompare(String(right.date || ''))
        || timeToMinutes(left.start) - timeToMinutes(right.start)
        || Number(left.routeDetourMinutes || 0) - Number(right.routeDetourMinutes || 0)
        || Number(left.travelMinutes || 999) - Number(right.travelMinutes || 999)
      )
      const earliestByDate = new Map()
      slots.value.forEach((slot) => {
        const date = String(slot.date || '')
        if (date && !earliestByDate.has(date)) earliestByDate.set(date, slot)
      })
      slots.value = [...earliestByDate.values()]
      slots.value.forEach((slot) => { slot.recommendation = 'Earliest for this day' })
    }
    slotSearchCompleted.value = true
    overview.value = {
      ...overview.value,
      availableSlots: Number(slotSearchSummary.value.sheetOpenSlots ?? overview.value.availableSlots ?? 0),
      routeSafeSlots: slots.value.length
    }
    if (showResultStatus) {
      const areaText = data.matchedArea && data.matchedArea !== 'Any area'
        ? ` for the ${data.matchedArea} side`
        : ''
      if (slots.value.length) {
        const removedText = travelCheck.rejectedCount
          ? ` ${travelCheck.rejectedCount} other space(s) were removed because of travel time or route backtracking.`
          : ''
        statusMessage.value = isScheduler.value
          ? `Showing the earliest route-safe appointment for ${slots.value.length} day(s) in the five-day window${areaText}.${removedText}`
          : `Found ${slots.value.length} travel-safe available slots${areaText}.${removedText}`
      } else if (!travelCheck.verified) {
        statusMessage.value = 'No slots can be offered until Google driving time is successfully verified.'
      } else if (travelCheck.rejectedCount) {
        statusMessage.value = `${slotSearchSummary.value.sheetOpenSlots || travelCheck.rejectedCount} Google Sheet space(s) were checked${areaText}, but none leaves enough verified driving time or keeps the route moving forward.`
      } else if (Number(slotSearchSummary.value.sheetOpenSlots || 0) > 0) {
        statusMessage.value = recommendedSlotsEmptyMessage.value
      } else if ((data.availableDates || []).length) {
        statusMessage.value = `No Google Sheet spaces exist in ${bookingDateLabel.value}${areaText}. Choose one of the imported dates shown above.`
      } else {
        statusMessage.value = 'No Google Sheet spaces have been imported. Import the required week first.'
      }
    }
  } catch (err) {
    logError('Book', 'Failed to search recommended slots', err)
    errorMessage.value = err.message
  } finally {
    findingSlots.value = false
  }
}

async function bookSlot(slot) {
  const validationMessage = validateBookingForm()
  if (validationMessage) {
    errorMessage.value = validationMessage
    return
  }
  if (!slot.travelVerified || !slot.travelFeasible || !slot.routeContinuous) {
    errorMessage.value = 'This time has not passed the Google travel-time and route-direction checks. Click Find slots again.'
    return
  }
  if (!window.confirm(`Book ${slot.inspector} at ${slot.date} ${slot.start}?`)) {
    return
  }
  bookingKey.value = slotKey(slot)
  errorMessage.value = ''
  statusMessage.value = ''
  try {
    const payload = {
      ...buildBookingPayload(),
      slotId: slot.slotId,
      inspector: slot.inspector,
      inspectionDate: slot.date,
      inspectionTime: slot.start,
      projects: slot.product,
      travelVerification: {
        verified: true,
        travelInMinutes: slot.travelInMinutes,
        travelOutMinutes: slot.travelOutMinutes,
        travelBufferMinutes: slot.travelBufferMinutes,
        previousEndMinutes: slot.previousEndMinutes,
        nextStartMinutes: slot.nextStartMinutes,
        travelFrom: slot.travelFrom,
        travelTo: slot.travelTo,
        routeContinuous: slot.routeContinuous,
        routeContinuityMode: slot.routeContinuityMode,
        routePathMinutes: slot.routePathMinutes,
        routeDirectMinutes: slot.routeDirectMinutes,
        routeDetourMinutes: slot.routeDetourMinutes
      }
    }
    logInfo('Book', 'Saving appointment', payload)
    const saved = await http.post('/routes/book/appointments', payload)
    form.rescheduleId = null
    await loadOverview()
    await findSlots(false)
    statusMessage.value = `Fixed ${saved.customerName || saved.macId} with ${saved.inspector} at ${saved.inspectionTime}. Saved in this application; Google Sheet was not changed.`
  } catch (err) {
    logError('Book', 'Failed to save appointment', err)
    errorMessage.value = err.message
  } finally {
    bookingKey.value = ''
  }
}

async function loadCustomerByMacId(silent = false) {
  if (!form.macId) {
    if (!silent) errorMessage.value = 'MAC ID is required.'
    return
  }
  errorMessage.value = ''
  try {
    logInfo('Book', 'Looking up customer by MACID', { macId: form.macId })
    const record = await http.get('/routes/book/customer', { params: { macId: form.macId } })
    form.customerName = record.customerName || form.customerName
    form.phoneNumber = normalizeAustralianPhone(record.phoneNumber) || form.phoneNumber
    form.secondaryPhone = normalizeAustralianPhone(record.secondaryPhone) || form.secondaryPhone
    form.email = record.email || form.email
    form.address = record.address || form.address
    form.state = record.state || inferStateFromAddress(form.address) || form.state
    warningMessage.value = `Existing customer ${record.macId || form.macId} loaded. Review the details before booking; this does not block the booking.`
    statusMessage.value = 'Customer details loaded.'
  } catch (err) {
    logError('Book', 'Failed to look up customer by MACID', err)
    if (!silent) errorMessage.value = err.message
  }
}

function applyCrmCard() {
  const parsed = parseCrmCard(form.crmCard)
  Object.assign(form, {
    macId: parsed.macId || form.macId,
    customerName: parsed.customerName || form.customerName,
    phoneNumber: parsed.phone || form.phoneNumber,
    secondaryPhone: parsed.secondaryPhone || form.secondaryPhone,
    address: parsed.address || form.address,
    state: parsed.state || form.state
  })
  statusMessage.value = parsed.macId || parsed.customerName || parsed.phone || parsed.address
    ? 'CRM customer card applied.'
    : 'No supported CRM customer fields were found.'
}

function clearCrmCard() {
  form.crmCard = ''
}

function resetForm() {
  Object.assign(form, {
    crmCard: '',
    macId: '',
    customerName: '',
    phoneNumber: '',
    secondaryPhone: '',
    email: '',
    address: '',
    state: '',
    products: [],
    notes: '',
    inspectionDate: today,
    inspectionEndDate: isScheduler.value ? schedulerEndDate : addDays(today, 4),
    rescheduleId: null
  })
  slots.value = []
  slotSearchCompleted.value = false
  Object.assign(slotFilters, { area: 'Any area', timeSlot: 'Any hour' })
  statusMessage.value = ''
  warningMessage.value = ''
  errorMessage.value = ''
  loadOverview()
}

function buildBookingPayload() {
  return {
    macId: form.macId,
    customerName: form.customerName,
    phoneNumber: form.phoneNumber,
    secondaryPhone: form.secondaryPhone,
    email: form.email,
    address: completeAustralianAddress(form.address, form.state),
    state: form.state || inferStateFromAddress(form.address),
    products: form.products,
    notes: form.notes,
    inspectionDate: form.inspectionDate,
    inspectionEndDate: form.inspectionEndDate,
    durationMinutes: inspectorWorkMinutes.value,
    rescheduleId: form.rescheduleId
  }
}

function validateBookingForm() {
  if (!form.customerName) {
    return 'Customer name is required.'
  }
  if (!isAustralianPhone(form.phoneNumber)) {
    return 'Enter a valid 10-digit Australian mobile or landline number.'
  }
  if (form.secondaryPhone && !isAustralianPhone(form.secondaryPhone)) {
    return 'Enter a valid Australian secondary phone number.'
  }
  if (!isCompleteAustralianAddress(form.address)) {
    return 'Enter a complete Australian address with a street number, street name/type, and suburb or postcode.'
  }
  const addressState = inferStateFromAddress(form.address)
  if (!form.state) {
    return 'Choose the Australian state.'
  }
  if (addressState && form.state !== addressState) {
    return 'Selected state does not match the Australian address.'
  }
  if (!form.products.length) {
    return 'Choose at least one inspection product before finding slots.'
  }
  if (!allowedProductCombinations.has(selectedProductLabel(form.products))) {
    return 'The selected inspection product combination is not supported.'
  }
  if (!form.inspectionDate) {
    return 'Booking start date is required.'
  }
  if (!form.inspectionEndDate) {
    return 'Booking end date is required.'
  }
  if (form.inspectionEndDate < form.inspectionDate) {
    return 'Booking end date cannot be before the start date.'
  }
  if (form.inspectionEndDate > addDays(form.inspectionDate, 4)) {
    return 'Booking date range cannot exceed 5 days.'
  }
  return ''
}

function isAustralianPhone(value) {
  const digits = normalizeAustralianPhone(value)
  return /^04\d{8}$/.test(digits)
    || /^0[2378]\d{8}$/.test(digits)
}

function normalizeAustralianPhone(value) {
  let digits = String(value || '').trim().replace(/[^0-9+]/g, '')
  if (digits.startsWith('+61')) {
    digits = `0${digits.slice(3)}`
  } else if (digits.startsWith('61') && digits.length >= 11) {
    digits = `0${digits.slice(2)}`
  }
  digits = digits.replace(/\D/g, '')
  if (/^[23478]\d{8}$/.test(digits)) {
    digits = `0${digits}`
  }
  return digits
}

function isCompleteAustralianAddress(value) {
  const address = String(value || '').trim()
  // 接受 Google/CRM 常见的澳洲街道类型及缩写；例如 Circuit 经常只保存成 Cct。
  const streetType = '(?:street|st|road|rd|avenue|ave|drive|dr|lane|ln|court|ct|crescent|cres|circuit|cct|boulevard|blvd|highway|hwy|parade|pde|place|pl|way|terrace|tce|close|cl|grove|gr|gardens|gdns|green|grn|esplanade|esp|freeway|fwy|parkway|pkwy|square|sq|track|trk|walk|rise|mews|quay|loop|strand|vale|view|hill|ridge)'
  const streetMatch = address.match(new RegExp(`^(?:(?:unit|u|suite|shop|lot)\\s*[A-Za-z0-9-]+[\\s,/-]+|[A-Za-z0-9-]+/)?\\d+[A-Za-z]?\\s+.+?\\b${streetType}\\b`, 'i'))
  const hasStreet = Boolean(streetMatch)
  const hasPostcode = /\b\d{4}\b/.test(address)
  const locationText = streetMatch
    ? address.slice(streetMatch[0].length)
      .replace(/,?\s*Australia\s*$/i, '')
      .replace(/\b(?:NSW|VIC|QLD|SA|WA|TAS|ACT|NT)\b/ig, '')
      .replace(/\b\d{4}\b/g, '')
      .replace(/[\s,]+/g, ' ')
      .trim()
    : ''
  const hasSuburb = /^[A-Za-z][A-Za-z .'-]{1,}$/.test(locationText)
  return hasStreet && (hasPostcode || hasSuburb)
}

function completeAustralianAddress(address, selectedState) {
  let completed = String(address || '').trim().replace(/[\s,]+$/, '')
  if (selectedState && !inferStateFromAddress(completed)) {
    completed += `, ${selectedState}`
  }
  if (completed && !/\bAustralia\b/i.test(completed)) {
    completed += ', Australia'
  }
  return completed
}

function selectedProductLabel(products) {
  const values = [...new Set(products)]
  if (values.length === 1) {
    if (values[0] === 'HP' || values[0] === 'Battery') return `${values[0]} only`
    return values[0]
  }
  if (values.includes('SP') && values.includes('Battery') && values.includes('MAC') && values.length === 3) {
    return 'SP + Battery + MAC'
  }
  if (values.includes('SP') && values.includes('Battery') && values.length === 2) {
    return 'SP + Battery'
  }
  return ['MAC', 'DAC', 'HP', 'Battery', 'SP'].filter((item) => values.includes(item)).join(' + ')
}

async function enrichSlotsWithDrivingTimes(items) {
  if (!items.length || !form.address) return { verified: true, rejectedCount: 0 }
  const originalCount = items.length
  items.forEach((slot) => {
    const estimate = Number(slot.travelMinutes || 0)
    slot.travelInMinutes = estimate
    slot.travelOutMinutes = 0
    slot.totalTravelMinutes = estimate
    slot.googleMapsUrl = googleDirectionsUrl(
      slot.travelFrom || slot.base,
      form.address,
      slot.travelTo || slot.base
    )
  })
  try {
    await resolveGoogleMapsKey()
    if (!googleMapsKey.value) throw new Error('Google Maps key is not configured.')
    await ensureGooglePlaces()
    const origins = [...new Set(items.map((slot) => slot.travelFrom || slot.base).filter(Boolean))]
    const destinations = [...new Set(items.map((slot) => slot.travelTo || slot.base).filter(Boolean))]
    if (!origins.length || !destinations.length) return
    const continuityOrigins = [...new Set(items.flatMap((slot) => (
      slot.routeContinuityMode ? [slot.routeAnchorFrom, slot.routeVia] : []
    )).filter(Boolean))]
    const continuityDestinations = [...new Set(items.flatMap((slot) => (
      slot.routeContinuityMode ? [slot.routeVia, slot.routeAnchorTo] : []
    )).filter(Boolean))]
    const [travelInResult, travelOutResult, continuityResult] = await Promise.all([
      requestDistanceMatrix(origins, [form.address]),
      requestDistanceMatrix([form.address], destinations),
      continuityOrigins.length && continuityDestinations.length
        ? requestDistanceMatrix(continuityOrigins, continuityDestinations)
        : Promise.resolve(null)
    ])
    const travelInByOrigin = new Map()
    travelInResult.rows.forEach((row, index) => {
      const element = row.elements?.[0]
      if (element?.status === 'OK' && element.duration?.value) {
        travelInByOrigin.set(origins[index], {
          minutes: Math.max(1, Math.round(element.duration.value / 60)),
          text: element.duration.text,
          distance: element.distance?.text || ''
        })
      }
    })
    const travelOutByDestination = new Map()
    const outboundElements = travelOutResult.rows?.[0]?.elements || []
    outboundElements.forEach((element, index) => {
      if (element?.status === 'OK' && element.duration?.value) {
        travelOutByDestination.set(destinations[index], {
          minutes: Math.max(1, Math.round(element.duration.value / 60)),
          text: element.duration.text
        })
      }
    })
    const continuityDurations = matrixDurationLookup(
      continuityResult, continuityOrigins, continuityDestinations
    )
    items.forEach((slot) => {
      const travelFrom = slot.travelFrom || slot.base
      const travelTo = slot.travelTo || slot.base
      const travelIn = travelInByOrigin.get(travelFrom)
      const travelOut = travelOutByDestination.get(travelTo)
      slot.travelInMinutes = travelIn?.minutes
      slot.travelOutMinutes = travelOut?.minutes
      slot.travelBufferMinutes = TRAVEL_BUFFER_MINUTES
      slot.travelVerified = Boolean(travelIn && travelOut)
      if (!slot.travelVerified) return
      slot.totalTravelMinutes = slot.travelInMinutes + slot.travelOutMinutes
      slot.travelMinutes = slot.totalTravelMinutes
      slot.travel = `${slot.totalTravelMinutes} min Google driving`
      slot.planningMinutes = Number(slot.durationMinutes || inspectorWorkMinutes.value || 60) + slot.totalTravelMinutes
      slot.googleMapsUrl = googleDirectionsUrl(travelFrom, form.address, travelTo)
      applyRouteContinuity(slot, continuityDurations)
      slot.travelFeasible = isTravelGapFeasible(slot) && slot.routeContinuous
    })
    // 只向用户展示前后两段行程均有足够时间的空位。
    const safeSlots = items.filter((slot) => slot.travelFeasible)
    items.splice(0, items.length, ...safeSlots)
    items.sort((left, right) =>
      Number(left.routeDetourMinutes || 0) - Number(right.routeDetourMinutes || 0)
      || Number(left.travelMinutes || 999) - Number(right.travelMinutes || 999)
      || Number(left.workload || 0) - Number(right.workload || 0)
      || String(left.start || '').localeCompare(String(right.start || ''))
    )
    items.forEach((slot, index) => {
      slot.recommendation = index === 0 ? 'Best' : `Option ${index + 1}`
    })
    return { verified: true, rejectedCount: originalCount - items.length }
  } catch (err) {
    logError('Book', 'Google driving time is unavailable; safe mode will not offer appointment slots', err)
    const routesDisabled = /SERVICE_DISABLED|Routes API has not been used|Routes API.*disabled/i.test(err.message || '')
    warningMessage.value = routesDisabled
      ? 'Google Routes API is disabled for this key. No booking slots are offered until it is enabled.'
      : `Google driving time could not be verified (${err.message}). No booking slots are offered for safety.`
    items.splice(0, items.length)
    return { verified: false, rejectedCount: originalCount }
  }
}

function matrixDurationLookup(result, origins, destinations) {
  const lookup = new Map()
  if (!result) return lookup
  result.rows.forEach((row, originIndex) => {
    ;(row.elements || []).forEach((element, destinationIndex) => {
      if (element?.status === 'OK' && element.duration?.value) {
        lookup.set(
          `${origins[originIndex]}\u0000${destinations[destinationIndex]}`,
          Math.max(1, Math.round(element.duration.value / 60))
        )
      }
    })
  })
  return lookup
}

function applyRouteContinuity(slot, durations) {
  if (!slot.routeContinuityMode) {
    // 少于两个相邻真实站点时没有形成折返判断所需的三点，时间安全检查仍然有效。
    slot.routePathMinutes = 0
    slot.routeDirectMinutes = 0
    slot.routeDetourMinutes = 0
    slot.routeContinuous = true
    return
  }
  const firstLeg = durations.get(`${slot.routeAnchorFrom}\u0000${slot.routeVia}`)
  const secondLeg = durations.get(`${slot.routeVia}\u0000${slot.routeAnchorTo}`)
  const directLeg = durations.get(`${slot.routeAnchorFrom}\u0000${slot.routeAnchorTo}`)
  if (![firstLeg, secondLeg, directLeg].every(Number.isFinite) || directLeg <= 0) {
    slot.routeContinuous = false
    return
  }
  slot.routePathMinutes = firstLeg + secondLeg
  slot.routeDirectMinutes = directLeg
  slot.routeDetourMinutes = Math.max(0, slot.routePathMinutes - directLeg)
  const detourRatio = slot.routePathMinutes / directLeg
  slot.routeContinuous = slot.routeDetourMinutes <= MAX_ROUTE_DETOUR_MINUTES
    || detourRatio <= MAX_ROUTE_DETOUR_RATIO
}

function isTravelGapFeasible(slot) {
  const candidateStart = timeToMinutes(slot.start)
  const candidateEnd = candidateStart + Number(slot.durationMinutes || inspectorWorkMinutes.value || 60)
  const hasPrevious = slot.previousEndMinutes !== null && slot.previousEndMinutes !== undefined
  const hasNext = slot.nextStartMinutes !== null && slot.nextStartMinutes !== undefined
  const previousEnd = hasPrevious ? Number(slot.previousEndMinutes) : null
  const nextStart = hasNext ? Number(slot.nextStartMinutes) : null
  const travelIn = Number(slot.travelInMinutes)
  const travelOut = Number(slot.travelOutMinutes)
  const enoughTimeFromPrevious = !hasPrevious
    || candidateStart - previousEnd >= travelIn + TRAVEL_BUFFER_MINUTES
  const enoughTimeToNext = !hasNext
    || nextStart - candidateEnd >= travelOut + TRAVEL_BUFFER_MINUTES
  return enoughTimeFromPrevious && enoughTimeToNext
}

function addRouteContext(items, appointments) {
  const inspectors = [...new Set(items.map((slot) => slot.inspector).filter(Boolean))].sort()
  items.forEach((slot) => {
    const candidateStart = timeToMinutes(slot.start)
    const candidateEnd = candidateStart + Number(slot.durationMinutes || inspectorWorkMinutes.value || 60)
    const routeStops = appointments
      .filter((item) => item.inspector === slot.inspector && item.address && item.inspectionDate === slot.date)
      .map((item) => {
        const start = timeToMinutes(item.inspectionTime)
        const savedDuration = Number(item.workDurationMinutes)
        const duration = Number.isFinite(savedDuration) && savedDuration > 0
          ? savedDuration
          : Number(inspectionWorkMinutes(item.projects) || 60)
        return {
          address: item.address,
          start,
          end: start + duration
        }
      })
      .filter((item) => item.start >= 0)
      .sort((left, right) => left.start - right.start)
    const previousStops = routeStops.filter((item) => item.end <= candidateStart)
    const nextStops = routeStops.filter((item) => item.start >= candidateEnd)
    const previous = previousStops.length ? previousStops[previousStops.length - 1] : null
    const previousPrevious = previousStops.length > 1 ? previousStops[previousStops.length - 2] : null
    const next = nextStops.length ? nextStops[0] : null
    const nextNext = nextStops.length > 1 ? nextStops[1] : null
    slot.routeNumber = inspectors.indexOf(slot.inspector) + 1
    slot.jobsAfterBooking = Number(slot.workload || routeStops.length) + 1
    slot.routeTarget = '6-8'
    slot.travelFrom = previous?.address || slot.base
    slot.travelTo = next?.address || slot.base
    slot.previousEndMinutes = previous?.end ?? null
    slot.nextStartMinutes = next?.start ?? null
    // 用相邻三个地点检查路线方向：中间插入、末尾追加和开头插入分别选择不同的局部三点。
    if (previous && next) {
      slot.routeContinuityMode = 'between'
      slot.routeAnchorFrom = previous.address
      slot.routeVia = form.address
      slot.routeAnchorTo = next.address
    } else if (previousPrevious && previous) {
      slot.routeContinuityMode = 'after'
      slot.routeAnchorFrom = previousPrevious.address
      slot.routeVia = previous.address
      slot.routeAnchorTo = form.address
    } else if (next && nextNext) {
      slot.routeContinuityMode = 'before'
      slot.routeAnchorFrom = form.address
      slot.routeVia = next.address
      slot.routeAnchorTo = nextNext.address
    } else {
      slot.routeContinuityMode = ''
      slot.routeAnchorFrom = ''
      slot.routeVia = ''
      slot.routeAnchorTo = ''
    }
  })
}

function timeToMinutes(value) {
  const match = String(value || '').trim().match(/^(\d{1,2}):(\d{2})(?:\s*([AP]M))?/i)
  if (!match) return -1
  let hour = Number(match[1])
  const meridiem = String(match[3] || '').toUpperCase()
  if (meridiem === 'PM' && hour < 12) hour += 12
  if (meridiem === 'AM' && hour === 12) hour = 0
  return hour * 60 + Number(match[2])
}

function requestDistanceMatrix(origins, destinations) {
  return Promise.race([
    requestRouteMatrix(origins, destinations),
    new Promise((resolve, reject) => {
      window.setTimeout(() => reject(new Error('Google driving-time request timed out.')), 15000)
    })
  ])
}

async function requestRouteMatrix(origins, destinations) {
  const [{ RouteMatrix }, { UnitSystem }] = await Promise.all([
    window.google.maps.importLibrary('routes'),
    window.google.maps.importLibrary('core')
  ])
  const { matrix } = await RouteMatrix.computeRouteMatrix({
      origins,
      destinations,
      travelMode: 'DRIVING',
      units: UnitSystem.METRIC,
      fields: ['durationMillis', 'distanceMeters', 'condition']
  })
  return {
    rows: matrix.rows.map((row) => ({
      elements: row.items.map((item) => {
        const durationMillis = Number(item.durationMillis)
        const distanceMeters = Number(item.distanceMeters || 0)
        const available = Number.isFinite(durationMillis) && durationMillis > 0
        const seconds = available ? durationMillis / 1000 : 0
        return {
          status: available ? 'OK' : item.condition,
          duration: available ? {
            value: seconds,
            text: `${Math.max(1, Math.round(seconds / 60))} min`
          } : null,
          distance: available ? {
            value: distanceMeters,
            text: `${(distanceMeters / 1000).toFixed(1)} km`
          } : null
        }
      })
    }))
  }
}

function googleDirectionsUrl(origin, waypoint, destination) {
  const params = new URLSearchParams({
    api: '1',
    origin,
    destination,
    waypoints: waypoint,
    travelmode: 'driving'
  })
  return `https://www.google.com/maps/dir/?${params.toString()}`
}

function useReschedule(item) {
  const addressState = inferStateFromAddress(item.address)
  const recordState = stateOptions.includes(item.cityCouncil) ? item.cityCouncil : ''
  Object.assign(form, {
    macId: item.macId || '',
    customerName: item.customerName || '',
    phoneNumber: normalizeAustralianPhone(item.phoneNumber),
    address: item.address || '',
    state: addressState || recordState,
    products: productsFromLabel(item.projects),
    notes: item.schedulerRemarks || '',
    rescheduleId: item.id
  })
  slots.value = []
  warningMessage.value = ''
  errorMessage.value = ''
  statusMessage.value = `Appointment #${item.id} loaded from the reschedule pool. Choose a new date and slot.`
}

function clearRescheduleSelection() {
  form.rescheduleId = null
  statusMessage.value = 'Reschedule selection cleared.'
}

function productsFromLabel(label) {
  const text = String(label || '').replace(/\bonly\b/gi, '')
  return atomicProducts.filter((product) => new RegExp(`\\b${product}\\b`, 'i').test(text))
}

function editAppointmentTime(item) {
  // 不能直接输入任意时间；改期同样必须先选择 Google Sheet 中真实存在的空白行。
  useReschedule(item)
  form.inspectionDate = item.inspectionDate || form.inspectionDate
  if (canManageBookingWindow.value) {
    form.inspectionEndDate = form.inspectionDate
  }
  statusMessage.value = `Appointment #${item.id} is ready to move. Choose a date, click Find slots, then book a Sheet space.`
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function cancelAppointment(item) {
  if (!window.confirm(`Cancel the appointment for ${item.customerName || item.macId}?`)) return
  appointmentActionId.value = item.id
  errorMessage.value = ''
  try {
    await http.put(`/routes/book/appointments/${item.id}/cancel`)
    await loadOverview()
    slots.value = []
    statusMessage.value = `Appointment #${item.id} cancelled in this application; Google Sheet was not changed.`
  } catch (err) {
    logError('Book', 'Failed to cancel appointment', err)
    errorMessage.value = err.message
  } finally {
    appointmentActionId.value = null
  }
}

function parseCrmCard(text) {
  const lines = String(text || '').replace(/\r/g, '').split('\n')
  const field = (label) => {
    const pattern = new RegExp(`^\\s*${label}\\s*:\\s*(.*)$`, 'i')
    const line = lines.find((item) => pattern.test(item))
    return line ? line.match(pattern)?.[1]?.trim() || '' : ''
  }
  const address = field('Address').replace(/\s+/g, ' ').trim()
  return {
    macId: field('MACID') || field('MAC ID'),
    customerName: field('Name') || field('Customer'),
    phone: field('Phone'),
    secondaryPhone: field('Preferred Mobile') || field('Mobile'),
    address,
    state: inferStateFromAddress(address)
  }
}

function inferStateFromAddress(address) {
  const upper = String(address || '').toUpperCase()
  return stateOptions.find((state) => new RegExp(`(?:^|[\\s,])${state}(?:[\\s,]|$)`).test(upper)) || ''
}

function retryAddressAutocomplete() {
  if (!addressAutocomplete && !bookGoogleScriptPromise) {
    addressHint.value = 'Loading Google Maps address autocomplete...'
    initializeAddressAutocomplete()
  }
}

function slotKey(slot) {
  return `${slot.slotId || ''}:${slot.inspector}:${slot.date}:${slot.start}`
}

async function initializeAddressAutocomplete() {
  try {
    await resolveGoogleMapsKey()
    if (!googleMapsKey.value) {
      addressHint.value = 'Google Maps key is not configured; enter the full address manually.'
      return
    }
    await ensureGooglePlaces()
    if (!window.google?.maps?.places?.Autocomplete || !addressInput.value) {
      throw new Error('Google Maps Places autocomplete is unavailable.')
    }
    addressAutocomplete = new window.google.maps.places.Autocomplete(addressInput.value, {
      componentRestrictions: { country: 'au' },
      fields: ['formatted_address', 'address_components']
    })
    addressAutocomplete.addListener('place_changed', () => {
      const place = addressAutocomplete.getPlace()
      if (place.formatted_address) {
        form.address = place.formatted_address
      }
      const stateComponent = (place.address_components || []).find((component) => component.types?.includes('administrative_area_level_1'))
      form.state = stateComponent?.short_name || inferStateFromAddress(form.address) || form.state
    })
    addressHint.value = 'Google Maps autocomplete is active.'
  } catch (err) {
    logError('Book', 'Failed to initialize Google address autocomplete', err)
    addressHint.value = 'Google Maps autocomplete is unavailable; enter the full address manually.'
  }
}

function ensureGooglePlaces() {
  const existing = document.querySelector('script[src*="maps.googleapis.com/maps/api/js"]')
  if (window.__scheduleGoogleMapsAuthFailed || existing?.dataset.googleMapsAuthFailed === '1') {
    return Promise.reject(new Error(`Google map authorization failed for ${window.location.origin}.`))
  }
  if (window.google?.maps?.places?.Autocomplete) {
    return Promise.resolve()
  }
  if (bookGoogleScriptPromise) {
    return bookGoogleScriptPromise
  }
  bookGoogleScriptPromise = new Promise(async (resolve, reject) => {
    const waitForPlaces = () => {
      const startedAt = Date.now()
      const timer = window.setInterval(() => {
        if (window.__scheduleGoogleMapsAuthFailed) {
          window.clearInterval(timer)
          reject(new Error(`Google map authorization failed for ${window.location.origin}.`))
          return
        }
        if (window.google?.maps?.places?.Autocomplete) {
          window.clearInterval(timer)
          resolve()
          return
        }
        if (Date.now() - startedAt >= 10000) {
          window.clearInterval(timer)
          reject(new Error('Google Maps Places autocomplete did not become ready.'))
        }
      }, 150)
    }

    if (window.google?.maps?.importLibrary) {
      try {
        await window.google.maps.importLibrary('places')
        waitForPlaces()
      } catch (err) {
        reject(err)
      }
      return
    }

    if (existing) {
      waitForPlaces()
      return
    }
    window.gm_authFailure = () => {
      window.__scheduleGoogleMapsAuthFailed = true
      const googleScript = document.querySelector('script[src*="maps.googleapis.com/maps/api/js"]')
      if (googleScript) {
        googleScript.dataset.googleMapsAuthFailed = '1'
      }
      addressHint.value = `Google map authorization failed for ${window.location.origin}.`
      logError('Book', 'Google Maps browser key authorization failed', { referrer: window.location.href })
      reject(new Error(addressHint.value))
    }
    const script = document.createElement('script')
    script.src = `https://maps.googleapis.com/maps/api/js?key=${encodeURIComponent(googleMapsKey.value)}&libraries=places&loading=async&language=en&region=AU`
    script.async = true
    script.dataset.scheduleGoogleMaps = '1'
    script.onload = waitForPlaces
    script.onerror = () => reject(new Error('Google Maps script failed to load.'))
    document.head.appendChild(script)
  }).catch((err) => {
    bookGoogleScriptPromise = null
    throw err
  })
  return bookGoogleScriptPromise
}

function resolveGoogleMapsKey() {
  if (googleMapsKey.value || window.GOOGLE_MAPS_BROWSER_KEY) {
    googleMapsKey.value = googleMapsKey.value || window.GOOGLE_MAPS_BROWSER_KEY || ''
    return Promise.resolve()
  }
  if (bookConfigScriptPromise) {
    return bookConfigScriptPromise
  }
  bookConfigScriptPromise = new Promise((resolve) => {
    const existing = document.querySelector('script[src$="/google-config.js"]')
    if (existing) {
      existing.addEventListener('load', () => {
        googleMapsKey.value = window.GOOGLE_MAPS_BROWSER_KEY || ''
        resolve()
      }, { once: true })
      return
    }
    const script = document.createElement('script')
    script.src = '/google-config.js'
    script.async = true
    script.onload = () => {
      googleMapsKey.value = window.GOOGLE_MAPS_BROWSER_KEY || ''
      resolve()
    }
    script.onerror = () => resolve()
    document.head.appendChild(script)
  })
  return bookConfigScriptPromise
}
</script>

<style scoped>
.product-check-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(90px, 1fr));
  gap: 8px;
  margin-top: 6px;
}

.check-card {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 40px;
  padding: 8px 12px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: #f8fafc;
  color: var(--ink);
  cursor: pointer;
}

.check-card input {
  width: auto;
  margin: 0;
  flex: 0 0 auto;
}

.imported-date-help {
  display: grid;
  gap: 7px;
  padding: 10px 12px;
  border: 1px solid #e1b955;
  border-radius: 7px;
  background: #fff8df;
  color: #6d4b00;
}

.imported-date-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.imported-date-list button {
  width: auto;
  padding: 6px 9px;
}

.reschedule-banner,
.book-warning {
  margin: 8px 0;
  padding: 9px 11px;
  border: 1px solid #d8a83d;
  border-radius: 7px;
  background: #fff8df;
  color: #6d4b00;
}

.reschedule-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.compact-button {
  padding: 4px 8px;
}

@media (max-width: 760px) {
  .product-check-grid {
    grid-template-columns: repeat(2, minmax(100px, 1fr));
  }
}

.slot-card.best-slot {
  border-color: #1d7a55;
  box-shadow: 0 0 0 1px #1d7a55;
  background: #f3fbf7;
}

.availability-board {
  display: grid;
  gap: 12px;
}

.availability-board h2,
.availability-board p {
  margin: 0;
}

.availability-chips {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(145px, 1fr));
  gap: 8px;
}

.availability-chip {
  display: grid;
  justify-items: start;
  gap: 3px;
  padding: 10px 12px;
  border: 1px solid #cbd7e8;
  background: #f5f8fc;
  color: var(--text);
  text-align: left;
}

.availability-chip span,
.availability-chip small {
  font-size: 11px;
}

.availability-chip.recommended {
  border-color: #2d806d;
  background: #effaf6;
}

.availability-chip.selected {
  box-shadow: 0 0 0 2px #2d806d;
}

.availability-chip small {
  color: #176343;
  font-weight: 700;
}

.book-slots-panel .inline-control select {
  width: 132px;
  min-width: 132px;
}

.panel-divider {
  margin: 16px 0;
  border: 0;
  border-top: 1px solid var(--border);
}

.slot-card {
  position: relative;
  grid-template-columns: 1fr auto;
  border-color: #8bbcae;
  background: #effaf6;
}

.slot-card .slot-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  grid-column: 1 / -1;
  gap: 10px;
}

.recommendation-badge,
.fixed-badge,
.route-badge,
.job-badge {
  display: inline-flex;
  width: fit-content;
  padding: 2px 7px;
  border-radius: 999px;
  background: #dff4e9;
  color: #176343;
  font-size: 11px;
  font-weight: 700;
}

.route-badge,
.job-badge {
  border: 1px solid #cbd7e8;
  background: #f5f8ff;
  color: var(--text);
}

.slot-inspector {
  display: grid;
  gap: 2px;
}

.slot-inspector .job-badge {
  position: absolute;
  top: 42px;
  right: 12px;
}

.slot-card > .recommendation-badge {
  justify-self: end;
  align-self: start;
  background: #2d806d;
  color: white;
}

.slot-summary {
  grid-column: 1 / -1;
  margin: 4px 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.5;
}

.travel-safe-note {
  margin: 0 0 10px;
  color: #18705f;
  font-size: 0.82rem;
  font-weight: 600;
}

.map-route-link {
  align-self: center;
  color: #216d61;
  font-size: 12px;
  font-weight: 700;
}

.slot-details {
  display: grid;
  grid-column: 1 / -1;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px 12px;
  margin: 0;
}

.slot-details > div {
  display: grid;
  grid-template-columns: 70px minmax(0, 1fr);
  gap: 6px;
}

.slot-details dt {
  color: var(--muted);
  font-size: 12px;
}

.slot-details dd {
  margin: 0;
  font-size: 12px;
}

.slot-card > button {
  justify-self: end;
}

.appointment-actions {
  display: flex;
  min-width: 190px;
  gap: 6px;
}

.appointment-actions button {
  padding: 5px 7px;
  white-space: nowrap;
}

@media (max-width: 980px) {
  .slot-details {
    grid-template-columns: 1fr;
  }
}
</style>
