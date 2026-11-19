param(
    [Parameter(Position = 0)]
    [ValidateSet("start", "stop", "status", "restart")]
    [string]$Action = "start"
)

$ErrorActionPreference = "Stop"

# ============================================================
# PATHS
# ============================================================

$Root = "D:\DuAn\Livekit"

$FastApiPath  = Join-Path $Root "meeting-fastapi"
$ServicePath  = Join-Path $Root "meeting-service"
$FrontendPath = Join-Path $Root "meeting-frontend"

# ============================================================
# PORTS
# ============================================================

$FastApiPort  = 8000
$ServicePort  = 8080
$FrontendPort = 3000

# ============================================================
# HELPERS
# ============================================================

function Write-Step($Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Get-PortProcess($Port) {
    return Get-NetTCPConnection `
        -LocalPort $Port `
        -State Listen `
        -ErrorAction SilentlyContinue |
        Select-Object -First 1
}

function Show-PortStatus($Name, $Port) {
    $connection = Get-PortProcess $Port

    if ($connection) {
        $pidValue = $connection.OwningProcess
        $process = Get-Process -Id $pidValue -ErrorAction SilentlyContinue

        if ($process) {
            Write-Host "[RUNNING] $Name :$Port  PID=$pidValue  $($process.ProcessName)" `
                -ForegroundColor Green
        }
        else {
            Write-Host "[RUNNING] $Name :$Port  PID=$pidValue" `
                -ForegroundColor Green
        }
    }
    else {
        Write-Host "[STOPPED] $Name :$Port" -ForegroundColor Yellow
    }
}

function Stop-Port($Name, $Port) {
    $connection = Get-PortProcess $Port

    if (-not $connection) {
        Write-Host "[SKIP] $Name :$Port is not running."
        return
    }

    $pidValue = $connection.OwningProcess
    $process = Get-Process -Id $pidValue -ErrorAction SilentlyContinue

    if ($process) {
        Write-Host "Stopping $Name :$Port PID=$pidValue ($($process.ProcessName))..."
        Stop-Process -Id $pidValue -Force
    }
}

function Wait-Port($Name, $Port, $TimeoutSeconds = 60) {
    Write-Host "Waiting for $Name on port $Port..."

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)

    while ((Get-Date) -lt $deadline) {
        if (Get-PortProcess $Port) {
            Write-Host "[READY] $Name :$Port" -ForegroundColor Green
            return $true
        }

        Start-Sleep -Seconds 1
    }

    Write-Host "[FAILED] $Name did not open port $Port." -ForegroundColor Red
    return $false
}

function Assert-Directory($Name, $Path) {
    if (-not (Test-Path $Path)) {
        throw "$Name directory not found: $Path"
    }
}

# ============================================================
# STATUS
# ============================================================

function Show-Status {
    Write-Step "SYSTEM STATUS"

    Show-PortStatus "meeting-fastapi " $FastApiPort
    Show-PortStatus "meeting-service " $ServicePort
    Show-PortStatus "meeting-frontend" $FrontendPort
}

# ============================================================
# START FASTAPI
# ============================================================

function Start-FastApi {
    if (Get-PortProcess $FastApiPort) {
        Write-Host "[SKIP] FastAPI already running on :$FastApiPort"
        return $true
    }

    Write-Step "START meeting-fastapi"

    Assert-Directory "meeting-fastapi" $FastApiPath

    $VenvPython = Join-Path $FastApiPath ".venv\Scripts\python.exe"

    if (Test-Path $VenvPython) {

        $Command = @"
Set-Location '$FastApiPath'
Write-Host 'meeting-fastapi' -ForegroundColor Cyan
& '$VenvPython' -m uvicorn app.main:app --host 0.0.0.0 --port $FastApiPort --reload
"@
    }
    else {

        $Command = @"
Set-Location '$FastApiPath'
Write-Host 'meeting-fastapi' -ForegroundColor Cyan
py -m uvicorn app.main:app --host 0.0.0.0 --port $FastApiPort --reload
"@
    }

    Start-Process powershell.exe `
        -ArgumentList "-NoExit", "-Command", $Command

    return Wait-Port "meeting-fastapi" $FastApiPort 30
}

# ============================================================
# START JAVA
# ============================================================

function Start-Service {
    if (Get-PortProcess $ServicePort) {
        Write-Host "[SKIP] meeting-service already running on :$ServicePort"
        return $true
    }

    Write-Step "START meeting-service"

    Assert-Directory "meeting-service" $ServicePath

    $Command = @"
Set-Location '$ServicePath'
Write-Host 'meeting-service' -ForegroundColor Cyan
mvn spring-boot:run
"@

    Start-Process powershell.exe `
        -ArgumentList "-NoExit", "-Command", $Command

    return Wait-Port "meeting-service" $ServicePort 90
}

# ============================================================
# START FRONTEND
# ============================================================

function Start-Frontend {
    if (Get-PortProcess $FrontendPort) {
        Write-Host "[SKIP] meeting-frontend already running on :$FrontendPort"
        return $true
    }

    Write-Step "START meeting-frontend"

    Assert-Directory "meeting-frontend" $FrontendPath

    $Command = @"
Set-Location '$FrontendPath'
Write-Host 'meeting-frontend' -ForegroundColor Cyan

if (Test-Path '.next\trace') {
    Remove-Item '.next\trace' -Force -ErrorAction SilentlyContinue
}

npm run dev
"@

    Start-Process powershell.exe `
        -ArgumentList "-NoExit", "-Command", $Command

    return Wait-Port "meeting-frontend" $FrontendPort 60
}

# ============================================================
# START ALL
# ============================================================

function Start-All {
    Write-Host ""
    Write-Host "=============================================" -ForegroundColor Cyan
    Write-Host " LIVEKIT MEETING - DEVELOPMENT START" -ForegroundColor Cyan
    Write-Host "=============================================" -ForegroundColor Cyan

    Assert-Directory "meeting-fastapi" $FastApiPath
    Assert-Directory "meeting-service" $ServicePath
    Assert-Directory "meeting-frontend" $FrontendPath

    # Start dependency order:
    # FastAPI -> Java -> Frontend

    if (-not (Start-FastApi)) {
        throw "FastAPI failed to start. Java/Frontend startup cancelled."
    }

    if (-not (Start-Service)) {
        throw "meeting-service failed to start. Frontend startup cancelled."
    }

    if (-not (Start-Frontend)) {
        throw "meeting-frontend failed to start."
    }

    Write-Step "ALL SERVICES READY"

    Show-Status

    Write-Host ""
    Write-Host "Frontend : http://localhost:3000" -ForegroundColor Green
    Write-Host "Java     : http://localhost:8080" -ForegroundColor Green
    Write-Host "FastAPI  : http://localhost:8000" -ForegroundColor Green
    Write-Host "FastAPI Docs: http://localhost:8000/docs" -ForegroundColor Green
}

# ============================================================
# STOP ALL
# ============================================================

function Stop-All {
    Write-Step "STOP LIVEKIT MEETING"

    # Reverse dependency order:
    # Frontend -> Java -> FastAPI

    Stop-Port "meeting-frontend" $FrontendPort
    Stop-Port "meeting-service" $ServicePort
    Stop-Port "meeting-fastapi" $FastApiPort

    Start-Sleep -Seconds 1

    Show-Status
}

# ============================================================
# COMMAND
# ============================================================

switch ($Action) {

    "start" {
        Start-All
    }

    "stop" {
        Stop-All
    }

    "status" {
        Show-Status
    }

    "restart" {
        Stop-All
        Start-Sleep -Seconds 2
        Start-All
    }
}