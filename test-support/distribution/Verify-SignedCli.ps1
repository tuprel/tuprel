param([string]$FixtureParent = $(if ($IsWindows) { $env:LOCALAPPDATA } else { $HOME }))

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$fixture = Join-Path $FixtureParent ("tuprel signed cli " + [guid]::NewGuid())
[IO.Directory]::CreateDirectory($fixture) | Out-Null
if ($IsWindows) {
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls $fixture /setowner "*$sid" | Out-Null
    if ($LASTEXITCODE) { throw 'Cannot assign the fixture owner' }
    & icacls $fixture /grant:r "*${sid}:(OI)(CI)F" /inheritance:r | Out-Null
    if ($LASTEXITCODE) { throw 'Cannot secure the fixture ACL' }
} else { [IO.File]::SetUnixFileMode($fixture, [IO.UnixFileMode]448) }
$fixture = (Resolve-Path -LiteralPath $fixture).Path
$before = $env:PATH
try {
    $native = Join-Path $fixture $(if ($IsWindows) { 'tuprel-staging.dll' } else { 'libtuprel-staging.so' })
    $nativeSource = Join-Path $root 'native/cli-installer'
    if ($IsWindows) { & "$nativeSource/build-windows-staging.cmd" $native }
    else { & bash "$nativeSource/build-linux-staging.sh" $native }
    if ($LASTEXITCODE) { throw 'Staging native validator did not compile' }
    $classes = Join-Path $fixture 'classes'
    [IO.Directory]::CreateDirectory($classes) | Out-Null
    $java = Join-Path $env:JAVA_HOME 'bin/java'
    $javac = Join-Path $env:JAVA_HOME 'bin/javac'
    & $javac --release 21 -Xlint:all -Werror -d $classes "$PSScriptRoot/SignedCliBundle.java" "$PSScriptRoot/SignedCliBundleFixture.java" "$PSScriptRoot/VerifyCliBundle.java" "$PSScriptRoot/StagingRoot.java"
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
    $keyId = & $java -cp $classes SignedCliBundleFixture prepare $fixture @archives
    if ($LASTEXITCODE -ne 0 -or $keyId -cnotmatch '^[a-f0-9]{64}$') { throw 'Signed bundle preparation failed' }

    $verifier = Join-Path $fixture 'verify-cli.jar'
    $jar = Join-Path $env:JAVA_HOME 'bin/jar'
    & $jar --create --file $verifier --main-class VerifyCliBundle -C $classes SignedCliBundle.class -C $classes VerifyCliBundle.class -C $classes StagingRoot.class
    if ($LASTEXITCODE -ne 0) { throw 'Independent staging verifier packaging failed' }
    $verifyArguments = @($versions[0], $archives[0], (Join-Path $fixture "$($versions[0]).manifest"),
        (Join-Path $fixture "$($versions[0]).ed25519"), (Join-Path $fixture 'public-key.bin'))
    & $java -jar $verifier @verifyArguments $keyId
    if ($LASTEXITCODE -ne 0) { throw 'The independent verifier rejected an authentic staging bundle' }
    $wrongPin = if ($keyId.StartsWith('0')) { '1' + $keyId.Substring(1) } else { '0' + $keyId.Substring(1) }
    & $java -jar $verifier @verifyArguments $wrongPin
    if ($LASTEXITCODE -ne 1) { throw 'The independent verifier accepted a wrong trust pin' }

    $secondArguments = @($versions[1], $archives[1], (Join-Path $fixture "$($versions[1]).manifest"),
        (Join-Path $fixture "$($versions[1]).ed25519"), (Join-Path $fixture 'public-key.bin'))
    $unsafe = Join-Path $fixture 'unsafe root'
    [IO.Directory]::CreateDirectory($unsafe) | Out-Null
    if ($IsWindows) {
        & icacls $unsafe /setowner "*$sid" | Out-Null
        if ($LASTEXITCODE) { throw 'Cannot assign the unsafe fixture owner' }
        & icacls $unsafe /grant '*S-1-1-0:(WDAC)' | Out-Null
        if ($LASTEXITCODE) { throw 'Cannot prepare the unsafe fixture ACL' }
    } else { [IO.File]::SetUnixFileMode($unsafe, [IO.UnixFileMode]504) }
    foreach ($operation in @('install', 'remove')) {
        & $java -jar $verifier @verifyArguments $keyId $operation $unsafe $native
        if ($LASTEXITCODE -ne 1 -or @(Get-ChildItem -LiteralPath $unsafe -Force).Count -ne 0) {
            throw 'Unsafe root must be refused before any installation mutation'
        }
    }
    & $java -jar $verifier @verifyArguments $keyId install $fixture (Join-Path $fixture 'missing-native-library')
    if ($LASTEXITCODE -ne 1 -or (Test-Path -LiteralPath (Join-Path $fixture '.tuprel-staging.lock'))) {
        throw 'Missing validator must be refused before creating the staging lock'
    }
    & $java -jar $verifier @verifyArguments $keyId install $fixture $native
    if ($LASTEXITCODE -ne 0) { throw 'First signed installation failed' }
    & $java -jar $verifier @verifyArguments $keyId install $fixture $native
    if ($LASTEXITCODE -ne 0) { throw 'An identical signed installation must be idempotent' }
    $abandoned = @(Get-ChildItem -LiteralPath $fixture -Force -Directory -Filter ".$($versions[0])-pending-*")
    if ($abandoned.Count -ne 1 -or (Get-Content -Raw -LiteralPath (Join-Path $abandoned[0].FullName 'partial')) -cne 'interrupted attempt') {
        throw 'Reinstall must preserve abandoned staging data'
    }
    & $java -jar $verifier @secondArguments $keyId install $fixture $native
    if ($LASTEXITCODE -ne 0) { throw 'Second signed installation failed' }

    $launcherName = if ($IsWindows) { 'tuprel.bat' } else { 'tuprel' }
    $bash = if (-not $IsWindows) { (Get-Command bash -CommandType Application | Select-Object -First 1).Source }
    $project = Join-Path $fixture 'external Java project'
    [IO.Directory]::CreateDirectory($project) | Out-Null
    Push-Location -LiteralPath $project
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
            if (-not (Test-Path -LiteralPath 'tuprel/schema.tuprel' -PathType Leaf)) {
                & $launcher init
                if ($LASTEXITCODE -ne 0) { throw 'Installed CLI init failed' }
            }
            & $launcher validate
            if ($LASTEXITCODE -ne 0) { throw 'Installed CLI validate failed' }
            & $launcher generate
            if ($LASTEXITCODE -ne 0) { throw 'Installed CLI generate failed' }
        }
    } finally {
        $env:PATH = $before
        Pop-Location
    }
    & $java -cp $classes SignedCliBundleFixture test-refusal $fixture @archives $keyId $native
    if ($LASTEXITCODE -ne 0) { throw 'Modified-file protection failed' }
    & $java -cp $classes SignedCliBundleFixture interrupt-removal $fixture @archives
    if ($LASTEXITCODE -ne 42) { throw 'Expected the removal fixture to halt after deleting one file' }
    $removing = Join-Path $fixture ".$($versions[0])-removing"
    $foreign = Join-Path $removing 'user-file.txt'
    [IO.File]::WriteAllText($foreign, 'preserve')
    & $java -jar $verifier @verifyArguments $keyId remove $fixture $native
    if ($LASTEXITCODE -ne 1 -or (Get-Content -LiteralPath $foreign -Raw) -cne 'preserve' -or
            -not (Test-Path -LiteralPath (Join-Path $removing 'bin/tuprel.bat') -PathType Leaf)) {
        throw 'Interrupted removal did not preserve unknown files and verified siblings'
    }
    Remove-Item -LiteralPath $foreign
    & $java -jar $verifier @verifyArguments $keyId install $fixture $native
    if ($LASTEXITCODE -ne 1) { throw 'Installation must refuse while a removal is pending' }
    & $java -jar $verifier @secondArguments $keyId remove $fixture $native
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $removing -PathType Container)) {
        throw 'Removing the second version must not affect the pending removal'
    }
    $survivor = Join-Path $removing 'bin/tuprel.bat'
    if ($IsWindows) {
        $locked = [IO.File]::Open($survivor, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
        try {
            & $java -jar $verifier @verifyArguments $keyId remove $fixture $native
            if ($LASTEXITCODE -ne 1) { throw 'Expected a Windows sharing violation during removal' }
        } finally { $locked.Dispose() }
    } else {
        $bin = Split-Path -Parent $survivor
        $priorMode = [IO.File]::GetUnixFileMode($bin)
        [IO.File]::SetUnixFileMode($bin, [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserExecute)
        try {
            & $java -jar $verifier @verifyArguments $keyId remove $fixture $native
            if ($LASTEXITCODE -ne 1) { throw 'Expected Linux to refuse removal from a non-writable directory' }
        } finally { [IO.File]::SetUnixFileMode($bin, $priorMode) }
    }
    if (-not (Test-Path -LiteralPath $survivor -PathType Leaf)) {
        throw 'Removal failure lost the protected file'
    }
    & $java -jar $verifier @verifyArguments $keyId remove $fixture $native
    if ($LASTEXITCODE -ne 0 -or (Test-Path -LiteralPath $removing)) {
        throw 'Restart did not finish scoped removal of the first version'
    }
    & $java -jar $verifier @verifyArguments $keyId remove $fixture $native
    if ($LASTEXITCODE -ne 0) { throw 'Repeat removal must be idempotent' }
    'Signed installation, version switching, process interruption and scoped recovery verified'
} finally {
    $env:PATH = $before
    $resolved = (Resolve-Path -LiteralPath $fixture).Path
    if ($resolved -ne $fixture -or -not (Split-Path $resolved -Leaf).StartsWith('tuprel signed cli ')) {
        throw 'Refused cleanup outside the staging fixture'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
