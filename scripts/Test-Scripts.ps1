# No Pester dependency or real .env access; every configuration below is synthetic.
$ErrorActionPreference = 'Stop'
$checks = 0
function Assert-Check([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:checks++
}
function Read-SyntheticConfig([string]$Path) {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
    }
    return $values
}

foreach ($file in Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1' -File) {
    $tokens = $null
    $parseErrors = $null
    [Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$parseErrors) | Out-Null
    Assert-Check ($parseErrors.Count -eq 0) ('PowerShell syntax errors in ' + $file.Name)
}

$resources = Join-Path $PSScriptRoot '../backend/src/main/resources'
$configurations = @(Get-ChildItem -LiteralPath $resources -Filter 'application*' -File)
Assert-Check ($configurations.Count -eq 1 -and $configurations[0].Name -eq 'application.yml') 'Spring runtime configuration must be a single YAML file.'
Assert-Check (Test-Path -LiteralPath (Join-Path $PSScriptRoot '../deploy/mysql/schema.sql') -PathType Leaf) 'Manual MySQL schema must live under deploy/mysql.'
Assert-Check (-not (Test-Path -LiteralPath (Join-Path $resources 'schema.sql'))) 'Manual DDL must not be packaged as a runtime initialization resource.'

$names = @('ADMIN_USERNAME', 'ADMIN_PASSWORD', 'COOKIE_SECURE', 'DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'DB_INIT_MODE', 'BOOK_STORAGE', 'REDIS_HOST', 'REDIS_PORT', 'REDIS_USERNAME', 'REDIS_PASSWORD', 'REDIS_DATABASE', 'REDIS_NAMESPACE', 'REDIS_SSL', 'TEST_MYSQL_HOST', 'TEST_MYSQL_PORT', 'TEST_DB_USERNAME', 'TEST_DB_PASSWORD', 'E2E_DB_USERNAME', 'E2E_DB_PASSWORD', 'TEST_REDIS_HOST', 'TEST_REDIS_PORT', 'TEST_REDIS_USERNAME', 'TEST_REDIS_PASSWORD', 'CLOUD_NOVEL_UNKNOWN_TEST_SETTING')
$before = @{}
foreach ($name in $names) { $before[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
$directoryName = 'cloud-novel-config-test-' + [Guid]::NewGuid().ToString('N')
$directory = Join-Path $tempRoot $directoryName
try {
    New-Item -ItemType Directory -Path $directory | Out-Null
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, 'synthetic-before-import', 'Process') }
    $file = Join-Path $directory 'synthetic.env'
    $lines = @(
        '# DB_PASSWORD=must-ignore-comment',
        'ADMIN_USERNAME=configuration-test',
        'ADMIN_PASSWORD=synthetic-test-password-only',
        'COOKIE_SECURE=false',
        'DB_URL=jdbc:mysql://127.0.0.1:3306/test_only?characterEncoding=UTF-8&serverTimezone=Asia/Shanghai',
        'DB_USERNAME=test_only',
        'DB_PASSWORD=synthetic=password=with=equals',
        'DB_INIT_MODE=always',
        'BOOK_STORAGE=./target/synthetic books',
        'REDIS_HOST=127.0.0.1',
        'REDIS_PORT=16379',
        'REDIS_USERNAME=',
        'REDIS_PASSWORD=$(throw "must not execute")=literal',
        'REDIS_DATABASE=3',
        'REDIS_NAMESPACE=synthetic:session',
        'REDIS_SSL=false',
        'TEST_MYSQL_HOST=localhost',
        'TEST_MYSQL_PORT=13306',
        'TEST_DB_USERNAME=cloud_novel_test',
        'TEST_DB_PASSWORD=synthetic-test-db-password',
        'E2E_DB_USERNAME=cloud_novel_e2e',
        'E2E_DB_PASSWORD=synthetic-e2e-db-password',
        'TEST_REDIS_HOST=localhost',
        'TEST_REDIS_PORT=16379',
        'TEST_REDIS_USERNAME=test-user',
        'TEST_REDIS_PASSWORD=synthetic-redis-password',
        'CLOUD_NOVEL_UNKNOWN_TEST_SETTING=must-not-be-imported',
        'db_password=must-ignore-lowercase',
        'not an environment assignment'
    )
    [IO.File]::WriteAllLines($file, $lines, [Text.UTF8Encoding]::new($false))
    & (Join-Path $PSScriptRoot 'Import-LocalConfig.ps1') -Path $file
    Assert-Check ($env:DB_URL -eq $lines[4].Substring('DB_URL='.Length)) 'JDBC ampersands must remain literal.'
    Assert-Check ($env:DB_PASSWORD -eq 'synthetic=password=with=equals') 'Password equals signs must remain literal.'
    Assert-Check ($env:BOOK_STORAGE -eq './target/synthetic books') 'Storage paths with spaces must be preserved.'
    Assert-Check ($env:ADMIN_USERNAME -eq 'configuration-test' -and $env:ADMIN_PASSWORD -eq 'synthetic-test-password-only') 'Website identity was not imported.'
    Assert-Check ($env:COOKIE_SECURE -eq 'false' -and $env:DB_USERNAME -eq 'test_only') 'Runtime settings were not imported.'
    Assert-Check ($env:REDIS_HOST -eq '127.0.0.1' -and $env:REDIS_PORT -eq '16379') 'Redis connection settings were not imported.'
    Assert-Check ([string]::IsNullOrEmpty($env:REDIS_USERNAME)) 'An empty Redis username must be supported.'
    Assert-Check ($env:REDIS_PASSWORD -eq '$(throw "must not execute")=literal') 'Configuration must not execute PowerShell expressions.'
    Assert-Check ($env:REDIS_DATABASE -eq '3' -and $env:REDIS_NAMESPACE -eq 'synthetic:session' -and $env:REDIS_SSL -eq 'false') 'Redis isolation settings were not imported.'
    Assert-Check ($env:DB_INIT_MODE -eq 'synthetic-before-import') 'Schema initialization must not be configurable through .env.'
    Assert-Check ($env:TEST_DB_PASSWORD -eq 'synthetic-before-import') 'Runtime import must ignore test credentials.'
    Assert-Check ($env:CLOUD_NOVEL_UNKNOWN_TEST_SETTING -eq 'synthetic-before-import') 'Unknown settings must be ignored.'

    & (Join-Path $PSScriptRoot 'Import-LocalConfig.ps1') -Path $file -Mode Test
    Assert-Check ($env:TEST_MYSQL_HOST -eq 'localhost' -and $env:TEST_MYSQL_PORT -eq '13306') 'Test MySQL settings were not imported.'
    Assert-Check ($env:TEST_DB_USERNAME -eq 'cloud_novel_test' -and $env:TEST_DB_PASSWORD -eq 'synthetic-test-db-password') 'Dedicated integration credentials were not imported.'
    Assert-Check ($env:E2E_DB_USERNAME -eq 'cloud_novel_e2e' -and $env:E2E_DB_PASSWORD -eq 'synthetic-e2e-db-password') 'Dedicated browser-test credentials were not imported.'
    Assert-Check ($env:TEST_REDIS_HOST -eq 'localhost' -and $env:TEST_REDIS_PORT -eq '16379' -and $env:TEST_REDIS_USERNAME -eq 'test-user' -and $env:TEST_REDIS_PASSWORD -eq 'synthetic-redis-password') 'Test Redis settings were not imported.'
    [IO.File]::WriteAllText($file, "DB_PASSWORD=must-not-change-runtime`nADMIN_USERNAME=must-not-change-admin", [Text.UTF8Encoding]::new($false))
    & (Join-Path $PSScriptRoot 'Import-LocalConfig.ps1') -Path $file -Mode Test
    Assert-Check ($env:DB_PASSWORD -eq 'synthetic=password=with=equals' -and $env:ADMIN_USERNAME -eq 'configuration-test') 'Test import must not load runtime credentials.'

    $project = Join-Path $directory 'synthetic-project'
    New-Item -ItemType Directory -Path $project | Out-Null
    $originalEnv = Join-Path $project '.env'
    $originalText = @'
ADMIN_USERNAME=existing-owner
ADMIN_PASSWORD=synthetic-existing-owner-password
COOKIE_SECURE=true
DB_USERNAME=root
DB_PASSWORD=must-not-copy-old-db-password
DB_URL=jdbc:obsolete:must-not-copy
'@
    [IO.File]::WriteAllText($originalEnv, $originalText, [Text.UTF8Encoding]::new($false))
    & (Join-Path $PSScriptRoot 'Prepare-LocalEnvironment.ps1') -ProjectRoot $project
    Assert-Check ([IO.File]::ReadAllText($originalEnv) -ceq $originalText) 'Preparation must not alter the existing .env.'
    $output = Join-Path $project '.local/setup'
    $outputs = @('application.env', 'test.env', 'bootstrap.sql', 'redis.conf')
    $hashes = @{}
    foreach ($name in $outputs) {
        $target = Join-Path $output $name
        Assert-Check (Test-Path -LiteralPath $target -PathType Leaf) ('Missing generated file: ' + $name)
        $hashes[$name] = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
    }
    $app = Read-SyntheticConfig (Join-Path $output 'application.env')
    $test = Read-SyntheticConfig (Join-Path $output 'test.env')
    $sql = [IO.File]::ReadAllText((Join-Path $output 'bootstrap.sql'))
    $redis = [IO.File]::ReadAllText((Join-Path $output 'redis.conf'))
    Assert-Check ($app.ADMIN_USERNAME -eq 'existing-owner' -and $app.ADMIN_PASSWORD -eq 'synthetic-existing-owner-password' -and $app.COOKIE_SECURE -eq 'true') 'Preparation must preserve a valid existing website identity.'
    Assert-Check ($app.DB_USERNAME -eq 'cloud_novel_app' -and $app.DB_URL.StartsWith('jdbc:mysql://127.0.0.1:3306/cloud_novel?')) 'Preparation must use a dedicated MySQL application account.'
    Assert-Check ($app.BOOK_STORAGE -eq './data/books' -and $app.REDIS_DATABASE -eq '0' -and $app.REDIS_HOST -eq '127.0.0.1') 'Default business storage must remain local and private.'
    $secrets = @($app.DB_PASSWORD, $test.TEST_DB_PASSWORD, $test.E2E_DB_PASSWORD, $app.REDIS_PASSWORD)
    foreach ($secret in $secrets) { Assert-Check ($secret -cmatch '^[a-f0-9]{48}$') 'Generated secrets must contain 192 bits of random entropy.' }
    Assert-Check (@($secrets | Select-Object -Unique).Count -eq 4) 'Application, integration, browser and Redis passwords must be independent.'
    Assert-Check ($test.TEST_REDIS_PASSWORD -eq $app.REDIS_PASSWORD -and $redis.Contains('requirepass ' + $app.REDIS_PASSWORD)) 'Generated Redis credentials must agree.'
    Assert-Check ($redis.Contains('appendonly yes') -and $redis.Contains('maxmemory-policy noeviction')) 'Local Redis must have persistence and predictable eviction configured.'
    foreach ($entry in @(
        @{ database = 'cloud_novel'; username = $app.DB_USERNAME; password = $app.DB_PASSWORD },
        @{ database = 'cloud_novel_test'; username = $test.TEST_DB_USERNAME; password = $test.TEST_DB_PASSWORD },
        @{ database = 'cloud_novel_e2e'; username = $test.E2E_DB_USERNAME; password = $test.E2E_DB_PASSWORD }
    )) {
        Assert-Check ($sql.Contains("CREATE USER '$($entry.username)'@'127.0.0.1' IDENTIFIED BY '$($entry.password)';")) 'Generated SQL credentials do not match the corresponding environment.'
        Assert-Check ($sql.Contains("GRANT SELECT, INSERT, UPDATE, DELETE ON $($entry.database).* TO '$($entry.username)'@'127.0.0.1';")) 'Each account must have DML rights on its own schema only.'
    }
    Assert-Check (([regex]::Matches($sql, '(?m)^CREATE DATABASE ')).Count -eq 3) 'Preparation must create three separate schemas.'
    Assert-Check (([regex]::Matches($sql, '(?m)^CREATE TABLE IF NOT EXISTS ')).Count -eq 15) 'All three schemas need the five MySQL tables.'
    Assert-Check ($sql -notmatch '(?im)^\s*(DROP|TRUNCATE|ALTER|GRANT\s+ALL|FLUSH)\b') 'Preparation must not reset data or grant administrative privileges.'
    Assert-Check (-not $sql.Contains('must-not-copy-old-db-password') -and -not $sql.Contains("'root'")) 'Preparation must not copy old database/root credentials.'
    $refused = $false
    try { & (Join-Path $PSScriptRoot 'Prepare-LocalEnvironment.ps1') -ProjectRoot $project } catch { $refused = $true }
    Assert-Check $refused 'A second preparation must refuse to overwrite generated credentials.'
    foreach ($name in $outputs) {
        Assert-Check ((Get-FileHash -LiteralPath (Join-Path $output $name) -Algorithm SHA256).Hash -eq $hashes[$name]) ('Preparation overwrote ' + $name)
    }
    $fresh = Join-Path $directory 'fresh-project'
    & (Join-Path $PSScriptRoot 'Prepare-LocalEnvironment.ps1') -ProjectRoot $fresh
    $freshApp = Read-SyntheticConfig (Join-Path $fresh '.local/setup/application.env')
    Assert-Check ($freshApp.ADMIN_USERNAME -eq 'admin' -and $freshApp.ADMIN_PASSWORD -cmatch '^[a-f0-9]{48}$') 'Fresh installations need a generated administrator secret.'
    Assert-Check (-not (Test-Path -LiteralPath (Join-Path $fresh '.env'))) 'Preparation must not activate configuration automatically.'
    Assert-Check ($freshApp.DB_PASSWORD -ne $app.DB_PASSWORD) 'Separate preparations must not reuse credentials.'

    $existingRedisProject = Join-Path $directory 'existing-redis-project'
    $existingRedisSecret = ConvertTo-SecureString 'synthetic-existing-redis-secret' -AsPlainText -Force
    & (Join-Path $PSScriptRoot 'Prepare-LocalEnvironment.ps1') -ProjectRoot $existingRedisProject -RedisPassword $existingRedisSecret
    $existingRedisApp = Read-SyntheticConfig (Join-Path $existingRedisProject '.local/setup/application.env')
    $existingRedisTest = Read-SyntheticConfig (Join-Path $existingRedisProject '.local/setup/test.env')
    Assert-Check ($existingRedisApp.REDIS_PASSWORD -eq 'synthetic-existing-redis-secret') 'Explicit existing Redis credentials must be preserved, not randomized.'
    Assert-Check ($existingRedisTest.TEST_REDIS_PASSWORD -eq $existingRedisApp.REDIS_PASSWORD) 'Existing Redis credentials must agree in runtime and test configuration.'
    $unsafeProject = Join-Path $directory 'invalid-redis-project'
    $refused = $false
    try {
        $unsafeSecret = ConvertTo-SecureString 'invalid secret with spaces' -AsPlainText -Force
        & (Join-Path $PSScriptRoot 'Prepare-LocalEnvironment.ps1') -ProjectRoot $unsafeProject -RedisPassword $unsafeSecret
    } catch { $refused = $true }
    Assert-Check $refused 'Starter generation must reject Redis configuration injection.'
    Assert-Check (-not (Test-Path -LiteralPath $unsafeProject)) 'Rejected preparation must not create partial files.'

    $start = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'Start-Local.ps1') -Raw
    Assert-Check ($start.Contains('Import-LocalConfig.ps1') -and $start.Contains("StartsWith('jdbc:mysql://')")) 'Startup must require MySQL via the tested loader.'
    Assert-Check ($start.Contains("'REDIS_PASSWORD'") -and $start.Contains('/api/ready') -and $start.Contains('/api/books')) 'Startup must verify Redis, MySQL and schema readiness.'
    Assert-Check ($start.Contains('clean verify -DskipITs;')) 'Startup must clearly separate packaging from full infrastructure verification.'
    Assert-Check (-not $start.Contains('WriteAllText')) 'Startup must not silently replace configuration.'
    $verification = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'Test.ps1') -Raw
    Assert-Check ($verification.Contains("'.env.test'") -and $verification.Contains('-Mode Test')) 'Full verification must load only dedicated test credentials.'
    Assert-Check ($verification.Contains('if ($UnitOnly)') -and $verification.Contains("'-DskipITs'")) 'Skipping infrastructure tests must be an explicit opt-in.'
    Assert-Check ($verification.Contains("@('-B', '-ntp', 'clean', 'verify')")) 'Verification must remove stale classes and configuration before building.'
    Assert-Check ($verification.Contains('../backend/src/main/resources/application.yml')) 'YAML formatting must be included in verification.'
    $rejected = $false
    try { & (Join-Path $PSScriptRoot 'Test.ps1') -UnitOnly -BrowserTests } catch { $rejected = $true }
    Assert-Check $rejected 'Browser verification must not run in UnitOnly mode.'
} finally {
    foreach ($name in $names) {
        if ($null -eq $before[$name]) { Remove-Item -LiteralPath ('Env:' + $name) -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($name, $before[$name], 'Process') }
    }
    if (Test-Path -LiteralPath $directory) {
        # Resolve and verify the exact GUID-named directory before native recursive cleanup.
        $resolved = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $directory).Path)
        if ([IO.Path]::GetDirectoryName($resolved) -ne $tempRoot -or [IO.Path]::GetFileName($resolved) -ne $directoryName -or $directoryName -notmatch '^cloud-novel-config-test-[a-f0-9]{32}$' -or ((Get-Item -LiteralPath $resolved).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Refusing to remove a path outside the synthetic test directory.'
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
foreach ($name in $names) {
    Assert-Check ([object]::Equals([Environment]::GetEnvironmentVariable($name, 'Process'), $before[$name])) ('Environment was not restored: ' + $name)
}
Write-Host "PowerShell checks passed: $checks (syntax, isolation, generated configuration, least privilege, no overwrite and environment restoration)."
