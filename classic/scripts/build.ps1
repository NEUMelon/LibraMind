$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location -LiteralPath $projectRoot
try {
    & mvn '-B' '-ntp' 'package'
    if($LASTEXITCODE -ne 0){throw 'Build failed.'}
    New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot 'dist') | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot 'target\library.war') -Destination (Join-Path $projectRoot 'dist\library.war') -Force
} finally { Pop-Location }
