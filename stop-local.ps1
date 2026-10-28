$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts\load-env.ps1')
$runDir = Join-Path $PSScriptRoot 'target\run'
$markers = @{
    backend = (Join-Path $PSScriptRoot 'target\gogo-agent-1.0.0-SNAPSHOT.jar')
    frontend = (Join-Path $PSScriptRoot 'frontend\node_modules\vite\bin\vite.js')
    mem0 = (Join-Path $PSScriptRoot 'infra\mem0')
    redis = (Join-Path $runDir 'redis-local.conf')
    mysql = (Join-Path $PSScriptRoot '.local-data\mysql')
    minio = (Join-Path $PSScriptRoot '.local-data\minio')
}
foreach ($name in @('frontend','backend','mem0','minio','redis','mysql')) {
    $pidFile = Join-Path $runDir "$name.pid"
    if (-not (Test-Path -LiteralPath $pidFile)) { continue }
    $servicePid = [int](Get-Content -LiteralPath $pidFile)
    $running = Get-CimInstance Win32_Process -Filter "ProcessId=$servicePid"
    if (-not $running) { continue }
    if (-not $running.CommandLine.Contains($markers[$name])) { throw "PID $servicePid no longer belongs to $name." }
    if ($name -eq 'mysql') {
        $config = Join-Path $runDir 'mysql-admin.cnf'
        [IO.File]::WriteAllLines($config,@('[client]','user=root',('password='+$env:MYSQL_ROOT_PASSWORD),'host=127.0.0.1','port=23306'),(New-Object Text.UTF8Encoding($false)))
        $admin = Join-Path (Split-Path $env:MYSQL_SERVER) 'mysqladmin.exe'
        & $admin "--defaults-extra-file=$config" shutdown
        if ($LASTEXITCODE -ne 0) { throw 'MySQL shutdown failed.' }
    } elseif ($name -eq 'redis') {
        $redisConfig = Get-Content -LiteralPath $markers.redis -Encoding UTF8
        $passwordLine = $redisConfig | Where-Object { $_ -match '^requirepass ' } | Select-Object -First 1
        $portLine = $redisConfig | Where-Object { $_ -match '^port ' } | Select-Object -First 1
        $previousAuth = $env:REDISCLI_AUTH
        try {
            $env:REDISCLI_AUTH = $passwordLine.Substring('requirepass '.Length)
            $cli = if ($env:REDIS_SERVER) { Join-Path (Split-Path $env:REDIS_SERVER) 'redis-cli.exe' } else { (Get-Command redis-cli.exe).Source }
            & $cli -h 127.0.0.1 -p $portLine.Substring('port '.Length) SHUTDOWN SAVE
            if ($LASTEXITCODE -ne 0) { throw 'Redis shutdown failed.' }
        } finally { $env:REDISCLI_AUTH = $previousAuth }
    } else { Stop-Process -Id $servicePid }
    Write-Host "Stopped $name (PID $servicePid)"
}
