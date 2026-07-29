# 启动 LiteLLM 网关：先把 .env 加载进进程环境，再启动（litellm 不会自动读 .env）
# 用法: 在 llm-gateway 目录执行  powershell -ExecutionPolicy Bypass -File start-gateway.ps1
param([int]$Port = 4000)

$envFile = Join-Path $PSScriptRoot ".env"
if (-not (Test-Path $envFile)) {
  Write-Error "缺少 .env，请先执行 Copy-Item .env.example .env 并填入 Key"
  exit 1
}

Get-Content $envFile | ForEach-Object {
  $line = $_.Trim()
  if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
    $k, $v = $line -split "=", 2
    [Environment]::SetEnvironmentVariable($k.Trim(), $v.Trim(), "Process")
  }
}

Write-Host "已加载 .env，启动 LiteLLM 网关 (127.0.0.1:$Port) ..." -ForegroundColor Cyan
# 只监听本机：网关持有真实上游 Key，不得对局域网暴露
litellm --config (Join-Path $PSScriptRoot "litellm-config.yaml") --host 127.0.0.1 --port $Port
