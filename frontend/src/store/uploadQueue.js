import { computed, reactive } from 'vue'

const activeStatuses = new Set(['queued', 'uploading'])
let sequence = 0
let processing = false

export const uploadQueueState = reactive({
  items: []
})

export const activeUploadCount = computed(() =>
  uploadQueueState.items.filter((item) => activeStatuses.has(item.status)).length
)

export function queueBackgroundUpload({ name, size, description, upload }) {
  const item = reactive({
    id: `${Date.now()}-${++sequence}`,
    name,
    size,
    description,
    progress: 0,
    status: 'queued',
    error: '',
    upload
  })
  uploadQueueState.items.unshift(item)
  void processQueue()
  return item
}

export function retryBackgroundUpload(id) {
  const item = uploadQueueState.items.find((candidate) => candidate.id === id)
  if (!item || item.status !== 'failed') return
  item.error = ''
  item.progress = 0
  item.status = 'queued'
  void processQueue()
}

export function dismissBackgroundUpload(id) {
  const index = uploadQueueState.items.findIndex((item) => item.id === id)
  if (index >= 0 && !activeStatuses.has(uploadQueueState.items[index].status)) {
    uploadQueueState.items.splice(index, 1)
  }
}

async function processQueue() {
  if (processing) return
  processing = true
  try {
    let item = uploadQueueState.items.find((candidate) => candidate.status === 'queued')
    while (item) {
      item.status = 'uploading'
      item.error = ''
      try {
        await item.upload((progress) => {
          item.progress = Math.max(1, Math.min(100, Math.round(progress || 0)))
        })
        item.progress = 100
        item.status = 'complete'
      } catch (error) {
        item.status = 'failed'
        item.error = error?.message || 'Upload failed. Check the connection and retry.'
      }
      item = uploadQueueState.items.find((candidate) => candidate.status === 'queued')
    }
  } finally {
    processing = false
  }
}

