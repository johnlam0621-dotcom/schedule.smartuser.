$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$mysqlScript = Join-Path $PSScriptRoot "start-backend-mysql.ps1"
$googleScript = Join-Path $PSScriptRoot "start-backend-google-webapp.ps1"
$frontendScript = Join-Path $PSScriptRoot "start-frontend-node24.ps1"
$logFile = Join-Path $projectRoot "logs\backend-current.log"
$frontendOutLog = Join-Path $projectRoot "logs\frontend-current.out.log"
$frontendErrorLog = Join-Path $projectRoot "logs\frontend-current.err.log"
$localEnvFile = Join-Path $PSScriptRoot "backend-env.local.ps1"

# 统一从本地忽略文件加载数据库和 Google Sheet 配置，避免用正则读取脚本中的明文密钥。
if (Test-Path $localEnvFile) { . $localEnvFile }
if (-not $env:MYSQL_URL -or -not $env:MYSQL_USERNAME -or -not $env:MYSQL_PASSWORD) {
  throw "Backend environment is missing. Configure $localEnvFile first."
}

# 一个脚本同时启动前后端；5173 已被占用时保留现有前端，避免重复启动 Vite。
$frontendPortInUse = netstat -ano | Select-String ":5173\s+.*LISTENING"
if (-not $frontendPortInUse) {
  $frontendArguments = '-NoProfile -ExecutionPolicy Bypass -File "' + $frontendScript + '"'
  Start-Process -FilePath "powershell.exe" `
    -ArgumentList $frontendArguments `
    -WorkingDirectory $projectRoot `
    -RedirectStandardOutput $frontendOutLog `
    -RedirectStandardError $frontendErrorLog `
    -WindowStyle Hidden
  Start-Sleep -Seconds 4
  if (-not (netstat -ano | Select-String ":5173\s+.*LISTENING")) {
    throw "Frontend failed to start. Check $frontendErrorLog."
  }
}

# 后端保持在当前进程中运行，关闭此终端即可停止后端；运行日志统一写入 logs。
& $googleScript *> $logFile
