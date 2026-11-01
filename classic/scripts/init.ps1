param([string]$AdminConfig)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if(-not $AdminConfig){$AdminConfig=Join-Path $projectRoot 'config\admin.properties'}
if(-not(Test-Path -LiteralPath $AdminConfig)){
    Copy-Item -LiteralPath (Join-Path $projectRoot 'config\admin.example.properties') -Destination $AdminConfig
    throw "Fill the local MySQL password in $AdminConfig, then run initialization again. Do not share this file."
}
& (Join-Path $PSScriptRoot 'run-java.ps1') -MainClass 'library.tools.SetupMain' -MainArgs @($AdminConfig,(Join-Path $projectRoot 'config'))
