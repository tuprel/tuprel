param(
    [string]$Maven = $(if ($IsWindows) { 'mvn.cmd' } else { 'mvn' }),
    [string]$PreviousCheckout,
    [string]$ReplayJdk25
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$previous = $null
if ($ReplayJdk25 -and -not $PreviousCheckout) { throw 'A replay needs the pinned previous checkout' }
if ($PreviousCheckout) {
    $previous = (Resolve-Path -LiteralPath $PreviousCheckout).Path
    $revision = git -C $previous rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $revision -cne '2b9ef01baa96633efc630cae1d288c061a94256b') {
        throw 'Expected the pinned pre-fix Java runtime revision'
    }
    git -C $previous diff --quiet HEAD --
    if ($LASTEXITCODE -ne 0) { throw 'The pinned checkout has tracked edits' }
}
$mavenCommand = (Get-Command $Maven -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$fixture = Join-Path ([IO.Path]::GetTempPath()) ("tuprel-maven-consumer-" + [guid]::NewGuid())
[IO.Directory]::CreateDirectory($fixture) | Out-Null
try {
    $project = Join-Path $fixture 'external consumer'
    Copy-Item -LiteralPath "$PSScriptRoot/maven-consumer" -Destination $project -Recurse
    $localRepository = Join-Path $fixture 'maven-cache'
    $settings = Join-Path $fixture 'settings.xml'
    [IO.File]::WriteAllText($settings, '<settings/>')
    $gradle = if ($IsWindows) { Join-Path $root 'gradlew.bat' } else { Join-Path $root 'gradlew' }
    $launcherName = if ($IsWindows) { 'tuprel.bat' } else { 'tuprel' }
    $launcher = Join-Path $root "tuprel-cli/build/install/tuprel/bin/$launcherName"

    Push-Location -LiteralPath $root
    try {
        & $gradle :tuprel-cli:installDist --dependency-verification strict
        if ($LASTEXITCODE -ne 0) { throw 'CLI staging failed' }
    } finally { Pop-Location }

    $previousLauncher = $launcher
    if ($previous) {
        $previousGradle = Join-Path $previous (Split-Path -Leaf $gradle)
        Push-Location -LiteralPath $previous
        try {
            & $previousGradle :tuprel-cli:installDist --dependency-verification strict
            if ($LASTEXITCODE -ne 0) { throw 'Pinned CLI staging failed' }
        } finally { Pop-Location }
        $previousLauncher = Join-Path $previous "tuprel-cli/build/install/tuprel/bin/$launcherName"
    }

    Push-Location -LiteralPath $project
    try {
        & $launcher init
        if ($LASTEXITCODE -ne 0) { throw 'External project initialization failed' }
    } finally { Pop-Location }

    foreach ($version in @('0.0.0-fixture', '0.0.1-fixture')) {
        $repository = Join-Path $fixture "tuprel-maven-fixture-$version"
        [IO.Directory]::CreateDirectory($repository) | Out-Null
        $isOld = $version -eq '0.0.0-fixture'
        $revisionRoot = if ($previous -and $isOld) { $previous } else { $root }
        $revisionGradle = Join-Path $revisionRoot (Split-Path -Leaf $gradle)
        Push-Location -LiteralPath $revisionRoot
        try {
            & $revisionGradle :tuprel-postgresql:publishFixturePublicationToFixtureRepository :tuprel-runtime:publishFixturePublicationToFixtureRepository :tuprel-sql:publishFixturePublicationToFixtureRepository --no-configuration-cache --dependency-verification strict -I "$PSScriptRoot/staged-maven.init.gradle" "-Dtuprel.test.maven.repository=$repository" "-Dtuprel.test.maven.version=$version"
            if ($LASTEXITCODE -ne 0) { throw 'Maven repository staging failed' }
        } finally { Pop-Location }

        $repositoryUrl = [uri]::new($repository + [IO.Path]::DirectorySeparatorChar).AbsoluteUri
        $arguments = @('--batch-mode', '--no-transfer-progress', '--strict-checksums',
            '--settings', $settings, '--global-settings', $settings,
            "-Dmaven.repo.local=$localRepository", "-Dfixture.version=$version", "-Dfixture.repository=$repositoryUrl")
        if ($previous) { $arguments += '-Dmaven.compiler.release=21' }
        Push-Location -LiteralPath $project
        try {
            if ($previous -and -not $isOld) {
                & $mavenCommand @arguments exec:java
                if ($LASTEXITCODE -ne 0) { throw 'The old compiled consumer could not link against the new runtime' }
                if ($ReplayJdk25) {
                    $compiled = @(Get-ChildItem -LiteralPath 'target/classes' -File -Recurse -Filter '*.class')
                    if ($compiled.Count -eq 0) { throw 'Missing compiled legacy fixture' }
                    $before = ($compiled | Get-FileHash -Algorithm SHA256 | ForEach-Object Hash) -join ','
                    $buildJava = $env:JAVA_HOME
                    $buildPath = $env:PATH
                    try {
                        $env:JAVA_HOME = (Resolve-Path -LiteralPath $ReplayJdk25).Path
                        $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $buildPath
                        $reported = (& $mavenCommand --version | Out-String)
                        if ($LASTEXITCODE -ne 0 -or $reported -notmatch 'Java version: 25[.,]') {
                            throw 'Expected Maven to run on Java 25 for the pinned binary replay'
                        }
                        & $mavenCommand @arguments exec:java
                        if ($LASTEXITCODE -ne 0) { throw 'The pinned Java 21 binary could not link on Java 25' }
                        $after = ($compiled | Get-FileHash -Algorithm SHA256 | ForEach-Object Hash) -join ','
                        if ($after -cne $before) { throw 'Pinned consumer classes changed during the replay' }
                        Write-Output 'Pinned Java 21 Maven consumer linked on Java 21 and 25 without recompilation'
                    } finally {
                        $env:JAVA_HOME = $buildJava
                        $env:PATH = $buildPath
                    }
                }
                & $mavenCommand @arguments clean compile exec:java
                if ($LASTEXITCODE -ne 0) { throw 'The old generated sources could not compile against the new runtime' }
            }
            if (-not $isOld) {
                $schema = [IO.File]::ReadAllText((Join-Path $project 'tuprel/schema.tuprel'))
                $updated = $schema.Replace('  email String', "  displayName String?`n  email String")
                if ($updated -ceq $schema) { throw 'The fixture schema did not change' }
                [IO.File]::WriteAllText((Join-Path $project 'tuprel/schema.tuprel'), $updated)
            }
            $generator = if ($isOld) { $previousLauncher } else { $launcher }
            & $generator generate 'tuprel/schema.tuprel'
            if ($LASTEXITCODE -ne 0) { throw 'Java generation failed' }
            & $mavenCommand @arguments clean verify exec:java
            if ($LASTEXITCODE -ne 0) { throw 'External Maven consumer failed' }
            foreach ($library in @('tuprel-sql', 'tuprel-runtime', 'tuprel-postgresql')) {
                if (-not (Test-Path -LiteralPath "$localRepository/dev/tuprel/staging/$library/$version/$library-$version.jar" -PathType Leaf)) {
                    throw "External Maven did not resolve $library $version"
                }
            }
        } finally { Pop-Location }
    }
} finally {
    $resolved = (Resolve-Path -LiteralPath $fixture).Path
    if ($resolved -ne [IO.Path]::GetFullPath($fixture) -or -not (Split-Path $resolved -Leaf).StartsWith('tuprel-maven-consumer-')) {
        throw 'Refused cleanup outside the Maven consumer fixture'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
