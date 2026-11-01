param([string]$DataDirectory)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if(-not $DataDirectory){$DataDirectory=Join-Path $projectRoot 'data\demo-20261026'}
$configPath=Join-Path $projectRoot 'config\db.properties'
if(-not (Test-Path -LiteralPath $configPath)){throw 'Missing config/db.properties.'}
$jdbcLib=Join-Path $projectRoot 'target\library\WEB-INF\lib'
if(-not (Test-Path -LiteralPath (Join-Path $jdbcLib 'mysql-connector-j-9.7.0.jar'))){
    & (Join-Path $PSScriptRoot 'build.ps1')
    if($LASTEXITCODE -ne 0){throw 'Build failed.'}
}
$snapshotPath=Join-Path $projectRoot ('.runtime\data-backups\import-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8))
& java '-Dfile.encoding=UTF-8' -cp "$jdbcLib\*" (Join-Path $projectRoot 'tools\ImportDemoData.java') apply $configPath $DataDirectory $snapshotPath
if($LASTEXITCODE -ne 0){throw 'Import did not complete. See output above.'}
Write-Host "Snapshot and result: $snapshotPath"