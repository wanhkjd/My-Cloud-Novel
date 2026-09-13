# No Pester dependency or real .env access; all inputs below are synthetic.
$ErrorActionPreference = 'Stop'
$checks = 0
function Assert-Check([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:checks++
}

# Parse every maintained script without starting services or executing its body.
foreach ($file in Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1' -File) {
    $tokens = $null
    $parseErrors = $null
    [Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$parseErrors) | Out-Null
    Assert-Check ($parseErrors.Count -eq 0) ("PowerShell syntax errors in " + $file.Name)
}

$names = @('ADMIN_USERNAME', 'ADMIN_PASSWORD', 'COOKIE_SECURE', 'DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'DB_INIT_MODE', 'BOOK_STORAGE', 'CLOUD_NOVEL_UNKNOWN_TEST_SETTING')
$before = @{}
foreach ($name in $names) { $before[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$directory = Join-Path $tempRoot ('cloud-novel-config-test-' + [Guid]::NewGuid().ToString('N'))
$file = Join-Path $directory 'synthetic.env'
try {
    New-Item -ItemType Directory -Path $directory | Out-Null
    $lines = @(
        '# DB_INIT_MODE=always',
        'ADMIN_USERNAME=configuration-test',
        'ADMIN_PASSWORD=synthetic-test-password-only',
        'COOKIE_SECURE=false',
        'DB_URL=jdbc:mysql://127.0.0.1:3306/test_only?characterEncoding=UTF-8&serverTimezone=Asia/Shanghai',
        'DB_USERNAME=test_only',
        'DB_PASSWORD=synthetic=password=with=equals',
        'DB_INIT_MODE=never',
        'BOOK_STORAGE=./target/synthetic books',
        'CLOUD_NOVEL_UNKNOWN_TEST_SETTING=must-not-be-imported',
        'not an environment assignment'
    )
    [IO.File]::WriteAllLines($file, $lines, [Text.UTF8Encoding]::new($false))
    & (Join-Path $PSScriptRoot 'Import-LocalConfig.ps1') -Path $file
    Assert-Check ($env:DB_INIT_MODE -eq 'never') 'Manual-schema mode was not imported.'
    Assert-Check ($env:DB_URL -eq $lines[4].Substring('DB_URL='.Length)) 'JDBC URL was changed.'
    Assert-Check ($env:DB_PASSWORD -eq 'synthetic=password=with=equals') 'Password must remain a literal value.'
    Assert-Check ($env:BOOK_STORAGE -eq './target/synthetic books') 'Storage paths with spaces must be preserved.'
    Assert-Check ($env:ADMIN_USERNAME -eq 'configuration-test') 'Administrator settings were not imported.'
    Assert-Check ($env:ADMIN_PASSWORD -eq 'synthetic-test-password-only') 'Administrator password was not imported.'
    Assert-Check ($env:COOKIE_SECURE -eq 'false') 'Cookie setting was not imported.'
    Assert-Check ($env:DB_USERNAME -eq 'test_only') 'Database username was not imported.'
    Assert-Check ([Environment]::GetEnvironmentVariable('CLOUD_NOVEL_UNKNOWN_TEST_SETTING', 'Process') -eq $before['CLOUD_NOVEL_UNKNOWN_TEST_SETTING']) 'Unknown settings must be ignored.'

    [IO.File]::WriteAllText($file, "# DB_INIT_MODE=always" + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    & (Join-Path $PSScriptRoot 'Import-LocalConfig.ps1') -Path $file
    Assert-Check ($env:DB_INIT_MODE -eq 'never') 'Comments must not override configuration.'

    $start = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'Start-Local.ps1') -Raw
    Assert-Check ($start.Contains('Import-LocalConfig.ps1')) 'Startup must use the tested configuration loader.'
    Assert-Check ($start.Contains('& mvn.cmd -q -ntp verify;')) 'Startup builds must enforce JavaDoc and all backend checks.'
} finally {
    foreach ($name in $names) {
        if ($null -eq $before[$name]) {
            Remove-Item -LiteralPath ("Env:" + $name) -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable($name, $before[$name], 'Process')
        }
    }
    # Delete only our named temporary file and then the empty directory; never recurse.
    if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file -Force }
    if (Test-Path -LiteralPath $directory) { Remove-Item -LiteralPath $directory }
}

# An unset variable must remain absent, not an empty string that overrides Spring defaults.
foreach ($name in $names) {
    $actual = [Environment]::GetEnvironmentVariable($name, 'Process')
    Assert-Check ([object]::Equals($actual, $before[$name])) ("Environment was not restored: " + $name)
}
Write-Host "PowerShell checks passed: $checks (syntax, configuration, environment restoration, startup contract)."
