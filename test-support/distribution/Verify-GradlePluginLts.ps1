param(
    [Parameter(Mandatory)][string]$Jdk17,
    [Parameter(Mandatory)][string]$Jdk21,
    [Parameter(Mandatory)][string]$Jdk25,
    [string]$ArchiveDirectory,
    [ValidateSet('9.7.1', '9.8.2', '9.9.1')][string[]]$GradleVersions = @('9.7.1', '9.8.2', '9.9.1'),
    [ValidateSet(17, 21, 25)][int[]]$JavaVersions = @(17, 21, 25),
    [switch]$Java17Container
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$root = (Resolve-Path -LiteralPath "$PSScriptRoot/../..").Path
$jdks = @{17 = (Resolve-Path $Jdk17).Path; 21 = (Resolve-Path $Jdk21).Path; 25 = (Resolve-Path $Jdk25).Path}
$environment = @{}
foreach ($name in @('JAVA_HOME', 'PATH', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS',
        'GRADLE_OPTS', 'GRADLE_USER_HOME', 'CLASSPATH')) {
    $environment[$name] = [Environment]::GetEnvironmentVariable($name)
    if ($name -notin @('JAVA_HOME', 'PATH')) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
}
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('tuprel-gradle-lts-' + [guid]::NewGuid())
[IO.Directory]::CreateDirectory($fixture) | Out-Null
if (-not $ArchiveDirectory) { $ArchiveDirectory = Join-Path $root 'build/gradle-lts-distributions' }
[IO.Directory]::CreateDirectory($ArchiveDirectory) | Out-Null
$distributionHashes = @{
    '9.7.1' = 'acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a'
    '9.8.2' = '21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607'
    '9.9.1' = 'd8a18a7a841dd5df825d35a685fad6f2d7f12ac0c280e57b67c27b4a520ba228'
}

function Select-Jdk([int]$Version) {
    $env:JAVA_HOME = $jdks[$Version]
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $environment['PATH']
    $java = Join-Path $env:JAVA_HOME $(if ($IsWindows) { 'bin/java.exe' } else { 'bin/java' })
    $reported = (& $java -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $reported -notmatch ('version "' + $Version + '[."]')) {
        throw "Expected the actual Java $Version"
    }
    Write-Host $reported.Trim()
}

function Get-Hashes([string]$Directory, [string]$Filter = '*') {
    $Directory = (Resolve-Path -LiteralPath $Directory).Path
    $result = [ordered]@{}
    foreach ($file in Get-ChildItem -LiteralPath $Directory -Recurse -Force -File -Filter $Filter | Sort-Object FullName) {
        $name = [IO.Path]::GetRelativePath($Directory, $file.FullName).Replace('\', '/')
        $result[$name] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    }
    if ($result.Count -eq 0) { throw 'Expected a nonempty artifact inventory' }
    return $result
}

function Assert-Hashes($Expected, $Actual, [string]$Description) {
    if ($Expected.Count -ne $Actual.Count) { throw "Changed inventory for $Description" }
    foreach ($entry in $Expected.GetEnumerator()) {
        if ($Actual[$entry.Key] -cne $entry.Value) { throw "SHA-256 mismatch in $Description at $($entry.Key)" }
    }
}

function New-Consumer([string]$Project, [int]$Java, [string]$Repository) {
    Copy-Item -LiteralPath "$PSScriptRoot/gradle-consumer" -Destination $Project -Recurse
    $cli = Join-Path $Project 'installed CLI/lib'
    $runtime = Join-Path $Project 'runtime libraries'
    [IO.Directory]::CreateDirectory($cli) | Out-Null
    [IO.Directory]::CreateDirectory($runtime) | Out-Null
    Get-ChildItem -LiteralPath $cliSource -File -Filter '*.jar' |
        Copy-Item -Destination $cli
    foreach ($library in @('tuprel-runtime', 'tuprel-sql')) {
        $jar = Join-Path $runtimeSource "$library.jar"
        if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "Missing current runtime library $library" }
        Copy-Item -LiteralPath $jar -Destination $runtime
    }
    $paths = @($jdks[$Java], $jdks[17]) | Select-Object -Unique | ForEach-Object { $_.Replace('\', '/') }
    [IO.File]::WriteAllText((Join-Path $Project 'gradle.properties'), @"
org.gradle.java.installations.auto-detect=false
org.gradle.java.installations.auto-download=false
org.gradle.java.installations.paths=$($paths -join ',')
org.gradle.workers.max=1
org.gradle.jvmargs=-Xms64m -Xmx384m -XX:MaxMetaspaceSize=256m
"@)
    $components = @()
    foreach ($component in @(
            @('dev.tuprel.staging', 'tuprel-gradle-plugin'),
            @('dev.tuprel.codegen', 'dev.tuprel.codegen.gradle.plugin'))) {
        $group, $artifact = $component
        $files = @()
        foreach ($extension in @('pom', 'module', 'jar')) {
            $name = "$artifact-0.0.0-fixture.$extension"
            $file = Join-Path $Repository "$($group.Replace('.', '/'))/$artifact/0.0.0-fixture/$name"
            if (Test-Path -LiteralPath $file -PathType Leaf) {
                $hash = $publication[[IO.Path]::GetRelativePath($repository, $file).Replace('\', '/')]
                if (-not $hash) { throw 'Artifact has no frozen publication digest' }
                $files += "<artifact name='$name'><sha256 value='$($hash.ToLowerInvariant())'/></artifact>"
            }
        }
        $components += "<component group='$group' name='$artifact' version='0.0.0-fixture'>$($files -join '')</component>"
    }
    [IO.Directory]::CreateDirectory((Join-Path $Project 'gradle')) | Out-Null
    [IO.File]::WriteAllText((Join-Path $Project 'gradle/verification-metadata.xml'), @"
<verification-metadata>
  <configuration><verify-metadata>true</verify-metadata><verify-signatures>false</verify-signatures></configuration>
  <components>$($components -join '')</components>
</verification-metadata>
"@)
}

function Invoke-Consumer([string[]]$Tasks, [string]$ExpectedFailure) {
    $arguments = @('--offline', '--no-daemon', '--console=plain', '--info', '--build-cache', '--configuration-cache',
        '--configuration-cache-problems=fail', '--dependency-verification', 'strict',
        '-g', $consumerHome, "-Dorg.gradle.java.home=$($jdks[$java])",
        "-Pfixture.java=$java", "-Pfixture.javaHome=$($jdks[$java])", "-Pfixture.repository=$repositoryUrl") + $Tasks
    if ($IsWindows) { $lines = & "$distribution/bin/gradle.bat" @arguments 2>&1 }
    else { $lines = & /bin/bash "$distribution/bin/gradle" @arguments 2>&1 }
    $code = $LASTEXITCODE
    $output = $lines | Out-String
    if ($ExpectedFailure) {
        if ($code -eq 0 -or $output -notmatch $ExpectedFailure) {
            Write-Host $output
            throw "Expected consumer failure $ExpectedFailure"
        }
    } elseif ($code -ne 0) {
        Write-Host $output
        throw "Consumer failed on Gradle $gradleVersion / Java $java"
    }
    return $output
}

function Assert-Outcome([string]$Output, [string]$Task, [string]$Outcome) {
    $suffix = if ($Outcome -ceq 'SUCCESS') { '' } else { ' ' + $Outcome }
    if ($Output -notmatch ('(?m)^> Task :' + [regex]::Escape($Task + $suffix) + '\r?$')) {
        Write-Host $Output
        throw "Expected $Task to have outcome $Outcome"
    }
}

try {
    if ($Java17Container -and -not $IsLinux) { throw 'The single-JDK container proof requires Linux' }
    if ($fixture.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Expected the consumer outside the checkout'
    }
    Select-Jdk 21
    $repository = Join-Path $fixture 'tuprel-gradle-plugin-fixture-lts'
    [IO.Directory]::CreateDirectory($repository) | Out-Null
    & "$PSScriptRoot/Verify-StagedGradlePlugin.ps1" -Repository $repository -PublishOnly
    $publication = Get-Hashes $repository
    $cliSource = Join-Path $fixture 'frozen CLI libraries'
    $runtimeSource = Join-Path $fixture 'frozen runtime libraries'
    Copy-Item -LiteralPath "$root/tuprel-cli/build/install/tuprel/lib" -Destination $cliSource -Recurse
    [IO.Directory]::CreateDirectory($runtimeSource) | Out-Null
    foreach ($library in @('tuprel-runtime', 'tuprel-sql')) {
        Copy-Item -LiteralPath "$root/$library/build/libs/$library.jar" -Destination $runtimeSource
    }
    $cliHashes = Get-Hashes $cliSource
    $runtimeHashes = Get-Hashes $runtimeSource
    foreach ($entry in $cliHashes.GetEnumerator()) { Write-Host "CLI $($entry.Key) SHA-256 $($entry.Value)" }
    foreach ($entry in $runtimeHashes.GetEnumerator()) { Write-Host "RUNTIME $($entry.Key) SHA-256 $($entry.Value)" }
    $prefix = Join-Path $repository 'dev/tuprel/staging/tuprel-gradle-plugin/0.0.0-fixture/tuprel-gradle-plugin-0.0.0-fixture'
    $pom = ([xml](Get-Content -LiteralPath "$prefix.pom" -Raw)).project
    if ($pom.groupId -cne 'dev.tuprel.staging' -or $pom.version -cne '0.0.0-fixture' -or
            @($pom.dependencies.dependency | Where-Object { $_ }).Count -ne 0) {
        throw 'Unexpected implementation POM or runtime dependencies'
    }
    $module = Get-Content -LiteralPath "$prefix.module" -Raw | ConvertFrom-Json
    foreach ($name in @('apiElements', 'runtimeElements')) {
        $variant = @($module.variants | Where-Object name -CEQ $name)
        if ($variant.Count -ne 1 -or $variant[0].attributes.'org.gradle.jvm.version' -ne 17) {
            throw "Expected JVM 17 variant $name"
        }
    }
    $zip = [IO.Compression.ZipFile]::OpenRead("$prefix.jar")
    try {
        $classes = @($zip.Entries | Where-Object FullName -CLike '*.class')
        if ($classes.Count -ne 5) { throw 'Unexpected production class inventory' }
        foreach ($entry in $classes) {
            $stream = $entry.Open()
            try {
                $header = [byte[]]::new(8)
                $stream.ReadExactly($header)
                if ([Convert]::ToHexString($header, 0, 4) -cne 'CAFEBABE' -or
                        $header[6] * 256 + $header[7] -ne 61) { throw 'Expected major 61 production classfiles' }
            } finally { $stream.Dispose() }
        }
        $descriptor = [IO.StreamReader]::new($zip.GetEntry('META-INF/gradle-plugins/dev.tuprel.codegen.properties').Open())
        try {
            if ($descriptor.ReadToEnd().Trim() -cne 'implementation-class=dev.tuprel.gradle.TuprelCodegenPlugin') {
                throw 'Unexpected plugin descriptor'
            }
        } finally { $descriptor.Dispose() }
    } finally { $zip.Dispose() }
    foreach ($entry in $publication.GetEnumerator() | Where-Object Key -Match '\.(jar|pom|module)$') {
        if ((Get-Content -LiteralPath (Join-Path $repository "$($entry.Key).sha256") -Raw).Trim() -ine $entry.Value) {
            throw 'Published sidecar SHA-256 differs'
        }
        Write-Host "ARTIFACT $($entry.Key) SHA-256 $($entry.Value)"
    }
    $repositoryUrl = [uri]::new($repository + [IO.Path]::DirectorySeparatorChar).AbsoluteUri
    $initialSources = $null
    $updatedSources = $null
    foreach ($gradleVersion in $GradleVersions) {
        $archive = Join-Path $ArchiveDirectory "gradle-$gradleVersion-bin.zip"
        if (-not (Test-Path -LiteralPath $archive)) {
            $download = "$archive.$([guid]::NewGuid()).partial"
            Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip" -OutFile $download -TimeoutSec 180
            if ((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash -ine $distributionHashes[$gradleVersion]) {
                throw 'Downloaded Gradle distribution failed SHA-256 verification'
            }
            Move-Item -LiteralPath $download -Destination $archive
        }
        if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ine $distributionHashes[$gradleVersion]) {
            throw 'Cached Gradle distribution failed SHA-256 verification'
        }
        Write-Host "DISTRIBUTION Gradle $gradleVersion SHA-256 $($distributionHashes[$gradleVersion])"
        $unpack = Join-Path $fixture "distribution-$gradleVersion"
        [IO.Compression.ZipFile]::ExtractToDirectory($archive, $unpack)
        $distribution = Join-Path $unpack "gradle-$gradleVersion"
        foreach ($java in $JavaVersions) {
            Select-Jdk $java
            $project = Join-Path $fixture "external consumer $gradleVersion Java $java"
            New-Consumer $project $java $repository
            $consumerHome = Join-Path $fixture "gradle-home-$gradleVersion-$java"
            Push-Location -LiteralPath $project
            try {
                $tasks = @('verifyEnvironment', 'tuprelValidate', 'runConsumer')
                $first = Invoke-Consumer $tasks
                Assert-Outcome $first 'tuprelValidate' 'SUCCESS'
                Assert-Outcome $first 'tuprelGenerate' 'SUCCESS'
                Assert-Outcome $first 'compileJava' 'SUCCESS'
                if ($first -notmatch "REPORT daemon Java $java at " -or
                        $first -notmatch "Tuprel CLI JVM: Java $java at " -or
                        $first -notmatch 'REPORT compiler Java 17 at ' -or
                        $first -notmatch "Generated Java 17 consumer passed on Java $java") {
                    Write-Host $first
                    throw 'The daemon, compiler, CLI or consumer JVM proof is missing'
                }
                $sources = Join-Path $project 'build/generated/sources/tuprel/main'
                $hashes = Get-Hashes $sources
                if ($null -eq $initialSources) {
                    $initialSources = $hashes
                    foreach ($entry in $hashes.GetEnumerator()) { Write-Host "SOURCE $($entry.Key) SHA-256 $($entry.Value)" }
                }
                Assert-Hashes $initialSources $hashes 'generated sources'
                $second = Invoke-Consumer $tasks
                Assert-Outcome $second 'tuprelGenerate' 'UP-TO-DATE'
                if ($second -notmatch 'Reusing configuration cache') { throw 'Configuration cache was not reused' }

                $extra = [IO.Compression.ZipFile]::Open((Join-Path $project 'installed CLI/lib/extra.jar'), [IO.Compression.ZipArchiveMode]::Create)
                $extra.Dispose()
                Assert-Outcome (Invoke-Consumer $tasks) 'tuprelGenerate' 'SUCCESS'
                $schema = Join-Path $project 'tuprel/schema.tuprel'
                [IO.File]::WriteAllText($schema, [IO.File]::ReadAllText($schema).Replace('  name String', "  name String`n  note String?"))
                Assert-Outcome (Invoke-Consumer $tasks) 'tuprelGenerate' 'SUCCESS'
                $changed = Get-Hashes $sources
                if ($null -eq $updatedSources) { $updatedSources = $changed }
                Assert-Hashes $updatedSources $changed 'updated generated sources'
                Assert-Outcome (Invoke-Consumer (@('clean') + $tasks)) 'tuprelGenerate' 'SUCCESS'
                $explicit = Invoke-Consumer @('tuprelValidate', '-Pfixture.toolchain=17')
                if ($explicit -notmatch 'Tuprel CLI JVM: Java 17 at ') { throw 'Explicit Java 17 toolchain was ignored' }

                $owned = Join-Path $sources 'consumer/generated/TuprelClient.java'
                $ownedBytes = [IO.File]::ReadAllBytes($owned)
                [IO.File]::AppendAllText($owned, "// user edit`n")
                $edited = (Get-FileHash -LiteralPath $owned -Algorithm SHA256).Hash
                $null = Invoke-Consumer @('tuprelGenerate') 'TUPREL-CLI-GENERATE'
                if ((Get-FileHash -LiteralPath $owned -Algorithm SHA256).Hash -cne $edited) {
                    throw 'Generation replaced a user edit'
                }
                [IO.File]::WriteAllBytes($owned, $ownedBytes)
                $manifest = Join-Path $sources '.tuprel-generated'
                $ownership = [IO.File]::ReadAllBytes($manifest)
                $outside = Join-Path (Split-Path -Parent $sources) 'outside.java'
                [IO.File]::WriteAllText($outside, 'keep outside')
                $outsideHash = (Get-FileHash -LiteralPath $outside -Algorithm SHA256).Hash
                [IO.File]::WriteAllText($manifest, [Text.Encoding]::UTF8.GetString($ownership) +
                    "../outside.java`t$($outsideHash.ToLowerInvariant())`n")
                $null = Invoke-Consumer @('tuprelGenerate') 'TUPREL-CLI-GENERATE'
                if (-not (Test-Path -LiteralPath $outside) -or [IO.File]::ReadAllText($outside) -cne 'keep outside') {
                    throw 'A manifest escaped the output directory'
                }
                [IO.File]::WriteAllBytes($manifest, $ownership)
                [IO.File]::Delete($manifest)
                $null = Invoke-Consumer @('tuprelGenerate') 'TUPREL-CLI-GENERATE'
                if (Test-Path -LiteralPath $manifest) { throw 'Generation claimed ownership of foreign files' }
                Assert-Hashes $publication (Get-Hashes $repository) 'staged publication'
                Write-Host "PASS Gradle $gradleVersion Java $java; daemon/CLI $java, compiler 17; marker, incremental, configuration/build cache, schema/classpath edits, clean and ownership"
                $first -split "`n" | Where-Object { $_ -match '^REPORT |^Tuprel CLI JVM:|^Generated Java 17 consumer passed' } | Write-Host
            } finally { Pop-Location }
        }
    }
    foreach ($kind in @('jar', 'marker')) {
        $damaged = Join-Path $fixture "tampered-$kind"
        Copy-Item -LiteralPath $repository -Destination $damaged -Recurse
        $relative = if ($kind -ceq 'jar') {
            'dev/tuprel/staging/tuprel-gradle-plugin/0.0.0-fixture/tuprel-gradle-plugin-0.0.0-fixture.jar'
        } else {
            'dev/tuprel/codegen/dev.tuprel.codegen.gradle.plugin/0.0.0-fixture/dev.tuprel.codegen.gradle.plugin-0.0.0-fixture.pom'
        }
        $project = Join-Path $fixture "tampered $kind consumer"
        $java = 17
        Select-Jdk $java
        New-Consumer $project $java $damaged
        $change = if ($kind -ceq 'marker') { '<!-- tampered fixture -->' } else { 'tampered fixture' }
        [IO.File]::AppendAllText((Join-Path $damaged $relative), $change)
        $repositoryUrl = [uri]::new($damaged + [IO.Path]::DirectorySeparatorChar).AbsoluteUri
        $consumerHome = Join-Path $fixture "negative-home-$kind"
        Push-Location -LiteralPath $project
        try {
            $null = Invoke-Consumer @('tuprelGenerate') 'Dependency verification failed'
            if (Test-Path -LiteralPath 'build/generated/sources/tuprel/main') {
                throw 'A tampered artifact reached generation'
            }
        } finally { Pop-Location }
        Write-Host "PASS rejected tampered $kind before generation under dependency verification strict"
    }
    $repositoryUrl = [uri]::new($repository + [IO.Path]::DirectorySeparatorChar).AbsoluteUri
    if ($Java17Container) {
        # Official Temurin 17 index digest, independently obtained from registry-1.docker.io.
        $image = 'eclipse-temurin:17-jdk@sha256:7a0b2bc7e1372bb05ee361fb79e8a9e822df47343b8716253b3bbaa75060653e'
        & docker pull --platform linux/amd64 $image
        if ($LASTEXITCODE -ne 0) { throw 'Cannot obtain the pinned Java 17 test image' }
        $fixtureUid = & id -u
        if ($LASTEXITCODE -ne 0 -or $fixtureUid -notmatch '^[1-9][0-9]*$') { throw 'Expected an unprivileged test UID' }
        $fixtureGid = & id -g
        if ($LASTEXITCODE -ne 0 -or $fixtureGid -notmatch '^[0-9]+$') { throw 'Cannot identify the test GID' }
        foreach ($gradleVersion in $GradleVersions) {
            $project = Join-Path $fixture "single JDK17 consumer $gradleVersion"
            New-Consumer $project 17 $repository
            [IO.File]::WriteAllText((Join-Path $project 'gradle.properties'), @'
org.gradle.java.installations.auto-detect=false
org.gradle.java.installations.auto-download=false
org.gradle.java.installations.paths=/opt/java/openjdk
org.gradle.workers.max=1
org.gradle.jvmargs=-Xms64m -Xmx384m -XX:MaxMetaspaceSize=256m
'@)
            $consumerHome = Join-Path $fixture "single-jdk-home-$gradleVersion"
            [IO.Directory]::CreateDirectory($consumerHome) | Out-Null
            $command = @('run', '--rm', '--platform', 'linux/amd64', '--network', 'none', '--read-only',
                '--cap-drop=ALL', '--security-opt=no-new-privileges', '--user', "${fixtureUid}:${fixtureGid}", '--tmpfs', '/tmp',
                '--mount', "type=bind,source=$fixture,target=$fixture", '--workdir', $project, '--env', "HOME=$consumerHome",
                $image, '/bin/bash', "$fixture/distribution-$gradleVersion/gradle-$gradleVersion/bin/gradle",
                '--offline', '--no-daemon', '--console=plain', '--info', '--dependency-verification', 'strict',
                '-g', $consumerHome, '-Dorg.gradle.java.home=/opt/java/openjdk', '-Pfixture.java=17',
                '-Pfixture.javaHome=/opt/java/openjdk', "-Pfixture.repository=$repositoryUrl",
                'verifyEnvironment', 'tuprelValidate', 'runConsumer')
            $output = (& docker @command 2>&1 | Out-String)
            if ($LASTEXITCODE -ne 0 -or $output -notmatch 'REPORT daemon Java 17 at ' -or
                    $output -notmatch 'Tuprel CLI JVM: Java 17 at /opt/java/openjdk/' -or
                    $output -notmatch 'REPORT compiler Java 17 at ' -or
                    $output -notmatch 'Generated Java 17 consumer passed on Java 17') {
                Write-Host $output
                throw 'The single-JDK17 consumer proof failed'
            }
            Assert-Hashes $initialSources (Get-Hashes (Join-Path $project 'build/generated/sources/tuprel/main')) 'single-JDK17 generated sources'
            Write-Host "PASS Gradle $gradleVersion in pinned Java17-only container; no network, no host JDK mounted, unprivileged UID $fixtureUid"
        }
    }
    Assert-Hashes $publication (Get-Hashes $repository) 'final staged publication'
    Assert-Hashes $cliHashes (Get-Hashes $cliSource) 'frozen CLI libraries'
    Assert-Hashes $runtimeHashes (Get-Hashes $runtimeSource) 'frozen runtime libraries'
    Write-Host "Completed $($GradleVersions.Count * $JavaVersions.Count) external Gradle/JDK cells"
} finally {
    foreach ($entry in $environment.GetEnumerator()) {
        if ($null -eq $entry.Value) { Remove-Item "Env:$($entry.Key)" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value) }
    }
    $resolved = (Resolve-Path -LiteralPath $fixture).Path
    if ($resolved -cne [IO.Path]::GetFullPath($fixture) -or -not (Split-Path -Leaf $resolved).StartsWith('tuprel-gradle-lts-')) {
        throw 'Refused cleanup outside the Gradle LTS fixture'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
$global:LASTEXITCODE = 0
