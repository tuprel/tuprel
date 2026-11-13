param(
    [Parameter(Mandatory)][string]$Fixture,
    [Parameter(Mandatory)][ValidateSet('install','configure','propagation','relogin','prepare-update','activate','rollback','interrupt-removal','finish','verify-removal')][string]$Step,
    [Parameter(Mandatory)][switch]$DisposableGuest
)

$ErrorActionPreference = 'Stop'
if (-not $DisposableGuest) { throw 'Use a disposable VM' }
$Fixture = (Resolve-Path -LiteralPath $Fixture).Path
if (-not (Split-Path -Leaf $Fixture).StartsWith('tuprel signed cli café ')) { throw 'Expected a reboot fixture' }
if ($IsWindows) {
    $user = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($user)
    $computer = Get-CimInstance Win32_ComputerSystem
    if ($user.Name -ine "$env:COMPUTERNAME\tuprel-reboot" -or
            $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator) -or
            @($user.Groups | Where-Object Value -eq 'S-1-5-32-544').Count -ne 0 -or
            -not $computer.HypervisorPresent -or $computer.Model -notmatch 'Virtual|VMware|KVM|QEMU|HVM') {
        throw 'Use the standard tuprel-reboot account in a disposable Windows VM'
    }
    if ([IO.DriveInfo]::new([IO.Path]::GetPathRoot($Fixture)).DriveFormat -ne 'NTFS') { throw 'NTFS required' }
    $boot = (Get-CimInstance Win32_OperatingSystem).LastBootUpTime.ToUniversalTime().ToString('o')
    $session = (& "$env:SystemRoot/System32/whoami.exe" /logonid | Out-String).Trim()
    if ($LASTEXITCODE -or -not $session) { throw 'Cannot identify Windows logon session' }
    $account = $user.User.Value
    $machine = (Get-CimInstance Win32_ComputerSystemProduct).UUID
} elseif ($IsLinux) {
    & /usr/bin/systemd-detect-virt --vm --quiet
    if ($LASTEXITCODE -or (& /usr/bin/id -un) -cne 'tuprel-reboot' -or (& /usr/bin/id -u) -eq '0') {
        throw 'Use the unprivileged tuprel-reboot account in a disposable Linux VM'
    }
    if ((& /usr/bin/findmnt -n -o FSTYPE --target $Fixture) -cne 'ext4' -or $LASTEXITCODE) { throw 'ext4 required' }
    $boot = (Get-Content -Raw -LiteralPath /proc/sys/kernel/random/boot_id).Trim()
    $session = ''
    $account = & /usr/bin/id -u
    $machine = (Get-Content -Raw -LiteralPath /etc/machine-id).Trim()
} else { throw 'Unsupported guest' }
if (-not $boot -or -not $machine) { throw 'OS boot and guest identities are required' }

$bootstrap = Get-Content -Raw -LiteralPath (Join-Path $Fixture 'reboot-bootstrap.json') | ConvertFrom-Json
$java = $bootstrap.java
$classes = Join-Path $Fixture 'classes'
$verifier = Join-Path $Fixture 'verify-cli.jar'
$native = Join-Path $Fixture $(if ($IsWindows) { 'tuprel-staging.dll' } else { 'libtuprel-staging.so' })
$control = @('-cp', $verifier, 'StagedActivation', $Fixture, $native, $Fixture, $bootstrap.keyId)
$first = '0.0.0-staging'
$second = '0.0.1-staging'
$pointer = Join-Path $Fixture '.tuprel-active'
$launcher = Join-Path $Fixture $(if ($IsWindows) { 'tuprel.cmd' } else { 'tuprel' })
$steps = @('install','configure','propagation','relogin','prepare-update','activate','rollback','interrupt-removal','finish','verify-removal')
$index = [Array]::IndexOf($steps, $Step)
$evidence = Join-Path $Fixture 'reboot-evidence'
if ($index -eq 0) { New-Item -ItemType Directory -Path $evidence | Out-Null }
$lock = [IO.File]::Open((Join-Path $evidence 'harness.lock'), 'OpenOrCreate', 'ReadWrite', 'None')

function Write-Evidence($name, $value) {
    $stream = [IO.File]::Open((Join-Path $evidence "$name.json"), 'CreateNew', 'Write', 'None')
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes(($value | ConvertTo-Json -Depth 6))
        $stream.Write($bytes)
        $stream.Flush($true)
    } finally { $stream.Dispose() }
}

