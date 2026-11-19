param(
    [string]$Root = "D:\DuAn\Livekit",
    [string]$ServiceUrl = "http://localhost:8080",
    [string]$FastApiUrl = "http://localhost:8000",
    [string]$FrontendUrl = "http://localhost:3000",
    [System.Management.Automation.PSCredential]$OwnerCredential,
    [System.Management.Automation.PSCredential]$ManagerCredential,
    [System.Management.Automation.PSCredential]$DeniedCredential,
    [switch]$SkipCapture,
    [switch]$Cleanup
)

$ErrorActionPreference = "Stop"
$Results = [System.Collections.Generic.List[object]]::new()
$CreatedMeetings = [System.Collections.Generic.List[long]]::new()

function Write-Section {
    param([string]$Title)
    Write-Host ""
    Write-Host "============================================================" -ForegroundColor DarkCyan
    Write-Host " $Title" -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor DarkCyan
}

function Add-Result {
    param([string]$Name, [bool]$Passed, [string]$Detail)

    $Results.Add([PSCustomObject]@{
        Check  = $Name
        Result = if ($Passed) { "PASS" } else { "FAIL" }
        Detail = $Detail
    })

    if ($Passed) {
        Write-Host "[PASS] $Name - $Detail" -ForegroundColor Green
    }
    else {
        Write-Host "[FAIL] $Name - $Detail" -ForegroundColor Red
    }
}

function Convert-ResponseBody {
    param([string]$Content)

    if ([string]::IsNullOrWhiteSpace($Content)) {
        return $null
    }

    try {
        return $Content | ConvertFrom-Json
    }
    catch {
        return $Content
    }
}

function Invoke-Http {
    param(
        [ValidateSet("GET", "POST", "PUT", "DELETE")]
        [string]$Method,
        [string]$Url,
        [Microsoft.PowerShell.Commands.WebRequestSession]$WebSession,
        [hashtable]$Headers,
        $Body,
        [int[]]$ExpectedStatus = @(200)
    )

    $params = @{
        Uri             = $Url
        Method          = $Method
        UseBasicParsing = $true
        TimeoutSec      = 30
    }

    if ($WebSession) {
        $params.WebSession = $WebSession
    }

    if ($Headers) {
        $params.Headers = $Headers
    }

    if ($null -ne $Body) {
        $params.ContentType = "application/json"
        $params.Body = $Body | ConvertTo-Json -Depth 12
    }

    $statusCode = $null
    $content = ""

    try {
        $response = Invoke-WebRequest @params
        $statusCode = [int]$response.StatusCode
        $content = [string]$response.Content
    }
    catch {
        if ($_.Exception.Response) {
            try {
                $statusCode = [int]$_.Exception.Response.StatusCode
            }
            catch {
            }

            try {
                $stream = $_.Exception.Response.GetResponseStream()
                if ($stream) {
                    $reader = New-Object System.IO.StreamReader($stream)
                    $content = $reader.ReadToEnd()
                    $reader.Dispose()
                }
            }
            catch {
                if ($_.ErrorDetails.Message) {
                    $content = $_.ErrorDetails.Message
                }
            }
        }

        if ($null -eq $statusCode) {
            throw
        }
    }

    $parsed = Convert-ResponseBody $content

    if ($ExpectedStatus -notcontains $statusCode) {
        $bodyText = if ($content) { $content } else { "<empty>" }
        throw "$Method $Url expected [$($ExpectedStatus -join ',')] but got HTTP $statusCode. Body: $bodyText"
    }

    return [PSCustomObject]@{
        StatusCode = $statusCode
        Content    = $content
        Json       = $parsed
    }
}

function Get-Csrf {
    param([Microsoft.PowerShell.Commands.WebRequestSession]$Session)

    $result = Invoke-Http `
        -Method GET `
        -Url "$ServiceUrl/api/auth/csrf" `
        -WebSession $Session `
        -ExpectedStatus @(200)

    return $result.Json
}

