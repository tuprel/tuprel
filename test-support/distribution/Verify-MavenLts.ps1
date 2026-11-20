param(
    [Parameter(Mandatory)][string]$Maven,
    [Parameter(Mandatory)][string]$Jdk17,
    [Parameter(Mandatory)][string]$Jdk21,
    [Parameter(Mandatory)][string]$Jdk25,
    [string]$Gpg,
    [switch]$Postgresql
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$root = (Resolve-Path -LiteralPath "$PSScriptRoot/../..").Path
$mavenCommand = (Get-Command $Maven -CommandType Application | Select-Object -First 1).Source
$jdks = @{ 17 = $Jdk17; 21 = $Jdk21; 25 = $Jdk25 }
$environment = @{}
foreach ($name in @('JAVA_HOME', 'PATH', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS',
        'MAVEN_OPTS', 'MAVEN_ARGS', 'MAVEN_SKIP_RC', 'CLASSPATH')) {
    $environment[$name] = [Environment]::GetEnvironmentVariable($name)
    if ($name -notin @('JAVA_HOME', 'PATH')) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
}
$env:MAVEN_SKIP_RC = 'true'
$fixture = Join-Path ([IO.Path]::GetTempPath()) ("tuprel-maven-lts-" + [guid]::NewGuid())
[IO.Directory]::CreateDirectory($fixture) | Out-Null

function Select-Jdk([int]$Version) {
    $env:JAVA_HOME = (Resolve-Path -LiteralPath $jdks[$Version]).Path
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $environment['PATH']
    $java = Join-Path $env:JAVA_HOME $(if ($IsWindows) { 'bin/java.exe' } else { 'bin/java' })
    $javac = Join-Path $env:JAVA_HOME $(if ($IsWindows) { 'bin/javac.exe' } else { 'bin/javac' })
    $javaVersion = (& $java -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch ('version "' + $Version + '[."]')) {
        throw "Expected a real Java $Version executable"
    }
    $javacVersion = (& $javac -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $javacVersion -notmatch ('javac ' + $Version + '[.\s]')) {
        throw "Expected a real javac $Version executable"
    }
    $mavenVersion = (& $mavenCommand --version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $mavenVersion -notmatch 'Apache Maven 3\.9\.16\b' -or
            $mavenVersion -notmatch ("Java version: $Version" + '[.,]')) {
        throw "Expected Maven 3.9.16 on Java $Version"
    }
    Write-Host $mavenVersion.Trim()
}

function Get-Hashes([string]$Directory, [string]$Filter = '*') {
    $Directory = (Resolve-Path -LiteralPath $Directory).Path
    $hashes = [ordered]@{}
    foreach ($file in Get-ChildItem -LiteralPath $Directory -File -Recurse -Filter $Filter | Sort-Object FullName) {
        $relative = [IO.Path]::GetRelativePath($Directory, $file.FullName).Replace('\', '/')
        $hashes[$relative] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    }
    if ($hashes.Count -eq 0) { throw 'Expected a nonempty file inventory' }
    return $hashes
}

function Assert-Hashes($Expected, $Actual, [string]$Description) {
    if ($Actual.Count -ne $Expected.Count) { throw "Unexpected file inventory for $Description" }
    foreach ($entry in $Expected.GetEnumerator()) {
        if ($Actual[$entry.Key] -cne $entry.Value) { throw "SHA-256 mismatch for $Description at $($entry.Key)" }
    }
}

function Test-StagedCache([string]$Cache) {
    foreach ($entry in $stagedJars.GetEnumerator()) {
        $file = Join-Path $Cache $entry.Key
        if (-not (Test-Path -LiteralPath $file -PathType Leaf) -or
                (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -cne $entry.Value) { return $false }
    }
    return $true
}

function Assert-ClassVersion([IO.Stream]$Stream, [string]$Name, [int]$Major) {
    $header = [byte[]]::new(8)
    $Stream.ReadExactly($header)
    if ([Convert]::ToHexString($header, 0, 4) -cne 'CAFEBABE' -or
            $header[6] * 256 + $header[7] -ne $Major) {
        throw "Expected classfile major $Major for $Name"
    }
}

function Invoke-Maven([string[]]$Goals) {
    & $mavenCommand @mavenArgs @Goals
    if ($LASTEXITCODE -ne 0) { throw "Maven failed for $($Goals -join ' ')" }
}

function Assert-MavenFailure([string[]]$Goals, [string]$Pattern) {
    $output = (& $mavenCommand @mavenArgs @Goals 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or $output -notmatch $Pattern) {
        throw "Maven did not report the expected failure for $($Goals -join ' ')"
    }
}

function Invoke-Launcher([string[]]$Command, [int]$ExpectedExit = 0) {
    if ($IsWindows) { & $launcher @Command }
    else { & /bin/bash $launcher @Command }
    if ($LASTEXITCODE -ne $ExpectedExit) { throw "Unexpected launcher exit for $($Command -join ' ')" }
}

try {
    if ($fixture.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Expected fixtures outside the checkout'
    }
    Select-Jdk 21
    $repository = Join-Path $fixture 'tuprel-maven-fixture-lts'
    [IO.Directory]::CreateDirectory($repository) | Out-Null
    & "$PSScriptRoot/Verify-StagedMaven.ps1" -Repository $repository -IncludeCli -Gpg $Gpg
    $publication = Get-Hashes $repository
    $stagedJars = [ordered]@{}
    foreach ($library in @('tuprel-sql', 'tuprel-runtime', 'tuprel-postgresql')) {
        $relative = "dev/tuprel/staging/$library/0.0.0-fixture/$library-0.0.0-fixture"
        foreach ($extension in @('jar', 'pom', 'module')) {
            $file = Join-Path $repository "$relative.$extension"
            if ((Get-Content -LiteralPath "$file.sha256" -Raw).Trim() -ine $publication["$relative.$extension"]) {
                throw "Staged $extension checksum differs for $library"
            }
        }
        $module = Get-Content -LiteralPath (Join-Path $repository "$relative.module") -Raw | ConvertFrom-Json
        if ($module.component.group -cne 'dev.tuprel.staging' -or $module.component.module -cne $library -or
                $module.component.version -cne '0.0.0-fixture') { throw "Unexpected module coordinates for $library" }
        foreach ($name in @('apiElements', 'runtimeElements')) {
            $variant = @($module.variants | Where-Object name -CEQ $name)
            if ($variant.Count -ne 1 -or $variant[0].attributes.'org.gradle.jvm.version' -ne 17 -or
                    $variant[0].files.Count -ne 1 -or $variant[0].files[0].sha256 -ine $publication["$relative.jar"]) {
                throw "Unexpected Gradle variant $name for $library"
            }
        }
        $stagedJars["$relative.jar"] = $publication["$relative.jar"]
        $archive = [IO.Compression.ZipFile]::OpenRead((Join-Path $repository "$relative.jar"))
        try {
            $classes = @($archive.Entries | Where-Object FullName -CLike '*.class')
            if ($classes.Count -eq 0) { throw "No production classes for $library" }
            foreach ($entry in $classes) {
                $stream = $entry.Open()
                try { Assert-ClassVersion $stream $entry.FullName 61 } finally { $stream.Dispose() }
            }
            Write-Host "Staged $library, $($classes.Count) production classes, major 61, SHA-256 $($stagedJars["$relative.jar"])"
        } finally { $archive.Dispose() }
    }
    $zip = Join-Path $root 'tuprel-cli/build/distributions/tuprel.zip'
    Write-Host "CLI ZIP SHA-256 $((Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash)"
    $installation = Join-Path $fixture 'installed CLI'
    [IO.Compression.ZipFile]::ExtractToDirectory($zip, $installation)
    $launcher = Join-Path $installation $(if ($IsWindows) { 'tuprel/bin/tuprel.bat' } else { 'tuprel/bin/tuprel' })
    $repositoryUrl = [uri]::new($repository + [IO.Path]::DirectorySeparatorChar).AbsoluteUri
    $settings = Join-Path $fixture 'settings.xml'
    [IO.File]::WriteAllText($settings, '<settings/>')
    $metadata = [xml](Get-Content -LiteralPath "$root/gradle/verification-metadata.xml" -Raw)
    $byVersion = @{}
    $firstGenerated = $null
    foreach ($version in @(17, 21, 25)) {
        Select-Jdk $version
        $project = Join-Path $fixture "external Maven consumer $version"
        Copy-Item -LiteralPath "$PSScriptRoot/maven-consumer" -Destination $project -Recurse
        $cache = Join-Path $fixture "maven-cache-$version"
        $mavenArgs = @('--batch-mode', '--no-transfer-progress', '--strict-checksums', '--settings', $settings,
            '--global-settings', $settings, "-Dmaven.repo.local=$cache", '-Dfixture.version=0.0.0-fixture',
            "-Dfixture.repository=$repositoryUrl")
        $byVersion[$version] = @{ Project = $project; Args = $mavenArgs; Cache = $cache }
        Push-Location -LiteralPath $project
        try {
            Invoke-Launcher @('--version')
            Invoke-Launcher @('init')
            Invoke-Launcher @('generate')
            Invoke-Launcher @('generate', '--check')
            $generated = Join-Path $project 'build/generated/sources/tuprel/main'
            $hashes = Get-Hashes $generated '*.java'
            if ($null -eq $firstGenerated) { $firstGenerated = $hashes }
            Assert-Hashes $firstGenerated $hashes "generated sources on Java $version"
            Invoke-Maven @('org.apache.maven.plugins:maven-dependency-plugin:3.9.0:resolve')
            if (-not (Test-StagedCache $cache)) { throw "Resolved staging artifact differs on Java $version" }
            $transitives = @()
            $runtime = @($stagedJars.Keys | ForEach-Object { Join-Path $cache $_ })
            foreach ($dependency in @(@('org.postgresql', 'postgresql'), @('org.checkerframework', 'checker-qual'))) {
                $group, $artifact = $dependency
                $componentPath = Join-Path $cache "$($group.Replace('.', '/'))/$artifact"
                $jar = @(Get-ChildItem -LiteralPath $componentPath -Recurse -File -Filter '*.jar')
                if ($jar.Count -ne 1) { throw "Expected one resolved JAR for $artifact" }
                $componentVersion = $jar[0].Directory.Name
                $verified = @($metadata.'verification-metadata'.components.component | Where-Object {
                    $_.group -ceq $group -and $_.name -ceq $artifact -and $_.version -ceq $componentVersion
                } | ForEach-Object artifact | Where-Object name -CEQ $jar[0].Name | ForEach-Object sha256 |
                    ForEach-Object value)
                $actual = (Get-FileHash -LiteralPath $jar[0].FullName -Algorithm SHA256).Hash
                if ($actual -notin $verified) { throw "Unverified resolved JAR for $artifact" }
                $runtime += $jar[0].FullName
                if ($artifact -ceq 'postgresql') { $byVersion[$version].Driver = $componentVersion }
                $transitives += "${artifact}:$componentVersion SHA-256 $actual"
            }
            Write-Host ($transitives -join [Environment]::NewLine)
            Invoke-Maven @('clean', 'verify')
            foreach ($file in Get-ChildItem -LiteralPath 'target/classes' -File -Recurse -Filter '*.class') {
                $stream = $file.OpenRead()
                try { Assert-ClassVersion $stream $file.Name 61 } finally { $stream.Dispose() }
            }
            Invoke-Maven @('exec:java')
            $byVersion[$version].Runtime = $runtime
            Assert-Hashes $publication (Get-Hashes $repository) 'staging publication'
            Write-Host "Maven/JDK $version passed with $($hashes.Count) byte-identical generated sources"
        } finally { Pop-Location }
    }

    if ($Postgresql) {
        foreach ($version in @(17, 21, 25)) {
            Select-Jdk $version
            $cell = $byVersion[$version]
            $project = Join-Path $fixture "external PostgreSQL consumer $version"
            $sources = Join-Path $project 'src/test/java'
            [IO.Directory]::CreateDirectory($sources) | Out-Null
            Copy-Item -LiteralPath (Join-Path $cell.Project 'pom.xml') -Destination $project
            Copy-Item -LiteralPath "$root/examples/staged-consumer/tuprel" -Destination $project -Recurse
            Copy-Item -LiteralPath "$root/examples/staged-consumer/src/Consumer.java" -Destination $sources
            Copy-Item -LiteralPath "$PSScriptRoot/maven-postgresql/MavenPostgresqlFixture.java" -Destination $sources
            foreach ($helper in @('LocalPostgresqlTarget.java', 'LocalTestEnvironment.java')) {
                Copy-Item -LiteralPath "$root/test-support/src/main/java/dev/tuprel/testsupport/$helper" -Destination $sources
            }
            $mavenArgs = $cell.Args
            Push-Location -LiteralPath $project
            try {
                Invoke-Launcher @('generate')
                Invoke-Maven @('--offline', '-Ppostgresql-proof', "-Dfixture.postgresql.version=$($cell.Driver)", 'verify')
                $classpath = (@((Join-Path $project 'target/classes'), (Join-Path $project 'target/test-classes')) +
                    $cell.Runtime) -join [IO.Path]::PathSeparator
                $java = Join-Path $env:JAVA_HOME $(if ($IsWindows) { 'bin/java.exe' } else { 'bin/java' })
                & $java -cp $classpath fixture.consumer.MavenPostgresqlFixture "$root/examples/tuprel-playground/.env"
                if ($LASTEXITCODE -ne 0) { throw "PostgreSQL fixture failed on Java $version" }
            } finally { Pop-Location }
        }
    }

    foreach ($compileJdk in @(17, 21)) {
        Select-Jdk $compileJdk
        $cell = $byVersion[$compileJdk]
        $mavenArgs = $cell.Args
        $binary = Join-Path $fixture "consumer-binary-$compileJdk"
        Push-Location -LiteralPath $cell.Project
        try {
            if ($compileJdk -eq 21) { Invoke-Maven @('-Dmaven.compiler.release=21', 'clean', 'verify') }
            Copy-Item -LiteralPath 'target/classes' -Destination $binary -Recurse
        } finally { Pop-Location }
        $original = Get-Hashes $binary '*.class'
        foreach ($runJdk in @(17, 21, 25)) {
            Select-Jdk $runJdk
            $cell = $byVersion[$runJdk]
            $replay = Join-Path $fixture "replay $compileJdk on $runJdk"
            [IO.Directory]::CreateDirectory((Join-Path $replay 'target')) | Out-Null
            Copy-Item -LiteralPath (Join-Path $cell.Project 'pom.xml') -Destination $replay
            Copy-Item -LiteralPath $binary -Destination (Join-Path $replay 'target/classes') -Recurse
            $mavenArgs = $cell.Args
            Push-Location -LiteralPath $replay
            try {
                if ($compileJdk -eq 21 -and $runJdk -eq 17) {
                    Assert-MavenFailure @('--offline', 'exec:java') 'class file version 65\.0'
                } else {
                    Invoke-Maven @('--offline', 'exec:java')
                    Write-Host "Consumer compiled on Java $compileJdk ran without recompilation on Java $runJdk"
                }
                Assert-Hashes $original (Get-Hashes 'target/classes' '*.class') 'replayed consumer bytecode'
            } finally { Pop-Location }
        }
    }

    Select-Jdk 17
    $cell = $byVersion[17]
    $mavenArgs = $cell.Args
    Push-Location -LiteralPath $cell.Project
    try {
        Assert-MavenFailure @('--offline', '-Dfixture.version=0.0.9-missing', 'exec:java') 'tuprel-postgresql:jar:0\.0\.9-missing'
        $damaged = Join-Path $fixture 'damaged staging'
        Copy-Item -LiteralPath $repository -Destination $damaged -Recurse
        $jarPath = @($stagedJars.Keys)[-1]
        [IO.File]::AppendAllText((Join-Path $damaged $jarPath), 'tampered')
        $damagedUrl = [uri]::new($damaged + [IO.Path]::DirectorySeparatorChar).AbsoluteUri
        $damagedCache = Join-Path $fixture 'negative cache'
        Copy-Item -LiteralPath $cell.Cache -Destination $damagedCache -Recurse
        [IO.File]::AppendAllText((Join-Path $damagedCache $jarPath), 'cache contamination')
        if (Test-StagedCache $damagedCache) { throw 'Accepted a modified cached staging artifact' }
        $cachedStaging = Join-Path $damagedCache 'dev/tuprel/staging'
        if (-not $cachedStaging.StartsWith($fixture + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Refused to remove artifacts outside the generated negative cache'
        }
        Remove-Item -LiteralPath $cachedStaging -Recurse -Force
        Assert-MavenFailure @("-Dmaven.repo.local=$damagedCache", "-Dfixture.repository=$damagedUrl", 'verify') 'Checksum validation failed'
        $generated = Get-ChildItem -LiteralPath 'build/generated/sources/tuprel/main' -File -Recurse -Filter '*.java' |
            Select-Object -First 1
        [IO.File]::AppendAllText($generated.FullName, "// user edit`n")
        $edited = (Get-FileHash -LiteralPath $generated.FullName -Algorithm SHA256).Hash
        Invoke-Launcher @('generate', '--check') 1
        Invoke-Launcher @('generate') 2
        if ((Get-FileHash -LiteralPath $generated.FullName -Algorithm SHA256).Hash -cne $edited) {
            throw 'Generation overwrote a manually edited source'
        }
        Write-Host 'Missing Maven version, invalid artifact checksum, contaminated cache, incompatible bytecode and manual generated-source edit rejected'
    } finally { Pop-Location }
} finally {
    foreach ($entry in $environment.GetEnumerator()) {
        if ($null -eq $entry.Value) { Remove-Item "Env:$($entry.Key)" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value) }
    }
    $resolved = (Resolve-Path -LiteralPath $fixture).Path
    if ($resolved -cne [IO.Path]::GetFullPath($fixture) -or -not (Split-Path -Leaf $resolved).StartsWith('tuprel-maven-lts-')) {
        throw 'Refused cleanup outside the Maven LTS fixture'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

# Expected native failures are asserted above. Report success only after cleanup.
$global:LASTEXITCODE = 0
