param([int]$Port=8086,[switch]$SkipBuild,[switch]$NoBrowser)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtimeRoot=Join-Path $projectRoot '.runtime'
$serverRoot=Join-Path $runtimeRoot 'server'
$configPath=Join-Path $projectRoot 'config\db.properties'
$tomcatVersion='9.0.123'
$tomcatHome=Join-Path $runtimeRoot "apache-tomcat-$tomcatVersion"
if($Port -lt 1024 -or $Port -gt 65535){throw 'Port must be between 1024 and 65535.'}
if(-not (Test-Path -LiteralPath $configPath)){throw 'Missing config/db.properties. See README.md for first-time initialization.'}
if(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue){throw "Port $Port is already in use. Stop this project's previous instance or use -Port with a free port."}
if(-not $SkipBuild){& (Join-Path $PSScriptRoot 'build.ps1')}
$warPath=Join-Path $projectRoot 'target\library.war'
if($SkipBuild -and (Test-Path -LiteralPath (Join-Path $projectRoot 'dist\library.war'))){$warPath=Join-Path $projectRoot 'dist\library.war'}
if(-not (Test-Path -LiteralPath $warPath)){throw 'Build target/library.war first, or provide dist/library.war.'}
New-Item -ItemType Directory -Force -Path $runtimeRoot | Out-Null
if(-not (Test-Path -LiteralPath (Join-Path $tomcatHome 'bin\bootstrap.jar'))){
    $archiveName="apache-tomcat-$tomcatVersion.zip"
    $url="https://downloads.apache.org/tomcat/tomcat-9/v$tomcatVersion/bin/$archiveName"
    $archive=Join-Path $runtimeRoot $archiveName
    $bundledArchive=Join-Path $projectRoot "vendor\$archiveName"
    $bundledChecksum=Join-Path $projectRoot "vendor\$archiveName.sha512"
    if(Test-Path -LiteralPath $bundledChecksum){$checksumText=Get-Content -LiteralPath $bundledChecksum -Raw}
    else{$checksumText=(Invoke-WebRequest -Uri "$url.sha512" -UseBasicParsing).Content}
    $expected=[regex]::Match([string]$checksumText,'[a-fA-F0-9]{128}').Value
    if($expected.Length -ne 128){throw 'No valid SHA-512 checksum was found.'}
    if((Test-Path -LiteralPath $bundledArchive) -and -not(Test-Path -LiteralPath $archive)){Copy-Item -LiteralPath $bundledArchive -Destination $archive}
    if(-not (Test-Path -LiteralPath $archive)){Invoke-WebRequest -Uri $url -OutFile $archive -UseBasicParsing}
    $actual=(Get-FileHash -LiteralPath $archive -Algorithm SHA512).Hash
    if($actual -ine $expected){throw 'Tomcat SHA-512 checksum failed. No unverified program was started.'}
    Expand-Archive -LiteralPath $archive -DestinationPath $runtimeRoot -Force
}
foreach($folder in @('conf','logs','temp','work','webapps')){New-Item -ItemType Directory -Force -Path (Join-Path $serverRoot $folder) | Out-Null}
foreach($file in @('web.xml','catalina.properties','logging.properties','context.xml')){Copy-Item -LiteralPath (Join-Path $tomcatHome "conf\$file") -Destination (Join-Path $serverRoot "conf\$file") -Force}
# Host 直接部署标准 WAR，不解包到工程目录；容器配置和日志都位于本工程 .runtime 内。
$warXml=[System.Security.SecurityElement]::Escape($warPath.Replace('\','/'))
$serverXml=@"
<?xml version="1.0" encoding="UTF-8"?>
<Server port="-1">
  <Listener className="org.apache.catalina.core.JreMemoryLeakPreventionListener"/>
  <Listener className="org.apache.catalina.core.ThreadLocalLeakPreventionListener"/>
  <Service name="Catalina">
    <Connector address="127.0.0.1" port="$Port" protocol="HTTP/1.1" connectionTimeout="20000" URIEncoding="UTF-8" maxPostSize="1048576" server="Library"/>
    <Engine name="Catalina" defaultHost="localhost">
      <Host name="localhost" appBase="webapps" autoDeploy="false" deployOnStartup="true" unpackWARs="false">
        <Context path="/library" docBase="$warXml" reloadable="false"/>
        <Valve className="org.apache.catalina.valves.ErrorReportValve" showReport="false" showServerInfo="false"/>
      </Host>
    </Engine>
  </Service>
</Server>
"@
[IO.File]::WriteAllText((Join-Path $serverRoot 'conf\server.xml'),$serverXml,[Text.UTF8Encoding]::new($false))
# 仅清理本应用的 JSP 编译缓存，校验绝对路径后再删除，保留业务数据与日志。
$cacheRoot=Join-Path $serverRoot 'work\Catalina\localhost\library'
if(Test-Path -LiteralPath $cacheRoot){$resolvedCache=(Resolve-Path -LiteralPath $cacheRoot).Path;if(-not $resolvedCache.StartsWith($runtimeRoot+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe cache path.'};Remove-Item -LiteralPath $resolvedCache -Recurse -Force}
$javaPath=(Get-Command java -ErrorAction Stop).Source
$classPath=(Join-Path $tomcatHome 'bin\bootstrap.jar')+';'+(Join-Path $tomcatHome 'bin\tomcat-juli.jar')
$javaArgs=@('-Dfile.encoding=UTF-8','-Duser.timezone=Asia/Shanghai',"-Dcatalina.home=$tomcatHome","-Dcatalina.base=$serverRoot","-Djava.io.tmpdir=$(Join-Path $serverRoot 'temp')","-Djava.util.logging.config.file=$(Join-Path $serverRoot 'conf\logging.properties')",'-Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager',"-Dlibrary.config=$configPath",'-cp',$classPath,'org.apache.catalina.startup.Bootstrap','start')
$quotedArgs=($javaArgs | ForEach-Object {'"'+$_+'"'}) -join ' '
$process=Start-Process -FilePath $javaPath -ArgumentList $quotedArgs -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $serverRoot 'logs\console.out.log') -RedirectStandardError (Join-Path $serverRoot 'logs\console.err.log') -PassThru
$process.Id | Set-Content -LiteralPath (Join-Path $runtimeRoot 'server.pid')
$url="http://127.0.0.1:$Port/library/"
$ready=$false
for($attempt=0;$attempt -lt 45;$attempt++){
    Start-Sleep -Milliseconds 700
    if($process.HasExited){throw "Tomcat exited. See $serverRoot\logs\console.err.log"}
    try{$response=Invoke-WebRequest -Uri ($url+'app') -TimeoutSec 3 -UseBasicParsing;if($response.StatusCode -eq 200 -and $response.Content -match 'data-app="library"'){$ready=$true;break}}catch{}
}
if(-not $ready){throw "Application did not become ready. See $serverRoot\logs\console.err.log"}
Write-Host "Ready: $url"
Write-Host "PID: $($process.Id)  Logs: $serverRoot\logs"
if(-not $NoBrowser){Start-Process $url -WindowStyle Hidden}
