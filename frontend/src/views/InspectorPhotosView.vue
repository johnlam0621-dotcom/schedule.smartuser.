<template>
  <main class="review-page">
    <section class="review-header">
      <div>
        <h2>Inspector Photos</h2>
        <p>Review and manage inspection photos and videos stored in this application.</p>
      </div>
      <div class="review-filters">
        <label>Inspector
          <select v-model="selectedInspector" @change="loadReview">
            <option value="">Choose an inspector</option>
            <option v-for="name in data.inspectors" :key="name" :value="name">{{ name }}</option>
          </select>
        </label>
        <label>Date
          <select v-model="selectedDate" :disabled="!selectedInspector || !data.availableDates?.length" @change="loadReview">
            <option v-if="!data.availableDates?.length" :value="selectedDate">No imported dates</option>
            <option v-for="date in data.availableDates" :key="date" :value="date">{{ date }}</option>
          </select>
        </label>
        <button type="button" :disabled="loading || !selectedInspector" @click="loadReview">
          {{ loading ? 'Loading…' : 'Show images' }}
        </button>
      </div>
    </section>

    <p v-if="error" class="review-message error">{{ error }}</p>
    <p v-if="notice" class="review-message success">{{ notice }}</p>
    <section class="review-summary">
      <div><strong>{{ data.jobCount || 0 }}</strong><span>inspection jobs</span></div>
      <div><strong>{{ data.fileCount || 0 }}</strong><span>uploaded files</span></div>
      <div><strong>{{ displayDate }}</strong><span>selected date</span></div>
    </section>

    <section v-if="!selectedInspector" class="empty-state">Choose an inspector and date to view uploaded files.</section>
    <section v-else-if="!loading && !data.jobs?.length" class="empty-state">
      No inspection jobs were found for {{ selectedInspector }} on {{ displayDate }}.
    </section>

    <article v-for="job in data.jobs || []" :key="job.id" class="job-card">
      <header>
        <div>
          <h3>{{ job.start }} – {{ job.end }} · {{ job.customerName || 'Unnamed customer' }}</h3>
          <p>MACID: {{ job.macId || '—' }} · {{ job.projects || 'No product recorded' }}</p>
          <p>{{ job.address || 'No address recorded' }}</p>
        </div>
        <span class="status-pill">{{ statusLabel(job.fieldStatus) }}</span>
      </header>
      <div v-if="job.schedulerRemarks || job.inspectorRemark" class="notes">
        <p v-if="job.schedulerRemarks"><strong>Scheduler remark:</strong> {{ job.schedulerRemarks }}</p>
        <p v-if="job.inspectorRemark"><strong>Inspector note:</strong> {{ job.inspectorRemark }}</p>
      </div>

      <section v-if="photoSlots(job).length" class="media-section">
        <div class="media-title"><h4>Inspection Photos</h4><span>{{ mediaCount(photoSlots(job)) }} photo(s)</span></div>
        <div class="file-grid">
          <template v-for="slot in photoSlots(job)" :key="slot.key">
            <article v-for="file in slot.files" :key="file.id" class="file-card">
              <button type="button" class="media-preview-button" @click="openMedia(file, slot, job)">
                <img :src="previews[file.id]" :alt="mediaSlotLabel(slot)" />
              </button>
              <strong>{{ mediaSlotLabel(slot) }}</strong>
              <small>{{ file.name }}</small>
              <p v-if="file.remark && remarkEditor.fileId !== file.id" class="media-remark"><strong>Remark:</strong> {{ file.remark }}</p>
              <div class="media-actions">
                <button type="button" @click="openMedia(file, slot, job)">View</button>
                <button type="button" @click="openAnnotationEditor(job, slot, file)">Edit photo</button>
                <button type="button" @click="openRemarkEditor(file)">Remark</button>
                <button type="button" class="danger" :disabled="deleting[file.id]" @click="deleteMedia(job, slot, file)">{{ deleting[file.id] ? 'Deleting…' : 'Delete' }}</button>
              </div>
              <div v-if="remarkEditor.fileId === file.id" class="remark-editor">
                <textarea v-model="remarkEditor.value" maxlength="2000" rows="3" placeholder="Add a remark for this photo"></textarea>
                <div><button type="button" class="secondary" @click="closeRemarkEditor">Cancel</button><button type="button" :disabled="remarkEditor.saving" @click="saveRemark(file)">{{ remarkEditor.saving ? 'Saving…' : 'Save remark' }}</button></div>
              </div>
            </article>
          </template>
        </div>
      </section>

      <section class="media-section video-section">
        <div class="media-title"><h4>Inspection Videos</h4><span>{{ mediaCount(videoSlots(job)) }} video(s)</span></div>
        <div v-if="videoSlots(job).length" class="file-grid video-grid">
          <template v-for="slot in videoSlots(job)" :key="slot.key">
            <article v-for="file in slot.files" :key="file.id" class="file-card video-card">
              <div class="video-placeholder">Video ready to view</div>
              <strong>{{ slot.label }}</strong>
              <small>{{ file.name }}</small>
              <p v-if="file.remark && remarkEditor.fileId !== file.id" class="media-remark"><strong>Remark:</strong> {{ file.remark }}</p>
              <div class="media-actions">
                <button type="button" @click="openMedia(file, slot, job)">Play</button>
                <a class="video-download" :href="downloadUrl(file)" download>Download</a>
                <button type="button" @click="openRemarkEditor(file)">Remark</button>
                <button type="button" class="danger" :disabled="deleting[file.id]" @click="deleteMedia(job, slot, file)">{{ deleting[file.id] ? 'Deleting…' : 'Delete' }}</button>
              </div>
              <div v-if="remarkEditor.fileId === file.id" class="remark-editor">
                <textarea v-model="remarkEditor.value" maxlength="2000" rows="3" placeholder="Add a remark for this video"></textarea>
                <div><button type="button" class="secondary" @click="closeRemarkEditor">Cancel</button><button type="button" :disabled="remarkEditor.saving" @click="saveRemark(file)">{{ remarkEditor.saving ? 'Saving…' : 'Save remark' }}</button></div>
              </div>
            </article>
          </template>
        </div>
        <div v-else class="job-empty compact">No inspection videos uploaded for this job.</div>
      </section>
    </article>

    <div v-if="activeMedia" class="media-modal" role="dialog" aria-modal="true" @click.self="closeMedia">
      <div class="modal-panel">
        <button type="button" class="modal-close" aria-label="Close preview" @click="closeMedia">×</button>
        <div v-if="activeMedia.group === 'video' && activeMedia.preparing" class="video-preparing">{{ activeMedia.status }}</div>
        <video v-else-if="activeMedia.group === 'video' && activeMedia.playbackUrl" :src="activeMedia.playbackUrl" controls autoplay preload="metadata" @error="mediaPlaybackFailed"></video>
        <img v-else :src="previews[activeMedia.file.id]" :alt="activeMedia.label" />
        <div class="modal-caption">
          <strong>{{ activeMedia.label }}</strong>
          <span>{{ activeMedia.customer }} · {{ activeMedia.file.name }}</span>
          <a v-if="activeMedia.group === 'video' && activeMedia.playbackUrl" class="video-download modal-download" :href="`${activeMedia.playbackUrl}?download=true`" download>Download smaller video</a>
          <a v-if="activeMedia.group === 'video'" class="video-download modal-download" :href="downloadUrl(activeMedia.file)" download>Download original</a>
        </div>
      </div>
    </div>

    <Teleport to="body">
      <div v-if="annotationEditor.open" class="annotation-backdrop" @click.self="closeAnnotationEditor">
        <section class="annotation-dialog" role="dialog" aria-modal="true" aria-labelledby="manager-annotation-title">
          <header class="annotation-header">
            <div><strong id="manager-annotation-title">Edit inspection photo</strong><span>{{ annotationEditor.file?.name }}</span></div>
            <button type="button" class="annotation-close" aria-label="Close photo editor" @click="closeAnnotationEditor">×</button>
          </header>
          <div class="annotation-toolbar">
            <button type="button" :class="{ active: annotationEditor.tool === 'draw' }" @click="annotationEditor.tool = 'draw'">Draw</button>
            <label>Colour <input v-model="annotationEditor.color" type="color" aria-label="Drawing colour" /></label>
            <label>Brush <input v-model.number="annotationEditor.brushSize" type="range" min="2" max="28" step="1" /></label>
            <button type="button" :disabled="!annotationActions.length" @click="undoAnnotation">Undo</button>
            <button type="button" :disabled="!annotationActions.length" @click="clearAnnotations">Clear marks</button>
          </div>
          <div class="annotation-text-tools">
            <input v-model.trim="annotationEditor.text" type="text" maxlength="100" placeholder="Optional typed note" @keyup.enter="startTextPlacement" />
            <button type="button" :disabled="!annotationEditor.text" :class="{ active: annotationEditor.tool === 'text' }" @click="startTextPlacement">Place text</button>
            <span v-if="annotationEditor.tool === 'text'">Tap the photo where the note should appear.</span>
          </div>
          <div class="annotation-stage">
            <p v-if="annotationEditor.loading">Loading photo editor…</p>
            <p v-else-if="annotationEditor.error" class="annotation-error">{{ annotationEditor.error }}</p>
            <canvas v-show="!annotationEditor.loading && !annotationEditor.error" ref="annotationCanvas" aria-label="Photo drawing area"
              @pointerdown="startAnnotationPointer" @pointermove="moveAnnotationPointer" @pointerup="endAnnotationPointer" @pointercancel="endAnnotationPointer"></canvas>
          </div>
          <footer class="annotation-footer">
            <span>The original photo remains unchanged; an edited copy will be saved in the same category.</span>
            <div><button type="button" class="secondary" :disabled="annotationEditor.saving" @click="closeAnnotationEditor">Cancel</button><button type="button" :disabled="annotationEditor.loading || annotationEditor.saving || !annotationActions.length" @click="saveAnnotatedCopy">{{ annotationEditor.saving ? 'Saving…' : 'Save edited copy' }}</button></div>
          </footer>
        </section>
      </div>
    </Teleport>
  </main>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import http from '../api/http'
