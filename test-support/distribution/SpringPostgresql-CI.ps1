param([switch]$Stop)

$ErrorActionPreference='Stop'
if ($env:GITHUB_ACTIONS -cne 'true' -or $env:RUNNER_OS -cne 'Windows' -or -not $env:RUNNER_TEMP) {
    throw 'This fixture is restricted to disposable Windows GitHub Actions runners'
}
$bin=Join-Path $env:ProgramFiles 'PostgreSQL/17/bin'
$version=(& "$bin/pg_ctl.exe" --version | Out-String)
if ($LASTEXITCODE -ne 0 -or $version -notmatch 'PostgreSQL\) 17\.') { throw 'Expected preinstalled PostgreSQL 17' }
if ($Stop) {
    if ($env:TUPREL_SPRING_PG_DATA) {
        $data=[IO.Path]::GetFullPath($env:TUPREL_SPRING_PG_DATA)
        if (-not $data.StartsWith([IO.Path]::GetFullPath($env:RUNNER_TEMP)+[IO.Path]::DirectorySeparatorChar,
                [StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $data) -notlike 'tuprel-spring-pg-*') {
            throw 'Refused to stop a database outside the disposable fixture'
        }
        & "$bin/pg_ctl.exe" -D $data -w -t 30 -m fast stop
        if ($LASTEXITCODE -ne 0) { throw 'Disposable PostgreSQL did not stop' }
    }
    return
}
$data=Join-Path $env:RUNNER_TEMP ('tuprel-spring-pg-'+[guid]::NewGuid())
$password=Join-Path $env:RUNNER_TEMP ('tuprel-spring-pw-'+[guid]::NewGuid())
$pgPasswordBefore=$env:PGPASSWORD
try {
    [IO.File]::WriteAllText($password,'disposable_fixture')
    & "$bin/initdb.exe" -D $data -U tuprel_fixture --encoding=UTF8 --no-locale `
        --auth-host=scram-sha-256 --auth-local=scram-sha-256 --pwfile=$password
    if ($LASTEXITCODE -ne 0) { throw 'Disposable PostgreSQL initialization failed' }
    "TUPREL_SPRING_PG_DATA=$data" | Out-File -FilePath $env:GITHUB_ENV -Append -Encoding utf8
    & "$bin/pg_ctl.exe" -D $data -l "$data/server.log" -w -t 30 -o '-h 127.0.0.1 -p 55432' start
    if ($LASTEXITCODE -ne 0) { throw 'Disposable PostgreSQL startup failed' }
    $env:PGPASSWORD='disposable_fixture'
    & "$bin/createdb.exe" -h 127.0.0.1 -p 55432 -U tuprel_fixture tuprel_devtest
    if ($LASTEXITCODE -ne 0) { throw 'Disposable test database creation failed' }
    @('TUPREL_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:55432/tuprel_devtest',
        'TUPREL_TEST_DB_USER=tuprel_fixture','TUPREL_TEST_DB_PASSWORD=disposable_fixture') |
        Out-File -FilePath $env:GITHUB_ENV -Append -Encoding utf8
} finally {
    $env:PGPASSWORD=$pgPasswordBefore
    Remove-Item -LiteralPath $password -ErrorAction SilentlyContinue
}
