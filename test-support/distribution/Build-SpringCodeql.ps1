param([string]$Maven = $(if ($IsWindows) { 'mvn.cmd' } else { 'mvn' }))

$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot/../..").Path
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('tuprel-spring-codeql-'+[guid]::NewGuid())
$mavenCommand=(Get-Command $Maven -CommandType Application | Select-Object -First 1).Source
$previousRepository=$env:TUPREL_FIXTURE_REPOSITORY
$previousCli=$env:TUPREL_CLI_HOME
try {
    $stage=& "$PSScriptRoot/Stage-SpringProductsApi.ps1" -Destination $fixture | Select-Object -Last 1
    $env:TUPREL_FIXTURE_REPOSITORY=$stage.Repository
    $env:TUPREL_CLI_HOME=$stage.CliHome
    $settings=Join-Path $fixture 'settings.xml'
    [IO.File]::WriteAllText($settings,'<settings/>')
    # Compile repository paths so CodeQL can map REST findings to the PR.
    # Execution, PostgreSQL and executable-JAR proofs remain in the Spring LTS jobs.
    & $mavenCommand --batch-mode --no-transfer-progress --strict-checksums --settings $settings `
        --global-settings $settings "-Dmaven.repo.local=$fixture/maven-cache" `
        -f "$root/examples/spring-products-api/pom.xml" test-compile
    if ($LASTEXITCODE -ne 0) { throw 'Spring REST compilation for CodeQL failed' }
} finally {
    $env:TUPREL_FIXTURE_REPOSITORY=$previousRepository
    $env:TUPREL_CLI_HOME=$previousCli
    if (Test-Path -LiteralPath $fixture) {
        $resolved=(Resolve-Path -LiteralPath $fixture).Path
        if ($resolved -cne [IO.Path]::GetFullPath($fixture) -or
                -not (Split-Path -Leaf $resolved).StartsWith('tuprel-spring-codeql-') -or
                ((Get-Item -LiteralPath $resolved).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Refused cleanup outside the CodeQL fixture'
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
