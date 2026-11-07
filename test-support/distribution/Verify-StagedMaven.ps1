$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$repository = Join-Path ([IO.Path]::GetTempPath()) ("tuprel-maven-fixture-" + [guid]::NewGuid())
[IO.Directory]::CreateDirectory($repository) | Out-Null
Push-Location -LiteralPath $root
try {
    $gradle = if ($IsWindows) { '.\gradlew.bat' } else { './gradlew' }
    $libraries = @('tuprel-sql', 'tuprel-runtime', 'tuprel-postgresql')
    $tasks = $libraries | ForEach-Object { ":${_}:publishFixturePublicationToFixtureRepository" }
    & $gradle @tasks --no-configuration-cache --dependency-verification strict -I "$PSScriptRoot/staged-maven.init.gradle" "-Dtuprel.test.maven.repository=$repository"
    if ($LASTEXITCODE -ne 0) { throw 'Local Maven staging failed' }

    $coordinates = @{ group = 'dev.tuprel.staging'; version = '0.0.0-fixture' }
    $dependencies = @{
        'tuprel-sql' = @()
        'tuprel-runtime' = @('tuprel-sql:compile')
        'tuprel-postgresql' = @('tuprel-runtime:compile', 'tuprel-sql:runtime', 'postgresql:runtime')
    }
    foreach ($library in $libraries) {
        $directory = Join-Path $repository "dev/tuprel/staging/$library/$($coordinates.version)"
        $prefix = Join-Path $directory "$library-$($coordinates.version)"
        if (-not (Test-Path -LiteralPath "$prefix.jar" -PathType Leaf)) { throw 'Missing staged library' }
        $pom = ([xml](Get-Content -LiteralPath "$prefix.pom" -Raw)).project
        if ($pom.groupId -cne $coordinates.group -or $pom.artifactId -cne $library -or $pom.version -cne $coordinates.version) {
            throw 'Unexpected fixture coordinates'
        }
        $actual = @($pom.dependencies.dependency | Where-Object { $_ } | ForEach-Object {
            if ($_.artifactId -in $libraries -and ($_.groupId -cne $coordinates.group -or $_.version -cne $coordinates.version)) {
                throw 'A project dependency escaped the fixture coordinates'
            }
            "$($_.artifactId):$($_.scope)"
        } | Sort-Object)
        if (($actual -join ',') -cne (($dependencies[$library] | Sort-Object) -join ',')) {
            throw "Unexpected dependency scopes for $library"
        }
    }
    Write-Output 'Three staged libraries and their Maven dependency scopes verified'
} finally {
    Pop-Location
    $resolved = (Resolve-Path -LiteralPath $repository).Path
    if ($resolved -ne [IO.Path]::GetFullPath($repository) -or -not (Split-Path $resolved -Leaf).StartsWith('tuprel-maven-fixture-')) {
        throw 'Refused cleanup outside the staging repository'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
