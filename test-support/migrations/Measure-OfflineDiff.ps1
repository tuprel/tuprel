param(
    [Parameter(Mandatory)][string]$Launcher,
    [ValidateRange(1, 30)][int]$Samples = 7
)

$ErrorActionPreference = 'Stop'
$cli = (Resolve-Path -LiteralPath $Launcher).ProviderPath
if (-not [IO.File]::Exists($cli)) { throw 'Pass the path to an installed Tuprel CLI launcher' }
$cliVersion = & $cli --version
if ($LASTEXITCODE -ne 0) { throw 'The Tuprel launcher failed its version probe' }

$parent = [IO.Path]::TrimEndingDirectorySeparator((Resolve-Path -LiteralPath ([IO.Path]::GetTempPath())).ProviderPath)
$fixtureName = 'tuprel-f6-diff-' + [guid]::NewGuid().ToString('N')
$fixture = Join-Path $parent $fixtureName
$null = New-Item -Path $fixture -ItemType Directory
$firstMigration = [datetime]::new(2026, 10, 31, 12, 0, 0)
$invariant = [Globalization.CultureInfo]::InvariantCulture

try {
    $workloads = foreach ($count in @(1, 100)) {
        $project = Join-Path $fixture "catalog-$count"
        $catalog = Join-Path $project 'tuprel/migrations'
        $null = [IO.Directory]::CreateDirectory($catalog)
        [IO.File]::WriteAllText((Join-Path $catalog '.tuprel-migrations'), "TUPREL-MIGRATIONS 1`n")
        $models = foreach ($index in 1..$count) {
            $name = 'Item{0:D3}' -f $index
            $timestamp = $firstMigration.AddSeconds($index).ToString('yyyyMMdd_HHmmss', $invariant)
            $migration = Join-Path $catalog "${timestamp}_create_item"
            $null = [IO.Directory]::CreateDirectory($migration)
            $sql = 'CREATE TABLE "app"."{0}" ("id" UUID PRIMARY KEY);' -f $name
            [IO.File]::WriteAllText((Join-Path $migration 'migration.sql'), $sql + "`n")
            "model $name { id UUID @id }"
        }
        [IO.File]::WriteAllText((Join-Path $project 'tuprel/schema.tuprel'), ($models -join "`n") + "`n")

        Push-Location -LiteralPath $project
        try {
            $elapsed = for ($index = 0; $index -le $Samples; $index++) {
                $watch = [Diagnostics.Stopwatch]::StartNew()
                $output = & $cli migrate diff --target-schema app
                $status = $LASTEXITCODE
                $watch.Stop()
                if ($status -ne 0 -or ($output -join "`n") -notmatch 'PHYSICAL_STATE_NOT_CHECKED') {
                    throw "Offline diff did not confirm equality for $count migrations"
                }
                if ($index -gt 0) { [Math]::Round($watch.Elapsed.TotalMilliseconds, 3) }
            }
        } finally {
            Pop-Location
        }

        $sorted = @($elapsed | Sort-Object)
        $lower = [int][Math]::Floor(($Samples - 1) / 2)
        $upper = [int][Math]::Floor($Samples / 2)
        [pscustomobject]@{
            migrations = $count
            samplesMs = @($elapsed)
            medianMs = [Math]::Round(($sorted[$lower] + $sorted[$upper]) / 2, 3)
        }
    }

    [pscustomobject]@{
        launcher = "$cliVersion"
        platform = [Runtime.InteropServices.RuntimeInformation]::OSDescription
        architecture = [Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
        warmupPerWorkload = 1
        includesJvmStartup = $true
        workloads = @($workloads)
    } | ConvertTo-Json -Depth 4
} finally {
    $resolved = (Resolve-Path -LiteralPath $fixture).ProviderPath
    if ($resolved -cne $fixture -or (Split-Path -Parent $resolved) -cne $parent -or
            (Split-Path -Leaf $resolved) -cne $fixtureName) {
        throw 'Refused cleanup outside the generated benchmark fixture'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
