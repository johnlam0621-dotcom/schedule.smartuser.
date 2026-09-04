const PREFIX = '[Schedule SmartUser]'

// 前端统一日志入口：统一格式，避免各页面直接散落 console 调用。
function write(level, scope, message, detail) {
  // 统一追加系统前缀和业务作用域，浏览器控制台可按 scope 快速筛选问题来源。
  const payload = detail === undefined ? '' : detail
  const text = `${PREFIX} ${scope} - ${message}`
  if (level === 'error') {
    console.error(text, payload)
    return
  }
  console.info(text, payload)
}

export function logInfo(scope, message, detail) {
  // info 级别用于记录正常业务动作，例如查询、导入、导出、自动保存开始/完成。
  write('info', scope, message, detail)
}

export function logError(scope, message, error) {
  // error 级别用于记录接口失败、业务异常和浏览器运行时错误。
  write('error', scope, message, normalizeError(error))
}

function normalizeError(error) {
  // Error 对象保留 message 和 stack，普通对象按原样输出，便于排查接口返回错误。
  if (!error) {
    return ''
  }
  if (error instanceof Error) {
    return { message: error.message, stack: error.stack }
  }
  return error
}
