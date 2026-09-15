import { computed, reactive } from 'vue'

const activeStatuses = new Set(['queued', 'uploading'])
// Photos and videos use separate lanes. A slow video must never occupy every
// worker and leave a newly captured inspection photo waiting behind it.
const maximumConcurrentByType = Object.freeze({ photo: 2, video: 1 })
const completedUploadVisibilityMs = 1500
let sequence = 0
const activeWorkersByType = { photo: 0, video: 0 }

export const uploadQueueState = reactive({
  items: []
})

export const activeUploadCount = computed(() =>
  uploadQueueState.items.filter((item) => activeStatuses.has(item.status)).length
)

export function queueBackgroundUpload({ name, size, description, mediaType = 'photo', upload }) {
  const normalizedMediaType = mediaType === 'video' ? 'video' : 'photo'
  const item = reactive({
    id: `${Date.now()}-${++sequence}`,
    name,
    size,
    description,
    progress: 0,
    status: 'queued',
    mediaType: normalizedMediaType,
    error: '',
    upload
  })
  uploadQueueState.items.unshift(item)
  processQueue()
  return item
}

export function retryBackgroundUpload(id) {
  const item = uploadQueueState.items.find((candidate) => candidate.id === id)
  if (!item || item.status !== 'failed') return
  item.error = ''
  item.progress = 0
  item.status = 'queued'
  processQueue()
}

export function dismissBackgroundUpload(id) {
  const index = uploadQueueState.items.findIndex((item) => item.id === id)
  if (index >= 0 && !activeStatuses.has(uploadQueueState.items[index].status)) {
    uploadQueueState.items.splice(index, 1)
  }
}

function processQueue() {
  // Start photos first, then fill the independent video lane. This keeps the
  // camera workflow responsive even while one or more large videos are active.
  for (const mediaType of ['photo', 'video']) {
    while (activeWorkersByType[mediaType] < maximumConcurrentByType[mediaType]) {
      const item = uploadQueueState.items.find((candidate) =>
        candidate.status === 'queued' && candidate.mediaType === mediaType
      )
      if (!item) break
      item.status = 'uploading'
      item.error = ''
      activeWorkersByType[mediaType] += 1
      void runUpload(item)
    }
  }
}

async function runUpload(item) {
  try {
    await item.upload((progress) => {
      item.progress = Math.max(1, Math.min(100, Math.round(progress || 0)))
    })
    item.progress = 100
    item.status = 'complete'
    // Successful uploads need no inspector action. Briefly show success, then
    // remove the item so the mobile panel closes automatically when it is empty.
    window.setTimeout(() => dismissBackgroundUpload(item.id), completedUploadVisibilityMs)
  } catch (error) {
    item.status = 'failed'
    item.error = error?.message || 'Upload failed. Check the connection and retry.'
  } finally {
    activeWorkersByType[item.mediaType] = Math.max(0, activeWorkersByType[item.mediaType] - 1)
    processQueue()
  }
}
