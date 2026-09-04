param(
  [string]$BaseUrl = "http://127.0.0.1:8088",
  [string]$Username = "admin",
  [string]$Password = $env:SCHEDULE_TEST_PASSWORD
)

$ErrorActionPreference = "Stop"
if (-not $Password) {
  throw "Provide -Password or set SCHEDULE_TEST_PASSWORD. No default password is stored in this script."
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
$status = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/inspections/google-sheet/status" -Headers @{ Authorization = "Bearer $token" }
if (-not $status.success) {
  throw "Google Sheet status request failed."
}

[PSCustomObject]@{
  Health = $health.data.status
  LoginUser = $login.data.user.username
  GoogleSheetStatus = $status.data.status
  GoogleSheetRows = $status.data.rows
  GoogleSheetMessage = $status.data.message
}
