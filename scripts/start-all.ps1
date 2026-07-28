# 一键启动全部服务（每个服务独立窗口）
# 用法: 在仓库根目录执行  powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1
# 前置: llm-gateway\.env 已配置; 本地 MySQL 已启动; agent\.venv 已装依赖; web 已 npm install

$root = Split-Path $PSScriptRoot -Parent

Write-Host "[1/4] 启动 LiteLLM 网关 (:4000) ..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command",
  "cd '$root\llm-gateway'; .\start-gateway.ps1"

Write-Host "[2/4] 启动 Agent 服务 (:8001) ..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command",
  "cd '$root\agent'; .venv\Scripts\uvicorn app.main:app_factory --factory --host 0.0.0.0 --port 8001"

Write-Host "[3/4] 启动 SpringBoot 业务后端 (:8080) ..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command",
  "cd '$root\server'; mvn spring-boot:run"

Write-Host "[4/4] 启动前端 (:5173) ..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command",
  "cd '$root\web'; npm run dev"

Write-Host "全部启动指令已发出。浏览器访问 http://localhost:5173 （默认账号 admin/admin123）" -ForegroundColor Green
