# Independent fixed-fixture reference for the proposed binary protocol. PowerShell 7.
$ErrorActionPreference = 'Stop'
$utf8 = [Text.UTF8Encoding]::new($false, $true)
$script:planGoldenMemory = [IO.MemoryStream]::new()
function Write-Int32([int]$value) {
    $bytes = [BitConverter]::GetBytes($value)
    if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($bytes) }
    $script:planGoldenMemory.Write($bytes, 0, $bytes.Length)
}
function Write-Int64([long]$value) {
    $bytes = [BitConverter]::GetBytes($value)
    if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($bytes) }
    $script:planGoldenMemory.Write($bytes, 0, $bytes.Length)
}
function Write-Text([string]$value) {
    $bytes = $utf8.GetBytes($value)
    Write-Int32 $bytes.Length
    $script:planGoldenMemory.Write($bytes, 0, $bytes.Length)
}
function Get-Hash([byte[]]$value) { [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($value)).ToLowerInvariant() }
function Write-Risks {
    Write-Int32 2
    Write-Text 'DDL_LOCKS'
    Write-Text 'PHYSICAL_DRIFT_NOT_CHECKED'
}
$lf = [string][char]10
$source = 'CREATE TABLE "app"."Thing" ("id" UUID PRIMARY KEY);' + $lf
$body = [string]::Join($lf, @('CREATE TABLE "app"."Thing" (', '    "id" "pg_catalog"."uuid" NOT NULL PRIMARY KEY', ');', ''))
$preview = '-- Migration 20261031_120001_first' + $lf + $body
$sourceHash = Get-Hash $utf8.GetBytes($source)
$bodyHash = Get-Hash $utf8.GetBytes($body)
$previewHash = Get-Hash $utf8.GetBytes($preview)
Write-Text 'TUPREL-PG-TARGET'
Write-Int32 1
Write-Text 'jdbc:postgresql://localhost:5432/demo'
Write-Text 'app'
Write-Text 'reviewer'
Write-Text 'disable'
$targetHash = Get-Hash $script:planGoldenMemory.ToArray()
$script:planGoldenMemory.Dispose()
$script:planGoldenMemory = [IO.MemoryStream]::new()
Write-Text 'TUPREL-MIGRATION-PLAN'
Write-Int32 1
Write-Text 'postgresql-ddl-v1-proposal'
Write-Text 'postgresql-ddl-preview-v1-proposal'
Write-Text 'postgresql-plan-v1-proposal'
Write-Text ('TUPREL-MIGRATIONS 1' + $lf)
Write-Text $targetHash
Write-Text 'app'
$script:planGoldenMemory.WriteByte(0) # Uninitialised history
Write-Int32 0
Write-Int32 1
Write-Text '20261031_120001'
Write-Text 'first'
Write-Text $sourceHash
Write-Text 'REVIEW_REQUIRED'
Write-Risks
Write-Text $previewHash
Write-Int32 1
Write-Text '20261031_120001'
Write-Int32 1
$script:planGoldenMemory.WriteByte(1)
Write-Text $bodyHash
$script:planGoldenMemory.WriteByte(0)
Write-Risks
$digest = Get-Hash $script:planGoldenMemory.ToArray()
$script:planGoldenMemory.Dispose()
$script:planGoldenMemory = [IO.MemoryStream]::new()
Write-Text 'TUPREL-MIGRATION-PLAN'
Write-Int32 1
Write-Text 'postgresql-ddl-v1-proposal'
Write-Text 'postgresql-ddl-preview-v1-proposal'
Write-Text 'postgresql-plan-v1-proposal'
Write-Text ('TUPREL-MIGRATIONS 1' + $lf)
Write-Text $targetHash
Write-Text 'app'
$script:planGoldenMemory.WriteByte(1)
Write-Int32 1
Write-Text '20261031_120001'
Write-Text 'first'
Write-Text $sourceHash
Write-Int64 2
Write-Int32 123456789
Write-Int64 3
Write-Int32 987654321
Write-Int64 1000
Write-Text 'development'
Write-Int32 1
Write-Text '20261031_120001'
Write-Text 'first'
Write-Text $sourceHash
Write-Text 'NO_CHANGES'
Write-Int32 1
Write-Text 'PHYSICAL_DRIFT_NOT_CHECKED'
Write-Text (Get-Hash $utf8.GetBytes(''))
Write-Int32 0
$historyDigest = Get-Hash $script:planGoldenMemory.ToArray()
$script:planGoldenMemory.Dispose()
if ($digest -ne 'd1af5be3dd5c6c329e75ca9e5e0e0e2c32be15456cfebf7f54b5b348849879fc' -or
    $historyDigest -ne 'efa69f321156a67ae67e92b61e702605d64b3232412fa5305e62a44cde9a6403') {
    throw 'Canonical protocol vector mismatch'
}
[pscustomobject]@{Target=$targetHash;Source=$sourceHash;Body=$bodyHash;Preview=$previewHash;Digest=$digest;HistoryDigest=$historyDigest}|ConvertTo-Json
