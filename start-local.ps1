param([string]$Model = 'qwen-flash', [switch]$NativeMem0, [switch]$ImportKnowledge, [switch]$Build)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts\load-env.ps1')
. (Join-Path $PSScriptRoot 'scripts\start-service.ps1')
$projectDir = $PSScriptRoot
$runDir = Join-Path $projectDir 'target\run'
$jar = Join-Path $projectDir 'target\gogo-agent-1.0.0-SNAPSHOT.jar'
$frontendDir = Join-Path $projectDir 'frontend'
$vite = Join-Path $frontendDir 'node_modules\vite\bin\vite.js'
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { (Get-Command java.exe).Source }
$node = (Get-Command node.exe).Source
$backendPort = if ($env:SERVER_PORT) { [int]$env:SERVER_PORT } else { 8081 }
$frontendPort = if ($env:FRONTEND_PORT) { [int]$env:FRONTEND_PORT } else { 5174 }
$redisPort = if ($env:REDIS_PORT) { [int]$env:REDIS_PORT } else { 6381 }
foreach ($name in @('DASHSCOPE_API_KEY','DB_USERNAME','DB_PASSWORD','PGVECTOR_PASSWORD')) {
    if (-not [Environment]::GetEnvironmentVariable($name)) { throw "Configure $name in .env.local or the environment." }
}
if ($Build) {
    Push-Location $projectDir
    try { & mvn.cmd -B -ntp verify; if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' } } finally { Pop-Location }
    Push-Location $frontendDir
    try {
        $env:VITE_API_BASE = "http://localhost:$backendPort"
        & npm.cmd ci --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally { Pop-Location }
}
foreach ($path in @($java,$jar,$vite,(Join-Path $frontendDir 'dist\index.html'))) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing runtime/build output: $path. Run with -Build after configuring Java and Maven." }
}
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
if ($env:MYSQL_SERVER) {
    $mysqlData = Join-Path $projectDir '.local-data\mysql'
    if (-not (Test-Path (Join-Path $mysqlData 'mysql'))) { throw 'Initialize the dedicated native MySQL data directory before starting.' }
    Start-ProjectService 'mysql' 23306 $env:MYSQL_SERVER @('--no-defaults',('--datadir="'+$mysqlData+'"'),'--port=23306','--bind-address=127.0.0.1','--mysqlx=OFF','--console') $projectDir $mysqlData
}
if ($env:REDIS_SERVER) {
    if (-not $env:REDIS_PASSWORD -or $env:REDIS_PASSWORD -match '[\s"\r\n]') { throw 'Use a nonempty Redis password without spaces or quotes.' }
    $redisConf = Join-Path $runDir 'redis-local.conf'
    $redisData = Join-Path $projectDir '.local-data\redis'
    New-Item -ItemType Directory -Force -Path $redisData | Out-Null
    $config = @('bind 127.0.0.1',"port $redisPort",'protected-mode yes',('requirepass '+$env:REDIS_PASSWORD),
        ('dir "'+$redisData.Replace('\','/')+'"'),'dbfilename corporate-travel.rdb','appendonly yes','appendfilename corporate-travel.aof')
    [IO.File]::WriteAllLines($redisConf,$config,(New-Object Text.UTF8Encoding($false)))
    Start-ProjectService 'redis' $redisPort $env:REDIS_SERVER @('"'+$redisConf+'"') $runDir $redisConf
}
if ($NativeMem0) { & (Join-Path $projectDir 'scripts\start-native-mem0.ps1') }
if ($env:MINIO_SERVER) {
    $minioData = Join-Path $projectDir '.local-data\minio'
    New-Item -ItemType Directory -Force -Path $minioData | Out-Null
    $env:MINIO_ROOT_USER = $env:MINIO_ACCESS_KEY
    $env:MINIO_ROOT_PASSWORD = $env:MINIO_SECRET_KEY
    Start-ProjectService 'minio' 29000 $env:MINIO_SERVER @('server',('"'+$minioData+'"'),'--address','127.0.0.1:29000','--console-address','127.0.0.1:29001') $projectDir $minioData
}
$env:AGENTSCOPE_DASHSCOPE_API_KEY = $env:DASHSCOPE_API_KEY
$backendArguments = @('-Dfile.encoding=UTF-8','-jar',('"'+$jar+'"'),'--server.address=127.0.0.1',"--server.port=$backendPort",'--logging.level.root=INFO','--logging.level.org.springframework=INFO',
    "--agentscope.dashscope.models.fast=$Model","--agentscope.dashscope.models.strong=$Model","--agentscope.dashscope.models.stable=$Model")
if ($ImportKnowledge) { $backendArguments += '--import-knowledge' }
Start-ProjectService 'backend' $backendPort $java $backendArguments $projectDir $jar
Start-ProjectService 'frontend' $frontendPort $node @(('"'+$vite+'"'),'preview','--host','127.0.0.1','--port',"$frontendPort",'--strictPort') $frontendDir $vite
Write-Host "Open http://localhost:$frontendPort/ (seed accounts: admin or alice / 123456)"
if ($ImportKnowledge) { Write-Host "Knowledge import continues during startup. Check $runDir\backend.stdout.log for completion." }
