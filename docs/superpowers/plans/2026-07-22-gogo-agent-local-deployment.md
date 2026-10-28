# GoGo Agent Local Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run the GoGo Agent Spring Boot API and Vite frontend locally, backed by the existing MySQL service and bundled Redis executable, then prove login and page connectivity.

**Architecture:** The backend runs on Java 21 at `http://localhost:8080`; the frontend runs with Vite at `http://localhost:5173`. An isolated MySQL 8 data directory under `target/run` listens on 3307 because the existing system MySQL credentials do not match the project, while Redis stores authentication/session and interruption state. DashScope supplies chat and embedding models; optional Bailian, weather, news, visa, and booking integrations remain degraded when their credentials are not configured.

**Tech Stack:** Java 21, Maven 3.9, Spring Boot 3.4, MySQL 8, Redis 5, Node.js 24, npm 11, React 19, Vite 8, DashScope

---

### Task 1: Verify the local runtime surface

**Files:**
- Inspect: `pom.xml`
- Inspect: `frontend/package.json`
- Inspect: `src/main/resources/application.yml`

- [ ] **Step 1: Select Java 21 for this deployment shell**

```powershell
$env:JAVA_HOME = 'D:\Soft-development\Soft\JDK\ORACLE_JDK_21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
mvn -version
```

Expected: both commands report Java 21, and Maven reports version 3.9.9.

- [ ] **Step 2: Verify the application ports are available**

```powershell
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
  Where-Object { $_.LocalPort -in 6379,8080,5173 }
```

Expected: no listener on 8080 or 5173 before startup; an existing 6379 listener may be reused only if it authenticates with the project configuration.

### Task 2: Start Redis without exposing its password

**Files:**
- Inspect: `src/main/resources/application.yml`
- Runtime output: `target/run/redis.stdout.log`
- Runtime output: `target/run/redis.stderr.log`

- [ ] **Step 1: Read the development Redis password into process memory**

```powershell
$yaml = Get-Content -Encoding UTF8 'src/main/resources/application.yml'
$redisPasswordLine = $yaml | Where-Object { $_ -match '^\s{6}password:\s*\$\{REDIS_PASSWORD:' } | Select-Object -First 1
$redisPassword = [regex]::Match($redisPasswordLine, '\$\{REDIS_PASSWORD:([^}]*)\}').Groups[1].Value
if ([string]::IsNullOrWhiteSpace($redisPassword)) { throw 'Redis password is not configured' }
```

Expected: `$redisPassword` is non-empty and is never printed.

- [ ] **Step 2: Start the bundled Redis server as a hidden local process**

```powershell
New-Item -ItemType Directory -Force 'target/run' | Out-Null
$redis = 'D:\Soft-development\Soft\Redis\redis-server.exe'
Start-Process -FilePath $redis -ArgumentList @('--port','6379','--bind','127.0.0.1','--requirepass',$redisPassword,'--dir',(Resolve-Path 'target/run').Path,'--dbfilename','gogo-agent.rdb') -WindowStyle Hidden -RedirectStandardOutput 'target/run/redis.stdout.log' -RedirectStandardError 'target/run/redis.stderr.log' -PassThru
```

Expected: the process remains running and port 6379 starts listening.

- [ ] **Step 3: Prove Redis authentication**

```powershell
$env:REDISCLI_AUTH = $redisPassword
& 'D:\Soft-development\Soft\Redis\redis-cli.exe' -h 127.0.0.1 -p 6379 PING
Remove-Item Env:REDISCLI_AUTH
```

Expected: `PONG`.

### Task 3: Initialize an isolated MySQL instance

**Files:**
- Read: `src/main/resources/db/schema.sql`

- [ ] **Step 1: Load database credentials from the existing local configuration**

```powershell
$yaml = Get-Content -Encoding UTF8 'src/main/resources/application.yml'
$start = [Array]::IndexOf($yaml, '  datasource:')
$section = $yaml[($start + 1)..($yaml.Count - 1)] | Select-Object -First 12
$dbUser = (($section | Where-Object { $_ -match '^\s{4}username:' } | Select-Object -First 1) -split ':',2)[1].Trim()
$dbPassword = (($section | Where-Object { $_ -match '^\s{4}password:' } | Select-Object -First 1) -split ':',2)[1].Trim()
```

