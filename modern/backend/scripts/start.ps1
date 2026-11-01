param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $projectRoot 'target\libramind-backend-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Run scripts/dev.ps1 -Action package first.' }
if (Get-NetTCPConnection -LocalPort 8090 -State Listen -ErrorAction SilentlyContinue) { throw 'Port 8090 is already in use. No existing process was stopped.' }
$runtimeDirectory = Join-Path $projectRoot '.runtime'
New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null
$runtimeJar = Join-Path $runtimeDirectory 'server-app.jar'
Copy-Item -LiteralPath $jar -Destination $runtimeJar -Force
$process = Start-Process -FilePath (Join-Path $JavaHome 'bin\java.exe') -ArgumentList @('-jar', "`"$runtimeJar`"") -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeDirectory 'server.log') -RedirectStandardError (Join-Path $runtimeDirectory 'server-error.log')
$process.Id | Set-Content -LiteralPath (Join-Path $runtimeDirectory 'server.pid')
Write-Output "LibraMind starting on http://127.0.0.1:8090/ (PID $($process.Id))."
