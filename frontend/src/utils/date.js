// 前端日期时间展示工具：把后端 LocalDateTime/ISO 字符串统一展示为 YYYY-MM-DD HH:mm:ss。
export function formatDisplayValue(value) {
  if (value === null || value === undefined || value === '') {
    return '-'
  }
  return formatDateTimeText(value)
}

export function localDateInputValue(date = new Date()) {
  // 不使用 toISOString()：它会先转换为 UTC，澳洲凌晨可能因此显示成前一天。
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function formatDateTimeText(value) {
  // 仅处理包含日期和时间的字符串，普通日期、时间、业务文本保持原样。
  if (typeof value !== 'string') {
    return value
  }
  const text = value.trim()
  const match = text.match(/^(\d{4})-(\d{2})-(\d{2})[T\s](\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?$/)
  if (!match) {
    return value
  }
  const [, year, month, day, hour, minute, second = '00'] = match
  return `${year}-${month}-${day} ${hour}:${minute}:${second}`
}
