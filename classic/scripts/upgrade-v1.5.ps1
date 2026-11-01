param(
    [Parameter(Mandatory=$true)][string]$AdminConfig,
    [string]$Config,
    [switch]$CheckOnly
)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if(-not $Config){$Config=Join-Path $projectRoot 'config\db.properties'}
$configPath=(Resolve-Path -LiteralPath $Config).Path
$adminPath=(Resolve-Path -LiteralPath $AdminConfig).Path
$jdbcLib=Join-Path $projectRoot 'target\library\WEB-INF\lib'
if(-not(Test-Path -LiteralPath (Join-Path $jdbcLib 'mysql-connector-j-9.7.0.jar'))){
    # 使用交付包内的驱动即可迁移，无需先运行可能尚未迁移的测试库。
    $war=Join-Path $projectRoot 'dist\library.war'
    if(-not(Test-Path -LiteralPath $war)){throw 'Missing dist/library.war; build or restore the release first.'}
    $jdbcLib=Join-Path $projectRoot '.runtime\migration-lib'
    New-Item -ItemType Directory -Force -Path $jdbcLib | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::OpenRead($war)
    try{
        $entry=$zip.GetEntry('WEB-INF/lib/mysql-connector-j-9.7.0.jar')
        if(-not $entry){throw 'MySQL JDBC driver missing from WAR.'}
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,(Join-Path $jdbcLib 'mysql-connector-j-9.7.0.jar'),$true)
    }finally{$zip.Dispose()}
}
$mode=if($CheckOnly){'check'}else{'apply'}
$backup=Join-Path $projectRoot ('.runtime\data-backups\v15-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8))
if(-not $CheckOnly){Write-Host 'Before applying, stop all Tomcat instances using this database, including IDEA.'}
& java '-Dfile.encoding=UTF-8' -cp "$jdbcLib\*" (Join-Path $projectRoot 'tools\MigrateV15.java') $mode $configPath $adminPath (Join-Path $projectRoot 'data\covers') $backup
if($LASTEXITCODE -ne 0){throw 'Migration/check failed. Preserve the backup and inspect the error before starting the application.'}
