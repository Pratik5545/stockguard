$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$environmentPath = Join-Path $projectRoot '.env.docker'

if (Test-Path -LiteralPath $environmentPath) {
    Write-Host '.env.docker already exists; existing credentials were preserved.'
    exit 0
}

function New-RandomSecret {
    $secretBytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($secretBytes)
        return [Convert]::ToBase64String($secretBytes)
    } finally {
        $generator.Dispose()
    }
}

$lines = @(
    '# Generated local Docker secrets. Never commit this file.'
    ('DOCKER_DB_PASSWORD=' + (New-RandomSecret))
    ('DOCKER_JWT_SECRET=' + (New-RandomSecret))
    'APP_PORT=8081'
)
[System.IO.File]::WriteAllLines($environmentPath, $lines)
Write-Host 'Created ignored .env.docker with generated secrets. No secrets were printed.'
