param([string]$JavaHome)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
if (-not (Test-Path (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    throw 'Set JAVA_HOME to Java 25 or pass -JavaHome with the JDK directory.'
}
$allowed = @('DB_URL','DB_USERNAME','DB_PASSWORD','DB_MIGRATION_USERNAME','DB_MIGRATION_PASSWORD','PORT','JWT_PRIVATE_KEY','JWT_PUBLIC_KEY','JWT_ISSUER','SPRING_PROFILES_ACTIVE','EVENTS_ENABLED','KAFKA_BOOTSTRAP_SERVERS','WEBHOOK_ENCRYPTION_KEY','WEBHOOK_ALLOWED_ORIGINS','WEBHOOK_ALLOW_LOCAL')
$envPath = Join-Path $projectRoot '.env'
if (Test-Path $envPath) {
    foreach ($line in Get-Content $envPath) {
        if ($line -match '^([A-Z_]+)=(.*)$' -and $allowed -contains $Matches[1]) {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
        }
    }
}
if (-not $env:WEBHOOK_ENCRYPTION_KEY -and (Test-Path (Join-Path $projectRoot '.local-secrets/webhook-key'))) {
    $env:WEBHOOK_ENCRYPTION_KEY = (Get-Content (Join-Path $projectRoot '.local-secrets/webhook-key') -Raw).Trim()
}
Push-Location (Join-Path $projectRoot 'backend')
try { & .\gradlew.bat bootRun; exit $LASTEXITCODE } finally { Pop-Location }
