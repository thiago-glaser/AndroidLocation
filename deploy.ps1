<#
.SYNOPSIS
    Builds and deploys DeliveryTrips Android App to Google Play via Developer Publishing API.

.EXAMPLE (default: builds signed release AAB and publishes to internal testing track)
    .\deploy.ps1

.EXAMPLE (skip build if app-release.aab is already built)
    .\deploy.ps1 -SkipBuild

.EXAMPLE (deploy to another track: internal, alpha, beta, production)
    .\deploy.ps1 -Track "beta"
#>
param(
    [string]$Track = "internal",
    [string]$KeyPath = "$PSScriptRoot\play-service-account.json",
    [string]$PackageName = "com.deliverytrips.locationservice",
    [string]$AabPath = "$PSScriptRoot\app\build\outputs\bundle\release\app-release.aab",
    [string]$ReleaseName = "",
    [string]$NotesEn = "Fix vehicle Bluetooth auto-start trip detection, resolve session event queuing, and auto-close stale sessions.",
    [string]$NotesPt = "Correção no início automático de viagens via Bluetooth do veículo, resolução da fila de eventos e encerramento automático de sessões antigas.",
    [switch]$SkipBuild,
    [switch]$Draft
)

$ErrorActionPreference = "Stop"

function TS { Get-Date -Format '[HH:mm:ss]' }
function Write-Step {
    param($msg)
    Write-Host ""
    Write-Host "$(TS) ------------------------------------------" -ForegroundColor DarkGray
    Write-Host "$(TS)   $msg" -ForegroundColor Cyan
    Write-Host "$(TS) ------------------------------------------" -ForegroundColor DarkGray
}
function Write-OK { param($msg) Write-Host "$(TS) [OK]   $msg" -ForegroundColor Green }
function Write-Warn { param($msg) Write-Host "$(TS) [WARN] $msg" -ForegroundColor Yellow }
function Write-Fail { param($msg) Write-Host "$(TS) [FAIL] $msg" -ForegroundColor Red }

# -----------------------------------------------------------------------------
# Preflight Checks
# -----------------------------------------------------------------------------
Write-Step "Preflight checks"

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Fail "Node.js is required for API token authentication and upload. Please install Node.js."
    exit 1
}
Write-OK "Node.js is available"

if (-not (Test-Path $KeyPath)) {
    Write-Fail "Service account key not found at: $KeyPath"
    Write-Host "    Please place your Google Play service account JSON key at $KeyPath" -ForegroundColor Yellow
    exit 1
}
Write-OK "Service account key found ($KeyPath)"

$status = if ($Draft) { "draft" } else { "completed" }

# -----------------------------------------------------------------------------
# Step 1: Build Release Bundle
# -----------------------------------------------------------------------------
if ($SkipBuild) {
    Write-Host "    [SKIP] Building release bundle (-SkipBuild)" -ForegroundColor DarkGray
    if (-not (Test-Path $AabPath)) {
        Write-Fail "Release bundle not found at $AabPath. Please build it first or remove -SkipBuild."
        exit 1
    }
} else {
    Write-Step "Step 1 - Building signed release bundle (AAB)"
    
    # Ensure any stuck gradle daemon or file lock is cleanly handled
    Push-Location $PSScriptRoot
    try {
        $gradleCmd = ".\gradlew.bat"
        if (-not (Test-Path $gradleCmd)) { $gradleCmd = "gradlew" }
        & $gradleCmd --stop | Out-Null
        if (Test-Path "app\build") {
            Remove-Item -Recurse -Force "app\build" -ErrorAction SilentlyContinue
        }
        Write-Host "    Running ./gradlew bundleRelease ..." -ForegroundColor DarkGray
        
        & $gradleCmd bundleRelease --no-daemon
        if ($LASTEXITCODE -ne 0) {
            Write-Fail "Gradle bundleRelease build failed with exit code $LASTEXITCODE"
            exit 1
        }
        Write-OK "Release bundle built successfully"
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path $AabPath)) {
    Write-Fail "Expected bundle file not found: $AabPath"
    exit 1
}

$aabSizeMb = [math]::Round(((Get-Item $AabPath).Length / 1MB), 2)
Write-OK "Bundle ready: $AabPath ($aabSizeMb MB)"

# -----------------------------------------------------------------------------
# Step 2: Publish via Google Play Developer API
# -----------------------------------------------------------------------------
Write-Step "Step 2 - Deploying to Google Play via Publishing API ($Track track)"

$publishScript = Join-Path $PSScriptRoot "scripts\publish.js"
if (-not (Test-Path $publishScript)) {
    Write-Fail "Publish script not found: $publishScript"
    exit 1
}

$nodeArgs = @(
    $publishScript,
    "--key", $KeyPath,
    "--aab", $AabPath,
    "--package", $PackageName,
    "--track", $Track,
    "--status", $status,
    "--notes-en", $NotesEn,
    "--notes-pt", $NotesPt
)

if ($ReleaseName) {
    $nodeArgs += "--name", $ReleaseName
}

& node @nodeArgs

if ($LASTEXITCODE -ne 0) {
    Write-Fail "Google Play API deployment failed."
    exit 1
}

Write-Host ""
Write-Host "$(TS) ==========================================" -ForegroundColor Green
Write-Host "$(TS) [OK]   Google Play API Deployment Complete!" -ForegroundColor Green
Write-Host "$(TS)   Package : $PackageName" -ForegroundColor White
Write-Host "$(TS)   Track   : $Track" -ForegroundColor White
Write-Host "$(TS)   Status  : $status" -ForegroundColor White
Write-Host "$(TS) ==========================================" -ForegroundColor Green
