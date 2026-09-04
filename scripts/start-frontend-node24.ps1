param(
  [string]$NodeHome = "",
  [int]$Port = 5173
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$frontendRoot = Join-Path $projectRoot "frontend"

if (-not $NodeHome) {
  # 优先使用系统 PATH；Codex 桌面环境没有全局 Node 时，再自动查找它自带的 Node 运行时。
  $nodeCommand = Get-Command node -ErrorAction SilentlyContinue
  if ($nodeCommand) {
    $NodeHome = Split-Path -Parent $nodeCommand.Source
  } else {
    $codexRuntimeRoot = Join-Path $env:LOCALAPPDATA "OpenAI\Codex\runtimes\cua_node"
    $runtime = Get-ChildItem -LiteralPath $codexRuntimeRoot -Directory -ErrorAction SilentlyContinue |
      ForEach-Object { Join-Path $_.FullName "bin" } |
      Where-Object { Test-Path (Join-Path $_ "node.exe") } |
      Sort-Object { (Get-Item (Join-Path $_ "node.exe")).LastWriteTime } -Descending |
      Select-Object -First 1
    if ($runtime) {
      $NodeHome = $runtime
    }
  }
}

$nodeExe = Join-Path $NodeHome "node.exe"
$npmCmd = Join-Path $NodeHome "npm.cmd"
if (-not (Test-Path $nodeExe) -or -not (Test-Path $npmCmd)) {
  throw "Node/npm runtime was not found. Install Node 24 or pass -NodeHome explicitly."
}
$nodeVersion = & $nodeExe --version
$nodeMajor = [int]([regex]::Match($nodeVersion, '^v(\d+)').Groups[1].Value)
if ($nodeMajor -lt 24) {
  throw "Node 24 or newer is required; detected $nodeVersion at $NodeHome."
}

$portInUse = netstat -ano | Select-String ":$Port\s+.*LISTENING"
if ($portInUse) {
  throw "Port $Port is already in use. Stop the current frontend before starting this one."
}

$env:PATH = $NodeHome + ";" + $env:PATH

Push-Location $frontendRoot
try {
  # 固定使用 Node v24 启动 Vite 开发服务，避免系统默认 PATH 指向其他 Node 版本。
  & $npmCmd run dev
} finally {
  Pop-Location
}
