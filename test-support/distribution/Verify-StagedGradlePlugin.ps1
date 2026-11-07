$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).ProviderPath
$repository = Join-Path ([IO.Path]::GetTempPath()) ('tuprel-gradle-plugin-fixture-' + [guid]::NewGuid())
[IO.Directory]::CreateDirectory($repository) | Out-Null
$repository = (Resolve-Path -LiteralPath $repository).ProviderPath
Push-Location -LiteralPath $root
try {
    $gradle = if ($IsWindows) { '.\gradlew.bat' } else { './gradlew' }
    $publicationOptions = @('-I', "$PSScriptRoot/staged-gradle-plugin.init.gradle", "-Dtuprel.test.gradle.repository=$repository")
    & $gradle :tuprel-gradle-plugin:publishAllPublicationsToFixtureRepository --no-configuration-cache --dependency-verification strict @publicationOptions
    if ($LASTEXITCODE -ne 0) { throw 'Local Gradle plugin staging failed' }

    $markerFile = Join-Path $repository 'dev/tuprel/codegen/dev.tuprel.codegen.gradle.plugin/0.0.0-fixture/dev.tuprel.codegen.gradle.plugin-0.0.0-fixture.pom'
    $marker = ([xml](Get-Content -LiteralPath $markerFile -Raw)).project
    $artifact = $marker.dependencies.dependency
    if ($marker.groupId -cne 'dev.tuprel.codegen' -or $marker.artifactId -cne 'dev.tuprel.codegen.gradle.plugin' -or
            $marker.version -cne '0.0.0-fixture' -or $artifact.groupId -cne 'dev.tuprel.staging' -or
            $artifact.artifactId -cne 'tuprel-gradle-plugin' -or $artifact.version -cne '0.0.0-fixture') {
        throw 'Unexpected Gradle plugin marker or implementation coordinates'
    }

    & $gradle :tuprel-gradle-plugin:test --dependency-verification strict "-PtuprelConsumerPluginRepository=$repository"
    if ($LASTEXITCODE -ne 0) { throw 'The external consumer could not use the staged Gradle plugin' }
    'Resolved and exercised the staged Gradle plugin marker in an external consumer'
} finally {
    Pop-Location
    $resolved = (Resolve-Path -LiteralPath $repository).ProviderPath
    if ($resolved -cne $repository -or -not (Split-Path -Leaf $resolved).StartsWith('tuprel-gradle-plugin-fixture-')) {
        throw 'Refused cleanup outside the Gradle plugin staging repository'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
