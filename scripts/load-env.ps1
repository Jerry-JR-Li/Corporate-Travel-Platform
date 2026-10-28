# Dot-source this file before starting project processes. Never evaluates env values as code.
$projectRoot = Split-Path -Parent $PSScriptRoot
foreach ($file in @('.env.integrations', '.env.local')) {
    $envPath = Join-Path $projectRoot $file
    if (-not (Test-Path -LiteralPath $envPath)) { continue }
    foreach ($line in (Get-Content -LiteralPath $envPath -Encoding UTF8)) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
            $key = $Matches[1]
            $value = $Matches[2].Trim()
            if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            [Environment]::SetEnvironmentVariable($key, $value, 'Process')
        }
    }
}
foreach ($key in @('DASHSCOPE_API_KEY', 'NEWS_API_KEY')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($key, 'Process'))) {
        [Environment]::SetEnvironmentVariable($key, [Environment]::GetEnvironmentVariable($key, 'User'), 'Process')
    }
}