Expected: `$dbUser` and `$dbPassword` are non-empty and the password is never printed.

- [ ] **Step 2: Initialize and start MySQL 8 on port 3307**

```powershell
$basedir = 'C:\Program Files\MySQL\MySQL Server 8.0'
$mysqld = Join-Path $basedir 'bin\mysqld.exe'
$dataDir = (Join-Path (Resolve-Path 'target/run').Path 'mysql-3307-data')
if (-not (Test-Path (Join-Path $dataDir 'auto.cnf'))) {
  New-Item -ItemType Directory -Force $dataDir | Out-Null
  & $mysqld --no-defaults --initialize-insecure --basedir=$basedir --datadir=$dataDir --console
  if ($LASTEXITCODE -ne 0) { throw 'MySQL data directory initialization failed' }
}
$quotedBasedir = "--basedir=`"$basedir`""
$mysqlProcess = Start-Process -FilePath $mysqld -ArgumentList @('--no-defaults',$quotedBasedir,"--datadir=$dataDir",'--port=3307','--bind-address=127.0.0.1','--mysqlx=0',"--pid-file=$((Join-Path (Resolve-Path 'target/run').Path 'mysql-3307.pid'))",'--console') -WindowStyle Hidden -RedirectStandardOutput 'target/run/mysql.stdout.log' -RedirectStandardError 'target/run/mysql.stderr.log' -PassThru
$deadline = (Get-Date).AddSeconds(30)
$mysqladmin = Join-Path $basedir 'bin\mysqladmin.exe'
while ($true) {
  & $mysqladmin --protocol=tcp --host=127.0.0.1 --port=3307 --user=root --connect-timeout=2 ping 2>$null | Out-Null
  if ($LASTEXITCODE -eq 0) { break }
  if ((Get-Date) -ge $deadline -or $mysqlProcess.HasExited) { throw 'Isolated MySQL did not start on port 3307' }
  Start-Sleep -Milliseconds 500
}
```

Expected: the process remains running and port 3307 starts listening; the system service on 3306 is unchanged.

- [ ] **Step 3: Create the application user, database, and idempotent schema**

```powershell
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$escapedPassword = $dbPassword.Replace("'", "''")
& $mysql --protocol=tcp --host=127.0.0.1 --port=3307 --user=root --execute="CREATE DATABASE IF NOT EXISTS gogo_travel DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER IF NOT EXISTS '$dbUser'@'localhost' IDENTIFIED BY '$escapedPassword'; ALTER USER '$dbUser'@'localhost' IDENTIFIED BY '$escapedPassword'; GRANT ALL PRIVILEGES ON gogo_travel.* TO '$dbUser'@'localhost'; FLUSH PRIVILEGES;"
$env:MYSQL_PWD = $dbPassword
cmd /c "`"$mysql`" --protocol=tcp --host=127.0.0.1 --port=3307 --user=$dbUser --default-character-set=utf8mb4 gogo_travel < src\main\resources\db\schema.sql"
```

Expected: both commands exit with code 0.

- [ ] **Step 4: Verify required tables and demo accounts**

```powershell
& $mysql --protocol=tcp --host=127.0.0.1 --port=3307 --user=$dbUser --database=gogo_travel --batch --skip-column-names --execute="SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='gogo_travel'; SELECT COUNT(*) FROM user_account;"
Remove-Item Env:MYSQL_PWD
```

Expected: at least 9 tables and at least 5 demo accounts.

### Task 4: Verify and start the backend

**Files:**
- Runtime output: `target/run/backend.stdout.log`
- Runtime output: `target/run/backend.stderr.log`

- [ ] **Step 1: Run backend tests on Java 21**

```powershell
$env:JAVA_HOME = 'D:\Soft-development\Soft\JDK\ORACLE_JDK_21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn test
```

Expected: Maven exits with `BUILD SUCCESS`.

- [ ] **Step 2: Package the backend**

```powershell
mvn -DskipTests package
```

