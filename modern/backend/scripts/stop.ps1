$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $projectRoot '.runtime\server.pid'
if (-not (Test-Path -LiteralPath $pidFile)) { Write-Output 'No managed server PID.'; exit }
$serverProcessId = [int](Get-Content -LiteralPath $pidFile)
$process = Get-CimInstance Win32_Process -Filter "ProcessId=$serverProcessId"
$expectedJar = Join-Path $projectRoot '.runtime\server-app.jar'
if ($process -and $process.Name -eq 'java.exe' -and $process.CommandLine.Contains($expectedJar)) {
    Stop-Process -Id $serverProcessId
    Write-Output 'LibraMind stopped.'
} elseif ($process) { throw 'PID was reused by another process; refused to stop it.' }
Remove-Item -LiteralPath $pidFile
