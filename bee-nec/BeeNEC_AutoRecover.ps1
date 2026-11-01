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

function StartApi {
    if (Listening) { return $true }
    if (-not (Test-Path $py) -or -not (Test-Path $server)) { Log 'ERROR: NEC API files missing'; return $false }
    $procs=@(ApiProcesses)
    if ($procs.Count -gt 1) { Log 'ERROR: multiple NEC API processes; left untouched'; return $false }
    if ($procs.Count -eq 1) {
        if ($procs[0].CreationDate -gt (Get-Date).AddSeconds(-100)) { return $false }
        try {
            Stop-Process -Id $procs[0].ProcessId -Force
            Log 'WARN: restarted only unresponsive bee_nec_mcp.py'
            Start-Sleep -Seconds 2
        } catch { Log 'ERROR: failed to stop stalled NEC API'; return $false }
    }
    try {
        $opts=@{FilePath=$py;ArgumentList=('"' + $server + '"');WorkingDirectory=$root;
            WindowStyle='Hidden';PassThru=$true;
            RedirectStandardOutput=(Join-Path $root 'mcp_stdout.log');
            RedirectStandardError=(Join-Path $root 'mcp_stderr.log')}
        $p=Start-Process @opts
        Log ('INFO: started NEC API PID ' + $p.Id)
        for ($i=0;$i -lt 12;$i++) {
            Start-Sleep -Seconds 1
            if (Listening) { return $true }
        }
    } catch { Log 'ERROR: NEC API start failed; check mcp_stderr.log' }
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
    Log 'INFO: scheduled task set for logon and 2-minute checks'
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
