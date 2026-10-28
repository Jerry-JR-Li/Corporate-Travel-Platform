$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$path = Join-Path $root '.env.integrations'
if (Test-Path -LiteralPath $path) { Write-Host 'Existing integration credentials preserved.'; return }
function New-Secret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}
$public = 'pk-lf-' + (New-Secret)
$secret = 'sk-lf-' + (New-Secret)
$auth = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${public}:${secret}"))
$values = [ordered]@{
    DB_NAME = 'corporate_travel'
    DB_URL = 'jdbc:mysql://127.0.0.1:23306/corporate_travel?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false'
    DB_USERNAME = 'corporate_travel'
    DB_PASSWORD = New-Secret
    MYSQL_ROOT_PASSWORD = New-Secret
    REDIS_HOST = '127.0.0.1'
    REDIS_PORT = '6381'
    REDIS_PASSWORD = New-Secret
    API_KEY_ENCRYPT_SECRET = New-Secret
    MINIO_ENDPOINT = 'http://127.0.0.1:29000'
    MINIO_ACCESS_KEY = 'corporate-travel'
    MINIO_SECRET_KEY = New-Secret
    MINIO_BUCKET = 'corporate-travel'
    SERVER_PORT = '8081'
    FRONTEND_PORT = '5174'
    VITE_API_BASE = 'http://localhost:8081'
    PGVECTOR_PASSWORD = New-Secret
    PGVECTOR_URL = 'jdbc:postgresql://127.0.0.1:25432/corporate_travel'
    PGVECTOR_USERNAME = 'corporate_travel'
    MEM0_ENABLED = 'true'
    MEM0_API_KEY = New-Secret
    MEM0_API_BASE_URL = 'http://127.0.0.1:18888'
    POSTGRES_PASSWORD = New-Secret
    CLICKHOUSE_PASSWORD = New-Secret
    REDIS_AUTH = New-Secret
    MINIO_ROOT_PASSWORD = New-Secret
    NEXTAUTH_SECRET = New-Secret
    SALT = New-Secret
    ENCRYPTION_KEY = New-Secret
    LANGFUSE_PUBLIC_KEY = $public
    LANGFUSE_SECRET_KEY = $secret
    LANGFUSE_OTEL_AUTH = "Basic $auth"
    LANGFUSE_INIT_USER_EMAIL = 'admin@travel.local'
    LANGFUSE_INIT_USER_PASSWORD = New-Secret
    LANGFUSE_ENABLED = 'true'
    LANGFUSE_CAPTURE_CONTENT = 'false'
    LANGFUSE_BASE_URL = 'http://localhost:3031'
    LANGFUSE_PROJECT_ID = 'corporate-travel'
    LANGFUSE_ENVIRONMENT = 'local'
    OTEL_EXPORTER_OTLP_TRACES_ENDPOINT = 'http://127.0.0.1:25318/v1/traces'
}
[IO.File]::WriteAllLines($path, @($values.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }), (New-Object Text.UTF8Encoding($false)))
Write-Host "Created local credentials: $path (ignored by Git)"
