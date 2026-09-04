param(
  [string]$MysqlInitUrl = "jdbc:mysql://127.0.0.1:3306/schedule_smartuser?useUnicode=true&characterEncoding=UTF-8&useAffectedRows=true&useTimezone=true&serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true",
  [string]$MysqlUsername = "root",
  [string]$MysqlPassword = $env:MYSQL_PASSWORD
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $projectRoot "backend"
$schema = Join-Path $projectRoot "database\schema.sql"

if (-not $MysqlPassword) {
  throw "MYSQL_PASSWORD is required. Set it in the environment or pass -MysqlPassword."
}

if (-not (Test-Path $schema)) {
  throw "Schema file not found: $schema"
}

$env:MYSQL_INIT_URL = $MysqlInitUrl
$env:MYSQL_USERNAME = $MysqlUsername
$env:MYSQL_PASSWORD = $MysqlPassword
$env:SCHEMA_FILE = $schema

Push-Location $backendRoot
try {
  mvn -q -DskipTests compile org.codehaus.mojo:exec-maven-plugin:3.3.0:java "-Dexec.mainClass=com.smartuser.schedule.tools.SchemaInitializer"
  if ($LASTEXITCODE -ne 0) {
    throw "MySQL JDBC initialization failed with exit code $LASTEXITCODE."
  }
} finally {
  Pop-Location
}
