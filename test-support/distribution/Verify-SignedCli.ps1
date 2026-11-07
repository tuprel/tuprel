$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$fixture = Join-Path ([IO.Path]::GetTempPath()) ("tuprel signed cli " + [guid]::NewGuid())
[IO.Directory]::CreateDirectory($fixture) | Out-Null
if (-not $IsWindows) { [IO.File]::SetUnixFileMode($fixture, [IO.UnixFileMode]448) }
$fixture = (Resolve-Path -LiteralPath $fixture).Path
$before = $env:PATH
try {
    $classes = Join-Path $fixture 'classes'
    [IO.Directory]::CreateDirectory($classes) | Out-Null
    $java = Join-Path $env:JAVA_HOME 'bin/java'
    $javac = Join-Path $env:JAVA_HOME 'bin/javac'
    & $javac --release 21 -Xlint:all -Werror -d $classes "$PSScriptRoot/SignedCliBundle.java" "$PSScriptRoot/SignedCliBundleFixture.java" "$PSScriptRoot/VerifyCliBundle.java"
    if ($LASTEXITCODE -ne 0) { throw 'Staging verifier did not compile' }

    $versions = @('0.0.0-staging', '0.0.1-staging')
    $archives = @()
    Push-Location -LiteralPath $root
    try {
        $gradle = if ($IsWindows) { '.\gradlew.bat' } else { './gradlew' }
        foreach ($version in $versions) {
            & $gradle :tuprel-cli:distZip --dependency-verification strict "-PtuprelStagedCliVersion=$version"
            if ($LASTEXITCODE -ne 0) { throw 'Versioned CLI staging failed' }
            $archives += (Resolve-Path -LiteralPath "tuprel-cli/build/distributions/tuprel-$version.zip").Path
        }
    } finally { Pop-Location }
    $keyId = & $java -cp $classes SignedCliBundleFixture install $fixture @archives
    if ($LASTEXITCODE -ne 0 -or $keyId -cnotmatch '^[a-f0-9]{64}$') { throw 'Signed bundle installation checks failed' }

    $verifier = Join-Path $fixture 'verify-cli.jar'
    $jar = Join-Path $env:JAVA_HOME 'bin/jar'
    & $jar --create --file $verifier --main-class VerifyCliBundle -C $classes SignedCliBundle.class -C $classes VerifyCliBundle.class
    if ($LASTEXITCODE -ne 0) { throw 'Independent staging verifier packaging failed' }
    $verifyArguments = @($versions[0], $archives[0], (Join-Path $fixture "$($versions[0]).manifest"),
        (Join-Path $fixture "$($versions[0]).ed25519"), (Join-Path $fixture 'public-key.bin'))
    & $java -jar $verifier @verifyArguments $keyId
    if ($LASTEXITCODE -ne 0) { throw 'The independent verifier rejected an authentic staging bundle' }
    $wrongPin = if ($keyId.StartsWith('0')) { '1' + $keyId.Substring(1) } else { '0' + $keyId.Substring(1) }
    & $java -jar $verifier @verifyArguments $wrongPin
    if ($LASTEXITCODE -ne 1) { throw 'The independent verifier accepted a wrong trust pin' }

    $launcherName = if ($IsWindows) { 'tuprel.bat' } else { 'tuprel' }
    $bash = if (-not $IsWindows) { (Get-Command bash -CommandType Application | Select-Object -First 1).Source }
    Push-Location -LiteralPath $fixture
    try {
        foreach ($version in @($versions[0], $versions[1], $versions[0])) {
            $bin = Join-Path $fixture "$version/bin"
            $env:PATH = $bin + [IO.Path]::PathSeparator + $before
            $expected = Join-Path $bin $launcherName
            if ($IsWindows) {
                $launcher = (Get-Command $launcherName -CommandType Application | Select-Object -First 1).Source
            } else {
                $mode = [IO.File]::GetUnixFileMode($expected)
                if ($mode -ne [IO.UnixFileMode]448) { throw 'Linux launcher does not have private executable permissions' }
                $launcher = & $bash --noprofile --norc -c 'command -v tuprel'
            }
            if ($launcher -ne $expected) { throw 'Session PATH did not select the expected installation' }
            $reported = & $launcher --version
            if ($LASTEXITCODE -ne 0 -or $reported -cne "tuprel $version") { throw 'Installed launcher reported the wrong version' }
        }
    } finally {
        $env:PATH = $before
        Pop-Location
    }
    & $java -cp $classes SignedCliBundleFixture uninstall $fixture @archives $keyId
    if ($LASTEXITCODE -ne 0) { throw 'Signed bundle removal checks failed' }
    'Signed installation, version switching and scoped removal verified'
} finally {
    $env:PATH = $before
    $resolved = (Resolve-Path -LiteralPath $fixture).Path
    if ($resolved -ne $fixture -or -not (Split-Path $resolved -Leaf).StartsWith('tuprel signed cli ')) {
        throw 'Refused cleanup outside the staging fixture'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