import { authState } from '../store/auth'
import { originalVideoDownloadUrl, prepareVideoPlayback } from '../utils/videoPlayback'

const selectedDate = ref(localDateText(new Date()))
const selectedInspector = ref('')
const loading = ref(false)
const error = ref('')
const notice = ref('')
const data = reactive({ inspectors: [], availableDates: [], jobs: [], jobCount: 0, fileCount: 0 })
const previews = reactive({})
const deleting = reactive({})
const activeMedia = ref(null)
const remarkEditor = reactive({ fileId: null, value: '', saving: false })
const annotationCanvas = ref(null)
const annotationActions = ref([])
const annotationEditor = reactive({
  open: false, loading: false, saving: false, error: '', tool: 'draw', color: '#ff2d2d', brushSize: 8,
  text: '', job: null, slot: null, file: null
})
let annotationBaseImage = null
let annotationImageUrl = ''
let activeAnnotationStroke = null
const displayDate = computed(() => selectedDate.value || '—')

onMounted(() => {
  loadReview()
  window.addEventListener('keydown', handleAnnotationKeydown)
})
onBeforeUnmount(() => {
  clearPreviews()
  releaseAnnotationImage()
  window.removeEventListener('keydown', handleAnnotationKeydown)
})

async function loadReview() {
  loading.value = true
  error.value = ''
  notice.value = ''
  closeRemarkEditor()
  clearPreviews()
  try {
    const response = await http.get('/inspector/photo-review', {
      params: { inspector: selectedInspector.value || undefined, date: selectedDate.value }
    })
    Object.assign(data, response)
    selectedDate.value = response.date || selectedDate.value
    await loadPreviews(data.jobs || [])
  } catch (requestError) {
    error.value = requestError.message || 'Inspector photos could not be loaded.'
  } finally {
    loading.value = false
  }
}

