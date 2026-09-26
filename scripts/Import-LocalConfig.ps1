param(
    [Parameter(Mandatory = $true)][string]$Path,
    [ValidateSet('Runtime', 'Test')][string]$Mode = 'Runtime'
)
$ErrorActionPreference = 'Stop'
$runtimeNames = @('ADMIN_USERNAME', 'ADMIN_PASSWORD', 'COOKIE_SECURE', 'DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'BOOK_STORAGE')
$testNames = @('TEST_MYSQL_HOST', 'TEST_MYSQL_PORT', 'TEST_DB_USERNAME', 'TEST_DB_PASSWORD', 'E2E_DB_USERNAME', 'E2E_DB_PASSWORD')
$allowed = if ($Mode -eq 'Test') { $testNames } else { $runtimeNames }
# Values are literal: JDBC ampersands, spaces and password equals signs must not execute as code.
foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$' -and $allowed -ccontains $Matches[1]) {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