function Invoke-Bundle($version, $operation, $expected = 0) {
    $bundle = @($version, (Join-Path $Fixture "$version.zip"), (Join-Path $Fixture "$version.manifest"),
        (Join-Path $Fixture "$version.ed25519"), (Join-Path $Fixture 'public-key.bin'), $bootstrap.keyId)
    & $java -jar $verifier @bundle $operation $Fixture $native
    if ($LASTEXITCODE -ne $expected) { throw "$operation returned an unexpected status for $version" }
}

function Invoke-Selection($operation, $version, $expected = 0) {
    & $java @control $operation $version
    if ($LASTEXITCODE -ne $expected) { throw "$operation returned an unexpected status for $version" }
}

function Assert-Version($version) {
    if ($IsWindows) {
        if (@($env:Path.Split(';') | Where-Object { $_ -ieq $Fixture }).Count -ne 1) {
            throw 'Expected exactly one inherited launcher PATH entry'
        }
        $resolved = @(Get-Command tuprel -All -ErrorAction SilentlyContinue)
        $where = @(& "$env:SystemRoot/System32/where.exe" tuprel)
        if ($LASTEXITCODE -or $resolved.Count -ne 1 -or $resolved[0].Source -ne $launcher -or
                $where.Count -ne 1 -or $where[0] -ne $launcher) { throw 'Missing launcher or Windows command shadowing' }
        $cmdVersion = & "$env:SystemRoot/System32/cmd.exe" /d /c 'tuprel --version'
        if ($LASTEXITCODE -or $cmdVersion -cne "tuprel $version") { throw 'CMD resolved an unexpected version' }
        $reported = & tuprel --version
        if ($LASTEXITCODE -or $reported -cne "tuprel $version") { throw 'PowerShell resolved an unexpected version' }
        $observations.commands = @($resolved.Source)
        $observations.where = $where
        $observations.executionPolicy = [string](Get-ExecutionPolicy)
    } else {
        foreach ($mode in @('-ic','-ilc')) {
            $reported = & /bin/bash $mode 'test "$(type -a tuprel)" = "tuprel is $1" && tuprel --version' bash $launcher
            if ($LASTEXITCODE -or $reported -cne "tuprel $version") { throw "Bash $mode resolved an unexpected launcher or version" }
        }
    }
    $observations.verifiedVersion = $version
    $observations.pointerSha256 = (Get-FileHash -LiteralPath $pointer -Algorithm SHA256).Hash
}

function Invoke-PathChange($operation) {
    if ($IsWindows) {
        $message = @(& (Join-Path $Fixture 'tuprel-path.exe') $operation $Fixture)
        if ($LASTEXITCODE) { throw 'Real user PATH transaction failed' }
        $observations.notification = $message
    } else {
        foreach ($profile in @('interactive','login')) {
            & $java -cp $classes StagedBashPath $operation $profile $HOME $Fixture $native
            if ($LASTEXITCODE) { throw "Bash $profile PATH change failed" }
        }
    }
}

