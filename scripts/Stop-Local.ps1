$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$stateFile = Join-Path $root '.local-dev.json'
if (-not (Test-Path -LiteralPath $stateFile)) { Write-Host 'No local development process record found.'; return }
$state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
if ($state.root -ne $root) { throw 'The process record belongs to another workspace.' }
foreach ($entry in $state.processes) {
    $argument = [System.IO.Path]::GetFullPath($entry.argument)
    if (-not $argument.StartsWith($root + [System.IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing to stop a process outside this workspace.' }
    $process = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + [int]$entry.id) -ErrorAction SilentlyContinue
    if (-not $process) { continue }
    if ($process.ExecutablePath -eq $entry.executable -and $process.CommandLine.Contains($argument)) {
        Stop-Process -Id ([int]$entry.id)
        Write-Host "Stopped local process $($entry.id)."
    } else { Write-Warning "PID $($entry.id) was reused or does not match; it was not stopped." }
}
Remove-Item -LiteralPath $stateFile