async function loadPreviews(jobs) {
  // 视频只在用户点击播放时读取，避免页面初次打开便下载数 GB 文件。
  const files = jobs.flatMap((job) => photoSlots(job)).flatMap((slot) => slot.files || [])
  await Promise.all(files.map(loadPreview))
}

async function loadPreview(file) {
  if (previews[file.id]) return
  try {
    const response = await fetch(file.url, { headers: { Authorization: `Bearer ${authState.token}` } })
    if (response.ok) previews[file.id] = URL.createObjectURL(await response.blob())
  } catch (_) {
    // 单个文件读取失败时，其他检查照片仍可继续显示。
  }
}

function clearPreviews() {
  Object.values(previews).forEach((url) => URL.revokeObjectURL(url))
  Object.keys(previews).forEach((key) => delete previews[key])
}
function fileSlots(job) { return (job.photoSlots || []).filter((slot) => slot.files?.length) }
function photoSlots(job) { return fileSlots(job).filter((slot) => slot.group !== 'video') }
function videoSlots(job) { return fileSlots(job).filter((slot) => slot.group === 'video') }
function mediaCount(slots) { return slots.reduce((total, slot) => total + (slot.files?.length || 0), 0) }
function mediaSlotLabel(slot) {
  if (slot.key === 'additional_photo_9') return 'Floor Plan'
  if (slot.key === 'additional_photo_10') return 'Measurements'
  return slot.label
}
async function openMedia(file, slot, job) {
  const isVideo = slot.group === 'video'
  activeMedia.value = {
    file,
    label: mediaSlotLabel(slot),
    group: slot.group,
    customer: job.customerName || 'Unnamed customer',
    preparing: isVideo,
    status: isVideo ? 'Preparing video…' : '',
    playbackUrl: ''
  }
  if (slot.group !== 'video') return
  try {
    const playbackUrl = await prepareVideoPlayback(file, (status) => {
      if (activeMedia.value?.file.id === file.id) activeMedia.value.status = status
    })
    if (activeMedia.value?.file.id === file.id) activeMedia.value.playbackUrl = playbackUrl
  } catch (requestError) {
    error.value = requestError.message || 'The video could not be prepared.'
  } finally {
    if (activeMedia.value?.file.id === file.id) activeMedia.value.preparing = false
  }
}
function closeMedia() { activeMedia.value = null }
function downloadUrl(file) {
  return originalVideoDownloadUrl(file)
}
function mediaPlaybackFailed() {
  error.value = 'This video format cannot be played in the browser. Use Download video to save the original file.'
}

