param([Parameter(Mandatory)][string]$Archive)

$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $Archive).Path
$fixture = Join-Path ([IO.Path]::GetTempPath()) ("tuprel staged " + [guid]::NewGuid())
[IO.Directory]::CreateDirectory($fixture) | Out-Null
try {
    $copy = Join-Path $fixture 'cli.zip'
    Copy-Item -LiteralPath $source -Destination $copy
    $checksum = (Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash
    if ($checksum -ne (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash) { throw 'Staging changed the archive bytes' }

    $installation = Join-Path $fixture 'installation'
    $roots = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $zip = [IO.Compression.ZipFile]::OpenRead($copy)
    try {
        foreach ($entry in $zip.Entries) {
            $parts = $entry.FullName.TrimEnd('/').Split('/')
            $mode = $entry.ExternalAttributes -shr 16
            if ($parts -contains '' -or $parts -contains '.' -or $parts -contains '..' -or $entry.FullName -match '[:\\]' -or
                    ($mode -band 0xF000) -eq 0xA000) { throw 'Unexpected path in the staged archive' }
            $roots.Add($parts[0]) | Out-Null
            $target = [IO.Path]::Combine($installation, [IO.Path]::Combine($parts))
            if ($entry.FullName.EndsWith('/')) {
                [IO.Directory]::CreateDirectory($target) | Out-Null
            } else {
                [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target)
                if (-not $IsWindows) { [IO.File]::SetUnixFileMode($target, [IO.UnixFileMode]($mode -band 0x1FF)) }
            }
        }
    } finally { $zip.Dispose() }
    if ($roots.Count -ne 1) { throw 'Expected one staged installation root' }
    $launcherName = if ($IsWindows) { 'tuprel.bat' } else { 'tuprel' }
    $launcher = Join-Path (Join-Path $installation @($roots)[0]) "bin/$launcherName"
    $project = Join-Path $fixture 'consumer project'
    [IO.Directory]::CreateDirectory($project) | Out-Null
    Push-Location -LiteralPath $project
    try {
        foreach ($arguments in @(@('--version'), @('--help'), @('init'), @('validate'), @('generate'), @('generate','--check'))) {
            & $launcher @arguments
            if ($LASTEXITCODE -ne 0) { throw 'The staged CLI command failed' }
        }
        if (-not (Test-Path -LiteralPath 'tuprel/schema.tuprel' -PathType Leaf) -or
                -not (Get-ChildItem -LiteralPath 'build' -File -Recurse -Filter '*.java')) {
            throw 'The staged launcher did not generate the expected project files'
        }
    } finally { Pop-Location }
    Write-Output "Staged CLI archive SHA-256 $checksum"
} finally {
    $resolved = (Resolve-Path -LiteralPath $fixture).Path
    if ($resolved -ne [IO.Path]::GetFullPath($fixture) -or -not (Split-Path $resolved -Leaf).StartsWith('tuprel staged ')) {
        throw 'Refused cleanup outside the staging fixture'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
