# ============================================================
# meeting-fastapi - Local Development Runner
# ============================================================

$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "========================================"
Write-Host " Starting meeting-fastapi"
Write-Host "========================================"
Write-Host ""

$ProjectDir = $PSScriptRoot
Set-Location $ProjectDir

# ------------------------------------------------------------
# Tim Python
# Uu tien:
# 1. .venv
# 2. venv
# 3. Python global
# ------------------------------------------------------------

$PythonExe = $null

$Candidates = @(
    (Join-Path $ProjectDir ".venv\Scripts\python.exe"),
    (Join-Path $ProjectDir "venv\Scripts\python.exe")
)

foreach ($Candidate in $Candidates) {
    if (Test-Path $Candidate) {
        $PythonExe = $Candidate
        break
    }
}

# Neu project khong co virtual environment thi dung Python global
if (-not $PythonExe) {
    $PythonCommand = Get-Command python -ErrorAction SilentlyContinue

    if ($PythonCommand) {
        $PythonExe = $PythonCommand.Source
    }
}

if (-not $PythonExe) {
    Write-Host "ERROR: Khong tim thay Python."
    Write-Host ""
    Write-Host "Kiem tra bang:"
    Write-Host "  python --version"
    Write-Host ""
    exit 1
}

# ------------------------------------------------------------
# Kiem tra uvicorn
# ------------------------------------------------------------

& $PythonExe -c "import uvicorn" 2>$null

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "ERROR: Python hien tai chua cai dependencies cua project."
    Write-Host ""
    Write-Host "Cai bang lenh:"
    Write-Host "  python -m pip install -e ."
    Write-Host ""
    exit 1
}

# ------------------------------------------------------------
# Thong tin
# ------------------------------------------------------------

Write-Host "Project : $ProjectDir"
Write-Host "Python  : $PythonExe"
Write-Host ""
Write-Host "API     : http://localhost:8000"
Write-Host "Swagger : http://localhost:8000/docs"
Write-Host "Health  : http://localhost:8000/health"
Write-Host ""
Write-Host "Nhan Ctrl+C de dung server."
Write-Host ""

# ------------------------------------------------------------
# Start FastAPI
# ------------------------------------------------------------

& $PythonExe -m uvicorn app.main:app `
    --host 0.0.0.0 `
    --port 8000 `
    --reload