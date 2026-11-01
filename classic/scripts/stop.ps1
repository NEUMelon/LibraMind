$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$pidFile=Join-Path $projectRoot '.runtime\server.pid'
if(-not(Test-Path -LiteralPath $pidFile)){Write-Host 'No project server PID file.';exit 0}
$serverProcessId=[int](Get-Content -LiteralPath $pidFile)
$serverProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$serverProcessId" -ErrorAction SilentlyContinue
if($serverProcess){
    if($serverProcess.Name -notmatch '^java(w)?\.exe$' -or $serverProcess.CommandLine -notlike "*-Dcatalina.base=$projectRoot\.runtime\server*"){throw 'PID does not belong to this project; nothing was stopped.'}
    Stop-Process -Id $serverProcessId
    Write-Host "Stopped this project's Tomcat (PID $serverProcessId)."
}
Remove-Item -LiteralPath $pidFile -Force