function openRemarkEditor(file) {
  if (remarkEditor.fileId === file.id) return closeRemarkEditor()
  remarkEditor.fileId = file.id
  remarkEditor.value = file.remark || ''
  remarkEditor.saving = false
}

function closeRemarkEditor() {
  remarkEditor.fileId = null
  remarkEditor.value = ''
  remarkEditor.saving = false
}

async function saveRemark(file) {
  remarkEditor.saving = true
  error.value = ''
  notice.value = ''
  try {
    const remark = remarkEditor.value.trim()
    await http.put(`/inspector/photos/${file.id}/remark`, { remark })
    file.remark = remark
    closeRemarkEditor()
    notice.value = remark ? 'Media remark saved.' : 'Media remark removed.'
  } catch (requestError) {
    error.value = requestError.message || 'The media remark could not be saved.'
  } finally {
    remarkEditor.saving = false
  }
}

async function deleteMedia(job, slot, file) {
  if (!window.confirm(`Delete ${file.name || 'this uploaded file'}? This cannot be undone.`)) return
  deleting[file.id] = true
  error.value = ''
  notice.value = ''
  try {
    await http.delete(`/inspector/photos/${file.id}`)
    slot.files = (slot.files || []).filter((item) => item.id !== file.id)
    if (previews[file.id]) URL.revokeObjectURL(previews[file.id])
    delete previews[file.id]
    if (activeMedia.value?.file?.id === file.id) closeMedia()
    if (remarkEditor.fileId === file.id) closeRemarkEditor()
    data.fileCount = Math.max(0, Number(data.fileCount || 0) - 1)
    notice.value = `${slot.group === 'video' ? 'Video' : 'Photo'} deleted.`
  } catch (requestError) {
    error.value = requestError.message || 'The uploaded media could not be deleted.'
  } finally {
    delete deleting[file.id]
  }
}

async function openAnnotationEditor(job, slot, file) {
  annotationEditor.open = true
  annotationEditor.loading = true
  annotationEditor.saving = false
  annotationEditor.error = ''
  annotationEditor.tool = 'draw'
  annotationEditor.text = ''
  annotationEditor.job = job
  annotationEditor.slot = slot
  annotationEditor.file = file
  annotationActions.value = []
  document.body.classList.add('annotation-open')
  releaseAnnotationImage()
  try {
    const response = await fetch(file.url, { headers: { Authorization: `Bearer ${authState.token}` } })
    if (!response.ok) throw new Error(`Photo download failed (${response.status}).`)
    annotationImageUrl = URL.createObjectURL(await response.blob())
    const image = new Image()
    image.src = annotationImageUrl
    await new Promise((resolve, reject) => {
      image.onload = resolve
      image.onerror = () => reject(new Error('This photo could not be opened for editing.'))
    })
    annotationBaseImage = image
    await nextTick()
    const canvas = annotationCanvas.value
    const scale = Math.min(1, 2400 / Math.max(image.naturalWidth, image.naturalHeight))
    canvas.width = Math.max(1, Math.round(image.naturalWidth * scale))
    canvas.height = Math.max(1, Math.round(image.naturalHeight * scale))
    annotationEditor.loading = false
    await nextTick()
    renderAnnotationCanvas()
  } catch (requestError) {
    annotationEditor.loading = false
    annotationEditor.error = requestError.message || 'This photo could not be opened for editing.'
  }
}

