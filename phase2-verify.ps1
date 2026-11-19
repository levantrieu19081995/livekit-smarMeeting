param(
    [string]$Root = "D:\DuAn\Livekit",
    [switch]$Full,
    [switch]$SkipRuntime
)

$ErrorActionPreference = "Stop"

$FrontendPath = Join-Path $Root "meeting-frontend"
$ServicePath  = Join-Path $Root "meeting-service"
$FastApiPath  = Join-Path $Root "meeting-fastapi"

$Results = [System.Collections.Generic.List[object]]::new()

function Add-Result {
    param(
        [string]$Name,
        [bool]$Passed,
        [string]$Detail
    )

    $Results.Add([PSCustomObject]@{
        Check  = $Name
        Result = if ($Passed) { "PASS" } else { "FAIL" }
        Detail = $Detail
    })
}

function Write-Section {
    param([string]$Title)

    Write-Host ""
    Write-Host "============================================================" -ForegroundColor DarkCyan
    Write-Host " $Title" -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor DarkCyan
}

function Assert-ProjectDirectory {
    param(
        [string]$Name,
        [string]$Path
    )

    if (-not (Test-Path $Path -PathType Container)) {
        throw "$Name directory not found: $Path"
    }
}

function Invoke-ExternalCheck {
    param(
        [string]$Name,
        [string]$WorkingDirectory,
        [scriptblock]$Command
    )

    Write-Host ""
    Write-Host ">>> $Name" -ForegroundColor Cyan

    Push-Location $WorkingDirectory

    try {
        & $Command
        $exitCode = $LASTEXITCODE

        if ($null -eq $exitCode) {
            $exitCode = 0
        }

        if ($exitCode -eq 0) {
            Write-Host "[PASS] $Name" -ForegroundColor Green
            Add-Result $Name $true "exitCode=0"
            return $true
        }

        Write-Host "[FAIL] $Name (exitCode=$exitCode)" -ForegroundColor Red
        Add-Result $Name $false "exitCode=$exitCode"
        return $false
    }
    catch {
        Write-Host "[FAIL] $Name - $($_.Exception.Message)" -ForegroundColor Red
        Add-Result $Name $false $_.Exception.Message
        return $false
    }
    finally {
        Pop-Location
    }
}

function Test-HttpEndpoint {
    param(
        [string]$Name,
        [string]$Url,
        [int]$ExpectedStatus = 200
    )

    Write-Host "Checking $Name -> $Url"

    try {
        $response = Invoke-WebRequest `
            -Uri $Url `
            -Method Get `
            -UseBasicParsing `
            -TimeoutSec 10

        if ($response.StatusCode -eq $ExpectedStatus) {
            Write-Host "[PASS] $Name HTTP $($response.StatusCode)" -ForegroundColor Green
            Add-Result $Name $true "HTTP $($response.StatusCode)"
            return $true
        }

        Write-Host "[FAIL] $Name expected HTTP $ExpectedStatus but got $($response.StatusCode)" -ForegroundColor Red
        Add-Result $Name $false "HTTP $($response.StatusCode)"
        return $false
    }
    catch {
        $statusCode = $null

        if ($_.Exception.Response) {
            try {
                $statusCode = [int]$_.Exception.Response.StatusCode
            }
            catch {
            }
        }

        $detail = if ($statusCode) {
            "HTTP $statusCode"
        }
        else {
            $_.Exception.Message
        }

        Write-Host "[FAIL] $Name - $detail" -ForegroundColor Red
        Add-Result $Name $false $detail
        return $false
    }
}

function Get-PythonCommand {
    param([string]$ProjectPath)

    $venvPython = Join-Path $ProjectPath ".venv\Scripts\python.exe"

    if (Test-Path $venvPython -PathType Leaf) {
        return @{
            File = $venvPython
            ArgsPrefix = @()
        }
    }

    $python = Get-Command python -ErrorAction SilentlyContinue

    if ($python) {
        return @{
            File = $python.Source
            ArgsPrefix = @()
        }
    }

    $py = Get-Command py -ErrorAction SilentlyContinue

    if ($py) {
        return @{
            File = $py.Source
            ArgsPrefix = @("-3")
        }
    }

    throw "Python not found. Expected .venv\Scripts\python.exe, python, or py."
}

Write-Section "LIVEKIT PHASE 1 + PHASE 2 VERIFICATION"

Assert-ProjectDirectory "meeting-frontend" $FrontendPath
Assert-ProjectDirectory "meeting-service"  $ServicePath
Assert-ProjectDirectory "meeting-fastapi"  $FastApiPath

Write-Host "Root: $Root"
Write-Host "Mode: $(if ($Full) { 'FOCUSED + FULL REGRESSION' } else { 'FOCUSED REGRESSION' })"
Write-Host "Runtime health: $(if ($SkipRuntime) { 'SKIPPED' } else { 'ENABLED' })"

# ============================================================
# 1. RUNTIME HEALTH
# ============================================================

