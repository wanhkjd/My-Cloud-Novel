param(
    [switch]$BrowserTests,
    [switch]$UnitOnly,
    [string]$NovelPath
)
$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ($UnitOnly -and $BrowserTests) { throw 'BrowserTests requires real MySQL and cannot be combined with UnitOnly.' }
$testNames = @('TEST_MYSQL_HOST', 'TEST_MYSQL_PORT', 'TEST_DB_USERNAME', 'TEST_DB_PASSWORD', 'E2E_DB_USERNAME', 'E2E_DB_PASSWORD', 'NOVEL_TEST_FILE')
$before = @{}
foreach ($name in $testNames) { $before[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    & (Join-Path $PSScriptRoot 'Test-Scripts.ps1')
    if (-not $UnitOnly) {
        $testFile = Join-Path $root '.env.test'
        if (Test-Path -LiteralPath $testFile) {
            & (Join-Path $PSScriptRoot 'Import-LocalConfig.ps1') -Path $testFile -Mode Test
        }
        if ([string]::IsNullOrWhiteSpace($env:TEST_DB_PASSWORD)) {
            throw 'Prepare the dedicated MySQL test schema/account and .env.test first. See docs/mysql-setup.md. UnitOnly is explicit and is not full verification.'
        }
    }
    if ($NovelPath) {
        $resolvedNovel = (Resolve-Path -LiteralPath $NovelPath -ErrorAction Stop).Path
        if (-not (Test-Path -LiteralPath $resolvedNovel -PathType Leaf)) { throw 'NovelPath must point to a TXT file.' }
        $env:NOVEL_TEST_FILE = $resolvedNovel
    }
    Push-Location (Join-Path $root 'backend')
    try {
        $arguments = @('-B', '-ntp', 'clean', 'verify')
        if ($UnitOnly) { $arguments += '-DskipITs' }
        & mvn.cmd @arguments
        if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed.' }
    } finally { Pop-Location }

    Push-Location (Join-Path $root 'frontend')
    try {
        if (-not (Test-Path -LiteralPath 'node_modules')) {
            & npm.cmd ci
            if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed.' }
        }
        & npm.cmd run format:check
        if ($LASTEXITCODE -ne 0) { throw 'Formatting check failed. Run npm run format in frontend.' }
        & npx.cmd --no-install prettier --check '../README.md' '../docs/*.md' '../.github/workflows/ci.yml' '../backend/src/main/resources/application.yml'
        if ($LASTEXITCODE -ne 0) { throw 'Documentation formatting check failed.' }
        & npm.cmd test
        if ($LASTEXITCODE -ne 0) { throw 'Frontend tests failed.' }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build/type checking failed.' }
        if ($BrowserTests) {
            & npm.cmd run test:e2e
            if ($LASTEXITCODE -ne 0) { throw 'Browser tests failed. If needed, install Chromium with npx playwright install chromium.' }
        }
    } finally { Pop-Location }
    if ($UnitOnly) { Write-Host 'Unit/static/build verification passed. Real MySQL integration and browser tests were NOT run.' }
    else { Write-Host 'All requested MySQL verification passed.' }
} finally {
    foreach ($name in $testNames) {
        if ($null -eq $before[$name]) {
            Remove-Item -LiteralPath ("Env:" + $name) -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable($name, $before[$name], 'Process')
        }
    }
}
