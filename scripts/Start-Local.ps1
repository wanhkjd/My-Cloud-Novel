param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$backend = Join-Path $root 'backend'
$frontend = Join-Path $root 'frontend'
$envFile = Join-Path $root '.env'
$stateFile = Join-Path $root '.local-dev.json'
$logs = Join-Path $backend 'data'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
foreach ($port in @(8080, 5173)) {
    $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($listener) { throw "Port $port is already in use. Stop the existing service first; no process was changed." }
}
if (-not (Test-Path -LiteralPath $envFile)) {
    throw 'Prepare MySQL first: see docs/mysql-setup.md. There is no embedded database fallback.'
}
& (Join-Path $PSScriptRoot 'Import-LocalConfig.ps1') -Path $envFile
if ([string]::IsNullOrWhiteSpace($env:ADMIN_PASSWORD) -or $env:ADMIN_PASSWORD.Length -lt 12) {
    throw "Set ADMIN_PASSWORD to at least 12 characters in $envFile."
}
foreach ($name in @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
        throw "Missing $name. Follow docs/mysql-setup.md; no service was started."
    }
}
if (-not $env:DB_URL.StartsWith('jdbc:mysql://')) { throw 'DB_URL must use MySQL. Embedded databases are not supported.' }
if ($env:DB_USERNAME -eq 'root') { throw 'Use the dedicated cloud_novel_app account, not root, for the running application.' }
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '8080'
$env:API_PROXY_TARGET = 'http://127.0.0.1:8080'
if (-not $SkipBuild) {
    Push-Location $backend
    # Clean removes obsolete compiled packages/configuration before unit checks and packaging. Full isolated MySQL verification is scripts/Test.ps1.
    try { & mvn.cmd -q -ntp clean verify -DskipITs; if ($LASTEXITCODE -ne 0) { throw 'Backend build/tests failed.' } } finally { Pop-Location }
    Push-Location $frontend
    try {
        if (-not (Test-Path -LiteralPath (Join-Path $frontend 'node_modules'))) { & npm.cmd ci; if ($LASTEXITCODE -ne 0) { throw 'npm ci failed.' } }
        & npm.cmd test; if ($LASTEXITCODE -ne 0) { throw 'Frontend tests failed.' }
        & npm.cmd run build; if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally { Pop-Location }
}
$jar = Join-Path $backend 'target\cloud-novel-0.1.0.jar'
$vite = Join-Path $frontend 'node_modules\vite\bin\vite.js'
if (-not (Test-Path -LiteralPath $jar) -or -not (Test-Path -LiteralPath $vite)) { throw 'Build artifacts are missing. Run without -SkipBuild.' }
$java = (Get-Command java.exe -ErrorAction Stop).Source
$node = (Get-Command node.exe -ErrorAction Stop).Source
$started = @()
try {
    $api = Start-Process -FilePath $java -ArgumentList @('-jar', ('"' + $jar + '"')) -WorkingDirectory $backend -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logs 'local-backend.log') -RedirectStandardError (Join-Path $logs 'local-backend-error.log')
    $started += $api
    $web = Start-Process -FilePath $node -ArgumentList @(('"' + $vite + '"'), '--host', '127.0.0.1', '--port', '5173', '--strictPort') -WorkingDirectory $frontend -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logs 'local-frontend.log') -RedirectStandardError (Join-Path $logs 'local-frontend-error.log')
    $started += $web
    $state = @{ root = $root; processes = @(@{ id = $api.Id; executable = $java; argument = $jar }, @{ id = $web.Id; executable = $node; argument = $vite }) }
    $state | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $stateFile -Encoding UTF8
    $ready = $false
    for ($i = 0; $i -lt 45; $i++) {
        try {
            $health = Invoke-RestMethod 'http://127.0.0.1:8080/api/ready' -TimeoutSec 5
            $catalog = Invoke-WebRequest 'http://127.0.0.1:8080/api/books' -UseBasicParsing -TimeoutSec 5
            $page = Invoke-WebRequest 'http://127.0.0.1:5173' -UseBasicParsing -TimeoutSec 2
            if ($health.status -eq 'UP' -and $catalog.StatusCode -eq 200 -and $page.StatusCode -eq 200) { $ready = $true; break }
        } catch { Start-Sleep -Seconds 1 }
    }
    if (-not $ready) { throw "Startup failed. See logs in $logs" }
    Write-Host 'Cloud Novel is ready: http://127.0.0.1:5173'
    Write-Host "Admin username: $($env:ADMIN_USERNAME)"
    Write-Host "The private password is in $envFile (never committed)."
    Write-Host 'Stop with: ./scripts/Stop-Local.ps1'
} catch {
    foreach ($process in $started) { if (-not $process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue } }
    throw
}
