# Starts the whole system in Docker for a demo (Windows):
#   - broker + publisher web UI (http://localhost:3000), opened in the browser
#   - a window with the broker's log
#   - one window per receiver in $receivers below: alice, bob, carol and dave
#
# Usage (from anywhere; or double-click start.cmd):
#   powershell -ExecutionPolicy Bypass -File Lab1\start.ps1              build, then start
#   powershell -ExecutionPolicy Bypass -File Lab1\start.ps1 -SkipBuild   start without rebuilding
#   powershell -ExecutionPolicy Bypass -File Lab1\start.ps1 -Stop        stop everything

param(
    [switch]$SkipBuild,
    [switch]$Stop
)

$root = $PSScriptRoot
$uiUrl = 'http://localhost:3000'
# Receivers to start, one window each. Add, remove or edit lines to change the demo.
# Name is the receiver's id (-c) and Topics its comma-separated topics (-t).
$receivers = @(
    @{ Name = 'alice'; Topics = 'news,sports' },
    @{ Name = 'bob';   Topics = 'news' },
    @{ Name = 'carol'; Topics = 'weather' },
    @{ Name = 'dave';  Topics = 'news,sports,weather' }
)

Set-Location $root

function Stop-Everything {
    Write-Host 'Stopping receivers, broker and publisher UI...' -ForegroundColor Cyan
    foreach ($receiver in $receivers) { docker rm -f "pad-receiver-$($receiver.Name)" 2>$null | Out-Null }
    docker compose --profile clients down --remove-orphans
    Write-Host 'Stopped. You can close the receiver and log windows.' -ForegroundColor Green
}

function Test-Docker {
    docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
    return $LASTEXITCODE -eq 0
}

function Start-DockerDesktop {
    $candidates = @(
        "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe",
        "$env:LOCALAPPDATA\Programs\DockerDesktop\Docker Desktop.exe"
    )
    $exe = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $exe) { return $false }
    Write-Host 'Starting Docker Desktop (this can take a minute)...' -ForegroundColor Cyan
    Start-Process $exe
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 3
        if (Test-Docker) { return $true }
    }
    return $false
}

# Opens a new PowerShell window in this folder that runs $command and stays open afterwards.
function Open-Window($title, $hint, $command) {
    $script = "`$Host.UI.RawUI.WindowTitle = '$title'; Set-Location '$root'; Write-Host '$hint' -ForegroundColor DarkGray; $command"
    Start-Process powershell -ArgumentList '-NoExit', '-NoProfile', '-Command', $script | Out-Null
}

if ($Stop) {
    Stop-Everything
    exit 0
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Host 'Docker is not installed. Install Docker Desktop: https://www.docker.com/products/docker-desktop/' -ForegroundColor Red
    exit 1
}
if (-not (Test-Docker) -and -not (Start-DockerDesktop)) {
    Write-Host 'Docker is not running. Start Docker Desktop, wait for "Engine running", and try again.' -ForegroundColor Red
    exit 1
}

if (-not $SkipBuild) {
    Write-Host 'Building images (the first build takes a few minutes)...' -ForegroundColor Cyan
    docker compose --profile clients build
    if ($LASTEXITCODE -ne 0) { Write-Host 'Build failed.' -ForegroundColor Red; exit 1 }
}

# Leftovers from a previous run would block the fixed container names.
foreach ($receiver in $receivers) { docker rm -f "pad-receiver-$($receiver.Name)" 2>$null | Out-Null }

Write-Host 'Starting broker and publisher UI...' -ForegroundColor Cyan
docker compose up -d broker publisher-ui
if ($LASTEXITCODE -ne 0) { Write-Host 'Could not start the containers.' -ForegroundColor Red; exit 1 }

$ready = $false
for ($i = 0; $i -lt 30 -and -not $ready; $i++) {
    try {
        Invoke-WebRequest $uiUrl -UseBasicParsing -TimeoutSec 2 | Out-Null
        $ready = $true
    } catch {
        Start-Sleep -Seconds 1
    }
}
if (-not $ready) { Write-Host "The UI did not come up at $uiUrl; check 'docker compose logs publisher-ui'." -ForegroundColor Yellow }

Open-Window 'Broker log' 'Broker log. Closing this window does not stop the broker.' 'docker compose logs -f broker'
foreach ($receiver in $receivers) {
    $name = $receiver.Name
    $run = "docker compose run --rm --name pad-receiver-$name receiver -t $($receiver.Topics) -c $name -w 1"
    Open-Window "Receiver $name" "Receiver $name. Ctrl+C stops it; press Up then Enter to start it again (missed messages are replayed)." $run
}

Start-Process $uiUrl

Write-Host ''
Write-Host 'Everything is running:' -ForegroundColor Green
Write-Host "  Publisher UI   $uiUrl  (opened in your browser)"
Write-Host '  Broker         localhost:5000  (log in the "Broker log" window)'
Write-Host '  Receivers      (one window each)'
foreach ($receiver in $receivers) { Write-Host ("                 {0,-6} {1}" -f $receiver.Name, ($receiver.Topics -replace ',', ', ')) }
Write-Host ''
Write-Host 'In the UI, add publisher-news / news, publisher-sports / sports and publisher-weather / weather,'
Write-Host 'then send messages.'
Write-Host 'See README.md, "What to try", for the full walkthrough.'
Write-Host ''
Read-Host 'Press Enter here to stop everything'
Stop-Everything
