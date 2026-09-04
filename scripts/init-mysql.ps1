param(
  [string]$MysqlExe = "mysql",
  [string]$HostName = "127.0.0.1",
  [int]$Port = 3306,
  [string]$User = "root",
  [string]$Password = $env:MYSQL_PASSWORD
)

# MySQL 初始化脚本负责验证客户端和 schema.sql，然后把表结构与初始数据写入业务数据库。
$ErrorActionPreference = "Stop"
# 项目根目录由脚本目录反向计算，避免依赖用户当前所在路径。
$projectRoot = Split-Path -Parent $PSScriptRoot
$schema = Join-Path $projectRoot "database\schema.sql"

if (-not $Password) {
  throw "MYSQL_PASSWORD is required. Set it in the environment or pass -Password."
}

if (-not (Test-Path $schema)) {
  throw "Schema file not found: $schema"
}

# 优先使用参数指定的 mysql 客户端，并在执行前确认命令真实存在。
$mysqlCommand = Get-Command $MysqlExe -ErrorAction SilentlyContinue
if (-not $mysqlCommand) {
  throw "mysql client not found. Install MySQL client or pass -MysqlExe with the full mysql.exe path."
}

# 所有连接参数都作为独立参数传入 mysql，schema 文件由客户端 source 命令执行。
& $mysqlCommand.Source `
  "--host=$HostName" `
  "--port=$Port" `
  "--user=$User" `
  "--password=$Password" `
  "--database=schedule_smartuser" `
  "--default-character-set=utf8mb4" `
  "--execute=source $schema"

if ($LASTEXITCODE -ne 0) {
  throw "MySQL schema initialization failed with exit code $LASTEXITCODE."
}

Write-Host "MySQL schema initialized: schedule_smartuser"
