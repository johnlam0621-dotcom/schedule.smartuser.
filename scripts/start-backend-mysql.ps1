param(
  [string]$MysqlUrl = "",
  [string]$MysqlUsername = "",
  [string]$MysqlPassword = "",
  [string]$RedisHost = "localhost",
  [int]$RedisPort = 6379,
  [string]$JavaExe = "D:\Program Files\Java\jdk-17.0.16.8-hotspot\bin\java.exe"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $projectRoot "backend"
$jar = Join-Path $backendRoot "target\schedule-smartuser-backend-1.0.0.jar"
$localEnvFile = Join-Path $PSScriptRoot "backend-env.local.ps1"

# 命令行参数优先；未传参数时从被忽略的本地环境文件读取，避免把密码写进可分享脚本。
if (Test-Path $localEnvFile) { . $localEnvFile }
if (-not $MysqlUrl) { $MysqlUrl = $env:MYSQL_URL }
if (-not $MysqlUsername) { $MysqlUsername = $env:MYSQL_USERNAME }
if (-not $MysqlPassword) { $MysqlPassword = $env:MYSQL_PASSWORD }
if (-not $MysqlUrl -or -not $MysqlUsername -or -not $MysqlPassword) {
  throw "MySQL settings are missing. Copy backend-env.example.ps1 to backend-env.local.ps1 and fill the values."
}

if (-not (Test-Path $JavaExe)) {
  $javaCommand = Get-Command java -ErrorAction Stop
  $JavaExe = $javaCommand.Source
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

$env:MYSQL_URL = $MysqlUrl
$env:MYSQL_USERNAME = $MysqlUsername
$env:MYSQL_PASSWORD = $MysqlPassword
$env:REDIS_HOST = $RedisHost
$env:REDIS_PORT = [string]$RedisPort

Push-Location $backendRoot
try {
  # 固定 JVM 输出编码，保证中文业务日志在文件和终端中可读。
  & $JavaExe "-Dfile.encoding=UTF-8" -jar target\schedule-smartuser-backend-1.0.0.jar
} finally {
  Pop-Location
}
