param(
    [Parameter(Mandatory)][string]$Jdk17,
    [Parameter(Mandatory)][string]$Jdk21,
    [Parameter(Mandatory)][string]$Jdk25,
    [string]$FixtureParent = $(if ($IsWindows) { $env:LOCALAPPDATA } else { $HOME }),
    [switch]$Java17Container
)

$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
$root=(Resolve-Path "$PSScriptRoot/../..").Path
$jdks=@{17=(Resolve-Path $Jdk17).Path;21=(Resolve-Path $Jdk21).Path;25=(Resolve-Path $Jdk25).Path}
$environment=@{}
foreach($name in @('JAVA_HOME','PATH','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH','GRADLE_OPTS','GRADLE_USER_HOME')) {
    $environment[$name]=[Environment]::GetEnvironmentVariable($name)
    if($name -notin @('JAVA_HOME','PATH')) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
}
$fixture=Join-Path $FixtureParent ('tuprel installer lts café '+[guid]::NewGuid())
[IO.Directory]::CreateDirectory($fixture) | Out-Null

function Select-Jdk([int]$Version) {
    $env:JAVA_HOME=$jdks[$Version]
    $env:PATH=(Join-Path $env:JAVA_HOME 'bin')+[IO.Path]::PathSeparator+$environment['PATH']
    $reported=(& (Join-Path $env:JAVA_HOME 'bin/java') -version 2>&1 | Out-String)
    if($LASTEXITCODE -ne 0 -or $reported -notmatch ('version "'+$Version+'[."]')) { throw "Expected actual Java $Version" }
    Write-Host $reported.Trim()
}