function Read-WindowsPath {
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment')
    if (-not $key) { throw 'Expected the disposable profile Environment key' }
    try {
        $value = $key.GetValue('Path', $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
        @{ value = $value; kind = if ($null -ne $value) { [string]$key.GetValueKind('Path') } }
    } finally { $key.Dispose() }
}

try {
    if ($index -gt 0) {
        $prior = Get-Content -Raw -LiteralPath (Join-Path $evidence "$($steps[$index - 1]).json") | ConvertFrom-Json
        if ($prior.machine -cne $machine -or $prior.account -cne $account) { throw 'Guest or account changed' }
        if ($Step -in @('propagation','verify-removal')) {
            if ($prior.boot -cne $boot -or $prior.session -cne $session) { throw 'Use a new terminal in the original session' }
        } elseif ($Step -eq 'relogin') {
            if ($prior.boot -cne $boot -or ($IsWindows -and $prior.session -ceq $session)) {
                throw 'Use the same OS boot and a fresh Windows sign-in'
            }
        } elseif ($prior.boot -ceq $boot) { throw 'A different OS boot is required before continuing' }
    }
    $observations = @{ step = $Step; machine = $machine; account = $account; boot = $boot; session = $session }
    Write-Evidence "$Step-intent" $observations
    switch ($Step) {
        'install' {
            Invoke-Bundle $first install
            if ([IO.File]::Exists($pointer)) { throw 'Unexpected activation after installation' }
        }
        'configure' {
            if ([IO.File]::Exists($pointer)) { throw 'Version became active before explicit activation' }
            if ($IsWindows) {
                $observations.originalPath = Read-WindowsPath
                if ($java -match '[^ -~]|[%!"]' -or $bootstrap.keyId -cnotmatch '^[0-9a-f]{64}$') {
                    throw 'Use an ASCII Java installation path and a valid test key pin'
                }
                $line = '"' + $java + '" -cp "%~dp0verify-cli.jar" StagedActivation "%TUPREL_FIXTURE_ROOT%" "%~dp0tuprel-staging.dll" "%TUPREL_FIXTURE_ROOT%" ' +
                    $bootstrap.keyId + ' launch'
                [IO.File]::WriteAllLines($launcher, @('@echo off', 'setlocal DisableDelayedExpansion',
                    'for %%I in ("%~dp0.") do set "TUPREL_FIXTURE_ROOT=%%~fI"', "$line %*", 'exit /b %errorlevel%'))
            } else {
                $line = ((@($java) + $control + 'launch') | ForEach-Object { "'" + $_.Replace("'", "'\''") + "'" }) -join ' '
                [IO.File]::WriteAllLines($launcher, @('#!/bin/bash', ('exec ' + $line + ' "$@"')))
                [IO.File]::SetUnixFileMode($launcher, [IO.UnixFileMode]448)
                $observations.originalProfiles = @('.bashrc','.bash_profile','.bash_login','.profile' | ForEach-Object {
                    $file = Join-Path $HOME $_
                    if ([IO.File]::Exists($file)) { @{ name = $_; digest = (Get-FileHash -LiteralPath $file).Hash } }
                })
            }
            Invoke-Selection activate $first
            Invoke-PathChange add
            if ($IsWindows) {
                $current = Read-WindowsPath
                $old = $observations.originalPath
                $expected = if ($old.value) { "$($old.value);$Fixture" } else { $Fixture }
                if ($current.value -cne $expected -or ($old.kind -and $old.kind -cne $current.kind)) {
                    throw 'Real Windows PATH did not preserve the preceding entries or registry type'
                }
            }
        }
        'propagation' { Assert-Version $first }
        'relogin' {
            Assert-Version $first
            $project = Join-Path $Fixture 'reboot consumer café'
            New-Item -ItemType Directory -Path $project | Out-Null
            Push-Location -LiteralPath $project
            try {
                if ($IsWindows) {
                    foreach ($command in @('init','validate','generate')) {
                        & tuprel $command
                        if ($LASTEXITCODE) { throw "Consumer $command failed" }
                    }
                } else {
                    & /bin/bash -ilc 'tuprel init && tuprel validate && tuprel generate'
                    if ($LASTEXITCODE) { throw 'Consumer generation failed' }
                }
                if (-not (Test-Path -LiteralPath 'tuprel/schema.tuprel' -PathType Leaf)) { throw 'Consumer schema missing' }
            } finally { Pop-Location }
        }
        'prepare-update' {
            Assert-Version $first
            Invoke-Bundle $second install
            $activation = @($Fixture, $native, $Fixture, $bootstrap.keyId, 'activate', $second)
            & $java -cp $classes SignedCliBundleFixture interrupt-activation BEFORE_REPLACE @activation
            if ($LASTEXITCODE -ne 42) { throw 'Expected an interrupted activation after candidate creation' }
            $candidates = @(Get-ChildItem -LiteralPath $Fixture -Force -File -Filter '.tuprel-activation-*')
            if ($candidates.Count -ne 1) { throw 'Expected exactly one staged activation candidate' }
            $observations.candidate = $candidates[0].Name
            $observations.candidateDigest = (Get-FileHash -LiteralPath $candidates[0].FullName).Hash
        }
        'activate' {
            Assert-Version $first
            if ((Get-FileHash -LiteralPath (Join-Path $Fixture $prior.candidate)).Hash -cne $prior.candidateDigest) {
                throw 'Interrupted activation evidence was lost or changed'
            }
            Invoke-Selection activate $second
        }
        'rollback' {
            Assert-Version $second
            Invoke-Selection activate $first 1
            $policy = Join-Path $Fixture 'authorization'
            $signature = Join-Path $Fixture 'authorization.ed25519'
            $savedPolicy = [IO.File]::ReadAllBytes($policy)
            $savedSignature = [IO.File]::ReadAllBytes($signature)
            try {
                [IO.File]::Copy((Join-Path $Fixture 'revoked-authorization'), $policy, $true)
                [IO.File]::Copy((Join-Path $Fixture 'revoked-authorization.ed25519'), $signature, $true)
                Invoke-Selection rollback $first 1
            } finally {
                [IO.File]::WriteAllBytes($policy, $savedPolicy)
                [IO.File]::WriteAllBytes($signature, $savedSignature)
            }
            Invoke-Selection rollback $first
        }
        'interrupt-removal' {
            Assert-Version $first
            Invoke-Bundle $first remove 1
            Invoke-Bundle $second remove
            if ([IO.Directory]::Exists((Join-Path $Fixture $second))) { throw 'Inactive version was not removed' }
            Invoke-Bundle $second install
            Invoke-Selection activate $second
            & $java -cp $classes SignedCliBundleFixture interrupt-removal $Fixture (Join-Path $Fixture "$first.zip") (Join-Path $Fixture "$second.zip")
            if ($LASTEXITCODE -ne 42) { throw 'Expected interrupted removal' }
            $pending = Join-Path $Fixture ".$first-removing"
            [IO.File]::WriteAllText((Join-Path $pending 'foreign.txt'), 'preserve')
            $modified = Join-Path $pending 'bin/tuprel.bat'
            [IO.File]::Copy($modified, (Join-Path $evidence 'original-removal-launcher'))
            [IO.File]::AppendAllText($modified, 'modified')
        }
        'finish' {
            Assert-Version $second
            Invoke-Bundle $first remove 1
            $pending = Join-Path $Fixture ".$first-removing"
            if ([IO.File]::ReadAllText((Join-Path $pending 'foreign.txt')) -cne 'preserve' -or
                    -not [IO.File]::ReadAllText((Join-Path $pending 'bin/tuprel.bat')).EndsWith('modified')) {
                throw 'Removal changed unknown or modified data'
            }
            $quarantine = Join-Path $evidence 'preserved-foreign.txt'
            [IO.File]::Move((Join-Path $pending 'foreign.txt'), $quarantine)
            [IO.File]::Copy((Join-Path $pending 'bin/tuprel.bat'), (Join-Path $evidence 'preserved-modified-launcher'))
            [IO.File]::Copy((Join-Path $evidence 'original-removal-launcher'), (Join-Path $pending 'bin/tuprel.bat'), $true)
            Invoke-Bundle $first remove
            if ([IO.Directory]::Exists($pending)) { throw 'Explicit removal recovery did not complete' }
            $initial = Get-Content -Raw -LiteralPath (Join-Path $evidence 'configure.json') | ConvertFrom-Json
            Invoke-PathChange remove
            if ($IsWindows) {
                $current = Read-WindowsPath
                if ($current.value -cne $initial.originalPath.value -or $current.kind -cne $initial.originalPath.kind) {
                    throw 'Windows PATH did not return to its original value and type'
                }
            } else {
                foreach ($profile in $initial.originalProfiles) {
                    if ((Get-FileHash -LiteralPath (Join-Path $HOME $profile.name)).Hash -cne $profile.digest) {
                        throw 'Bash startup content did not return to its original bytes'
                    }
                }
                foreach ($mode in @('-ic','-ilc')) {
                    & /bin/bash $mode 'test -z "$(command -v tuprel)"'
                    if ($LASTEXITCODE) { throw 'Bash still resolves Tuprel after PATH removal' }
                }
            }
        }
        'verify-removal' {
            if ($IsWindows) {
                $where = @(& "$env:SystemRoot/System32/where.exe" tuprel 2>$null)
                if ($LASTEXITCODE -ne 1 -or $where.Count -or
                        @(Get-Command tuprel -All -ErrorAction SilentlyContinue).Count -or
                        $Fixture -iin $env:Path.Split(';')) { throw 'Tuprel remains available after PATH removal' }
            } else {
                foreach ($mode in @('-ic','-ilc')) {
                    & /bin/bash $mode 'test -z "$(command -v tuprel)"'
                    if ($LASTEXITCODE) { throw 'Bash still resolves Tuprel after PATH removal' }
                }
            }
        }
    }
    Write-Evidence $Step $observations
    Write-Output "Recorded $Step in $evidence"
} finally { $lock.Dispose() }
