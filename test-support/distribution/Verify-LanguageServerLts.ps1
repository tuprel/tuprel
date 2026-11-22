param(
    [Parameter(Mandatory)][string]$Jdk17,
    [Parameter(Mandatory)][string]$Jdk21,
    [Parameter(Mandatory)][string]$Jdk25,
    [string]$BaselineDistribution,
    [switch]$Java17Container
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$root = (Resolve-Path -LiteralPath "$PSScriptRoot/../..").Path
$evidence = Join-Path $root 'build/java-lts-d2/evidence'
[IO.Directory]::CreateDirectory($evidence) | Out-Null
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('tuprel-lsp-lts-' + [guid]::NewGuid() + ' external consumer')
[IO.Directory]::CreateDirectory($fixture) | Out-Null
$savedJava = $env:JAVA_HOME
$savedPath = $env:PATH
$jdks = @{ 17=$Jdk17; 21=$Jdk21; 25=$Jdk25 }

function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed: $Executable (exit $LASTEXITCODE)" }
}

function Get-Inventory([string]$Directory) {
    $result = [ordered]@{}
    foreach ($file in Get-ChildItem -LiteralPath $Directory -Recurse -File | Sort-Object FullName) {
        $result[[IO.Path]::GetRelativePath($Directory, $file.FullName).Replace('\','/')] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    }
    return ($result | ConvertTo-Json -Depth 4)
}

function Get-ServerAbi([string]$Directory) {
    $archive = [IO.Compression.ZipFile]::OpenRead("$Directory/lib/tuprel-language-server.jar")
    try {
        $names = @($archive.Entries | Where-Object { $_.FullName.EndsWith('.class') } | ForEach-Object {
            $_.FullName.Substring(0,$_.FullName.Length - 6).Replace('/','.')
        } | Sort-Object)
    } finally { $archive.Dispose() }
    $output = & (Join-Path $Jdk21 'bin/javap') -protected -s -classpath "$Directory/lib/*" @names 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect server ABI' }
    return $output.Replace("`r`n","`n")
}

try {
    $env:JAVA_HOME = (Resolve-Path -LiteralPath $Jdk21).Path
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $savedPath
    Push-Location $root
    try {
        Invoke-Checked $(if ($IsWindows) { './gradlew.bat' } else { './gradlew' }) @(':tuprel-language-server:installDist', '--no-daemon', '--dependency-verification', 'strict')
    } finally { Pop-Location }
    $distribution = Join-Path $fixture 'installed language server'
    Copy-Item -LiteralPath "$root/tuprel-language-server/build/install/tuprel-language-server" -Destination $distribution -Recurse
    $inventory = Get-Inventory $distribution
    [IO.File]::WriteAllText((Join-Path $evidence 'distribution-sha256.json'), $inventory)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $classes = [ordered]@{}
    foreach ($jar in Get-ChildItem -LiteralPath "$distribution/lib" -Filter '*.jar' | Sort-Object Name) {
        $zip = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
        try {
            foreach ($entry in $zip.Entries | Where-Object { $_.FullName.EndsWith('.class') }) {
                if ($entry.FullName -match '^META-INF/versions/(\d+)/' -and [int]$Matches[1] -gt 17) { continue }
                $stream = $entry.Open()
                try {
                    $header = [byte[]]::new(8)
                    $stream.ReadExactly($header)
                    $major = $header[6] * 256 + $header[7]
                    if ([Convert]::ToHexString($header, 0, 4) -cne 'CAFEBABE' -or $major -gt 61) { throw "Incompatible bytecode: $($jar.Name)/$($entry.FullName)" }
                    if ($jar.Name -like 'tuprel-*.jar' -and $major -ne 61) { throw 'Expected target 17 for every Tuprel class' }
                    $classes[$jar.Name + '/' + $entry.FullName] = $major
                } finally { $stream.Dispose() }
            }
        } finally { $zip.Dispose() }
    }
    $classes | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath "$evidence/class-versions.json"
    $abi = Get-ServerAbi $distribution
    [IO.File]::WriteAllText("$evidence/server-abi.txt", $abi)
    $probe = Join-Path $fixture 'probe'
    [IO.Directory]::CreateDirectory($probe) | Out-Null
    Invoke-Checked (Join-Path $Jdk21 'bin/javac') @('--release','17','-cp', "$distribution/lib/*", '-d', $probe, "$PSScriptRoot/LanguageServerProbe.java")
    $reference = Join-Path $PSScriptRoot 'language-server-reference.jsonl'
    if ($BaselineDistribution) {
        $baseline = (Resolve-Path -LiteralPath $BaselineDistribution).Path
        if ((Get-ServerAbi $baseline) -cne $abi) { throw 'Server class inventory or public/protected ABI changed' }
        $baselineTranscript = Join-Path $evidence 'baseline.jsonl'
        Invoke-Checked (Join-Path $Jdk21 'bin/java') @('-cp', ($probe + [IO.Path]::PathSeparator + "$baseline/lib/*"), 'LanguageServerProbe', $baseline, $Jdk21, 'direct', $baselineTranscript)
        if ((Get-FileHash $baselineTranscript).Hash -cne (Get-FileHash $reference).Hash) { throw 'The baseline does not match the captured reference' }
    }
    foreach ($version in 17,21,25) {
        $jdk = (Resolve-Path -LiteralPath $jdks[$version]).Path
        $java = Join-Path $jdk 'bin/java'
        $identity = (& $java -XshowSettings:properties -version 2>&1 | Out-String)
        if ($LASTEXITCODE -ne 0 -or $identity -notmatch ("java.specification.version = $version\b")) { throw "Wrong JVM for Java $version" }
        $identity | Set-Content -LiteralPath "$evidence/java-$version.txt"
        foreach ($mode in 'direct','launcher') {
            $transcript = Join-Path $evidence "java-$version-$mode.jsonl"
            Invoke-Checked $java @('-cp', ($probe + [IO.Path]::PathSeparator + "$distribution/lib/*"), 'LanguageServerProbe', $distribution, $jdk, $mode, $transcript)
            if ($reference -and (Get-FileHash $transcript).Hash -cne (Get-FileHash $reference).Hash) { throw "Protocol differential failed: Java $version $mode" }
            if (-not $reference) { $reference = $transcript }
        }
        Invoke-Checked (Join-Path $jdk 'bin/jdeps') @('--multi-release','17','--recursive','--class-path', "$distribution/lib/*", "$distribution/lib/tuprel-language-server.jar")
    }
    if ($Java17Container) {
        if (-not $IsLinux) { throw 'The Java17-only container proof requires Linux' }
        $image = 'eclipse-temurin:17-jdk@sha256:7a0b2bc7e1372bb05ee361fb79e8a9e822df47343b8716253b3bbaa75060653e'
        Invoke-Checked 'docker' @('pull', $image)
        foreach ($mode in 'direct','launcher') {
            $transcript = "only17-$mode.jsonl"
            Invoke-Checked 'docker' @('run','--rm','--network','none','--read-only','--cap-drop=ALL','--security-opt','no-new-privileges',
                '--user', (& id -u), '--mount', "type=bind,source=$fixture,target=/consumer",
                '--entrypoint','/opt/java/openjdk/bin/java', $image,
                '-cp','/consumer/probe:/consumer/installed language server/lib/*', 'LanguageServerProbe',
                '/consumer/installed language server','/opt/java/openjdk',$mode,"/consumer/$transcript")
            if ((Get-FileHash "$fixture/$transcript").Hash -cne (Get-FileHash $reference).Hash) { throw 'Java17-only protocol differs' }
            Copy-Item -LiteralPath "$fixture/$transcript" -Destination $evidence
        }
        'PASS: pinned Java17-only image; unprivileged, no network, no host JDK or checkout mounted' | Set-Content "$evidence/java17-only.txt"
    }
    if ((Get-Inventory $distribution) -cne $inventory) { throw 'Installed distribution changed during certification' }
    Write-Host 'PASS: identical installed artifacts and protocol across Java 17, 21 and 25'
} finally {
    $env:JAVA_HOME = $savedJava
    $env:PATH = $savedPath
    $resolved = (Resolve-Path -LiteralPath $fixture).Path
    if ($resolved -cne [IO.Path]::GetFullPath($fixture) -or -not (Split-Path -Leaf $resolved).StartsWith('tuprel-lsp-lts-')) { throw 'Refused fixture cleanup' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
