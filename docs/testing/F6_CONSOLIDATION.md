# Evidência da consolidação F6-006

| Perfil | Verificação | Limite da evidência |
| --- | --- | --- |
| Windows x86-64, PowerShell e Java 21 | Launcher real para diff offline, regressões JDBC com PostgreSQL 17 local e publicação nativa em NTFS no runner Windows | O deploy gerido exige o perfil Linux ext4 |
| Linux x86-64, Bash e Java 21 | Launcher real, regressões JDBC com PostgreSQL 17 num container descartável e corte da via de escrita do executor legado | A prova de corte usa um artefacto legado fixado e roles da fixture |
| Linux x86-64 com ext4, PostgreSQL 17 | O consumidor de staging prova publicação exacta, aprovação independente, deploy gerido e preservação dos dados durante o upgrade | Activação operacional exige validação e autorização por alvo |
| Linux x86-64 com XFS | Provas nativas de publicação e recuperação | A prova integral de deploy gerido usa ext4 |

A CI exige a execução das provas de corte legado, perda de confirmação de
commit, término do backend, cancelamento, drift entre commits, rollback da
forma física inesperada e publicação gerida. Os casos de recuperação
distinguem o prefixo confirmado de `OUTCOME_UNKNOWN`. Após um resultado
incerto, seguir a [reconciliação do guia de migrations](../guides/migrations.md).
O playground conserva o bootstrap existente. Uma eventual adopção é uma
tarefa separada e depende de inventário read-only e consentimento.

## Medição reproduzível do diff offline

Empacotar a CLI com Java 21 e indicar o launcher gerado.

```powershell
./gradlew.bat :tuprel-cli:installDist --dependency-verification strict
pwsh -File test-support/migrations/Measure-OfflineDiff.ps1 -Launcher tuprel-cli/build/install/tuprel/bin/tuprel.bat
```

Em Linux, usar `./gradlew` e o launcher `bin/tuprel`. O script cria
catálogos sintéticos temporários com 1 e 100 migrations v1, uma tabela com
chave UUID por ficheiro e um schema Tuprel equivalente. Cada amostra arranca
um novo processo, incluindo a JVM. Depois de uma execução de aquecimento
por catálogo, o JSON regista as amostras e a mediana. A CI faz uma amostra
por catálogo para detectar falhas funcionais, sem impor um limite temporal.
Estas medições cobrem leitura e planeamento offline. A medição de
throughput de deploy deve indicar o perfil PostgreSQL e o ambiente de execução.
