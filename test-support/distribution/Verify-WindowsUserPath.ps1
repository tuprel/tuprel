param([Parameter(Mandatory)][string]$Binary, [Parameter(Mandatory)][string]$LauncherDirectory)

$ErrorActionPreference = 'Stop'
$subkey = 'Software\TuprelStagingPathFixture'
$currentUser = [Microsoft.Win32.Registry]::CurrentUser
$existing = $currentUser.OpenSubKey($subkey)
if ($null -ne $existing) {
    $existing.Dispose()
    throw 'The staging registry fixture already exists'
}
$key = $currentUser.CreateSubKey($subkey)
$prior = '%SystemRoot%\system32'
$kind = [Microsoft.Win32.RegistryValueKind]::ExpandString
$raw = [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames
try {
    $key.SetValue('Path', $prior, $kind)
    & $Binary add $LauncherDirectory abandon
    if ($LASTEXITCODE -ne 1 -or $key.GetValue('Path', '', $raw) -cne $prior) {
        throw 'Closing an uncommitted transaction changed PATH'
    }
    foreach ($attempt in 1, 2) {
        & $Binary add $LauncherDirectory commit
        if ($LASTEXITCODE -ne 0 -or $key.GetValueKind('Path') -ne $kind -or
                $key.GetValue('Path', '', $raw) -cne "$prior;$LauncherDirectory") {
            throw 'Repeated PATH installation failed to preserve type, order or uniqueness'
        }
    }
    $command = 'if ((Get-Command tuprel -ErrorAction Stop).Source -cne $env:TUPREL_EXPECTED_LAUNCHER) { exit 1 }; tuprel --version; exit $LASTEXITCODE'
    $pwsh = (Get-Process -Id $PID).Path
    $start = [Diagnostics.ProcessStartInfo]::new($pwsh)
    foreach ($argument in '-NoProfile', '-NonInteractive', '-Command', $command) { $start.ArgumentList.Add($argument) }
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.Environment['Path'] = $key.GetValue('Path')
    $start.Environment['TUPREL_EXPECTED_LAUNCHER'] = Join-Path $LauncherDirectory 'tuprel.ps1'
    $child = [Diagnostics.Process]::Start($start)
    try {
        $output = $child.StandardOutput.ReadToEndAsync()
        if (-not $child.WaitForExit(30000)) { $child.Kill(); throw 'New PowerShell did not finish' }
        $actual = $output.GetAwaiter().GetResult().Trim()
        if ($child.ExitCode -ne 0 -or $actual -cne 'tuprel 0.0.0-staging') { throw 'New PowerShell did not run Tuprel' }
    } finally { $child.Dispose() }

    $later = "$env:SystemRoot\System32\Wbem;$prior"
    $key.SetValue('Path', "$later;$LauncherDirectory", $kind)
    & $Binary remove $LauncherDirectory commit
    if ($LASTEXITCODE -ne 0 -or $key.GetValue('Path', '', $raw) -cne $later -or
            $key.GetValueKind('Path') -ne $kind) { throw 'PATH removal lost a user edit or changed the value type' }
    $key.SetValue('Path', "$later;$LauncherDirectory", $kind)
    & $Binary add $LauncherDirectory commit
    if ($LASTEXITCODE -ne 1) { throw 'An unowned launcher PATH entry must be refused' }
    & $Binary remove $LauncherDirectory commit
    if ($LASTEXITCODE -ne 0 -or $key.GetValue('Path', '', $raw) -cne "$later;$LauncherDirectory") {
        throw 'An unowned PATH entry was removed'
    }
    $collision = Join-Path $LauncherDirectory 'conflicting path'
    [IO.Directory]::CreateDirectory($collision) | Out-Null
    [IO.File]::WriteAllText((Join-Path $collision 'tuprel.cmd'), '@exit /b 2')
    $key.SetValue('Path', "$collision;$prior", $kind)
    & $Binary add $LauncherDirectory commit
    if ($LASTEXITCODE -ne 1 -or $key.GetValue('Path', '', $raw) -cne "$collision;$prior") {
        throw 'Existing shadowing executable must be refused without changing PATH'
    }
    'Windows registry staging PATH transaction and new PowerShell verified'
} finally {
    $key.Dispose()
    $currentUser.DeleteSubKey($subkey, $false)
}