function closeAnnotationEditor() {
  if (annotationEditor.saving) return
  annotationEditor.open = false
  annotationEditor.loading = false
  annotationEditor.error = ''
  annotationEditor.job = null
  annotationEditor.slot = null
  annotationEditor.file = null
  annotationActions.value = []
  activeAnnotationStroke = null
  document.body.classList.remove('annotation-open')
  releaseAnnotationImage()
}

function releaseAnnotationImage() {
  annotationBaseImage = null
  if (annotationImageUrl) URL.revokeObjectURL(annotationImageUrl)
  annotationImageUrl = ''
}

function handleAnnotationKeydown(event) {
  if (!annotationEditor.open) return
  if (event.key === 'Escape') closeAnnotationEditor()
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z') {
    event.preventDefault()
    undoAnnotation()
  }
}

function annotationPoint(event) {
  const canvas = annotationCanvas.value
  const rect = canvas.getBoundingClientRect()
  return { x: (event.clientX - rect.left) * canvas.width / rect.width, y: (event.clientY - rect.top) * canvas.height / rect.height, scale: canvas.width / rect.width }
}

function startAnnotationPointer(event) {
  if (annotationEditor.loading || annotationEditor.error) return
  event.preventDefault()
  const point = annotationPoint(event)
  if (annotationEditor.tool === 'text') {
    const text = annotationEditor.text.trim()
    if (!text) return
    annotationActions.value.push({ type: 'text', text, color: annotationEditor.color, fontSize: Math.max(22, annotationEditor.brushSize * 3.5) * point.scale, x: point.x, y: point.y })
    annotationEditor.text = ''
    annotationEditor.tool = 'draw'
    return renderAnnotationCanvas()
  }
  annotationCanvas.value.setPointerCapture?.(event.pointerId)
  activeAnnotationStroke = { type: 'stroke', color: annotationEditor.color, width: annotationEditor.brushSize * point.scale, points: [{ x: point.x, y: point.y }] }
  annotationActions.value.push(activeAnnotationStroke)
  renderAnnotationCanvas()
}

function moveAnnotationPointer(event) {
  if (!activeAnnotationStroke) return
  event.preventDefault()
  const point = annotationPoint(event)
  const previous = activeAnnotationStroke.points[activeAnnotationStroke.points.length - 1]
  if (Math.hypot(point.x - previous.x, point.y - previous.y) < Math.max(1, activeAnnotationStroke.width / 5)) return
  activeAnnotationStroke.points.push({ x: point.x, y: point.y })
  renderAnnotationCanvas()
}

function endAnnotationPointer(event) {
  if (!activeAnnotationStroke) return
  event.preventDefault()
  annotationCanvas.value.releasePointerCapture?.(event.pointerId)
  activeAnnotationStroke = null
}

function startTextPlacement() { if (annotationEditor.text.trim()) annotationEditor.tool = 'text' }
function undoAnnotation() { annotationActions.value.pop(); activeAnnotationStroke = null; renderAnnotationCanvas() }
function clearAnnotations() { annotationActions.value = []; activeAnnotationStroke = null; renderAnnotationCanvas() }