function Get-Hashes([string]$Directory) {
    $hashes=[ordered]@{}
    foreach($file in Get-ChildItem -LiteralPath $Directory -File -Recurse -Force | Sort-Object FullName) {
        $hashes[[IO.Path]::GetRelativePath($Directory,$file.FullName).Replace('\','/')]=(Get-FileHash $file.FullName -Algorithm SHA256).Hash
    }
    return $hashes
}

try {
    if($IsWindows) {
        $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        & icacls $fixture /setowner "*$sid" | Out-Null
        if($LASTEXITCODE -ne 0){throw 'Cannot set fixture owner'}
        & icacls $fixture /grant:r "*${sid}:(OI)(CI)F" /inheritance:r | Out-Null
        if($LASTEXITCODE -ne 0){throw 'Cannot secure fixture ACL'}
    } else { [IO.File]::SetUnixFileMode($fixture,[IO.UnixFileMode]448) }
    Select-Jdk 21
    $classes=Join-Path $fixture 'classes'
    [IO.Directory]::CreateDirectory($classes) | Out-Null
    $sources=@('SignedCliBundle','SignedCliBundleFixture','VerifyCliBundle','StagingRoot',
        'StagedActivation','StagedBashPath','InstallerRuntimeTest') | ForEach-Object { "$PSScriptRoot/$_.java" }
    & "$env:JAVA_HOME/bin/javac" --release 17 -Xlint:all -Werror -d $classes @sources
    if($LASTEXITCODE -ne 0){throw 'Installer target17 compilation failed'}
    $verifier=Join-Path $fixture 'verify-cli.jar'
    & "$env:JAVA_HOME/bin/jar" --create --file $verifier --main-class VerifyCliBundle `
        -C $classes SignedCliBundle.class -C $classes VerifyCliBundle.class -C $classes StagingRoot.class `
        -C $classes StagedActivation.class -C $classes 'StagedActivation$State.class' `
        -C $classes 'StagedActivation$Authorization.class' -C $classes 'StagedActivation$CommitStage.class'
    if($LASTEXITCODE -ne 0){throw 'Cannot package target17 verifier'}
    $native=Join-Path $fixture $(if($IsWindows){'tuprel-staging.dll'}else{'libtuprel-staging.so'})
    if($IsWindows) {
        & "$root/native/cli-installer/build-windows-staging.cmd" $native
        if($LASTEXITCODE -ne 0){throw 'Native installer compilation failed'}
        & "$root/native/cli-installer/build-windows-path.cmd" (Join-Path $fixture 'tuprel-path-fixture.exe') fixture
    } else { & /bin/bash "$root/native/cli-installer/build-linux-staging.sh" $native }
    if($LASTEXITCODE -ne 0){throw 'Native fixture compilation failed'}
    $versions=@('0.0.0-staging','0.0.1-staging')
    $archives=@()
    Push-Location $root
    try {
        $gradle=if($IsWindows){'.\gradlew.bat'}else{'./gradlew'}
        foreach($version in $versions) {
            & $gradle :tuprel-cli:distZip :tuprel-runtime:jar --no-daemon --dependency-verification strict "-PtuprelStagedCliVersion=$version"
            if($LASTEXITCODE -ne 0){throw 'Versioned CLI preparation failed'}
            $archive=Join-Path $fixture "tuprel-$version.zip"
            Copy-Item -LiteralPath "tuprel-cli/build/distributions/tuprel-$version.zip" -Destination $archive
            $archives+=$archive
        }
    } finally { Pop-Location }
    $consumerLibraries=Join-Path $fixture 'consumer-libraries'
    [IO.Directory]::CreateDirectory($consumerLibraries) | Out-Null
    Copy-Item -LiteralPath "$root/tuprel-runtime/build/libs/tuprel-runtime.jar" -Destination $consumerLibraries
    $catalogue=Join-Path $fixture 'catalogue'
    [IO.Directory]::CreateDirectory($catalogue) | Out-Null
    if(-not $IsWindows){[IO.File]::SetUnixFileMode($catalogue,[IO.UnixFileMode]448)}
    $pin=& "$env:JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED -cp $classes SignedCliBundleFixture prepare $catalogue @archives
    if($LASTEXITCODE -ne 0 -or $pin -cnotmatch '^[a-f0-9]{64}$'){throw 'Signed catalogue preparation failed'}
    [IO.File]::WriteAllText((Join-Path $fixture 'key-id'),$pin)
    & "$env:JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED -cp $classes SignedCliBundleFixture prepare-legacy $catalogue @archives $pin $native
    if($LASTEXITCODE -ne 0){throw 'Java21 legacy installation fixture failed'}
    Copy-Item -LiteralPath "$PSScriptRoot/Verify-InstallerSingleJdk.sh" -Destination (Join-Path $fixture 'single-jdk.sh')
    foreach($class in Get-ChildItem $classes -File -Filter '*.class') {
        $bytes=[IO.File]::ReadAllBytes($class.FullName)
        if($bytes[6]*256+$bytes[7] -ne 61){throw 'Installer class is not major 61'}
    }
    $frozen=Get-Hashes $fixture
    foreach($entry in $frozen.GetEnumerator()) { Write-Host "ARTIFACT $($entry.Key) SHA-256 $($entry.Value)" }
    foreach($version in @(17,21,25)) {
        Select-Jdk $version
        & "$env:JAVA_HOME/bin/java" -cp $classes InstallerRuntimeTest
        if($LASTEXITCODE -ne 0){throw 'Runtime selection unit proof failed'}
        & "$PSScriptRoot/Verify-SignedCli.ps1" -FixtureParent $fixture -PreparedDirectory $fixture
        $actual=Get-Hashes $fixture
        if($actual.Count -ne $frozen.Count){throw 'Prepared artifact inventory changed'}
        foreach($entry in $frozen.GetEnumerator()) {
            if($actual[$entry.Key] -cne $entry.Value){throw "Prepared bytes changed at $($entry.Key)"}
        }
        Write-Host "PASS installer and stable launcher Java $version; identical signed artifacts/native bytes, activation, rollback, recovery and generated compilation"
    }
    if($Java17Container) {
        if(-not $IsLinux){throw 'The Java17-only container proof is Linux-only'}
        $image='eclipse-temurin:17-jdk@sha256:7a0b2bc7e1372bb05ee361fb79e8a9e822df47343b8716253b3bbaa75060653e'
        & docker pull --platform linux/amd64 $image
        if($LASTEXITCODE -ne 0){throw 'Cannot obtain the pinned Java17 image'}
        $uidValue=& id -u
        $gidValue=& id -g
        if($uidValue -notmatch '^[1-9][0-9]*$' -or $gidValue -notmatch '^[0-9]+$'){throw 'Expected an unprivileged UID/GID'}
        $single=Join-Path $fixture 'single JDK17 consumer'
        [IO.Directory]::CreateDirectory($single) | Out-Null
        [IO.File]::SetUnixFileMode($single,[IO.UnixFileMode]448)
        & docker run --rm --platform linux/amd64 --network none --read-only --cap-drop=ALL `
            --security-opt=no-new-privileges --user "${uidValue}:${gidValue}" --tmpfs /tmp `
            --mount "type=bind,source=$fixture,target=$fixture" `
            --env "TUPREL_PREPARED_DIRECTORY=$fixture" --env "TUPREL_SINGLE_ROOT=$single" `
            $image /bin/bash "$fixture/single-jdk.sh"
        if($LASTEXITCODE -ne 0){throw 'Java17-only installed consumer failed'}
        $resolvedSingle=(Resolve-Path $single).Path
        if(-not $resolvedSingle.StartsWith($fixture+[IO.Path]::DirectorySeparatorChar)){throw 'Refused single-JDK cleanup'}
        Remove-Item -LiteralPath $resolvedSingle -Recurse -Force
        $actual=Get-Hashes $fixture
        if($actual.Count -ne $frozen.Count){throw 'Single-JDK proof changed the artifact inventory'}
        foreach($entry in $frozen.GetEnumerator()) {
            if($actual[$entry.Key] -cne $entry.Value){throw 'Single-JDK proof modified trusted artifacts'}
        }
        Write-Host 'PASS installer and launcher in Java17-only container; no network, no host JDK or checkout mounted'
    }
} finally {
    foreach($entry in $environment.GetEnumerator()) {
        if($null -eq $entry.Value){Remove-Item "Env:$($entry.Key)" -ErrorAction SilentlyContinue}
        else{[Environment]::SetEnvironmentVariable($entry.Key,$entry.Value)}
    }
    $resolved=(Resolve-Path $fixture).Path
    if($resolved -cne [IO.Path]::GetFullPath($fixture) -or -not(Split-Path -Leaf $resolved).StartsWith('tuprel installer lts ') -or
            ((Get-Item $resolved).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Refused cleanup outside installer fixture'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
$global:LASTEXITCODE=0
