# Disposable PostgreSQL only. No existing service, profile or trust store is changed.
function Invoke-InstallerTlsSql($State, [string]$Sql) {
    $saved=@{}
    foreach($name in @('PGPASSWORD','PGSSLMODE','PGSSLROOTCERT','PGCLIENTENCODING','PGCONNECT_TIMEOUT','PGOPTIONS')) {
        $saved[$name]=[Environment]::GetEnvironmentVariable($name)
    }
    try {
        $env:PGPASSWORD=$State.AdminPassword
        $env:PGSSLMODE='verify-full'
        $env:PGSSLROOTCERT=if($IsWindows){Join-Path $State.Tls 'ca.crt'}else{'/tls/ca.crt'}
        $env:PGCLIENTENCODING='UTF8'
        $env:PGCONNECT_TIMEOUT='5';$env:PGOPTIONS='-c statement_timeout=10000'
        if($IsWindows) {
            $result=$Sql | & "$($State.Bin)/psql.exe" -X -A -t -v ON_ERROR_STOP=1 -h 127.0.0.1 -p $State.Port -U tls_admin -d tuprel_installer_tls 2>&1
        } else {
            $result=$Sql | & docker exec -i --env PGPASSWORD --env PGSSLMODE --env PGSSLROOTCERT --env PGCLIENTENCODING --env PGCONNECT_TIMEOUT --env PGOPTIONS $State.Container `
                psql -X -A -t -v ON_ERROR_STOP=1 -h 127.0.0.1 -U tls_admin -d tuprel_installer_tls 2>&1
        }
        if($LASTEXITCODE -ne 0){throw 'Disposable TLS fixture SQL failed (details withheld)'}
        return ($result | Out-String).Trim()
    } finally {
        foreach($entry in $saved.GetEnumerator()) {[Environment]::SetEnvironmentVariable($entry.Key,$entry.Value)}
    }
}

function Get-InstallerTlsLog($State) {
    if($IsWindows) {
        $stream=[IO.File]::Open((Join-Path $State.Root 'server.log'),[IO.FileMode]::Open,[IO.FileAccess]::Read,
            [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete)
        $reader=[IO.StreamReader]::new($stream,[Text.Encoding]::UTF8)
        try {return $reader.ReadToEnd()} finally {$reader.Dispose()}
    }
    $result=& docker exec $State.Container cat /var/lib/postgresql/data/fixture-log/server.log 2>&1
    if($LASTEXITCODE -ne 0){throw 'Cannot read disposable TLS server evidence'}
    return ($result | Out-String)
}

function Stop-InstallerTlsFixture($State) {
    if($null -eq $State){return}
    $resolved=(Resolve-Path -LiteralPath $State.Root).Path
    $parent=[IO.Path]::GetFullPath($State.Parent)+[IO.Path]::DirectorySeparatorChar
    if(-not $resolved.StartsWith($parent,[StringComparison]::OrdinalIgnoreCase) -or
        -not (Split-Path -Leaf $resolved).StartsWith('tuprel-installer-tls-') -or
        ((Get-Item -LiteralPath $resolved).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Refused cleanup outside disposable TLS fixture'
    }
    if($IsWindows -and $State.Started) {
        if($State.Data -cne (Join-Path $State.Root 'data')){throw 'Refused a non-fixture cluster'}
        & "$($State.Bin)/pg_ctl.exe" -D $State.Data status *> $null
        if($LASTEXITCODE -eq 0) {
            & "$($State.Bin)/pg_ctl.exe" -D $State.Data -w -t 30 -m fast stop *> $null
            if($LASTEXITCODE -ne 0){throw 'Disposable TLS cluster did not stop'}
        } elseif($LASTEXITCODE -ne 3){throw 'Cannot determine disposable cluster state'}
    } elseif(-not $IsWindows -and $State.Started) {
        if($State.Container -cnotmatch '^tuprel-installer-tls-[0-9a-f]{32}$'){throw 'Refused non-fixture container'}
        $removed=& docker rm -f -v $State.Container 2>&1
        if($LASTEXITCODE -ne 0 -and ($removed|Out-String) -notmatch 'No such container:'){throw 'Disposable TLS container did not stop'}
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

function Start-InstallerTlsFixture([string]$Parent, [string]$PostgresqlBin) {
    if(-not $IsWindows -and $env:GITHUB_ACTIONS -cne 'true') {
        throw 'The Docker TLS fixture is CI-only; local Docker is not supported'
    }
    $parentPath=(Resolve-Path -LiteralPath $Parent).Path
    $state=[pscustomobject]@{
        Root=(Join-Path $parentPath ('tuprel-installer-tls-'+[guid]::NewGuid().ToString('N')))
        Parent=$parentPath; Tls=''; Data=''; Bin=$PostgresqlBin; Port=0; Started=$false
        Container=('tuprel-installer-tls-'+[guid]::NewGuid().ToString('N'))
        AdminPassword=([Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)))
        ClientPassword=('SCRAM-é-'+[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)))
    }
    [IO.Directory]::CreateDirectory($state.Root)|Out-Null
    if($IsWindows) {
        $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        & icacls $state.Root /grant:r "*${sid}:(OI)(CI)F" /inheritance:r *> $null
        if($LASTEXITCODE -ne 0){throw 'Cannot secure TLS fixture ACL'}
    } else {[IO.File]::SetUnixFileMode($state.Root,[IO.UnixFileMode]448)}
    try {
        $state.Tls=Join-Path $state.Root 'tls'
        [IO.Directory]::CreateDirectory($state.Tls)|Out-Null
        $caKey=[Security.Cryptography.RSA]::Create(2048)
        $serverKey=[Security.Cryptography.RSA]::Create(2048)
        $otherKey=[Security.Cryptography.RSA]::Create(2048)
        $ca=$null; $server=$null; $other=$null
        try {
            $sha=[Security.Cryptography.HashAlgorithmName]::SHA256
            $padding=[Security.Cryptography.RSASignaturePadding]::Pkcs1
            $from=[DateTimeOffset]::UtcNow.AddMinutes(-5); $to=$from.AddHours(3)
            $request=[Security.Cryptography.X509Certificates.CertificateRequest]::new('CN=Tuprel fixture CA',$caKey,$sha,$padding)
            $request.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($true,$false,0,$true))
            $request.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509KeyUsageExtension]::new([Security.Cryptography.X509Certificates.X509KeyUsageFlags]::KeyCertSign,$true))
            $ca=$request.CreateSelfSigned($from,$to)
            $request=[Security.Cryptography.X509Certificates.CertificateRequest]::new('CN=localhost',$serverKey,$sha,$padding)
            $san=[Security.Cryptography.X509Certificates.SubjectAlternativeNameBuilder]::new()
            $san.AddDnsName('localhost'); $san.AddIpAddress([Net.IPAddress]::Loopback)
            $request.CertificateExtensions.Add($san.Build())
            $server=$request.Create($ca,$from,$to,[Security.Cryptography.RandomNumberGenerator]::GetBytes(16))
            $request=[Security.Cryptography.X509Certificates.CertificateRequest]::new('CN=Untrusted fixture CA',$otherKey,$sha,$padding)
            $request.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($true,$false,0,$true))
            $other=$request.CreateSelfSigned($from,$to)
            [IO.File]::WriteAllText((Join-Path $state.Tls 'server.crt'),$server.ExportCertificatePem())
            [IO.File]::WriteAllText((Join-Path $state.Tls 'server.key'),$serverKey.ExportRSAPrivateKeyPem())
            [IO.File]::WriteAllText((Join-Path $state.Tls 'ca.crt'),$ca.ExportCertificatePem())
            [IO.File]::WriteAllText((Join-Path $state.Tls 'untrusted.crt'),$other.ExportCertificatePem())
        } finally {
            foreach($cert in @($server,$ca,$other)){if($cert){$cert.Dispose()}}
            $serverKey.Dispose();$caKey.Dispose();$otherKey.Dispose()
        }
        $hba="local all all scram-sha-256`nhostnossl all all 0.0.0.0/0 reject`nhostssl all all 0.0.0.0/0 scram-sha-256`nhost all all ::/0 reject`n"
        [IO.File]::WriteAllText((Join-Path $state.Tls 'pg_hba.conf'),$hba)
        if($IsWindows) {
            if(-not $state.Bin){$state.Bin=Join-Path $env:ProgramFiles 'PostgreSQL/17/bin'}
            $reported=(& "$($state.Bin)/pg_ctl.exe" --version | Out-String)
            if($LASTEXITCODE -ne 0 -or $reported -notmatch 'PostgreSQL\) 17\.'){throw 'Expected PostgreSQL 17 tooling'}
            $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
            $listener.Start();$state.Port=$listener.LocalEndpoint.Port;$listener.Stop()
            $state.Data=Join-Path $state.Root 'data'
            $passwordFile=Join-Path $state.Root 'init-password'
            [IO.File]::WriteAllText($passwordFile,$state.AdminPassword)
            & "$($state.Bin)/initdb.exe" -D $state.Data -U tls_admin --encoding=UTF8 --no-locale `
                --auth-host=scram-sha-256 --auth-local=scram-sha-256 "--pwfile=$passwordFile" *> $null
            if($LASTEXITCODE -ne 0){throw 'Disposable TLS initdb failed'}
            Remove-Item -LiteralPath $passwordFile
            foreach($name in @('server.crt','server.key','pg_hba.conf')) {Copy-Item -LiteralPath (Join-Path $state.Tls $name) -Destination $state.Data}
            $configuration=@"
listen_addresses='127.0.0.1'
port=$($state.Port)
ssl=on
ssl_cert_file='server.crt'
ssl_key_file='server.key'
ssl_min_protocol_version='TLSv1.2'
password_encryption='scram-sha-256'
log_connections=on
log_disconnections=on
log_line_prefix='[%p] '
log_statement='none'
log_min_error_statement='PANIC'
"@
            [IO.File]::AppendAllText((Join-Path $state.Data 'postgresql.conf'),"`n$configuration`n")
            $state.Started=$true
            # Wait for pg_ctl itself, not for inherited pipes held by postgres.
            $start=[Diagnostics.ProcessStartInfo]::new((Join-Path $state.Bin 'pg_ctl.exe'))
            $start.UseShellExecute=$false;$start.CreateNoWindow=$true
            foreach($arg in @('-D',$state.Data,'-l',(Join-Path $state.Root 'server.log'),'-w','-t','30','start')){$start.ArgumentList.Add($arg)}
            $control=[Diagnostics.Process]::Start($start)
            try {
                if(-not $control.WaitForExit(40000)){$control.Kill($true);throw 'Disposable TLS startup timed out'}
                if($control.ExitCode -ne 0){throw 'Disposable TLS PostgreSQL startup failed'}
            } finally {$control.Dispose()}
            $state.Started=$true
            # createdb takes secrets only from the environment, never process arguments.
            $saved=$env:PGPASSWORD;$mode=$env:PGSSLMODE;$cert=$env:PGSSLROOTCERT
            try {
                $env:PGPASSWORD=$state.AdminPassword
                $env:PGSSLMODE='verify-full';$env:PGSSLROOTCERT=Join-Path $state.Tls 'ca.crt'
                & "$($state.Bin)/createdb.exe" -h 127.0.0.1 -p $state.Port -U tls_admin tuprel_installer_tls *> $null
                if($LASTEXITCODE -ne 0){throw 'Disposable TLS database creation failed'}
            } finally {$env:PGPASSWORD=$saved;$env:PGSSLMODE=$mode;$env:PGSSLROOTCERT=$cert}
        } else {
            [IO.File]::SetUnixFileMode($state.Tls,[IO.UnixFileMode]493)
            [IO.File]::SetUnixFileMode((Join-Path $state.Tls 'server.key'),[IO.UnixFileMode]384)
            & sudo chown 999:999 (Join-Path $state.Tls 'server.key')
            if($LASTEXITCODE -ne 0){throw 'Cannot secure the disposable server key'}
            $envFile=Join-Path $state.Root 'container.env'
            [IO.File]::WriteAllLines($envFile,@('POSTGRES_USER=tls_admin','POSTGRES_DB=tuprel_installer_tls',
                "POSTGRES_PASSWORD=$($state.AdminPassword)",'POSTGRES_INITDB_ARGS=--auth-host=scram-sha-256 --auth-local=scram-sha-256'))
            [IO.File]::SetUnixFileMode($envFile,[IO.UnixFileMode]384)
            $state.Started=$true
            $result=& docker run -d --name $state.Container --env-file $envFile --publish '127.0.0.1::5432' `
                --mount "type=bind,source=$($state.Tls),target=/tls,readonly" postgres:17.11-trixie `
                -c ssl=on -c ssl_cert_file=/tls/server.crt -c ssl_key_file=/tls/server.key `
                -c hba_file=/tls/pg_hba.conf -c ssl_min_protocol_version=TLSv1.2 -c password_encryption=scram-sha-256 `
                -c log_connections=on -c log_disconnections=on '-c' 'log_line_prefix=[%p] ' -c log_statement=none -c log_min_error_statement=PANIC `
                -c logging_collector=on -c log_directory=fixture-log -c log_filename=server.log -c log_rotation_age=0 -c log_rotation_size=0 2>&1
            if($LASTEXITCODE -ne 0){throw 'Disposable TLS container startup failed'}
            $state.Started=$true
            $deadline=[DateTime]::UtcNow.AddSeconds(60)
            do {
                & docker exec $state.Container pg_isready -h 127.0.0.1 -U tls_admin -d tuprel_installer_tls *> $null
                if($LASTEXITCODE -eq 0){break}
                if([DateTime]::UtcNow -gt $deadline){throw 'Disposable TLS PostgreSQL readiness timed out'}
                Start-Sleep -Milliseconds 100
            } while($true)
            $port=& docker port $state.Container 5432/tcp
            if($LASTEXITCODE -ne 0 -or $port -notmatch '^127\.0\.0\.1:([0-9]+)$'){throw 'Expected a loopback-only fixture port'}
            $state.Port=[int]$Matches[1]
        }
        $setup=@"
CREATE ROLE fixture_client LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '$($state.ClientPassword)';
REVOKE ALL ON DATABASE tuprel_installer_tls FROM PUBLIC;
GRANT CONNECT ON DATABASE tuprel_installer_tls TO fixture_client;
CREATE SCHEMA fixture_status AUTHORIZATION tls_admin;
GRANT USAGE ON SCHEMA fixture_status TO fixture_client;
"@
        Invoke-InstallerTlsSql $state $setup | Out-Null
        $verified=Invoke-InstallerTlsSql $state @'
SELECT current_setting('server_version_num')::int BETWEEN 170000 AND 179999
 AND current_setting('ssl')='on'
 AND current_setting('password_encryption')='scram-sha-256'
 AND (SELECT rolpassword LIKE 'SCRAM-SHA-256$%' FROM pg_authid WHERE rolname='fixture_client')
 AND (SELECT count(*)=4 AND bool_and(error IS NULL) FROM pg_hba_file_rules)
 AND EXISTS (SELECT 1 FROM pg_hba_file_rules WHERE line_number=2 AND type='hostnossl' AND auth_method='reject')
 AND EXISTS (SELECT 1 FROM pg_hba_file_rules WHERE line_number=3 AND type='hostssl' AND auth_method='scram-sha-256');
'@
        if($verified -cne 't'){throw 'TLS/SCRAM/HBA server policy could not be proven'}
        Write-Host 'FIXTURE PostgreSQL 17: ssl=on, minimum TLSv1.2, SCRAM verifier; HBA line 2 hostnossl reject, line 3 hostssl scram-sha-256'
        return $state
    } catch {Stop-InstallerTlsFixture $state;throw}
}

function Test-InstalledCliTls($State, [string]$Launcher, [string]$Project, [int]$Jdk) {
    $reported=(& "$env:JAVA_HOME/bin/java" -version 2>&1 | Out-String)
    if($Jdk -notin @(17,21,25) -or $LASTEXITCODE -ne 0 -or $reported -notmatch ('version "'+$Jdk+'[."]')) {
        throw 'Installed CLI TLS proof requires the actual selected LTS JVM'
    }
    $clientHome=Join-Path $Project 'isolated TLS home'
    $trust=Join-Path $clientHome $(if($IsWindows){'postgresql/root.crt'}else{'.postgresql/root.crt'})
    [IO.Directory]::CreateDirectory((Split-Path -Parent $trust))|Out-Null
    $ca=Join-Path $State.Tls 'ca.crt'
    function Invoke-Status([string]$Password, [int]$Expected, [string]$Mode='verify-full') {
        $start=[Diagnostics.ProcessStartInfo]::new()
        if($IsWindows) {
            if($Launcher -match '["%\r\n]'){throw 'Unsupported fixture launcher path'}
            $start.FileName=Join-Path ([Environment]::SystemDirectory) 'cmd.exe'
            $start.Arguments='/d /s /c ""'+$Launcher+'" migrate status --target-schema fixture_status"'
        } else {
            $start.FileName=$Launcher
            foreach($arg in @('migrate','status','--target-schema','fixture_status')){$start.ArgumentList.Add($arg)}
        }
        $start.UseShellExecute=$false;$start.CreateNoWindow=$true
        $start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true;$start.WorkingDirectory=$Project
        foreach($name in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','TUPREL_OPTS')){$start.Environment.Remove($name)|Out-Null}
        $start.Environment['JAVA_HOME']=$env:JAVA_HOME
        $start.Environment['JAVA_OPTS']='"-Duser.home='+$clientHome+'"'
        $start.Environment['APPDATA']=$clientHome
        $start.Environment['TUPREL_DATABASE_URL']="jdbc:postgresql://127.0.0.1:$($State.Port)/tuprel_installer_tls?sslmode=$Mode"
        $start.Environment['TUPREL_DATABASE_USER']='fixture_client'
        $start.Environment['TUPREL_DATABASE_PASSWORD']=$Password
        $process=[Diagnostics.Process]::Start($start)
        try {
            $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
            if(-not $process.WaitForExit(60000)){$process.Kill($true);$process.WaitForExit();throw 'Installed CLI TLS command timed out'}
            $output=$stdout.GetAwaiter().GetResult()+$stderr.GetAwaiter().GetResult()
            if($output.Contains($State.ClientPassword) -or $output.Contains($State.AdminPassword) -or $output.Contains($Password)) {
                throw 'Installed CLI exposed a fixture credential'
            }
            if($output -match 'org\.postgresql|PSQLException|jdbc:postgresql://'){throw 'Installed CLI exposed raw driver details'}
            if($process.ExitCode -ne $Expected){throw 'Installed CLI TLS exit code differed (output withheld)'}
            if($Expected -eq 0 -and $output -notmatch 'History: UNINITIALISED'){throw 'Installed CLI did not read the dedicated schema'}
            if($Expected -ne 0 -and $output -match 'History: UNINITIALISED'){throw 'Rejected TLS connection returned a successful status'}
        } finally {
            if(-not $process.HasExited){$process.Kill($true);$process.WaitForExit()}
            $process.Dispose()
        }
    }
    function Wait-ServerEvidence([string]$Before, [string]$Pattern) {
        $deadline=[DateTime]::UtcNow.AddSeconds(5)
        do {
            $after=Get-InstallerTlsLog $State
            if(-not $after.StartsWith($Before)){throw 'TLS server evidence was truncated'}
            $found=[regex]::Match($after.Substring($Before.Length),$Pattern)
            if($found.Success){return $found}
            if([DateTime]::UtcNow -ge $deadline){throw 'Installed CLI server-side evidence missing'}
            Start-Sleep -Milliseconds 50
        } while($true)
    }
    Copy-Item -LiteralPath (Join-Path $State.Tls 'untrusted.crt') -Destination $trust
    Invoke-Status $State.ClientPassword 2
    Copy-Item -LiteralPath $ca -Destination $trust -Force
    $before=Get-InstallerTlsLog $State
    Invoke-Status $State.ClientPassword 0
    $auth=Wait-ServerEvidence $before '\[(\d+)\] LOG:  connection authenticated: identity="fixture_client" method=scram-sha-256 [^\r\n]*:3\)'
    $backend=$auth.Groups[1].Value
    $tls=Wait-ServerEvidence $before ('\['+$backend+'\] LOG:  connection authorized: user=fixture_client database=tuprel_installer_tls[^\r\n]*SSL enabled \(protocol=(TLSv1\.[23]), cipher=([^,\r\n]+), bits=([0-9]+)\)')
    $before=Get-InstallerTlsLog $State
    Invoke-Status ($State.ClientPassword+'-incorrect') 2
    Wait-ServerEvidence $before 'password authentication failed for user "fixture_client"' | Out-Null
    $before=Get-InstallerTlsLog $State
    Invoke-Status $State.ClientPassword 2 'disable'
    Wait-ServerEvidence $before 'pg_hba.conf rejects connection[^\r\n]*user "fixture_client"[^\r\n]*no encryption' | Out-Null
    Invoke-Status $State.ClientPassword 0
    $unchanged=Invoke-InstallerTlsSql $State "SELECT count(*)=0 FROM pg_class WHERE relnamespace='fixture_status'::regnamespace;"
    if($unchanged -cne 't'){throw 'Status unexpectedly created a schema object'}
    Write-Host "PASS installed CLI JDBC/TLS/SCRAM Java ${Jdk}: migrate status --target-schema fixture_status; verify-full, protocol=$($tls.Groups[1].Value), cipher=$($tls.Groups[2].Value), bits=$($tls.Groups[3].Value), backend=$backend, HBA=3/scram-sha-256; untrusted CA, invalid credential and non-TLS refused; history remains absent"
}
