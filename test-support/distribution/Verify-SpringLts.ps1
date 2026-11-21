param(
    [string]$Maven = $(if ($IsWindows) { 'mvn.cmd' } else { 'mvn' }),
    [string]$Jdk17,
    [string]$Jdk21,
    [string]$Jdk25,
    [switch]$VerifyCell,
    [switch]$Java17Container
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$mavenCommand = (Get-Command $Maven -CommandType Application | Select-Object -First 1).Source
if ($VerifyCell) {
    & $mavenCommand --batch-mode --no-transfer-progress --strict-checksums --settings $env:TUPREL_MAVEN_SETTINGS `
        --global-settings $env:TUPREL_MAVEN_SETTINGS "-Dmaven.repo.local=$env:TUPREL_CONSUMER_MAVEN_CACHE" verify
    if ($LASTEXITCODE -ne 0) { throw 'Spring Maven verification failed' }
    & (Join-Path $env:JAVA_HOME 'bin/java') -cp target/test-classes example.products.PackagedProductsProof
    if ($LASTEXITCODE -ne 0) { throw 'Independent packaged Spring application failed' }
    exit 0
}

$root = (Resolve-Path "$PSScriptRoot/../..").Path
$jdks = @{17=(Resolve-Path $Jdk17).Path;21=(Resolve-Path $Jdk21).Path;25=(Resolve-Path $Jdk25).Path}
$environment = @{}
foreach ($name in @('JAVA_HOME','PATH','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','MAVEN_OPTS',
        'MAVEN_ARGS','MAVEN_SKIP_RC','CLASSPATH','TUPREL_FIXTURE_REPOSITORY','TUPREL_CLI_HOME',
        'TUPREL_CONSUMER_MAVEN_CACHE','TUPREL_EXPECTED_JAVA','TUPREL_MAVEN_SETTINGS')) {
    $environment[$name] = [Environment]::GetEnvironmentVariable($name)
    if ($name -notin @('JAVA_HOME','PATH')) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
}
$env:MAVEN_SKIP_RC='true'
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('tuprel-spring-lts-' + [guid]::NewGuid())

function Select-Jdk([int]$Version) {
    $env:JAVA_HOME=$jdks[$Version]
    $env:PATH=(Join-Path $env:JAVA_HOME 'bin')+[IO.Path]::PathSeparator+$environment['PATH']
    $reported=(& $mavenCommand --version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $reported -notmatch 'Apache Maven 3\.9\.16\b' -or
            $reported -notmatch ("Java version: $Version"+'[.,]')) { throw "Expected Maven 3.9.16 on Java $Version" }
    $env:TUPREL_EXPECTED_JAVA="$Version"
    Write-Host $reported.Trim()
}

function Get-Hashes([string]$Directory) {
    $result=[ordered]@{}
    foreach ($file in Get-ChildItem -LiteralPath $Directory -File -Recurse -Force | Sort-Object FullName) {
        $result[[IO.Path]::GetRelativePath($Directory,$file.FullName).Replace('\','/')]=
            (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    }
    if ($result.Count -eq 0) { throw 'Expected a nonempty artifact inventory' }
    return $result
}

function Assert-Hashes($Expected,$Actual) {
    if ($Expected.Count -ne $Actual.Count) { throw 'Changed artifact inventory' }
    foreach ($entry in $Expected.GetEnumerator()) {
        if ($Actual[$entry.Key] -cne $entry.Value) { throw "Changed artifact bytes at $($entry.Key)" }
    }
}

function Invoke-DatabaseProof([string]$Project,[string[]]$Command) {
    & (Join-Path $env:JAVA_HOME 'bin/java') -cp $helper RunProductsApiWithLocalDatabase `
        "$root/examples/tuprel-playground/.env" $Project @Command
    if ($LASTEXITCODE -ne 0) { throw 'Isolated Spring/PostgreSQL proof failed' }
}

try {
    Select-Jdk 21
    $stage = & "$PSScriptRoot/Stage-SpringProductsApi.ps1" -Destination $fixture | Select-Object -Last 1
    $repository = Join-Path $fixture 'tuprel-maven-fixture-products'
    $publication = Get-Hashes $repository
    $cliHashes=Get-Hashes $stage.CliHome
    foreach ($entry in $publication.GetEnumerator() | Where-Object Key -Match '\.(jar|pom|module)$') {
        $file=Join-Path $repository $entry.Key
        if ((Get-Content -LiteralPath "$file.sha256" -Raw).Trim() -ine $entry.Value) { throw 'Invalid publication sidecar' }
        Write-Host "ARTIFACT $($entry.Key) SHA-256 $($entry.Value)"
    }
    $prefix=Join-Path $repository 'dev/tuprel/staging/tuprel-spring-boot-starter/0.0.0-fixture/tuprel-spring-boot-starter-0.0.0-fixture'
    $pom=([xml](Get-Content "$prefix.pom" -Raw)).project
    $scopes=@($pom.dependencies.dependency | ForEach-Object {
        if ($_.artifactId.StartsWith('tuprel-') -and ($_.groupId -cne 'dev.tuprel.staging' -or $_.version -cne '0.0.0-fixture')) {
            throw 'A starter dependency escaped staging'
        }
        "$($_.artifactId):$($_.scope)"
    } | Sort-Object)
    if ($pom.groupId -cne 'dev.tuprel.staging' -or $pom.version -cne '0.0.0-fixture' -or
            ($scopes -join ',') -cne 'spring-boot-autoconfigure:runtime,tuprel-postgresql:runtime,tuprel-runtime:compile') {
        throw 'Unexpected starter coordinates or Maven scopes'
    }
    $module=Get-Content "$prefix.module" -Raw | ConvertFrom-Json
    foreach ($name in @('apiElements','runtimeElements')) {
        $variant=@($module.variants | Where-Object name -CEQ $name)
        if ($variant.Count -ne 1 -or $variant[0].attributes.'org.gradle.jvm.version' -ne 17) { throw 'Incorrect starter JVM metadata' }
    }
    $jar=[IO.Compression.ZipFile]::OpenRead("$prefix.jar")
    try {
        $classes=@($jar.Entries | Where-Object FullName -CLike '*.class')
        if ($classes.Count -ne 1) { throw 'Unexpected starter class inventory' }
        $stream=$classes[0].Open()
        try { $header=[byte[]]::new(8); $stream.ReadExactly($header); if ($header[6]*256+$header[7] -ne 61) { throw 'Starter is not major 61' } }
        finally { $stream.Dispose() }
        $reader=[IO.StreamReader]::new($jar.GetEntry('META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports').Open())
        try { if ($reader.ReadToEnd().Trim() -cne 'dev.tuprel.spring.boot.TuprelAutoConfiguration') { throw 'Unexpected auto-configuration registration' } }
        finally { $reader.Dispose() }
    } finally { $jar.Dispose() }
    $env:TUPREL_FIXTURE_REPOSITORY=$stage.Repository
    $env:TUPREL_CLI_HOME=$stage.CliHome
    $env:TUPREL_MAVEN_SETTINGS=Join-Path $fixture 'settings.xml'
    [IO.File]::WriteAllText($env:TUPREL_MAVEN_SETTINGS,'<settings/>')
    $helper=Join-Path $fixture 'test-helper'
    $support="$root/test-support/src/main/java/dev/tuprel/testsupport"
    & (Join-Path $env:JAVA_HOME 'bin/javac') --release 17 -d $helper "$support/LocalTestEnvironment.java" `
        "$support/LocalPostgresqlTarget.java" "$PSScriptRoot/RunProductsApiWithLocalDatabase.java"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot compile the test-only database launcher' }
    $initialSources=$null
    $cells=@{}
    foreach ($version in @(17,21,25)) {
        Select-Jdk $version
        $project=Join-Path $fixture "independent Spring consumer $version"
        Copy-Item -LiteralPath $stage.Project -Destination $project -Recurse
        $cache=Join-Path $fixture "maven-cache-$version"
        $env:TUPREL_CONSUMER_MAVEN_CACHE=$cache
        Invoke-DatabaseProof $project @((Get-Process -Id $PID).Path,'-NoProfile','-File',$PSCommandPath,
            '-VerifyCell','-Maven',$mavenCommand)
        foreach ($entry in $publication.GetEnumerator() | Where-Object Key -Match '\.(jar|pom)$') {
            $cached=Join-Path $cache $entry.Key
            if ((Get-FileHash -LiteralPath $cached -Algorithm SHA256).Hash -cne $entry.Value) { throw 'Cached staging artifact differs' }
        }
        $generated=Get-Hashes (Join-Path $project 'build/generated/sources/tuprel/main')
        if ($null -eq $initialSources) {
            $initialSources=$generated
            foreach ($entry in $generated.GetEnumerator()) { Write-Host "SOURCE $($entry.Key) SHA-256 $($entry.Value)" }
        }
        Assert-Hashes $initialSources $generated
        $artifact=Join-Path $project 'target/spring-products-api-0.0.1-SNAPSHOT.jar'
        Write-Host "JAR Java $version SHA-256 $((Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash)"
        $cells[$version]=@{Project=$project;Cache=$cache;Jar=$artifact}
        Write-Host "PASS Spring Boot 4.1.2 Java ${version}: Maven verify, generated code, ownership, transactions, HTTP/PostgreSQL and java -jar"
    }
    $frozen=(Get-FileHash -LiteralPath $cells[17].Jar -Algorithm SHA256).Hash
    foreach ($version in @(17,21,25)) {
        Select-Jdk $version
        Invoke-DatabaseProof $cells[17].Project @((Join-Path $env:JAVA_HOME 'bin/java'),'-cp','target/test-classes',
            'example.products.PackagedProductsProof',$cells[17].Jar)
        if ((Get-FileHash -LiteralPath $cells[17].Jar -Algorithm SHA256).Hash -cne $frozen) { throw 'Frozen application JAR changed' }
        Write-Host "PASS same Java17 application JAR on Java $version SHA-256 $frozen"
    }
    Select-Jdk 17
    $damaged=Join-Path $fixture 'damaged staging'
    Copy-Item -LiteralPath $repository -Destination $damaged -Recurse
    $relative='dev/tuprel/staging/tuprel-spring-boot-starter/0.0.0-fixture'
    $jarName='tuprel-spring-boot-starter-0.0.0-fixture.jar'
    [IO.File]::AppendAllText((Join-Path $damaged "$relative/$jarName"),'tampered')
    $negativeCache=Join-Path $fixture 'negative cache'
    Copy-Item -LiteralPath $cells[17].Cache -Destination $negativeCache -Recurse
    $cached=Join-Path $negativeCache "$relative/$jarName"
    [IO.File]::AppendAllText($cached,'tampered cache')
    if ((Get-FileHash -LiteralPath $cached -Algorithm SHA256).Hash -ceq $publication["$relative/$jarName"]) {
        throw 'Cache tampering was not detected'
    }
    $component=[IO.Path]::GetFullPath((Join-Path $negativeCache $relative))
    if (-not $component.StartsWith($fixture+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refused cleanup outside the negative cache'
    }
    Remove-Item -LiteralPath $component -Recurse -Force
    $negative=Join-Path $fixture 'negative consumer'
    Copy-Item -LiteralPath $stage.Project -Destination $negative -Recurse
    Push-Location -LiteralPath $negative
    try {
        $damagedUrl=[uri]::new($damaged+[IO.Path]::DirectorySeparatorChar).AbsoluteUri
        $output=(& $mavenCommand --batch-mode --no-transfer-progress --strict-checksums `
            --settings $env:TUPREL_MAVEN_SETTINGS --global-settings $env:TUPREL_MAVEN_SETTINGS `
            "-Dmaven.repo.local=$negativeCache" "-Dtuprel.fixture.repository=$damagedUrl" compile 2>&1 | Out-String)
        if ($LASTEXITCODE -eq 0 -or $output -notmatch 'Checksum validation failed') {
            throw 'Maven did not reject the modified starter checksum'
        }
    } finally { Pop-Location }
    Push-Location -LiteralPath $cells[17].Project
    try {
        $owned=Get-ChildItem build/generated/sources/tuprel/main -Recurse -File -Filter '*.java' | Select-Object -First 1
        [IO.File]::AppendAllText($owned.FullName,"// user edit`n")
        $edited=(Get-FileHash -LiteralPath $owned.FullName -Algorithm SHA256).Hash
        $launcher=Join-Path $stage.CliHome $(if ($IsWindows) { 'bin/tuprel.bat' } else { 'bin/tuprel' })
        if ($IsWindows) { $null=& $launcher generate 2>&1 } else { $null=& /bin/bash $launcher generate 2>&1 }
        if ($LASTEXITCODE -ne 2 -or (Get-FileHash -LiteralPath $owned.FullName -Algorithm SHA256).Hash -cne $edited) {
            throw 'Generation did not preserve a manual edit'
        }
    } finally { Pop-Location }
    Write-Host 'PASS altered starter, contaminated cache and edited generated source rejected'
    Assert-Hashes $publication (Get-Hashes $repository)
    Assert-Hashes $cliHashes (Get-Hashes $stage.CliHome)
    if ($Java17Container) {
        if (-not $IsLinux) { throw 'The single-JDK container proof is Linux-only' }
        $image='eclipse-temurin:17-jdk@sha256:7a0b2bc7e1372bb05ee361fb79e8a9e822df47343b8716253b3bbaa75060653e'
        & docker pull --platform linux/amd64 $image
        if ($LASTEXITCODE -ne 0) { throw 'Cannot obtain the pinned Java 17 test image' }
        $fixtureUid=& id -u
        $fixtureGid=& id -g
        if ($fixtureUid -notmatch '^[1-9][0-9]*$' -or $fixtureGid -notmatch '^[0-9]+$') { throw 'Expected an unprivileged UID/GID' }
        $single=Join-Path $fixture 'single JDK17 consumer'
        Copy-Item -LiteralPath $stage.Project -Destination $single -Recurse
        $singleCache=Join-Path $fixture 'single-jdk-cache'
        Copy-Item -LiteralPath $cells[17].Cache -Destination $singleCache -Recurse
        $mavenHome=Join-Path $fixture 'apache-maven'
        Copy-Item -LiteralPath (Split-Path -Parent (Split-Path -Parent $mavenCommand)) -Destination $mavenHome -Recurse
        $shell=Join-Path $fixture 'single-jdk.sh'
        Copy-Item -LiteralPath "$PSScriptRoot/Verify-SpringSingleJdk.sh" -Destination $shell
        $docker=@('docker','run','--rm','--platform','linux/amd64','--network','host','--read-only',
            '--cap-drop=ALL','--security-opt=no-new-privileges','--user',"${fixtureUid}:${fixtureGid}",'--tmpfs','/tmp',
            '--mount',"type=bind,source=$fixture,target=$fixture",'--env',"HOME=$singleCache",
            '--env','JAVA_HOME=/opt/java/openjdk','--env','MAVEN_SKIP_RC=true','--env','TUPREL_EXPECTED_JAVA=17',
            '--env',"TUPREL_CONSUMER_PROJECT=$single",'--env',"TUPREL_CONSUMER_MAVEN=$mavenHome",
            '--env',"TUPREL_CONSUMER_MAVEN_CACHE=$singleCache",'--env',"TUPREL_FROZEN_JAR=$($cells[17].Jar)",
            '--env','TUPREL_MAVEN_SETTINGS','--env','TUPREL_FIXTURE_REPOSITORY','--env','TUPREL_CLI_HOME',
            '--env','TUPREL_TEST_JDBC_URL','--env','TUPREL_TEST_DB_USER','--env','TUPREL_TEST_DB_PASSWORD',
            $image,'/bin/bash',$shell)
        Invoke-DatabaseProof $single $docker
        Assert-Hashes $initialSources (Get-Hashes (Join-Path $single 'build/generated/sources/tuprel/main'))
        Write-Host 'PASS Spring/Maven/HTTP/PostgreSQL/java-jar in Java17-only container; offline Maven, no host JDK mounted'
    }
} finally {
    foreach ($entry in $environment.GetEnumerator()) {
        if ($null -eq $entry.Value) { Remove-Item "Env:$($entry.Key)" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($entry.Key,$entry.Value) }
    }
    if (Test-Path -LiteralPath $fixture) {
        $resolved=(Resolve-Path -LiteralPath $fixture).Path
        if ($resolved -cne [IO.Path]::GetFullPath($fixture) -or
                -not (Split-Path -Leaf $resolved).StartsWith('tuprel-spring-lts-') -or
                ((Get-Item -LiteralPath $resolved).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Refused cleanup outside the Spring LTS fixture'
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
$global:LASTEXITCODE=0
