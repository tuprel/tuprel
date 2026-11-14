param(
    [string]$Maven = $(if ($IsWindows) { 'mvn.cmd' } else { 'mvn' }),
    [switch]$VerifyStagedProject
)

$ErrorActionPreference = 'Stop'
$mavenPath = (Get-Command $Maven -CommandType Application | Select-Object -First 1).Source
if ($VerifyStagedProject) {
    & $mavenPath -B -ntp -C "-Dmaven.repo.local=$env:TUPREL_CONSUMER_MAVEN_CACHE" verify
    if ($LASTEXITCODE) { exit $LASTEXITCODE }
    & (Join-Path $env:JAVA_HOME 'bin/java') -cp target/test-classes example.products.PackagedProductsProof
    exit $LASTEXITCODE
}

$destination = Join-Path ([IO.Path]::GetTempPath()) "tuprel-products-$([guid]::NewGuid())"
try {
    $stage = & "$PSScriptRoot/Stage-SpringProductsApi.ps1" -Destination $destination | Select-Object -Last 1
    $env:TUPREL_FIXTURE_REPOSITORY = $stage.Repository
    $env:TUPREL_CLI_HOME = $stage.CliHome
    $env:TUPREL_CONSUMER_MAVEN_CACHE = Join-Path $destination 'maven-cache'
    $java = Join-Path $env:JAVA_HOME 'bin/java'
    $javac = Join-Path $env:JAVA_HOME 'bin/javac'
    $helper = Join-Path $destination 'test-helper'
    $testSupport = Join-Path $PSScriptRoot '../src/main/java/dev/tuprel/testsupport'
    & $javac --release 21 -d $helper "$testSupport/LocalTestEnvironment.java" `
        "$testSupport/LocalPostgresqlTarget.java" "$PSScriptRoot/RunProductsApiWithLocalDatabase.java"
    if ($LASTEXITCODE) { throw 'Could not compile the local HTTP test launcher' }
    $dotenv = Join-Path $PSScriptRoot '../../examples/tuprel-playground/.env'
    & $java -cp $helper RunProductsApiWithLocalDatabase $dotenv $stage.Project `
        (Get-Process -Id $PID).Path -NoProfile -File $PSCommandPath -VerifyStagedProject -Maven $mavenPath
    if ($LASTEXITCODE) { throw 'External Spring HTTP verification failed' }
} finally {
    if (Test-Path -LiteralPath $destination) {
        $resolved = (Resolve-Path -LiteralPath $destination).ProviderPath
        if ($resolved -ne [IO.Path]::GetFullPath($destination) -or
                -not (Split-Path -Leaf $resolved).StartsWith('tuprel-products-') -or
                ((Get-Item -LiteralPath $resolved).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Refused cleanup outside the products fixture'
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
