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
    & $javac --release 21 -Xlint:all -Werror -d $classes "$PSScriptRoot/SignedCliBundle.java" "$PSScriptRoot/SignedCliBundleFixture.java" "$PSScriptRoot/VerifyCliBundle.java" "$PSScriptRoot/StagingRoot.java" "$PSScriptRoot/StagedActivation.java" "$PSScriptRoot/StagedBashPath.java"
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
    & $jar --create --file $verifier --main-class VerifyCliBundle -C $classes SignedCliBundle.class -C $classes VerifyCliBundle.class -C $classes StagingRoot.class -C $classes StagedActivation.class -C $classes 'StagedActivation$State.class' -C $classes 'StagedActivation$Authorization.class'
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

    $control = @('-cp', $verifier, 'StagedActivation', $fixture, $native, $fixture, $keyId)
    $launcher = Join-Path $fixture $(if ($IsWindows) { 'tuprel.ps1' } else { 'tuprel' })
    if ($IsWindows) {
        $quote = { param($value) "'" + $value.Replace("'", "''") + "'" }
        $command = '& ' + (((@($java) + $control + 'launch') | ForEach-Object { & $quote $_ }) -join ' ')
        [IO.File]::WriteAllText($launcher, "$command @args`nexit `$LASTEXITCODE`n")
    } else {
        $quote = { param($value) "'" + $value.Replace("'", "'\''") + "'" }
        $command = ((@($java) + $control + 'launch') | ForEach-Object { & $quote $_ }) -join ' '
        [IO.File]::WriteAllText($launcher, "#!/bin/bash`nexec $command `"`$@`"`n")
        [IO.File]::SetUnixFileMode($launcher, [IO.UnixFileMode]448)
    }
    & $java @control activate $versions[0]
    if ($LASTEXITCODE) { throw 'First activation failed' }
    if ($IsWindows) {
        $pathTool = Join-Path $fixture 'tuprel-path-fixture.exe'
        & "$nativeSource/build-windows-path.cmd" $pathTool fixture
        if ($LASTEXITCODE) { throw 'Windows PATH fixture did not compile' }
        & "$PSScriptRoot/Verify-WindowsUserPath.ps1" -Binary $pathTool -LauncherDirectory $fixture
    } else {
        $fixtureHome = Join-Path $fixture 'Bash home'
        [IO.Directory]::CreateDirectory($fixtureHome) | Out-Null
        [IO.File]::SetUnixFileMode($fixtureHome, [IO.UnixFileMode]448)
        $interactiveProfile = Join-Path $fixtureHome '.bashrc'
        $loginProfile = Join-Path $fixtureHome '.bash_profile'
        $profileText = "# user content`n"
        foreach ($profileFile in @($interactiveProfile, $loginProfile)) {
            [IO.File]::WriteAllText($profileFile, $profileText)
            [IO.File]::SetUnixFileMode($profileFile, [IO.UnixFileMode]384)
        }
        foreach ($kind in @('interactive', 'login')) {
            & $java -cp $classes StagedBashPath add $kind $fixtureHome $fixture $native
            if ($LASTEXITCODE) { throw 'Bash PATH installation failed' }
            & $java -cp $classes StagedBashPath add $kind $fixtureHome $fixture $native
            if ($LASTEXITCODE) { throw 'Repeated Bash PATH installation failed' }
        }
        $bashCheck = 'test "$(command -v tuprel)" = "$1" && tuprel --version'
        $interactiveResult = & /usr/bin/env "HOME=$fixtureHome" 'PATH=/usr/bin:/bin' /bin/bash -ic $bashCheck bash $launcher
        if ($LASTEXITCODE -or $interactiveResult -cne "tuprel $($versions[0])") { throw 'New interactive Bash failed' }
        $loginResult = & /usr/bin/env "HOME=$fixtureHome" 'PATH=/usr/bin:/bin' /bin/bash -lc $bashCheck bash $launcher
        if ($LASTEXITCODE -or $loginResult -cne "tuprel $($versions[0])") { throw 'New login Bash failed' }
        $userEdit = "# later user edit`n"
        [IO.File]::AppendAllText($interactiveProfile, $userEdit)
        foreach ($kind in @('interactive', 'login')) {
            & $java -cp $classes StagedBashPath remove $kind $fixtureHome $fixture $native
            if ($LASTEXITCODE) { throw 'Bash PATH reversal failed' }
        }
        if ([IO.File]::ReadAllText($interactiveProfile) -cne ($profileText + $userEdit) -or
                [IO.File]::ReadAllText($loginProfile) -cne $profileText) { throw 'Bash PATH changed user content' }
        & /usr/bin/env "HOME=$fixtureHome" 'PATH=/usr/bin:/bin' /bin/bash -ic 'test -z "$(command -v tuprel)"'
        if ($LASTEXITCODE) { throw 'Bash still resolves Tuprel after reversal' }
        [IO.File]::AppendAllText($interactiveProfile, "return`n")
        & $java -cp $classes StagedBashPath add interactive $fixtureHome $fixture $native
        if ($LASTEXITCODE -ne 1) { throw 'Unverifiable Bash startup code must be refused' }
    }
    & $java @control activate '9.9.9-staging'
    if ($LASTEXITCODE -ne 1) { throw 'Unknown version must be refused' }
    & $java -jar $verifier @verifyArguments $keyId remove $fixture $native
    if ($LASTEXITCODE -ne 1) { throw 'Removal must refuse the active version' }
    $pointer = Join-Path $fixture '.tuprel-active'
    $beforeActivation = [IO.File]::ReadAllBytes($pointer)
    if ($IsWindows) {
        $busy = [IO.File]::Open($pointer, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
        try {
            & $java @control activate $versions[1]
            if ($LASTEXITCODE -ne 1 -or [Convert]::ToHexString([IO.File]::ReadAllBytes($pointer)) -ne
                    [Convert]::ToHexString($beforeActivation)) { throw 'Sharing violation must preserve the active pointer' }
        } finally { $busy.Dispose() }
    }
    $interrupted = Join-Path $fixture '.tuprel-activation-abandoned'
    [IO.File]::WriteAllText($interrupted, 'incomplete candidate')
    $bundleJar = Join-Path $fixture "$($versions[1])/lib/tuprel-cli-$($versions[1]).jar"
    $authenticJar = [IO.File]::ReadAllBytes($bundleJar)
    [IO.File]::AppendAllText($bundleJar, 'tampered')
    & $java @control activate $versions[1]
    if ($LASTEXITCODE -ne 1 -or [Convert]::ToHexString([IO.File]::ReadAllBytes($pointer)) -ne
            [Convert]::ToHexString($beforeActivation)) { throw 'Failed update changed the active pointer' }
    [IO.File]::WriteAllBytes($bundleJar, $authenticJar)
    $project = Join-Path $fixture 'external Java project'
    [IO.Directory]::CreateDirectory($project) | Out-Null
    Push-Location -LiteralPath $project
    try {
        foreach ($version in @($versions[0], $versions[1], $versions[0])) {
            if ($version -eq $versions[1]) {
                & $java @control activate $version
                if ($LASTEXITCODE) { throw 'Update activation failed' }
                & $java @control activate $versions[0]
                if ($LASTEXITCODE -ne 1) { throw 'Ordinary activation must refuse downgrade' }
                $authorization = Join-Path $fixture 'authorization'
                $signature = Join-Path $fixture 'authorization.ed25519'
                $originalPolicy = [IO.File]::ReadAllBytes($authorization)
                $originalSignature = [IO.File]::ReadAllBytes($signature)
                [IO.File]::Copy((Join-Path $fixture 'revoked-authorization'), $authorization, $true)
                [IO.File]::Copy((Join-Path $fixture 'revoked-authorization.ed25519'), $signature, $true)
                & $java @control rollback $versions[0]
                if ($LASTEXITCODE -ne 1) { throw 'Rollback must refuse a revoked version' }
                [IO.File]::WriteAllBytes($authorization, $originalPolicy)
                [IO.File]::WriteAllBytes($signature, $originalSignature)
            } elseif ([IO.File]::ReadAllText($pointer).Contains($versions[1])) {
                & $java @control rollback $version
                if ($LASTEXITCODE) { throw 'Authorized rollback failed' }
            }
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
    if ([IO.File]::ReadAllText($interrupted) -ne 'incomplete candidate') { throw 'Activation lost interruption evidence' }
    & $java @control activate $versions[1]
    if ($LASTEXITCODE) { throw 'Cannot reselect the authenticated latest version' }
    $launchJob = Start-Job -ArgumentList $launcher -ScriptBlock {
        param($stableLauncher)
        $actual = & $stableLauncher --version
        if ($LASTEXITCODE) { throw 'Concurrent launch failed' }
        $actual
    }
    try {
        & $java @control rollback $versions[0]
        if ($LASTEXITCODE) { throw 'Concurrent rollback failed' }
        $reported = Receive-Job -Job $launchJob -Wait
        if ($reported -cnotin @("tuprel $($versions[0])", "tuprel $($versions[1])")) {
            throw 'Concurrent launcher did not see a complete authorized version'
        }
    } finally { Remove-Job -Job $launchJob -Force }
    & $java @control activate $versions[1]
    if ($LASTEXITCODE) { throw 'Cannot reactivate the latest version' }
    [IO.File]::AppendAllText($bundleJar, 'tampered')
    & $launcher --version
    if ($LASTEXITCODE -ne 1) { throw 'Launcher must refuse tampered active bytes' }
    [IO.File]::WriteAllBytes($bundleJar, $authenticJar)
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
    if ($LASTEXITCODE -ne 1) { throw 'Removing the active second version must be refused' }
    [IO.File]::Delete($pointer)
    & $launcher --version
    if ($LASTEXITCODE -ne 1) { throw 'Launcher must refuse a missing active pointer' }
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