function Invoke-Mutation {
    param(
        [ValidateSet("POST", "PUT", "DELETE")]
        [string]$Method,
        [string]$Url,
        [Microsoft.PowerShell.Commands.WebRequestSession]$Session,
        $Body,
        [int[]]$ExpectedStatus
    )

    $csrf = Get-Csrf $Session
    $headers = @{}
    $headers[[string]$csrf.headerName] = [string]$csrf.token

    return Invoke-Http `
        -Method $Method `
        -Url $Url `
        -WebSession $Session `
        -Headers $headers `
        -Body $Body `
        -ExpectedStatus $ExpectedStatus
}

function New-LoginContext {
    param(
        [string]$Name,
        [System.Management.Automation.PSCredential]$Credential
    )

    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $csrf = Get-Csrf $session
    $headers = @{}
    $headers[[string]$csrf.headerName] = [string]$csrf.token

    $networkCredential = $Credential.GetNetworkCredential()

    $login = Invoke-Http `
        -Method POST `
        -Url "$ServiceUrl/api/auth/login" `
        -WebSession $session `
        -Headers $headers `
        -Body @{
            usernameOrEmail = $networkCredential.UserName
            password        = $networkCredential.Password
        } `
        -ExpectedStatus @(200)

    if (-not $login.Json.authenticated) {
        throw "$Name login returned authenticated=false"
    }

    Add-Result "$Name login" $true "$($login.Json.user.username) / $($login.Json.user.systemRole)"

    return [PSCustomObject]@{
        Name       = $Name
        Session    = $session
        User       = $login.Json.user
        Credential = $Credential
    }
}

function Test-Health {
    param(
        [string]$Name,
        [string]$Url,
        [bool]$Required = $true,
        [int]$Attempts = 1,
        [int]$DelaySeconds = 2
    )

    $lastError = $null

    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            Invoke-Http -Method GET -Url $Url -ExpectedStatus @(200) | Out-Null
            Add-Result $Name $true "HTTP 200"
            return $true
        }
        catch {
            $lastError = $_.Exception.Message

            if ($attempt -lt $Attempts) {
                Write-Host "[RETRY] $Name attempt $attempt/$Attempts failed: $lastError" -ForegroundColor Yellow
                Start-Sleep -Seconds $DelaySeconds
            }
        }
    }

    if ($Required) {
        Add-Result $Name $false $lastError
        throw "$Name health check failed after $Attempts attempt(s): $lastError"
    }

    Write-Host "[WARN] $Name unavailable after $Attempts attempt(s): $lastError" -ForegroundColor Yellow
    Write-Host "       Runtime smoke will continue because browser/frontend execution is not exercised by this script." -ForegroundColor Yellow
    return $false
}

function New-SmokeMeeting {
    param(
        $Context,
        [ValidateSet("PUBLIC", "PRIVATE")]
        [string]$AccessType,
        [string]$Suffix
    )

    $startTime = (Get-Date).AddMinutes(-2).ToString("yyyy-MM-ddTHH:mm:ss")
    $endTime   = (Get-Date).AddMinutes(30).ToString("yyyy-MM-ddTHH:mm:ss")

    # Capture control is intentionally participant-role based:
    # only CHAIRMAN or SECRETARY may start/stop Capture.
    # ADMIN / MEETING_MANAGER / creator do NOT override this permission.
    # Therefore every smoke meeting explicitly assigns the Owner as CHAIRMAN,
    # including PUBLIC meetings.
    $participants = @(
        @{
            userPublicId = [string]$Context.User.publicId
            invitedEmail = $null
            fullName     = [string]$Context.User.fullName
            role         = "CHAIRMAN"
        }
    )

    $body = @{
        title        = "SMOKE-PHASE2-$Suffix-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
        description  = "Disposable Phase 2 runtime smoke test"
        startTime    = $startTime
        endTime      = $endTime
        accessType   = $AccessType
        participants = $participants
    }

    $response = Invoke-Mutation `
        -Method POST `
        -Url "$ServiceUrl/api/meetings" `
        -Session $Context.Session `
        -Body $body `
        -ExpectedStatus @(201)

    $meetingId = [long]$response.Json.id
    $CreatedMeetings.Add($meetingId)

    Add-Result "Create $AccessType meeting" $true "meetingId=$meetingId status=$($response.Json.status)"

    return $response.Json
}

function Join-Authenticated {
    param($Context, [long]$MeetingId)

    return Invoke-Mutation `
        -Method POST `
        -Url "$ServiceUrl/api/meetings/$MeetingId/join" `
        -Session $Context.Session `
        -Body @{
            participantType = "AUTHENTICATED"
            guestId         = $null
            displayName     = if ($Context.User.fullName) { [string]$Context.User.fullName } else { [string]$Context.User.username }
        } `
        -ExpectedStatus @(200)
}

