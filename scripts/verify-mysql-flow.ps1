param(
  [string]$BaseUrl = "http://127.0.0.1:8088",
  [string]$Username = "admin",
  [string]$Password = $env:SCHEDULE_TEST_PASSWORD,
  [string]$CsvFile = ""
)

$ErrorActionPreference = "Stop"
if (-not $Password) {
  throw "Provide -Password or set SCHEDULE_TEST_PASSWORD. No default password is stored in this script."
}
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $CsvFile) {
  $CsvFile = Join-Path $projectRoot "MAC Inspection - Week 385.csv"
}
if (-not (Test-Path $CsvFile)) {
  throw "CSV file not found: $CsvFile"
}

$health = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/health/status"
if (-not $health.success) {
  throw "Backend health check failed."
}

$loginBody = @{ username = $Username; password = $Password } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/login" -ContentType "application/json" -Body $loginBody
if (-not $login.success -or -not $login.data.token) {
  throw "Login failed."
}

$token = $login.data.token
$importJson = curl.exe -s -X POST "$BaseUrl/api/inspections/import" -H "Authorization: Bearer $token" -F "file=@$CsvFile"
$import = $importJson | ConvertFrom-Json
if (-not $import.success) {
  throw "Import failed: $($import.message)"
}

$routes = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/routes/map" -Headers @{ Authorization = "Bearer $token" }
if (-not $routes.success) {
  throw "Routes map query failed."
}

[PSCustomObject]@{
  Health = $health.data.status
  LoginUser = $login.data.user.username
  ImportedRows = $import.data.successRows
  FailedRows = $import.data.failedRows
  TotalScannedRows = $import.data.totalRows
  RouteRecords = $routes.data.records.Count
  ScheduledInspections = $routes.data.summary.scheduledInspections
}