function renderAnnotationCanvas() {
  const canvas = annotationCanvas.value
  if (!canvas || !annotationBaseImage) return
  const context = canvas.getContext('2d')
  context.clearRect(0, 0, canvas.width, canvas.height)
  context.drawImage(annotationBaseImage, 0, 0, canvas.width, canvas.height)
  annotationActions.value.forEach((action) => {
    if (action.type === 'text') {
      context.save(); context.font = `700 ${action.fontSize}px Arial, sans-serif`; context.textBaseline = 'top'; context.lineJoin = 'round'
      context.lineWidth = Math.max(2, action.fontSize / 12); context.strokeStyle = 'rgba(255,255,255,.9)'; context.strokeText(action.text, action.x, action.y)
      context.fillStyle = action.color; context.fillText(action.text, action.x, action.y); context.restore(); return
    }
    const points = action.points || []
    if (!points.length) return
    context.save(); context.strokeStyle = action.color; context.fillStyle = action.color; context.lineWidth = action.width; context.lineCap = 'round'; context.lineJoin = 'round'
    if (points.length === 1) { context.beginPath(); context.arc(points[0].x, points[0].y, action.width / 2, 0, Math.PI * 2); context.fill() }
    else { context.beginPath(); context.moveTo(points[0].x, points[0].y); points.slice(1).forEach((point) => context.lineTo(point.x, point.y)); context.stroke() }
    context.restore()
  })
}

async function saveAnnotatedCopy() {
  const canvas = annotationCanvas.value
  const { job, slot, file: sourceFile } = annotationEditor
  if (!canvas || !job || !slot || !annotationActions.value.length) return
  annotationEditor.saving = true
  annotationEditor.error = ''
  try {
    const blob = await compressedJpegBlob(canvas, 1400 * 1024)
    if (!blob) throw new Error('The edited photo could not be created.')
    const sourceName = (sourceFile?.name || 'inspection-photo').replace(/\.[^.]+$/, '').replace(/^edited-/, '')
    const editedFile = new File([blob], `edited-${sourceName}-${Date.now()}.jpg`, { type: 'image/jpeg', lastModified: Date.now() })
    const formData = new FormData()
    formData.append('categoryKey', slot.key)
    formData.append('file', editedFile)
    const uploaded = await http.post(`/inspector/route/${job.id}/photos`, formData, { timeout: 0 })
    const uploadedFile = { id: uploaded.id, url: uploaded.url, name: editedFile.name, remark: '' }
    slot.files = [uploadedFile, ...(slot.files || [])]
    data.fileCount = Number(data.fileCount || 0) + 1
    await loadPreview(uploadedFile)
    annotationEditor.saving = false
    closeAnnotationEditor()
    notice.value = 'Edited photo saved. The original photo is unchanged.'
  } catch (requestError) {
    annotationEditor.saving = false
    annotationEditor.error = requestError.message || 'The edited photo could not be saved.'
  }
}

async function compressedJpegBlob(canvas, targetBytes) {
  let smallest = null
  for (const quality of [0.82, 0.74, 0.66, 0.58]) {
    const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', quality))
    if (!blob) continue
    smallest = blob
    if (blob.size <= targetBytes) break
  }
  return smallest
}

function statusLabel(status) { return status === 'done' ? 'Done' : status === 'customer_unavailable' ? 'Unavailable' : 'Scheduled' }
function localDateText(date) {
  const offset = date.getTimezoneOffset() * 60000
  return new Date(date.getTime() - offset).toISOString().slice(0, 10)
}
</script>

