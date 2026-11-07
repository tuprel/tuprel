param([string]$Maven = $(if ($IsWindows) { 'mvn.cmd' } else { 'mvn' }))

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
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

    Push-Location -LiteralPath $project
    try {
        & $launcher init
        if ($LASTEXITCODE -ne 0) { throw 'External project initialization failed' }
    } finally { Pop-Location }

    foreach ($version in @('0.0.0-fixture', '0.0.1-fixture')) {
        $repository = Join-Path $fixture "tuprel-maven-fixture-$version"
        [IO.Directory]::CreateDirectory($repository) | Out-Null
        Push-Location -LiteralPath $root
        try {
            & $gradle :tuprel-postgresql:publishFixturePublicationToFixtureRepository :tuprel-runtime:publishFixturePublicationToFixtureRepository :tuprel-sql:publishFixturePublicationToFixtureRepository --no-configuration-cache --dependency-verification strict -I "$PSScriptRoot/staged-maven.init.gradle" "-Dtuprel.test.maven.repository=$repository" "-Dtuprel.test.maven.version=$version"
            if ($LASTEXITCODE -ne 0) { throw 'Maven repository staging failed' }
        } finally { Pop-Location }

        Push-Location -LiteralPath $project
        try {
            if ($version -eq '0.0.1-fixture') {
                $schema = [IO.File]::ReadAllText((Join-Path $project 'tuprel/schema.tuprel'))
                $updated = $schema.Replace('  email String', "  displayName String?`n  email String")
                if ($updated -ceq $schema) { throw 'The fixture schema did not change' }
                [IO.File]::WriteAllText((Join-Path $project 'tuprel/schema.tuprel'), $updated)
            }
            & $launcher generate
            if ($LASTEXITCODE -ne 0) { throw 'Java generation failed' }
            $repositoryUrl = [uri]::new($repository + [IO.Path]::DirectorySeparatorChar).AbsoluteUri
            & $mavenCommand --batch-mode --no-transfer-progress --strict-checksums --settings $settings --global-settings $settings "-Dmaven.repo.local=$localRepository" "-Dfixture.version=$version" "-Dfixture.repository=$repositoryUrl" clean compile exec:java
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