function Join-Guest {
    param(
        [long]$MeetingId,
        [bool]$ExpectSuccess
    )

    $guestSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession

    $expected = if ($ExpectSuccess) {
        @(200)
    }
    else {
        @(400, 403, 404, 409, 422)
    }

    return Invoke-Mutation `
        -Method POST `
        -Url "$ServiceUrl/api/meetings/$MeetingId/join" `
        -Session $guestSession `
        -Body @{
            participantType = "GUEST"
            guestId         = "guest-$([Guid]::NewGuid().ToString('N'))"
            displayName     = "Phase2 Smoke Guest"
        } `
        -ExpectedStatus $expected
}

function End-Meeting {
    param($Context, [long]$MeetingId, [int[]]$ExpectedStatus)

    return Invoke-Mutation `
        -Method POST `
        -Url "$ServiceUrl/api/meetings/$MeetingId/end" `
        -Session $Context.Session `
        -Body $null `
        -ExpectedStatus $ExpectedStatus
}

function Remove-SmokeMeeting {
    param($Context, [long]$MeetingId)

    try {
        Invoke-Mutation `
            -Method DELETE `
            -Url "$ServiceUrl/api/meetings/$MeetingId" `
            -Session $Context.Session `
            -Body $null `
            -ExpectedStatus @(204) | Out-Null

        Add-Result "Cleanup meeting $MeetingId" $true "deleted"
    }
    catch {
        Add-Result "Cleanup meeting $MeetingId" $false $_.Exception.Message
    }
}

# ============================================================
# START
# ============================================================

Write-Section "PHASE 2 RUNTIME SMOKE"

Write-Host "This script creates disposable test meetings."
Write-Host "It validates API behavior against the frozen Phase 1/2 contracts."
Write-Host ""

Test-Health "FastAPI health" "$FastApiUrl/health" -Required $true -Attempts 2 | Out-Null
Test-Health "Java health" "$ServiceUrl/actuator/health" -Required $true -Attempts 2 | Out-Null

# Frontend is not used to execute the API smoke flow below.
# Next.js dev mode can occasionally spend longer than the generic HTTP timeout
# compiling a route. Retry it, but do not abort backend/Capture runtime validation.
Test-Health "Frontend health" "$FrontendUrl/" -Required $false -Attempts 3 -DelaySeconds 3 | Out-Null

if (-not $OwnerCredential) {
    Write-Host ""
    Write-Host "Enter an ADMIN or MEETING_MANAGER account." -ForegroundColor Yellow
    $OwnerCredential = Get-Credential -Message "Owner account (ADMIN or MEETING_MANAGER)"
}

$Owner = New-LoginContext "Owner" $OwnerCredential

if ($Owner.User.systemRole -notin @("ADMIN", "MEETING_MANAGER")) {
    throw "Owner account must be ADMIN or MEETING_MANAGER because the smoke script creates meetings. Current role: $($Owner.User.systemRole)"
}

# ============================================================
# C4 - PRIVATE JOIN-INFO PRIVACY
# ============================================================

Write-Section "C4 - PRIVATE JOIN-INFO PRIVACY"

$privateMeeting = New-SmokeMeeting -Context $Owner -AccessType PRIVATE -Suffix "PRIVATE"
$privateMeetingId = [long]$privateMeeting.id

$publicJoinInfo = Invoke-Http `
    -Method GET `
    -Url "$ServiceUrl/api/meetings/$privateMeetingId/join-info" `
    -ExpectedStatus @(200)

if ($null -eq $publicJoinInfo.Json.title) {
    Add-Result "C4 PRIVATE join-info title" $true "title=null"
}
else {
    Add-Result "C4 PRIVATE join-info title" $false "title leaked: $($publicJoinInfo.Json.title)"
}

# Owner is creator + CHAIRMAN in this private smoke meeting.
$ownerPrivateJoin = Join-Authenticated -Context $Owner -MeetingId $privateMeetingId

if ($ownerPrivateJoin.Json.permissions.canEndMeeting -eq $true -and
    $ownerPrivateJoin.Json.permissions.canManageParticipants -eq $true) {
    Add-Result "I6 owner/CHAIRMAN join permissions" $true "canEnd=true, canManageParticipants=true"
}
else {
    Add-Result "I6 owner/CHAIRMAN join permissions" $false (
        "canEnd=$($ownerPrivateJoin.Json.permissions.canEndMeeting), canManageParticipants=$($ownerPrivateJoin.Json.permissions.canManageParticipants)"
    )
}

# ============================================================
# C5 + C6 - PUBLIC JOIN / MINUTES PRECONDITION / CAPTURE / END
# ============================================================

Write-Section "C5 + C6 - TERMINAL / CAPTURE / MINUTES"

$mainMeeting = New-SmokeMeeting -Context $Owner -AccessType PUBLIC -Suffix "MAIN"
$mainMeetingId = [long]$mainMeeting.id

# Public guest join should work before terminal state.
$guestJoinBefore = Join-Guest -MeetingId $mainMeetingId -ExpectSuccess $true

if ($guestJoinBefore.Json.token -and $guestJoinBefore.Json.participant.liveKitIdentity) {
    Add-Result "PUBLIC guest join before end" $true "token + liveKitIdentity returned"
}
else {
    Add-Result "PUBLIC guest join before end" $false "missing token or liveKitIdentity"
}

# Capture/Minutes actions are performed by the authenticated Owner.
# The Owner must first join the PUBLIC meeting so the backend participant gate is satisfied.
$ownerMainJoin = Join-Authenticated -Context $Owner -MeetingId $mainMeetingId

if ($ownerMainJoin.Json.token -and $ownerMainJoin.Json.participant.liveKitIdentity) {
    Add-Result "Owner authenticated join before Capture" $true "token + liveKitIdentity returned"
}
else {
    Add-Result "Owner authenticated join before Capture" $false "missing token or liveKitIdentity"
}

# Minutes must not start before explicit durable meeting completion.
try {
    $minutesPre = Invoke-Mutation `
        -Method POST `
        -Url "$ServiceUrl/api/meetings/$mainMeetingId/minutes/generate" `
        -Session $Owner.Session `
        -Body $null `
        -ExpectedStatus @(400, 409, 422)

    Add-Result "C6 Minutes precondition before end" $true "rejected HTTP $($minutesPre.StatusCode)"
}
catch {
    Add-Result "C6 Minutes precondition before end" $false $_.Exception.Message
}

if (-not $SkipCapture) {
    try {
        $captureStart = Invoke-Mutation `
            -Method POST `
            -Url "$ServiceUrl/api/meetings/$mainMeetingId/capture/start" `
            -Session $Owner.Session `
            -Body $null `
            -ExpectedStatus @(200)

        if ($captureStart.Json.status -in @("RUNNING", "STARTING")) {
            Add-Result "C6 Capture start" $true "status=$($captureStart.Json.status) id=$($captureStart.Json.id)"
        }
        else {
            Add-Result "C6 Capture start" $false "unexpected status=$($captureStart.Json.status)"
        }
    }
    catch {
        Add-Result "C6 Capture start" $false $_.Exception.Message
        throw
    }
}

# Creator is allowed to End Meeting by I6.
try {
    End-Meeting -Context $Owner -MeetingId $mainMeetingId -ExpectedStatus @(204) | Out-Null
    Add-Result "I6 creator End Meeting" $true "HTTP 204"
}
catch {
    Add-Result "I6 creator End Meeting" $false $_.Exception.Message
    throw
}

$afterEndJoinInfo = Invoke-Http `
    -Method GET `
    -Url "$ServiceUrl/api/meetings/$mainMeetingId/join-info" `
    -ExpectedStatus @(200)

if ($afterEndJoinInfo.Json.status -eq "COMPLETED") {
    Add-Result "C6 durable Meeting COMPLETED" $true "join-info status=COMPLETED"
}
else {
    Add-Result "C6 durable Meeting COMPLETED" $false "status=$($afterEndJoinInfo.Json.status)"
}

if (-not $SkipCapture) {
    try {
        $captureAfterEnd = Invoke-Http `
            -Method GET `
            -Url "$ServiceUrl/api/meetings/$mainMeetingId/capture/status" `
            -WebSession $Owner.Session `
            -ExpectedStatus @(200)

        if ($captureAfterEnd.Json.status -eq "COMPLETED" -and $captureAfterEnd.Json.manifestPath) {
            Add-Result "C6 Capture finalized before Meeting completion" $true (
                "status=COMPLETED manifest=$($captureAfterEnd.Json.manifestPath)"
            )
        }
        else {
            Add-Result "C6 Capture finalized before Meeting completion" $false (
                "status=$($captureAfterEnd.Json.status) manifest=$($captureAfterEnd.Json.manifestPath)"
            )
        }
    }
    catch {
        Add-Result "C6 Capture finalized before Meeting completion" $false $_.Exception.Message
    }
}

# C5: terminal meeting must not issue a new join token.
try {
    $terminalJoin = Join-Guest -MeetingId $mainMeetingId -ExpectSuccess $false
    Add-Result "C5 terminal guest join blocked" $true "HTTP $($terminalJoin.StatusCode)"
}
catch {
    Add-Result "C5 terminal guest join blocked" $false $_.Exception.Message
}

# ============================================================
# I6 - OPTIONAL MEETING_MANAGER NON-CREATOR
# ============================================================

if ($ManagerCredential) {
    Write-Section "I6 - MEETING_MANAGER NON-CREATOR"

    $Manager = New-LoginContext "Manager" $ManagerCredential

    if ($Manager.User.systemRole -ne "MEETING_MANAGER") {
        Add-Result "Manager role prerequisite" $false "expected MEETING_MANAGER, got $($Manager.User.systemRole)"
    }
    elseif ($Manager.User.publicId -eq $Owner.User.publicId) {
        Add-Result "Manager non-creator prerequisite" $false "Manager account is the same account as Owner"
    }
    else {
        $managerMeeting = New-SmokeMeeting -Context $Owner -AccessType PUBLIC -Suffix "MANAGER-END"
        $managerMeetingId = [long]$managerMeeting.id

        $managerJoin = Join-Authenticated -Context $Manager -MeetingId $managerMeetingId

        if ($managerJoin.Json.permissions.canEndMeeting -eq $true -and
            $managerJoin.Json.permissions.canManageParticipants -eq $false) {
            Add-Result "I6 manager permission split" $true "canEnd=true, canManageParticipants=false"
        }
        else {
            Add-Result "I6 manager permission split" $false (
                "canEnd=$($managerJoin.Json.permissions.canEndMeeting), canManageParticipants=$($managerJoin.Json.permissions.canManageParticipants)"
            )
        }

        # C2 regression: normal manager is not participant manager by frozen canManageMeeting contract.
        try {
            $participantList = Invoke-Http `
                -Method GET `
                -Url "$ServiceUrl/api/meetings/$managerMeetingId/participants" `
                -WebSession $Manager.Session `
                -ExpectedStatus @(403)

            Add-Result "C2 manager participant-management denied" $true "HTTP 403"
        }
        catch {
            Add-Result "C2 manager participant-management denied" $false $_.Exception.Message
        }

        try {
            End-Meeting -Context $Manager -MeetingId $managerMeetingId -ExpectedStatus @(204) | Out-Null
            Add-Result "I6 MEETING_MANAGER non-creator End Meeting" $true "HTTP 204"
        }
        catch {
            Add-Result "I6 MEETING_MANAGER non-creator End Meeting" $false $_.Exception.Message
        }
    }
}
else {
    Write-Host ""
    Write-Host "[SKIP] I6 MEETING_MANAGER non-creator runtime case." -ForegroundColor Yellow
    Write-Host "       Pass -ManagerCredential (Get-Credential) to enable it." -ForegroundColor Yellow
}

# ============================================================
# I6 - OPTIONAL UNAUTHORIZED USER
# ============================================================

if ($DeniedCredential) {
    Write-Section "I6 - UNAUTHORIZED USER"

    $Denied = New-LoginContext "Denied user" $DeniedCredential

    if ($Denied.User.publicId -eq $Owner.User.publicId) {
        Add-Result "Denied-user prerequisite" $false "Denied account is the same account as Owner"
    }
    else {
        $deniedMeeting = New-SmokeMeeting -Context $Owner -AccessType PUBLIC -Suffix "DENIED-END"
        $deniedMeetingId = [long]$deniedMeeting.id

        $deniedJoin = Join-Authenticated -Context $Denied -MeetingId $deniedMeetingId

        if ($deniedJoin.Json.permissions.canEndMeeting -eq $false) {
            Add-Result "I6 unauthorized user canEndMeeting" $true "false"
        }
        else {
            Add-Result "I6 unauthorized user canEndMeeting" $false "unexpected true"
        }

        try {
            End-Meeting -Context $Denied -MeetingId $deniedMeetingId -ExpectedStatus @(403) | Out-Null
            Add-Result "I6 unauthorized End Meeting denied" $true "HTTP 403"
        }
        catch {
            Add-Result "I6 unauthorized End Meeting denied" $false $_.Exception.Message
        }

        # Finish the disposable meeting with its creator so it is not left active.
        try {
            End-Meeting -Context $Owner -MeetingId $deniedMeetingId -ExpectedStatus @(204) | Out-Null
        }
        catch {
            Add-Result "Owner cleanup end for denied-case meeting" $false $_.Exception.Message
        }
    }
}
else {
    Write-Host ""
    Write-Host "[SKIP] I6 unauthorized-user runtime case." -ForegroundColor Yellow
    Write-Host "       Pass -DeniedCredential (Get-Credential) to enable it." -ForegroundColor Yellow
}

# End the private privacy meeting after all checks.
try {
    End-Meeting -Context $Owner -MeetingId $privateMeetingId -ExpectedStatus @(204) | Out-Null
}
catch {
    Add-Result "Private smoke meeting end" $false $_.Exception.Message
}

# ============================================================
# OPTIONAL CLEANUP
# ============================================================

if ($Cleanup) {
    Write-Section "CLEANUP"

    foreach ($meetingId in $CreatedMeetings) {
        Remove-SmokeMeeting -Context $Owner -MeetingId $meetingId
    }
}
else {
    Write-Host ""
    Write-Host "[INFO] Smoke meetings were kept for inspection." -ForegroundColor Yellow
    Write-Host "       Re-run with -Cleanup if you want the script to delete them." -ForegroundColor Yellow
}

# ============================================================
# SUMMARY
# ============================================================

Write-Section "RUNTIME SMOKE SUMMARY"

$Results | Format-Table -AutoSize

$Failed = @($Results | Where-Object { $_.Result -eq "FAIL" })

if ($Failed.Count -eq 0) {
    Write-Host ""
    Write-Host "PHASE 2 RUNTIME SMOKE: PASS" -ForegroundColor Green
    Write-Host ""
    Write-Host "Validated at runtime:" -ForegroundColor Green
    Write-Host "  C4 PRIVATE join-info privacy"
    Write-Host "  C5 terminal join rejection"
    Write-Host "  C6 explicit completion + Capture finalization + Minutes precondition"
    Write-Host "  I6 creator/CHAIRMAN End permission"
    if ($ManagerCredential) {
        Write-Host "  I6 MEETING_MANAGER non-creator + C2 permission split"
    }
    if ($DeniedCredential) {
        Write-Host "  I6 unauthorized-user denial"
    }
    Write-Host ""
    Write-Host "C8 restart/crash is NOT destructive-tested by this script." -ForegroundColor Yellow
    Write-Host "Use the automated C8 regression suite in phase2-verify.ps1; a separate controlled restart smoke can be run when no other capture is active." -ForegroundColor Yellow

    exit 0
}

Write-Host ""
Write-Host "PHASE 2 RUNTIME SMOKE: FAIL" -ForegroundColor Red
Write-Host "$($Failed.Count) runtime check(s) failed." -ForegroundColor Red
Write-Host "Do not start Phase 3 until the failure is understood." -ForegroundColor Yellow

exit 1
