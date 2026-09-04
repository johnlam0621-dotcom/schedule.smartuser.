param(
  [string]$GoogleSheetWebAppUrl = "",
  [string]$GoogleSheetWebAppSecret = "",
  [string]$GoogleSheetSpreadsheetId = "",
  [long]$GoogleSheetGid = 0,
  [bool]$GoogleSheetWriteEnabled = $false,
  [string]$MysqlUrl = $env:MYSQL_URL,
  [string]$MysqlUsername = $env:MYSQL_USERNAME,
  [string]$MysqlPassword = $env:MYSQL_PASSWORD,
  [string]$RedisHost = "localhost",
  [int]$RedisPort = 6379,
  [int]$ServerPort = 8088,
  [string]$JavaExe = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\java.exe",
  [switch]$UseProxy,
  [string]$ProxyHost = "127.0.0.1",
  [int]$ProxyPort = 7890
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $projectRoot "backend"
$jar = Join-Path $backendRoot "target\schedule-smartuser-backend-1.0.0.jar"
$localEnvFile = Join-Path $PSScriptRoot "backend-env.local.ps1"

# 支持直接运行本脚本；真实密钥只从本地忽略文件或当前进程环境读取。
if (Test-Path $localEnvFile) { . $localEnvFile }
if (-not $GoogleSheetWebAppUrl) { $GoogleSheetWebAppUrl = $env:GOOGLE_SHEET_WEB_APP_URL }
if (-not $GoogleSheetWebAppSecret) { $GoogleSheetWebAppSecret = $env:GOOGLE_SHEET_WEB_APP_SECRET }
if (-not $GoogleSheetSpreadsheetId) { $GoogleSheetSpreadsheetId = $env:GOOGLE_SHEET_SPREADSHEET_ID }
if ($GoogleSheetGid -le 0 -and $env:GOOGLE_SHEET_GID) { $GoogleSheetGid = [long]$env:GOOGLE_SHEET_GID }

$portInUse = netstat -ano | Select-String ":$ServerPort\s+.*LISTENING"
if ($portInUse) {
  throw "Port $ServerPort is already in use. Stop the current backend before starting this one."
}

if (-not (Test-Path $JavaExe)) {
  $JavaExe = "java"
}

$javaHome = Split-Path -Parent (Split-Path -Parent $JavaExe)
$env:JAVA_HOME = $javaHome
$env:PATH = (Join-Path $javaHome "bin") + ";" + $env:PATH

if (-not (Test-Path $jar)) {
  Push-Location $backendRoot
  try {
    mvn -q -DskipTests package
  } finally {
    Pop-Location
  }
}

if ($MysqlUrl) { $env:MYSQL_URL = $MysqlUrl }
if ($MysqlUsername) { $env:MYSQL_USERNAME = $MysqlUsername }
if ($MysqlPassword) { $env:MYSQL_PASSWORD = $MysqlPassword }
$env:REDIS_HOST = $RedisHost
$env:REDIS_PORT = [string]$RedisPort
$env:SERVER_PORT = [string]$ServerPort
$env:GOOGLE_SHEET_SYNC_ENABLED = if ($GoogleSheetWriteEnabled) { "true" } else { "false" }
$env:GOOGLE_SHEET_WEB_APP_URL = $GoogleSheetWebAppUrl
$env:GOOGLE_SHEET_WEB_APP_SECRET = $GoogleSheetWebAppSecret
$env:GOOGLE_SHEET_SPREADSHEET_ID = $GoogleSheetSpreadsheetId
$env:GOOGLE_SHEET_GID = [string]$GoogleSheetGid

Push-Location $backendRoot
try {
  # 固定 JVM 输出编码，保证中文业务日志在文件和终端中可读。
  $javaArgs = @("-Dfile.encoding=UTF-8")
  if ($UseProxy) {
    # 仅在明确传入 -UseProxy 时才启用代理，避免本地代理未启动导致 Google Sheet 连接超时。
    $javaArgs += @(
      "-Dhttp.proxyHost=$ProxyHost",
      "-Dhttp.proxyPort=$ProxyPort",
      "-Dhttps.proxyHost=$ProxyHost",
      "-Dhttps.proxyPort=$ProxyPort"
    )
  }
  $javaArgs += @("-jar", "target\schedule-smartuser-backend-1.0.0.jar")
  & $JavaExe @javaArgs
} finally {
  Pop-Location
}
