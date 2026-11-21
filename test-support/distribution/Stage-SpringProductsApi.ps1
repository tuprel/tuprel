param([Parameter(Mandatory)][string]$Destination)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$fixture = [IO.Path]::GetFullPath($Destination)
$relative = [IO.Path]::GetRelativePath($root, $fixture)
if (($relative -ne '..' -and -not $relative.StartsWith('..' + [IO.Path]::DirectorySeparatorChar) -and
        -not [IO.Path]::IsPathRooted($relative)) -or (Test-Path -LiteralPath $fixture)) {
    throw 'Choose a new staging directory outside the Tuprel checkout'
}
$parent = Get-Item -LiteralPath (Split-Path -Parent $fixture)
for ($directory = $parent; $directory; $directory = $directory.Parent) {
    if ($directory.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'Staging requires an unlinked destination'
    }
}
$repository = Join-Path $fixture 'tuprel-maven-fixture-products'
[void](New-Item -ItemType Directory -Path $fixture)
[IO.Directory]::CreateDirectory($repository) | Out-Null
$gradle = if ($IsWindows) { '.\gradlew.bat' } else { './gradlew' }
Push-Location -LiteralPath $root
try {
    & $gradle :tuprel-cli:installDist :tuprel-sql:publishFixturePublicationToFixtureRepository `
        :tuprel-runtime:publishFixturePublicationToFixtureRepository :tuprel-postgresql:publishFixturePublicationToFixtureRepository `
        :tuprel-spring-boot-starter:publishFixturePublicationToFixtureRepository --no-configuration-cache `
        --dependency-verification strict -I "$PSScriptRoot/staged-maven.init.gradle" `
        "-Dtuprel.test.maven.repository=$repository" '-Dtuprel.test.maven.springConsumer=true' | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Spring consumer artifact staging failed' }
    Copy-Item -LiteralPath 'tuprel-cli/build/install/tuprel' -Destination (Join-Path $fixture 'cli') -Recurse
    $project = Join-Path $fixture 'project'
    [IO.Directory]::CreateDirectory($project) | Out-Null
    foreach ($entry in @('pom.xml','README.md','.gitignore','sql','src','tuprel')) {
        Copy-Item -LiteralPath "examples/spring-products-api/$entry" -Destination $project -Recurse
    }
} finally { Pop-Location }

[pscustomobject]@{
    Project = $project
    CliHome = Join-Path $fixture 'cli'
    Repository = [uri]::new($repository + [IO.Path]::DirectorySeparatorChar).AbsoluteUri
}
