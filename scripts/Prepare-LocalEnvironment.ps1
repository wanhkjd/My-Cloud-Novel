param(
    [string]$ProjectRoot = (Join-Path $PSScriptRoot '..')
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($ProjectRoot)
$output = Join-Path $root '.local/setup'
$names = @('application.env', 'test.env', 'bootstrap.sql')
foreach ($name in $names) {
    if (Test-Path -LiteralPath (Join-Path $output $name)) {
        throw 'Generated setup files already exist. Nothing was overwritten; review the existing private files.'
    }
}
function New-LocalSecret {
    $bytes = New-Object byte[] 24
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}
function Write-NewPrivateFile([string]$Name, [string]$Text) {
    $target = Join-Path $output $Name
    $stream = [IO.File]::Open($target, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    try {
        $bytes = [Text.UTF8Encoding]::new($false).GetBytes($Text)
        $stream.Write($bytes, 0, $bytes.Length)
    } finally { $stream.Dispose() }
}
# Preserve only existing website identity. Never copy root/database credentials into new application settings.
$identity = @{}
$existing = Join-Path $root '.env'
if (Test-Path -LiteralPath $existing) {
    foreach ($line in Get-Content -LiteralPath $existing -Encoding UTF8) {
        if ($line -match '^(ADMIN_USERNAME|ADMIN_PASSWORD|COOKIE_SECURE)=(.*)$') { $identity[$Matches[1]] = $Matches[2] }
    }
}
if (-not $identity.ADMIN_USERNAME) { $identity.ADMIN_USERNAME = 'admin' }
if (-not $identity.ADMIN_PASSWORD -or $identity.ADMIN_PASSWORD.Length -lt 12) { $identity.ADMIN_PASSWORD = New-LocalSecret }
if (-not $identity.COOKIE_SECURE) { $identity.COOKIE_SECURE = 'false' }
$appPassword = New-LocalSecret
$testPassword = New-LocalSecret
$e2ePassword = New-LocalSecret
$schema = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../deploy/mysql/schema.sql') -Raw -Encoding UTF8
New-Item -ItemType Directory -Path $output -Force | Out-Null
$app = @"
ADMIN_USERNAME=$($identity.ADMIN_USERNAME)
ADMIN_PASSWORD=$($identity.ADMIN_PASSWORD)
COOKIE_SECURE=$($identity.COOKIE_SECURE)
DB_URL=jdbc:mysql://127.0.0.1:3306/cloud_novel?characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&sslMode=DISABLED&allowPublicKeyRetrieval=true
DB_USERNAME=cloud_novel_app
DB_PASSWORD=$appPassword
BOOK_STORAGE=./data/books
"@
$test = @"
TEST_MYSQL_HOST=127.0.0.1
TEST_MYSQL_PORT=3306
TEST_DB_USERNAME=cloud_novel_test
TEST_DB_PASSWORD=$testPassword
E2E_DB_USERNAME=cloud_novel_e2e
E2E_DB_PASSWORD=$e2ePassword
"@
$sql = "-- PRIVATE generated credentials. Execute manually as your local DBA; never commit or share.
-- Fresh installation only: there is deliberately no DROP, ALTER USER or destructive reset.
"
foreach ($entry in @(
    @{ database = 'cloud_novel'; username = 'cloud_novel_app'; password = $appPassword },
    @{ database = 'cloud_novel_test'; username = 'cloud_novel_test'; password = $testPassword },
    @{ database = 'cloud_novel_e2e'; username = 'cloud_novel_e2e'; password = $e2ePassword }
)) {
    $sql += @"
CREATE DATABASE $($entry.database) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER '$($entry.username)'@'127.0.0.1' IDENTIFIED BY '$($entry.password)';
GRANT SELECT, INSERT, UPDATE, DELETE ON $($entry.database).* TO '$($entry.username)'@'127.0.0.1';
USE $($entry.database);
$schema

"@
}
Write-NewPrivateFile 'application.env' ($app + [Environment]::NewLine)
Write-NewPrivateFile 'test.env' ($test + [Environment]::NewLine)
Write-NewPrivateFile 'bootstrap.sql' $sql
Write-Host "Private setup files generated under $output"
Write-Host 'No database, user account, Windows service, Docker container or existing .env was changed.'
Write-Host 'Follow docs/mysql-setup.md to inspect and apply them yourself. Do not paste these files into chat.'