Expected: `target/gogo-agent-1.0.0-SNAPSHOT.jar` exists.

- [ ] **Step 3: Start Spring Boot with the session DashScope key and Redis 5 compatibility override**

```powershell
if ([string]::IsNullOrWhiteSpace($env:DASHSCOPE_API_KEY)) { throw 'DASHSCOPE_API_KEY is not set' }
$env:AGENTSCOPE_DASHSCOPE_API_KEY = $env:DASHSCOPE_API_KEY
$java = 'D:\Soft-development\Soft\JDK\ORACLE_JDK_21\bin\java.exe'
$jar = (Resolve-Path 'target/gogo-agent-1.0.0-SNAPSHOT.jar').Path
Start-Process -FilePath $java -ArgumentList @('-jar',$jar,'--spring.datasource.url=jdbc:mysql://127.0.0.1:3307/gogo_travel?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false','--spring.data.redis.username=','--app.weather-mcp.endpoint=http://127.0.0.1:9','--app.orizn-mcp.command=missing-orizn-mcp-command') -WindowStyle Hidden -RedirectStandardOutput 'target/run/backend.stdout.log' -RedirectStandardError 'target/run/backend.stderr.log' -PassThru
```

Expected: the process remains running and port 8080 starts listening. Weather and visa integrations log a controlled degraded-mode warning.

- [ ] **Step 4: Prove the authentication API**

```powershell
$body = @{ username = 'admin'; password = '123456' } | ConvertTo-Json
$response = Invoke-RestMethod -Uri 'http://localhost:8080/api/auth/login' -Method Post -ContentType 'application/json' -Body $body
if ([string]::IsNullOrWhiteSpace($response.token)) { throw 'Login response did not contain a token' }
```

Expected: the response contains a non-empty token.

### Task 5: Verify and start the frontend

**Files:**
- Runtime output: `target/run/frontend.stdout.log`
- Runtime output: `target/run/frontend.stderr.log`

- [ ] **Step 1: Install reproducible frontend dependencies**

```powershell
npm --prefix frontend ci
```

Expected: npm exits with code 0 using `frontend/package-lock.json`.

- [ ] **Step 2: Build the production frontend bundle**

```powershell
npm --prefix frontend run build
```

Expected: Vite reports a successful build and writes `frontend/dist`.

- [ ] **Step 3: Start the local frontend server**

```powershell
$npm = (Get-Command npm.cmd).Source
Start-Process -FilePath $npm -ArgumentList @('--prefix','frontend','run','dev','--','--host','127.0.0.1','--port','5173') -WorkingDirectory (Resolve-Path '.').Path -WindowStyle Hidden -RedirectStandardOutput 'target/run/frontend.stdout.log' -RedirectStandardError 'target/run/frontend.stderr.log' -PassThru
```

Expected: the process remains running and port 5173 starts listening.

- [ ] **Step 4: Smoke-test both HTTP surfaces**

```powershell
(Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:5173/').StatusCode
(Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:8080/api/auth/info' -SkipHttpErrorCheck).StatusCode
```

Expected: the frontend returns 200; unauthenticated backend info returns 401 or 403, proving the API is reachable and protected.

### Task 6: Record final runtime state

**Files:**
- Inspect: `target/run/backend.stdout.log`
- Inspect: `target/run/backend.stderr.log`
- Inspect: `target/run/frontend.stdout.log`
- Inspect: `target/run/frontend.stderr.log`

- [ ] **Step 1: Capture listeners and owning processes**

```powershell
Get-NetTCPConnection -State Listen |
  Where-Object { $_.LocalPort -in 3306,3307,6379,8080,5173 } |
  Select-Object LocalAddress,LocalPort,OwningProcess
```

Expected: listeners exist for the untouched system MySQL on 3306, isolated project MySQL on 3307, Redis 6379, backend 8080, and frontend 5173.

- [ ] **Step 2: Inspect logs for fatal startup errors without printing credentials**

```powershell
rg -n 'Started GogoTravelApplication|ERROR|Exception|BUILD SUCCESS|Local:' target/run
```

Expected: Spring reports `Started GogoTravelApplication`; no fatal exception follows it; Vite reports its local URL.