if (-not $SkipRuntime) {
    Write-Section "1. RUNTIME HEALTH"

    Test-HttpEndpoint `
        -Name "FastAPI health" `
        -Url "http://localhost:8000/health"

    Test-HttpEndpoint `
        -Name "Java actuator health" `
        -Url "http://localhost:8080/actuator/health"

    Test-HttpEndpoint `
        -Name "Next.js frontend" `
        -Url "http://localhost:3000/"
}

# ============================================================
# 2. JAVA PHASE 1 + PHASE 2 FOCUSED REGRESSION
# ============================================================

Write-Section "2. JAVA - PHASE 1 + PHASE 2 FOCUSED REGRESSION"

$JavaFocusedTests = @(
    "AuthSecurityTest",
    "MeetingAccessPolicyServiceTest",
    "MeetingServiceTest",
    "MeetingControllerTest",
    "MeetingParticipantServiceTest",
    "LiveKitWebhookServiceTest",
    "MeetingChatMessageServiceTest",
    "MeetingCaptureServiceTest",
    "MeetingCaptureStateServiceTest",
    "MeetingCaptureRecoveryServiceTest",
    "MeetingMinutesPreconditionServiceTest"
) -join ","

Invoke-ExternalCheck `
    -Name "Java focused Phase 1+2 tests" `
    -WorkingDirectory $ServicePath `
    -Command {
        & mvn "-Dtest=$JavaFocusedTests" test
    }

# ============================================================
# 3. FASTAPI C8 / CAPTURE REGRESSION
# ============================================================

Write-Section "3. FASTAPI - CAPTURE / C8 REGRESSION"

try {
    $PythonCommand = Get-PythonCommand $FastApiPath

    $FastApiFocusedTests = @(
        "tests/test_health.py",
        "tests/test_capture_api.py",
        "tests/test_capture_manager.py",
        "tests/test_capture_session.py",
        "tests/test_manifest_service.py",
        "tests/test_participant_identity_service.py",
        "tests/test_segment_recorder.py"
    )

    Invoke-ExternalCheck `
        -Name "FastAPI focused Capture/C8 tests" `
        -WorkingDirectory $FastApiPath `
        -Command {
            $args = @()
            $args += $PythonCommand.ArgsPrefix
            $args += "-m"
            $args += "pytest"
            $args += $FastApiFocusedTests

            & $PythonCommand.File @args
        }
}
catch {
    Write-Host "[FAIL] FastAPI test bootstrap - $($_.Exception.Message)" -ForegroundColor Red
    Add-Result "FastAPI focused Capture/C8 tests" $false $_.Exception.Message
}

# ============================================================
# 4. FRONTEND PHASE 1 / I6 CONTRACT REGRESSION
# ============================================================

Write-Section "4. FRONTEND - CONTRACT REGRESSION"

Invoke-ExternalCheck `
    -Name "Frontend TypeScript typecheck" `
    -WorkingDirectory $FrontendPath `
    -Command {
        & npx tsc --noEmit
    }

Invoke-ExternalCheck `
    -Name "Frontend tests" `
    -WorkingDirectory $FrontendPath `
    -Command {
        & npm test
    }

# ============================================================
# 5. OPTIONAL FULL REGRESSION
# ============================================================

if ($Full) {
    Write-Section "5. FULL REGRESSION"

    Invoke-ExternalCheck `
        -Name "Java full test suite" `
        -WorkingDirectory $ServicePath `
        -Command {
            & mvn test
        }

    try {
        if (-not $PythonCommand) {
            $PythonCommand = Get-PythonCommand $FastApiPath
        }

        Invoke-ExternalCheck `
            -Name "FastAPI full test suite" `
            -WorkingDirectory $FastApiPath `
            -Command {
                $args = @()
                $args += $PythonCommand.ArgsPrefix
                $args += "-m"
                $args += "pytest"

                & $PythonCommand.File @args
            }
    }
    catch {
        Write-Host "[FAIL] FastAPI full test bootstrap - $($_.Exception.Message)" -ForegroundColor Red
        Add-Result "FastAPI full test suite" $false $_.Exception.Message
    }
}

# ============================================================
# SUMMARY
# ============================================================

Write-Section "RESULT SUMMARY"

$Results | Format-Table -AutoSize

$Failed = @($Results | Where-Object { $_.Result -eq "FAIL" })

if ($Failed.Count -eq 0) {
    Write-Host ""
    Write-Host "PHASE 1 + PHASE 2 VERIFICATION: PASS" -ForegroundColor Green
    Write-Host ""
    Write-Host "Frozen contracts covered by this script:" -ForegroundColor Green
    Write-Host "  Phase 1: C1 / C2 / C3 / C4 / C7"
    Write-Host "  Phase 2: C5 / C6 / C8 / I6"
    Write-Host ""
    Write-Host "NOTE: this script validates automated source regression + service health." -ForegroundColor Yellow
    Write-Host "It does NOT simulate microphone/audio, browser LiveKit media, or kill/restart FastAPI during a live capture." -ForegroundColor Yellow

    exit 0
}

Write-Host ""
Write-Host "PHASE 1 + PHASE 2 VERIFICATION: FAIL" -ForegroundColor Red
Write-Host "$($Failed.Count) check(s) failed." -ForegroundColor Red
Write-Host "Do not continue to Phase 3 until the failure is understood." -ForegroundColor Yellow

exit 1
