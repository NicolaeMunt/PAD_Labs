# Builds all three modules natively (no Docker). Run from anywhere:
#   powershell -ExecutionPolicy Bypass -File Lab1\build.ps1
# A module whose toolchain is missing is skipped with a message; the others still build.

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$failed = $false

function Test-Tool($name) { return [bool](Get-Command $name -ErrorAction SilentlyContinue) }

function Invoke-Step($title, $dir, [scriptblock]$body) {
    Write-Host "`n=== $title" -ForegroundColor Cyan
    Push-Location $dir
    try {
        & $body
        if ($LASTEXITCODE -ne 0) { throw "exit code $LASTEXITCODE" }
        Write-Host "OK" -ForegroundColor Green
    } catch {
        Write-Host "FAILED: $_" -ForegroundColor Red
        $script:failed = $true
    } finally {
        Pop-Location
    }
}

if ((Test-Tool 'java') -and (Test-Tool 'mvn')) {
    Invoke-Step 'Broker (Java / Maven)' "$root\Broker" { mvn -q -B package -DskipTests }
} else {
    Write-Host "`n=== Broker skipped: install JDK 17+ and Maven" -ForegroundColor Yellow
}

if (Test-Tool 'npm') {
    Invoke-Step 'Publisher (Node.js / TypeScript)' "$root\Publisher" { npm ci --no-audit --no-fund; if ($LASTEXITCODE -eq 0) { npm run build } }
} else {
    Write-Host "`n=== Publisher skipped: install Node.js 18+" -ForegroundColor Yellow
}

if (Test-Tool 'dotnet') {
    Invoke-Step 'Receiver (.NET 8)' "$root\Receiver\src" { dotnet publish Receiver.csproj -c Release -o "$root\Receiver\out" --nologo }
} else {
    Write-Host "`n=== Receiver skipped: install the .NET 8 SDK" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Artifacts:"
Write-Host "  Broker    -> Broker\target\Broker-1.0-SNAPSHOT.jar"
Write-Host "  Publisher -> Publisher\dist\index.js"
Write-Host "  Receiver  -> Receiver\out\Receiver.dll"
if ($failed) { exit 1 }
