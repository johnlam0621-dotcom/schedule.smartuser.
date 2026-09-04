const MINUTES_PER_DAY = 24 * 60

export function scheduleMinutes(value) {
  const seconds = scheduleSeconds(value)
  return seconds === null ? null : Math.floor(seconds / 60)
}

export function scheduleSeconds(value) {
  const text = String(value || '').trim().toUpperCase().replace(/\./g, '')
  if (!text) return null

  const match = text.match(/^(\d{1,2})(?::(\d{1,2}))?(?::(\d{1,2}))?\s*(AM|PM)?$/)
  if (!match) return null

  let hour = Number(match[1])
  const minute = Number(match[2] || 0)
  const second = Number(match[3] || 0)
  const meridiem = match[4] || ''
  if (minute < 0 || minute > 59 || second < 0 || second > 59) return null
  if (meridiem) {
    if (hour < 1 || hour > 12) return null
    if (meridiem === 'PM' && hour < 12) hour += 12
    if (meridiem === 'AM' && hour === 12) hour = 0
  } else if (hour < 0 || hour > 23) {
    return null
  }
  return hour * 60 * 60 + minute * 60 + second
}

export function compareScheduleRecords(left, right) {
  const leftDate = String(left?.inspectionDate || '')
  const rightDate = String(right?.inspectionDate || '')
  if (leftDate !== rightDate) {
    if (!leftDate) return 1
    if (!rightDate) return -1
    return leftDate.localeCompare(rightDate)
  }

  const leftSeconds = scheduleSeconds(left?.inspectionTime)
  const rightSeconds = scheduleSeconds(right?.inspectionTime)
  if (leftSeconds !== rightSeconds) {
    if (leftSeconds === null) return 1
    if (rightSeconds === null) return -1
    return leftSeconds - rightSeconds
  }
  return Number(left?.id || 0) - Number(right?.id || 0)
}

export function groupScheduleRecords(records) {
  const groups = new Map()
  ;(records || []).forEach((record) => {
    const inspector = String(record?.inspector || 'Unassigned').trim() || 'Unassigned'
    const date = String(record?.inspectionDate || '')
    const key = `${date}\u0000${inspector}`
    if (!groups.has(key)) {
      groups.set(key, { inspector, date, records: [], recordKeys: new Set() })
    }
    const group = groups.get(key)
    const recordKey = [
      String(record?.inspectionTime || '').trim().toLowerCase(),
      String(record?.address || '').trim().toLowerCase(),
      String(record?.macId || '').trim().toLowerCase()
    ].join('\u0000')
    if (!group.recordKeys.has(recordKey)) {
      group.recordKeys.add(recordKey)
      group.records.push(record)
    }
  })
  return Array.from(groups.values())
    .map((group) => ({
      inspector: group.inspector,
      date: group.date,
      records: [...group.records].sort(compareScheduleRecords)
    }))
    .sort((left, right) => left.date.localeCompare(right.date) || left.inspector.localeCompare(right.inspector))
}

export function adjacentScheduleRecordPairs(records) {
  const pairs = []
  groupScheduleRecords(records).forEach((group, groupIndex) => {
    for (let stopIndex = 0; stopIndex < group.records.length - 1; stopIndex += 1) {
      pairs.push({
        group,
        groupIndex,
        stopIndex,
        fromRecord: group.records[stopIndex],
        toRecord: group.records[stopIndex + 1]
      })
    }
  })
  return pairs
}

export function inspectionWorkMinutes(productLabel) {
  const normalized = String(productLabel || '').trim()
  if (!normalized) return null
  if (/^all products$/i.test(normalized)) return 60

  const knownProducts = new Set(['a', 'b', 'c', 'mac', 'dac', 'battery', 'hp', 'sp'])
  const products = normalized
    .replace(/\bonly\b/gi, '')
    .split('+')
    .map((item) => item.trim().toLowerCase())
    .filter(Boolean)
  if (!products.length || products.some((product) => !knownProducts.has(product))) return null
  const productCount = new Set(products).size

  if (productCount === 1) return 30
  if (productCount === 2) return 45
  return 60
}

export function calculateTravelMinutes(durationSeconds, workMinutes) {
  const seconds = Number(durationSeconds)
  const normalizedWorkMinutes = workMinutes === null || workMinutes === undefined
    ? null
    : Number(workMinutes)
  const drivingMinutes = Number.isFinite(seconds) && seconds > 0
    ? Math.max(1, Math.round(seconds / 60))
    : null
  const totalMinutes = drivingMinutes === null
    || !Number.isFinite(normalizedWorkMinutes)
    || normalizedWorkMinutes < 0
    ? null
    : drivingMinutes + normalizedWorkMinutes
  return { drivingMinutes, totalMinutes }
}

export function calculateTravelLegDetails({
  durationSeconds,
  durationText,
  distance = '',
  workMinutes,
  productLabel,
  inspectionTime
}) {
  const { drivingMinutes, totalMinutes } = calculateTravelMinutes(durationSeconds, workMinutes)
  const sourceStart = scheduleMinutes(inspectionTime)
  const plannedWindow = sourceStart === null || totalMinutes === null
    ? ''
    : `${inspectionTime}–${formatScheduleTime(sourceStart + totalMinutes)}`
  const drivingDuration = durationText || (drivingMinutes === null ? 'unavailable' : `${drivingMinutes} min`)
  const product = productLabel || 'No product'
  const tooltip = totalMinutes === null
    ? `Total unavailable: Google driving ${drivingDuration}; Inspector work ${workMinutes === null ? 'unavailable because the product is missing or unknown' : `${workMinutes} min for ${product}`}. Click to open this route in Google Maps.`
    : `Total ${totalMinutes} min${plannedWindow ? ` (${plannedWindow})` : ''}: Google driving ${drivingDuration} + Inspector work ${workMinutes} min for ${product}. Click to open this route in Google Maps.`
  return {
    drivingMinutes,
    totalMinutes,
    plannedWindow,
    drivingDuration,
    distance,
    tooltip
  }
}

export function replaceMatchingTravelLeg(routeLegs, resolvedLeg) {
  const index = routeLegs.findIndex((item) => item.key === resolvedLeg?.key)
  if (index < 0) return false
  routeLegs.splice(index, 1, resolvedLeg)
  return true
}

export function formatScheduleTime(totalMinutes) {
  if (!Number.isFinite(totalMinutes)) return ''
  const dayOffset = Math.floor(totalMinutes / MINUTES_PER_DAY)
  const normalized = ((totalMinutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
  const hour24 = Math.floor(normalized / 60)
  const minute = normalized % 60
  const meridiem = hour24 >= 12 ? 'PM' : 'AM'
  const hour12 = hour24 % 12 || 12
  return `${hour12}:${String(minute).padStart(2, '0')} ${meridiem}${dayOffset > 0 ? ` (+${dayOffset} day)` : ''}`
}


