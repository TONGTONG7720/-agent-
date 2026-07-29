# 启动 SpringBoot 业务后端：先把 .env 加载进进程环境（环境变量优先级高于 application.yml 默认值）
# 用法: 在 server 目录执行  powershell -ExecutionPolicy Bypass -File start-server.ps1
# 缺少 .env 时仍可启动，但会使用 application.yml 里的弱默认值（change-me 等），仅限本机试用

$envFile = Join-Path $PSScriptRoot ".env"
if (Test-Path $envFile) {
  Get-Content $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
      $k, $v = $line -split "=", 2
      [Environment]::SetEnvironmentVariable($k.Trim(), $v.Trim(), "Process")
    }
  }
  Write-Host "已加载 server/.env（内网令牌/AES密钥已生效）" -ForegroundColor Green
} else {
  Write-Warning "未找到 server/.env，将使用 application.yml 弱默认值。建议 Copy-Item .env.example .env 后填入随机值"
}

Write-Host "启动 SpringBoot 业务后端 (:8080) ..." -ForegroundColor Cyan
Set-Location $PSScriptRoot
mvn spring-boot:run
