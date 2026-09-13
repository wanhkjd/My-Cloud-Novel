param([Parameter(Mandatory = $true)][string]$Path)
$ErrorActionPreference = 'Stop'

# Import only known application settings as literal values; never execute the .env file.
# In particular, JDBC URLs can contain ampersands and passwords can contain equals signs.
foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
    if ($line -match '^(ADMIN_USERNAME|ADMIN_PASSWORD|COOKIE_SECURE|DB_URL|DB_USERNAME|DB_PASSWORD|DB_INIT_MODE|BOOK_STORAGE)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
