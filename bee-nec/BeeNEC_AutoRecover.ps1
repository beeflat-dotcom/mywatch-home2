# Bee NEC automatic recovery; does not modify bee-line, LINE, or PTT.
# Windows PowerShell 5.1. Run -Install once in the NEC user's desktop session.
param([switch]$Install, [switch]$Check, [switch]$Status, [switch]$RenewKey)

$ErrorActionPreference = 'Stop'
$root = Join-Path $env:LOCALAPPDATA 'BeeNECMcp'
$py = Join-Path $root '.venv\Scripts\python.exe'
$server = Join-Path $root 'bee_nec_mcp.py'
$client = Join-Path $env:USERPROFILE 'Downloads\tunnel-client-v0.0.16-windows-amd64\tunnel-client.exe'
$profile = Join-Path $env:APPDATA 'tunnel-client\bee-nec.yaml'
$keyFile = Join-Path $root 'control_plane_api_key.dpapi'
$scriptFile = Join-Path $root 'BeeNEC_AutoRecover.ps1'
$failureFile = Join-Path $root 'watch_failures.txt'
$apiFailureFile = Join-Path $root 'api_watch_failures.txt'
$apiRestartFile = Join-Path $root 'api_last_restart_utc.txt'
$apiProbeUrl = 'http://127.0.0.1:8779/'
$log = Join-Path $root 'recovery.log'
$taskName = 'BeeNEC-Management-Watchdog'

