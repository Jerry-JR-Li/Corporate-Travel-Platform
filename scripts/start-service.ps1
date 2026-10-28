function Start-ProjectService {
    param($Name, [int]$Port, $Executable, $ServiceArguments, $WorkingDirectory, $ExpectedPath)
    $runDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'target\run'
    New-Item -ItemType Directory -Force -Path $runDir | Out-Null
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) {
        $running = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
        if (-not $running -or -not $running.CommandLine.Contains($ExpectedPath)) { throw "Port $Port belongs to another process." }
        $servicePid = $listener.OwningProcess
    } else {
        $started = Start-Process -FilePath $Executable -ArgumentList $ServiceArguments -WorkingDirectory $WorkingDirectory `
            -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runDir "$Name.stdout.log") `
            -RedirectStandardError (Join-Path $runDir "$Name.stderr.log") -PassThru
        $servicePid = $started.Id
        $deadline = (Get-Date).AddSeconds(120)
        while (-not (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)) {
            $started.Refresh()
            if ($started.HasExited -or (Get-Date) -ge $deadline) { throw "$Name failed to start. See logs in $runDir." }
            Start-Sleep -Milliseconds 500
        }
    }
    $servicePid | Set-Content -LiteralPath (Join-Path $runDir "$Name.pid")
    Write-Host "$Name listening on 127.0.0.1:$Port (PID $servicePid)"
}
