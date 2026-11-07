param(
    [Parameter(Mandatory)][string]$Gpg,
    [Parameter(Mandatory)][string]$Fixture,
    [Parameter(Mandatory)][string[]]$Files
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$root = (Resolve-Path -LiteralPath $Fixture).ProviderPath
if ($root -cne $Fixture -or -not (Split-Path -Leaf $root).StartsWith('tuprel-maven-fixture-') -or
        -not [IO.Path]::IsPathFullyQualified($Gpg)) {
    throw 'Expected an isolated Maven fixture and an absolute GnuPG executable'
}
$gpgconfName = if ($IsWindows) { 'gpgconf.exe' } else { 'gpgconf' }
$gpgconf = Join-Path (Split-Path -Parent $Gpg) $gpgconfName
if (-not (Test-Path -LiteralPath $gpgconf -PathType Leaf)) { throw 'GnuPG fixture cleanup requires gpgconf' }
$cygpath = Join-Path (Split-Path -Parent $Gpg) 'cygpath.exe'
function ConvertTo-GpgPath([string]$Path) {
    if (-not $IsWindows -or -not (Test-Path -LiteralPath $cygpath -PathType Leaf)) { return $Path }
    $converted = & $cygpath --unix --absolute -- $Path
    if ($LASTEXITCODE -ne 0 -or $converted -cnotmatch '^/') { throw 'Cannot translate the fixture path for MSYS' }
    return $converted
}
foreach ($file in $Files) {
    $relative = [IO.Path]::GetRelativePath($root, $file).Replace('\', '/')
    if ($relative -notmatch '^dev/tuprel/staging/[^/]+/0\.0\.0-fixture/[^/]+\.(jar|pom|module)$' -or
            (Resolve-Path -LiteralPath $file).ProviderPath -cne $file) {
        throw 'Artifact is outside the staging publication'
    }
    $hash = Get-FileHash -LiteralPath $file -Algorithm SHA256
    if ((Get-Content -LiteralPath "$file.sha256" -Raw).Trim() -ne $hash.Hash) {
        throw 'Staged artifact checksum differs'
    }
}

$signer = Join-Path $root 'sign'
$verifier = Join-Path $root 'verify'
foreach ($directory in @($signer, $verifier)) {
    if (Test-Path -LiteralPath $directory) { throw 'The keyring fixture must be fresh' }
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    if (-not $IsWindows) { [IO.File]::SetUnixFileMode($directory, [IO.UnixFileMode]448) }
}
$identity = 'Tuprel Maven Staging'
$publicKey = ConvertTo-GpgPath (Join-Path $root 'fixture-public.gpg')
$signerArgument = ConvertTo-GpgPath $signer
$sign = @('--homedir', $signerArgument, '--batch', '--no-options', '--no-tty')
$verify = @('--homedir', (ConvertTo-GpgPath $verifier), '--batch', '--no-options', '--no-tty', '--no-autostart')
try {
    & $Gpg @sign --pinentry-mode loopback --passphrase '' --quick-generate-key $identity rsa3072 sign 1d
    if ($LASTEXITCODE -ne 0) { throw 'Cannot generate the ephemeral fixture signer' }
    $listing = @(& $Gpg @sign --with-colons --fingerprint --list-keys $identity)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot identify the fixture signer' }
    $fingerprintLines = @($listing | Where-Object { $_ -cmatch '^fpr:' })
    if ($fingerprintLines.Count -ne 1) { throw 'Expected exactly one fixture signing identity' }
    $fingerprint = ($fingerprintLines[0] -split ':')[9]
    if ($fingerprint -cnotmatch '^[A-F0-9]{40}$') { throw 'Invalid signing identity' }

    & $Gpg @sign --output $publicKey --export $fingerprint
    if ($LASTEXITCODE -ne 0) { throw 'Cannot export the public fixture key' }
    & $Gpg @verify --import $publicKey
    if ($LASTEXITCODE -ne 0) { throw 'Cannot import the public fixture key' }

    foreach ($file in $Files) {
        $inputFile = ConvertTo-GpgPath $file
        $signatureFile = ConvertTo-GpgPath "$file.asc"
        & $Gpg @sign --local-user $fingerprint --pinentry-mode loopback --passphrase '' --armor --output $signatureFile --detach-sign $inputFile
        if ($LASTEXITCODE -ne 0) { throw 'Cannot sign the staged Maven artifact' }
        $status = @(& $Gpg @verify --status-fd 1 --verify $signatureFile $inputFile)
        if ($LASTEXITCODE -ne 0 -or -not ($status -cmatch "^\[GNUPG:\] VALIDSIG $fingerprint ")) {
            throw 'Staged artifact signature did not validate against the isolated keyring'
        }
    }
    $changed = Join-Path $root 'tampered-artifact.jar'
    $bytes = [IO.File]::ReadAllBytes($Files[0])
    $bytes[0] = $bytes[0] -bxor 1
    [IO.File]::WriteAllBytes($changed, $bytes)
    & $Gpg @verify --verify (ConvertTo-GpgPath "$($Files[0]).asc") (ConvertTo-GpgPath $changed)
    if ($LASTEXITCODE -ne 1) { throw 'A changed artifact did not produce the expected signature failure' }
    "Verified $($Files.Count) signed Maven staging artifacts and rejected changed bytes"
} finally {
    & $gpgconf --homedir $signerArgument --kill gpg-agent
    if ($LASTEXITCODE -ne 0) { throw 'Cannot stop the isolated staging signer' }
}
