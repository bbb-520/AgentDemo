$ErrorActionPreference = 'Stop'

Write-Host 'Building Maven artifacts...'
& mvn -DskipTests package
if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }

Write-Host 'Building Docker images...'
& docker compose -f infra/docker-compose.yml -f docker-compose.app.yml build
if ($LASTEXITCODE -ne 0) { throw 'Docker image build failed.' }
