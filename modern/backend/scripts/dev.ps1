param(
    [ValidateSet('run', 'test', 'verify', 'package')]
    [string]$Action = 'run',
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw 'Java 21 not found. Supply -JavaHome with your JDK directory.'
}
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:Path
Push-Location -LiteralPath $projectRoot
try {
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$(Join-Path $JavaHome 'bin');$previousPath"
    $mavenCommand = (Get-Command mvn.cmd -ErrorAction Stop).Source
    $mavenArgs = @('-B', '-ntp')
    switch ($Action) {
        'run' { $mavenArgs += 'spring-boot:run' }
        'test' { $mavenArgs += 'test' }
        'verify' { $mavenArgs += @('verify', '-Plocal-it') }
        'package' { $mavenArgs += 'package' }
    }
    & $mavenCommand @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw "Maven failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJavaHome
    $env:Path = $previousPath
}
