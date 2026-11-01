param([Parameter(Mandatory=$true)][string]$MainClass, [string[]]$MainArgs=@())
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$javaCommand=Get-Command java -ErrorAction Stop
$classes=Join-Path $projectRoot 'target\classes'
$libs=Join-Path $projectRoot 'target\library\WEB-INF\lib\*'
if(-not(Test-Path -LiteralPath (Join-Path $classes 'library\tools\SetupMain.class'))){
    $war=Join-Path $projectRoot 'dist\library.war'
    if(-not(Test-Path -LiteralPath $war)){throw 'Build the project or provide dist/library.war first.'}
    $hash=(Get-FileHash -LiteralPath $war -Algorithm SHA256).Hash.Substring(0,16)
    $toolRoot=Join-Path $projectRoot ".runtime\tools\$hash"
    if(-not(Test-Path -LiteralPath $toolRoot)){
        New-Item -ItemType Directory -Path $toolRoot -Force | Out-Null
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [IO.Compression.ZipFile]::ExtractToDirectory($war,$toolRoot)
    }
    $classes=Join-Path $toolRoot 'WEB-INF\classes'
    $libs=Join-Path $toolRoot 'WEB-INF\lib\*'
}
$classPath=$classes+';'+$libs
& $javaCommand.Source '-Dfile.encoding=UTF-8' '-Duser.timezone=Asia/Shanghai' '-cp' $classPath $MainClass @MainArgs
if($LASTEXITCODE -ne 0){throw "Java tool failed (exit $LASTEXITCODE)."}