<style scoped>
.review-page { display: grid; gap: 16px; padding: 18px; }
.review-header, .job-card, .empty-state, .review-summary { background: #fff; border: 1px solid #d9e2ef; border-radius: 12px; }
.review-header { padding: 18px; }
.review-header h2 { margin: 0 0 5px; }
.review-header p { margin: 0; color: #64748b; }
.review-filters { display: grid; grid-template-columns: minmax(220px, 1fr) 190px auto; gap: 12px; align-items: end; margin-top: 16px; }
label { display: grid; gap: 6px; color: #475569; font-size: 13px; }
select, input, button { min-height: 42px; border: 1px solid #cbd8e8; border-radius: 7px; padding: 8px 11px; font: inherit; }
button { background: #258184; color: #fff; border-color: #258184; font-weight: 700; cursor: pointer; }
button:disabled { opacity: .55; cursor: default; }
.review-summary { display: grid; grid-template-columns: repeat(3, 1fr); overflow: hidden; }
.review-summary div { display: grid; gap: 3px; padding: 15px; border-right: 1px solid #e1e8f2; }
.review-summary div:last-child { border-right: 0; }
.review-summary strong { font-size: 22px; color: #172033; }
.review-summary span { color: #64748b; }
.empty-state, .job-empty { padding: 28px; color: #64748b; text-align: center; }
.review-message { padding: 12px; border-radius: 8px; }.review-message.error { color: #b42318; background: #fef3f2; }.review-message.success { color: #176343; background: #edf9f3; }
.job-card { padding: 16px; }
.job-card > header { display: flex; justify-content: space-between; gap: 15px; }
.job-card h3 { margin: 0 0 7px; }.job-card p { margin: 3px 0; color: #52637b; }
.status-pill { align-self: flex-start; padding: 5px 10px; border: 1px solid #b8d8d0; border-radius: 999px; color: #176b61; background: #edf9f5; }
.notes { margin-top: 12px; padding: 10px 12px; background: #f7f9fc; border-radius: 8px; }
.media-section { margin-top: 18px; }.media-title { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.media-title h4 { margin: 0; font-size: 16px; }.media-title span { color: #64748b; font-size: 13px; }
.video-section { padding-top: 15px; border-top: 1px solid #e3eaf3; }
.file-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 14px; }
.file-card { display: grid; align-content: start; gap: 7px; color: #172033; text-align: left; text-decoration: none; border: 1px solid #d7e2ef; border-radius: 9px; padding: 9px; background: #fff; min-height: 0; }
.media-preview-button { min-height: 0; padding: 0; border: 0; background: transparent; cursor: zoom-in; }
.file-card img, .file-card video { width: 100%; aspect-ratio: 4 / 3; object-fit: cover; border-radius: 6px; background: #eef3f8; }
.file-card small { color: #64748b; overflow-wrap: anywhere; }
.media-actions { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 3px; }
.media-actions button { width: auto; min-height: 34px; padding: 5px 9px; font-size: 12px; }
.video-download { display: inline-flex; align-items: center; justify-content: center; width: auto; min-height: 34px; padding: 5px 9px; border: 1px solid #8fbde9; border-radius: 5px; background: #edf6ff; color: #075da8; font-size: 12px; font-weight: 700; text-decoration: none; }
.media-actions .danger { border-color: #efb0aa; background: #fff3f2; color: #b42318; }
.media-remark { margin: 2px 0; padding: 7px 8px; border-left: 3px solid #55a083; border-radius: 4px; background: #effaf6; color: #244e40; font-size: 12px; overflow-wrap: anywhere; }
.remark-editor { display: grid; gap: 7px; padding: 8px; border: 1px solid #86b7a6; border-radius: 7px; background: #f6fcf9; }
.remark-editor textarea { width: 100%; min-height: 70px; resize: vertical; border: 1px solid #cbd8e8; border-radius: 6px; padding: 8px; font: inherit; }
.remark-editor > div { display: flex; justify-content: flex-end; gap: 6px; }.remark-editor button { width: auto; min-height: 34px; padding: 5px 10px; }
button.secondary { border-color: #cbd8e8; background: #f5f7fa; color: #334155; }
.video-grid { grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); }.video-card { cursor: default; }
.video-placeholder { display: grid; place-items: center; min-height: 160px; border-radius: 6px; background: #0f172a; color: #fff; font-weight: 700; }
.video-open { min-height: 36px; padding: 6px 10px; }
.job-empty.compact { padding: 18px; background: #f7f9fc; border-radius: 8px; }
.media-modal { position: fixed; inset: 0; z-index: 1000; display: grid; place-items: center; padding: 24px; background: rgba(5, 15, 30, .86); }
.modal-panel { position: relative; display: grid; max-width: min(1200px, 96vw); max-height: 94vh; padding: 14px; border-radius: 12px; background: #fff; }
.modal-panel > img, .modal-panel > video { max-width: 100%; max-height: 78vh; object-fit: contain; background: #0b1220; }
.modal-close { position: absolute; top: -14px; right: -14px; z-index: 1; width: 42px; min-height: 42px; padding: 0; border-radius: 50%; font-size: 28px; }
.modal-caption { display: grid; gap: 3px; padding-top: 10px; }.modal-caption span { color: #64748b; }
.modal-download { justify-self: start; margin-top: 6px; }
.modal-loading { display: grid; place-items: center; min-width: min(800px, 85vw); min-height: 320px; background: #0b1220; color: #fff; font-size: 18px; }
@media (max-width: 700px) {
  .review-page { padding: 10px; }.review-filters, .review-summary { grid-template-columns: 1fr; }
  .review-summary div { border-right: 0; border-bottom: 1px solid #e1e8f2; }.file-grid, .video-grid { grid-template-columns: 1fr; }
  .media-modal { padding: 10px; }.modal-close { top: 4px; right: 4px; }
}
</style>
