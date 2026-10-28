$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'load-env.ps1')
. (Join-Path $PSScriptRoot 'start-service.ps1')
$python = Join-Path $projectRoot '.venv\Scripts\python.exe'
$moduleDir = Join-Path $projectRoot 'infra\mem0'
foreach ($name in @('DASHSCOPE_API_KEY','MEM0_API_KEY','PGVECTOR_PASSWORD')) {
    if (-not [Environment]::GetEnvironmentVariable($name)) { throw "Set $name before starting Mem0." }
}
$env:MEM0_TELEMETRY = 'false'
New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot '.local-data') | Out-Null
if (-not $env:MEM0_HISTORY_PATH) { $env:MEM0_HISTORY_PATH = Join-Path $projectRoot '.local-data\mem0-history.db' }
$port = ([uri]$env:MEM0_API_BASE_URL).Port
Start-ProjectService 'mem0' $port $python @('-m','uvicorn','server:app','--app-dir',('"'+$moduleDir+'"'),'--host','127.0.0.1','--port',"$port") $projectRoot $moduleDir
