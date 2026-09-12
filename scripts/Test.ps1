param(
    [switch]$BrowserTests,
    [string]$NovelPath
)
$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$previousNovel = $env:NOVEL_TEST_FILE
try {
    if ($NovelPath) {
        $resolvedNovel = (Resolve-Path -LiteralPath $NovelPath -ErrorAction Stop).Path
        if (-not (Test-Path -LiteralPath $resolvedNovel -PathType Leaf)) { throw 'NovelPath must point to a TXT file.' }
        $env:NOVEL_TEST_FILE = $resolvedNovel
    }
    Push-Location (Join-Path $root 'backend')
    try {
        & mvn.cmd -B -ntp verify
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
        & npm.cmd test
        if ($LASTEXITCODE -ne 0) { throw 'Frontend tests failed.' }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build/type checking failed.' }
        if ($BrowserTests) {
            & npm.cmd run test:e2e
            if ($LASTEXITCODE -ne 0) { throw 'Browser tests failed. If needed, install Chromium with npx playwright install chromium.' }
        }
    } finally { Pop-Location }
    Write-Host 'All requested verification passed.'
} finally {
    [Environment]::SetEnvironmentVariable('NOVEL_TEST_FILE', $previousNovel, 'Process')
}