function Log([string]$message) {
    [void][IO.Directory]::CreateDirectory($root)
    Add-Content -LiteralPath $log -Encoding UTF8 -Value ((Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + ' ' + $message)
}
function Listening {
    try { return (@(Get-NetTCPConnection -LocalPort 8779 -State Listen -ErrorAction Stop).Count -gt 0) }
    catch { return $false }
}
function ApiProcesses {
    return @(Get-CimInstance Win32_Process -Filter "Name='python.exe' OR Name='pythonw.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine.IndexOf($server,[StringComparison]::OrdinalIgnoreCase) -ge 0 })
}
function TunnelProcesses {
    return @(Get-CimInstance Win32_Process -Filter "Name='tunnel-client.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine -match 'run\s+--profile\s+["'']?bee-nec(?:["'']|\s|$)' })
}
function FailCount {
    if (-not (Test-Path $failureFile)) { return 0 }
    $n=0
    if ([int]::TryParse(([IO.File]::ReadAllText($failureFile).Trim()),[ref]$n)) { return $n }
    return 0
}
function SetFail([int]$n) { [IO.File]::WriteAllText($failureFile,[string]$n) }

function ApiFailCount {
    if (-not (Test-Path $apiFailureFile)) { return 0 }
    $n=0
    if ([int]::TryParse(([IO.File]::ReadAllText($apiFailureFile).Trim()),[ref]$n)) { return $n }
    return 0
}
function SetApiFail([int]$n) { [IO.File]::WriteAllText($apiFailureFile,[string]$n) }
function ApiRestartCoolingDown {
    if (-not (Test-Path $apiRestartFile)) { return $false }
    try {
        return (([DateTime]::UtcNow - (Get-Item -LiteralPath $apiRestartFile).LastWriteTimeUtc).TotalMinutes -lt 10)
    } catch { return $false }
}
function MarkApiRestart { [IO.File]::WriteAllText($apiRestartFile,[DateTime]::UtcNow.ToString('o')) }

function ApiHealthy {
    # The HTTP server must reply, not merely have a TCP LISTEN socket.
    # Verify port ownership so another process cannot count as NEC healthy.
    $procs=@(ApiProcesses)
    if ($procs.Count -eq 0) { return $false }
    $ids=@($procs | ForEach-Object { [int]$_.ProcessId })
    $listeners=@(Get-NetTCPConnection -LocalPort 8779 -State Listen -ErrorAction SilentlyContinue)
    if (@($listeners | Where-Object { $ids -contains [int]$_.OwningProcess }).Count -eq 0) { return $false }
    $response=$null
    try {
        # 404/405 also prove that the server is responding, even without a / route.
        # No credentials, no redirects, and no mutating API calls.
        $req=[System.Net.HttpWebRequest]::Create($apiProbeUrl)
        $req.Method='GET'
        $req.Timeout=2000
        $req.ReadWriteTimeout=2000
        $req.AllowAutoRedirect=$false
        $req.KeepAlive=$false
        $req.Proxy=$null
        $response=$req.GetResponse()
        return ([int]$response.StatusCode -ge 200 -and [int]$response.StatusCode -lt 500)
    } catch [System.Net.WebException] {
        if ($_.Exception.Response -is [System.Net.HttpWebResponse]) {
            $response=$_.Exception.Response
            return ([int]$response.StatusCode -ge 200 -and [int]$response.StatusCode -lt 500)
        }
        return $false
    } catch {
        return $false
    } finally {
        if ($response) { $response.Dispose() }
    }
}

function StartApi {
    if (ApiHealthy) {
        if ((ApiFailCount) -gt 0) { SetApiFail 0; Log 'INFO: NEC API HTTP responsive again' }
        return $true
    }
    if (-not (Test-Path $py) -or -not (Test-Path $server)) { Log 'ERROR: NEC API files missing'; return $false }
    $procs=@(ApiProcesses)
    if ($procs.Count -gt 2) { Log 'ERROR: unexpected NEC API process count; left untouched'; return $false }
    if ($procs.Count -eq 2) {
        $linked = ($procs[0].ParentProcessId -eq $procs[1].ProcessId) -or
                  ($procs[1].ParentProcessId -eq $procs[0].ProcessId)
        if (-not $linked) { Log 'ERROR: unrelated NEC API processes; left untouched'; return $false }
    }
    $listeners=@(Get-NetTCPConnection -LocalPort 8779 -State Listen -ErrorAction SilentlyContinue)
    if ($listeners.Count -gt 0) {
        $ids=@($procs | ForEach-Object { [int]$_.ProcessId })
        if (@($listeners | Where-Object { $ids -contains [int]$_.OwningProcess }).Count -eq 0) {
            Log 'ERROR: TCP 8779 belongs to another process; left untouched'
            return $false
        }
    }
    if ($procs.Count -gt 0) {
        # A new worker may still be initializing. Never kill it before 100 seconds.
        foreach ($worker in $procs) {
            if ($worker.CreationDate -gt (Get-Date).AddSeconds(-100)) { return $false }
        }
        $n=(ApiFailCount)+1
        SetApiFail $n
        Log ('WARN: NEC API HTTP probe failed; consecutive checks=' + $n)
        if ($n -lt 2) { return $false }
        if (ApiRestartCoolingDown) { Log 'INFO: NEC API restart cooldown active'; return $false }
        try {
            foreach ($worker in $procs) {
                if (Get-Process -Id $worker.ProcessId -ErrorAction SilentlyContinue) {
                    Stop-Process -Id $worker.ProcessId -Force -ErrorAction Stop
                }
            }
            Log 'WARN: restarted only unresponsive bee_nec_mcp.py parent/child processes'
            Start-Sleep -Seconds 2
        } catch { Log 'ERROR: failed to stop unresponsive NEC API; left untouched'; return $false }
    } else {
        # Start an absent API immediately, unless a prior restart just failed.
        if (ApiRestartCoolingDown) { return $false }
        SetApiFail 0
    }
    try {
        $opts=@{FilePath=$py;ArgumentList=('"'+$server+'"');WorkingDirectory=$root;
            WindowStyle='Hidden';PassThru=$true;
            RedirectStandardOutput=(Join-Path $root 'mcp_stdout.log');
            RedirectStandardError=(Join-Path $root 'mcp_stderr.log')}
        $p=Start-Process @opts
        MarkApiRestart
        Log ('INFO: started NEC API PID ' + $p.Id)
        for ($i=0;$i -lt 10;$i++) {
            Start-Sleep -Seconds 1
            if (ApiHealthy) { SetApiFail 0; return $true }
        }
        Log 'ERROR: NEC API started but HTTP did not become responsive; check mcp_stderr.log'
    } catch { Log ('ERROR: NEC API start failed: ' + $_.Exception.GetType().Name) }
    return $false
}
function StartTunnel {
    if (-not (Test-Path $client) -or -not (Test-Path $profile) -or -not (Test-Path $keyFile)) {
        Log 'ERROR: tunnel client, profile or encrypted key missing'
        return
    }
    $sec=$null
    $ptr=[IntPtr]::Zero
    try {
        $sec=ConvertTo-SecureString ([IO.File]::ReadAllText($keyFile).Trim())
        $ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
        $plain=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
        if ([string]::IsNullOrWhiteSpace($plain)) { throw 'Empty key' }
        $env:CONTROL_PLANE_API_KEY=$plain
        $opts=@{FilePath=$client;ArgumentList=@('run','--profile','bee-nec');
            WorkingDirectory=(Split-Path -Parent $client);WindowStyle='Hidden';PassThru=$true;
            RedirectStandardOutput=(Join-Path $root 'tunnel_stdout.log');
            RedirectStandardError=(Join-Path $root 'tunnel_stderr.log')}
        $p=Start-Process @opts
        SetFail 0
        Log ('INFO: started bee-nec PID ' + $p.Id)
    } catch { Log 'ERROR: tunnel start or key decryption failed' }
    finally {
        Remove-Item Env:\CONTROL_PLANE_API_KEY -ErrorAction SilentlyContinue
        if ($ptr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
        if ($sec) { $sec.Dispose() }
        $plain=$null
    }
}
function Recover {
    [void][IO.Directory]::CreateDirectory($root)
    $mutex=[System.Threading.Mutex]::new($false,'Local\BeeNECRecoverWatchdog')
    $entered=$false
    try {
        try { $entered=$mutex.WaitOne(0) }
        catch [System.Threading.AbandonedMutexException] { $entered=$true }
        if (-not $entered) { return }
        if (-not (StartApi)) { return }
        $procs=@(TunnelProcesses)
        if ($procs.Count -gt 1) { Log 'ERROR: multiple bee-nec processes; left untouched'; return }
        if ($procs.Count -eq 0) { StartTunnel; return }
        $proc=$procs[0]
        if ($proc.CreationDate -gt (Get-Date).AddMinutes(-3)) { return }
        $links=@(Get-NetTCPConnection -OwningProcess $proc.ProcessId -State Established -ErrorAction SilentlyContinue |
            Where-Object { $_.RemotePort -eq 443 })
        if ($links.Count -gt 0) { if ((FailCount) -gt 0) { SetFail 0 }; return }
        $n=(FailCount)+1
        SetFail $n
        Log ('WARN: bee-nec TCP 443 unavailable; count=' + $n)
        if ($n -lt 3) { return }
        Stop-Process -Id $proc.ProcessId -Force
        Start-Sleep -Seconds 2
        StartTunnel
    } catch { Log ('ERROR: watchdog issue ' + $_.Exception.GetType().Name) }
    finally {
        if ($entered) { try { $mutex.ReleaseMutex() } catch {} }
        $mutex.Dispose()
    }
}
function ShowStatus {
    Write-Host ('NEC API 8779 listening: ' + (Listening))
    Write-Host ('NEC API HTTP responsive: ' + (ApiHealthy))
    Write-Host ('NEC API consecutive failures: ' + (ApiFailCount))
    Write-Host ('NEC API processes: ' + @(ApiProcesses).Count)
    $t=@(TunnelProcesses)
    Write-Host ('bee-nec processes: ' + $t.Count)
    Write-Host ('Encrypted key present: ' + (Test-Path $keyFile))
    $job=Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    Write-Host ('Auto recovery task: ' + [bool]$job)
    if (Test-Path $log) { Get-Content -LiteralPath $log -Tail 8 }
}

if ($Install) {
    foreach ($p in @($py,$server,$client,$profile)) {
        if (-not (Test-Path -LiteralPath $p)) { throw ('Required file missing: ' + $p) }
    }
    if (-not ([IO.File]::ReadAllText($profile).Contains('env:CONTROL_PLANE_API_KEY'))) {
        throw 'bee-nec config no longer uses CONTROL_PLANE_API_KEY'
    }
    [void][IO.Directory]::CreateDirectory($root)
    if ($RenewKey -or -not (Test-Path $keyFile)) {
        $sec=Read-Host 'Existing Runtime API Key (hidden)' -AsSecureString
        try {
            if ($sec.Length -lt 12) { throw 'API key too short' }
            if (Test-Path $keyFile) {
                Copy-Item $keyFile ($keyFile+'.bak_'+(Get-Date -Format 'yyyyMMdd_HHmmss'))
            }
            $blob=ConvertFrom-SecureString -SecureString $sec
            [IO.File]::WriteAllText($keyFile,$blob,[Text.UTF8Encoding]::new($false))
            $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
            & icacls.exe $keyFile '/inheritance:r' '/grant:r' "*$($sid):(F)" '*S-1-5-18:(F)' | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Could not lock key file ACL' }
            Log 'INFO: Windows-DPAPI encrypted key stored locally'
        } finally { if ($sec) { $sec.Dispose() } }
    }
    if ($PSCommandPath -ne $scriptFile) { Copy-Item $PSCommandPath $scriptFile -Force }
    $user=[Security.Principal.WindowsIdentity]::GetCurrent().Name
    $act=New-ScheduledTaskAction -Execute (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') -Argument ('-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "'+$scriptFile+'" -Check')
    $triggers=@((New-ScheduledTaskTrigger -AtLogOn -User $user),
        (New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 2) -RepetitionDuration (New-TimeSpan -Days 3650)))
    $principal=New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
    $settings=New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 1) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
    Register-ScheduledTask -TaskName $taskName -Action $act -Trigger $triggers -Principal $principal -Settings $settings -Force | Out-Null
    Log 'INFO: installed HTTP-responsive watchdog, two-check restart, 10-minute backoff'
    Recover
    ShowStatus
    Write-Host 'Installed. After Windows sign-in, auto recovery is enabled.'
} elseif ($Check) {
    Recover
} elseif ($Status) {
    ShowStatus
} else {
    Write-Host 'Usage: -Install [-RenewKey], -Check, -Status'
}
