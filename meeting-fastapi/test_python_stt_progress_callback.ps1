param(
    [string]$PythonExe = ""
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

function Fail-Test {
    param([string]$Message)
    Write-Host ""
    Write-Host "============================================"
    Write-Host " PYTHON STT PROGRESS CALLBACK TEST FAILED"
    Write-Host "============================================"
    Write-Host $Message
    exit 1
}

function Resolve-Python {
    if (-not [string]::IsNullOrWhiteSpace($PythonExe)) {
        if (-not (Test-Path $PythonExe)) {
            Fail-Test "PythonExe does not exist: $PythonExe"
        }
        return $PythonExe
    }

    $candidates = @(
        ".\.venv\Scripts\python.exe",
        ".\venv\Scripts\python.exe"
    )

    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            return (Resolve-Path $candidate).Path
        }
    }

    $cmd = Get-Command python -ErrorAction SilentlyContinue
    if ($null -ne $cmd) {
        return $cmd.Source
    }

    Fail-Test "Could not find Python. Activate your venv or pass -PythonExe explicitly."
}

function Run-Pytest {
    param(
        [string]$Title,
        [string[]]$Tests
    )

    Write-Host ""
    Write-Host "=== $Title ==="

    & $script:Python -m pytest -q @Tests

    if ($LASTEXITCODE -ne 0) {
        Fail-Test "$Title failed with exit code $LASTEXITCODE"
    }

    Write-Host "PASS: $Title"
}

Write-Host "============================================"
Write-Host " PYTHON STT PROGRESS CALLBACK TEST"
Write-Host "============================================"

$script:Python = Resolve-Python
Write-Host "Python:" $script:Python

$requiredFiles = @(
    "tests/test_stt_progress_callback_client.py",
    "tests/test_transcription_job_service.py",
    "tests/test_manifest_transcription_service.py"
)

Write-Host ""
Write-Host "=== 1. CHECK REQUIRED TEST FILES ==="

foreach ($file in $requiredFiles) {
    if (-not (Test-Path $file)) {
        Fail-Test "Required test file not found: $file"
    }
    Write-Host "FOUND:" $file
}

Write-Host ""
Write-Host "=== 2. CHECK PYTHON IMPORTS ==="

& $script:Python -c "import httpx; import app.services.stt_progress_callback_client; import app.services.transcription_job_service; print('IMPORT_OK')"

if ($LASTEXITCODE -ne 0) {
    Fail-Test "Import check failed. Verify httpx is installed as a runtime dependency and the new callback client imports correctly."
}

Write-Host "PASS: imports"

Run-Pytest `
    -Title "CALLBACK CLIENT UNIT TESTS" `
    -Tests @(
        "tests/test_stt_progress_callback_client.py"
    )

Run-Pytest `
    -Title "TRANSCRIPTION JOB SERVICE TESTS" `
    -Tests @(
        "tests/test_transcription_job_service.py"
    )

Run-Pytest `
    -Title "MULTI-MANIFEST REGRESSION TESTS" `
    -Tests @(
        "tests/test_manifest_transcription_service.py"
    )

Write-Host ""
Write-Host "=== 6. COMBINED VERIFICATION ==="

& $script:Python -m pytest -q `
    tests/test_stt_progress_callback_client.py `
    tests/test_transcription_job_service.py `
    tests/test_manifest_transcription_service.py

if ($LASTEXITCODE -ne 0) {
    Fail-Test "Combined verification failed."
}

Write-Host ""
Write-Host "============================================"
Write-Host " PYTHON STT PROGRESS CALLBACK TEST PASS"
Write-Host "============================================"
Write-Host "Confirmed by tests:"
Write-Host "  Callback HTTP client contract           PASS"
Write-Host "  Internal API key header behavior        PASS"
Write-Host "  Callback error propagation in client    PASS"
Write-Host "  JobService progress integration         PASS"
Write-Host "  Callback scheduling/throttle            PASS"
Write-Host "  Callback failure does not fail STT      PASS"
Write-Host "  Callback shutdown cleanup               PASS"
Write-Host "  Multi-manifest regression               PASS"
Write-Host ""
Write-Host "NOTE:"
Write-Host "This script tests the Python side only."
Write-Host "It does NOT prove Python -> Java runtime callback yet."
Write-Host "After this PASS, restart Python and run the real Java/Python integration test."
