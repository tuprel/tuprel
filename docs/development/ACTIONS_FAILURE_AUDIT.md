# Actions dependency update audit

The audit starts at main `39b35e30fb18ad2bd1d70ff27333fb31a390bb0e`,
after #116. The corrections for CodeQL (#89/#90) and setup-gradle (#47)
use independent branches based on that commit.

## Recorded failures

| PR and job | Evidence | Classification |
| --- | --- | --- |
| #89, [Java CodeQL](https://github.com/tuprel/tuprel/actions/runs/44802519923/job/136518031897) | Configuration loaded by 4.39.1, analyze running 4.39.3 | Incompatible action pair |
| #90, [native CodeQL](https://github.com/tuprel/tuprel/actions/runs/44802511096/job/136518007010) | Configuration loaded by 4.39.3, analyze running 4.39.1 | Incompatible action pair |
| #89, native XFS job 136518033311 | Maven Central returned HTTP 429 resolving Kotlin metadata | Repository throttling |
| #90, native XFS job 136518008242 | Maven Central returned HTTP 429 resolving Kotlin metadata | Repository throttling |
| #47, external Maven job 136518022044 | Maven Central returned HTTP 429 during build-logic configuration | Repository throttling |
| #89, external Maven job 136518031843 | Kotlin metadata could not be found through Plugin Portal | Resolution failure; this log does not establish HTTP 429 |
| #90, [Java CodeQL build](https://github.com/tuprel/tuprel/actions/runs/44802511096/job/136518007553) | Five JavaConventionsPluginTest cases failed | Investigated separately below |

The action pins were checked against the upstream annotated release tags
with `git ls-remote`. CodeQL v4.39.3 peels to
`33d7b85da1a04171e211befe2c739303b9a4c066`. Gradle actions v6.4.1 peels
to `8da46b539cd8fe6ab27ca75128919a18d757604c`.

The CodeQL correction updates all four init/analyze references together and
groups future Dependabot updates. Existing permissions and language coverage
remain part of the validation.

The setup-gradle correction preserves the Dependabot commit and adds cache
restoration before Maven staging. Only the Gradle dependency cache is reused.
The Maven/JDK certification still uses independent empty Maven caches. Staged
CLI concurrency cancels obsolete runs on the same PR.

## TestKit investigation

All six convention-plugin tests passed locally on the baseline using Java 21,
Gradle 9.9.1 and strict verification. A separate experiment changed only the
repository URL of isolated TestKit fixtures to a loopback server returning
HTTP 429. Exactly these five cases then failed:

- `errorProneFailsOnKnownBugPattern`
- `warningsAreErrors`
- `wiresIntegrationTestIntoCheck`
- `runsUnitTestsOnJUnitPlatform`
- `rejectsDependencyOutsideVerificationBaseline`

The configuration-report test passed. Each failed case's complete diagnostic
contained HTTP 429. Artifact resolution failed before the compiler warning,
Error Prone, JUnit execution or dependency-verification assertion it was meant
to exercise. No assertion was changed.

This reproduces the failed-test set in #90. Its original console log omits
the nested build diagnostics, so the historical cause remains unconfirmed.
The correction enables full failure logging for build-logic tests so subsequent
failures include that evidence. Repeating all six tests with a fresh TestKit
cache and the normal repository passed. Remote HEAD checks are recorded in
the corrective PR descriptions.

### Reproduce the isolated repository failure

Use PowerShell 7 and Java 21. In a first terminal, start a loopback-only server.
Stop it with Ctrl+C when the experiment finishes.

```powershell
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$listener.Start()
$listener.LocalEndpoint.Port
try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        try {
            $stream = $client.GetStream()
            $stream.ReadTimeout = 5000
            $buffer = [byte[]]::new(8192)
            $null = $stream.Read($buffer, 0, $buffer.Length)
            $reply = [Text.Encoding]::ASCII.GetBytes(
                "HTTP/1.1 429 Too Many Requests`r`nRetry-After: 1`r`nContent-Length: 0`r`nConnection: close`r`n`r`n")
            $stream.Write($reply, 0, $reply.Length)
        } finally { $client.Dispose() }
    }
} finally { $listener.Stop() }
```

In a second terminal, create a fresh fixture directory under the checkout's
ignored `build/` directory. Put the following in
`<fixture>/init.d/throttled-repository.gradle`, substituting the printed port.
The server returns no artifact bytes.

```groovy
settingsEvaluated { settings ->
    if (settings.rootProject.name == 'fixture') {
        settings.dependencyResolutionManagement.repositories
            .withType(org.gradle.api.artifacts.repositories.MavenArtifactRepository)
            .configureEach { repository ->
                repository.setUrl('http://127.0.0.1:PORT/')
                repository.setAllowInsecureProtocol(true)
            }
    }
}
```

Put this in a separate `<audit>/isolated-testkit.gradle` file.

```groovy
gradle.projectsEvaluated {
    rootProject.tasks.named('test').configure {
        systemProperty 'org.gradle.testkit.dir',
            System.getProperty('tuprel.audit.testkit')
    }
}
```

```powershell
.\gradlew.bat -p build-logic test --rerun --no-daemon `
  --no-configuration-cache --dependency-verification strict `
  -I $auditInit "-Dtuprel.audit.testkit=$fixture"
```

Expect a failed build with six cases and exactly five failures, all containing
HTTP 429. Repeat with a different fresh fixture directory containing no
repository override to check the normal behavior. Inspect
`build-logic/build/test-results/test/TEST-dev.tuprel.buildlogic.JavaConventionsPluginTest.xml`.
The HTTP override belongs exclusively to this failure experiment.

## Rate-limit boundaries

Sonatype documents that shared CI egress can aggregate requests from unrelated
jobs. An individual HTTP 429 does not establish which workload crossed the
threshold. Cache reuse and cancelling superseded runs reduce redundant work;
they cannot guarantee availability for a cold cache. Keep strict verification
active and classify repository failures separately from assertion failures.
[Maven Central rate-limit guidance](https://central.sonatype.org/faq/429-error/)
