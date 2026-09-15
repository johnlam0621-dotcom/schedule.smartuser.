import http from '../api/http'

export async function prepareVideoPlayback(file, onStatus = () => {}) {
  if (!file?.id) throw new Error('The selected video is unavailable.')
  // Playback copies are prepared automatically after upload. Never hold the
  // modal behind a polling screen: open the stream immediately and only nudge
  // the server in the background for legacy files that have no copy yet.
  void http.post(`/inspector/photos/${file.id}/playback/prepare`).catch(() => {})
  return `/api/inspector/photos/${file.id}/playback`
}

export function originalVideoDownloadUrl(file) {
  const separator = String(file?.url || '').includes('?') ? '&' : '?'
  return `${file.url}${separator}download=true`
}
