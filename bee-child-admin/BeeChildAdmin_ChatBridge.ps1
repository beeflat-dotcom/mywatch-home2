#requires -Version 5.1
<#
NEC child admin bridge for ChatGPT.
Reads protected local credentials. Calls same API as Android.
Preview is read-only; -Apply is required to update formal JSON.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidateSet('options','search','get','create','update','retire','retirements')][string]$Action,
    [string]$Query = '',
    [string]$ChildId = '',
    [string]$PayloadFile = '',
    [switch]$Apply,
    [switch]$DeletePayload
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$base = 'http://127.0.0.1:8769'
$keyPath = Join-Path $env:LOCALAPPDATA 'BeeMonitorGateway\child_admin_secret.txt'
if (-not (Test-Path -LiteralPath $keyPath)) { throw 'NEC child-admin authorization key is unavailable' }
$secret = ([IO.File]::ReadAllText($keyPath,[Text.Encoding]::ASCII)).Trim()
if ($secret -notmatch '^[0-9a-f]{64}$') { throw 'NEC admin key format invalid' }
$headers = @{ 'X-Bee-Admin-Secret' = $secret }

function Invoke-ChildAdmin([string]$Path,[string]$Method='GET',[string]$Payload='') {
    try {
        if ($Method -eq 'GET') {
            return Invoke-RestMethod -Uri ($base+$Path) -Headers $headers -Method Get -TimeoutSec 17
        }
        $bytes=[Text.Encoding]::UTF8.GetBytes($Payload)
        return Invoke-RestMethod -Uri ($base+$Path) -Headers $headers -Method Post -Body $bytes -ContentType 'application/json; charset=utf-8' -TimeoutSec 28
    } catch {
        $code=if($_.Exception.Response){[int]$_.Exception.Response.StatusCode}else{0}
        throw ('NEC_CHILD_ADMIN_API_FAILED HTTP='+$code+' message='+$_.Exception.Message)
    }
}
switch ($Action) {
    options {
        $d=Invoke-ChildAdmin '/child-admin/options'
        $d|ConvertTo-Json -Depth 6
    }
    search {
        if ([string]::IsNullOrWhiteSpace($Query)){throw 'Search query cannot be empty'}
        $rows=@(Invoke-RestMethod -Uri ($base+'/child-master') -TimeoutSec 20)
        $norm=[regex]::Replace($Query.Trim(),'\s+','')
        $matches=@($rows|Where-Object {
            ([regex]::Replace([string]$_.name,'\s+','')).Contains($norm) -or
            ([regex]::Replace([string]$_.short_name,'\s+','')).Contains($norm)
        }|Select-Object -First 12 child_id,name,short_name,shop,active,no)
        @{ok=$true;matches=$matches;total=$matches.Count}|ConvertTo-Json -Depth 8
    }
    get {
        if($ChildId -notmatch '^child_[0-9a-f]{32}$'){throw 'Formal child ID required'}
        $d=Invoke-ChildAdmin ('/child-admin/'+[uri]::EscapeDataString($ChildId))
        $d|ConvertTo-Json -Depth 8
    }
    retirements {
        $d=Invoke-ChildAdmin '/child-admin/retirements/list'
        $d|ConvertTo-Json -Depth 8
    }
    {$_ -in @('create','update','retire')} {
        if(-not $PayloadFile -or -not (Test-Path -LiteralPath $PayloadFile)){throw 'PayloadFile missing'}
        try {
            $raw=Get-Content -LiteralPath $PayloadFile -Raw -Encoding UTF8
            if($raw.Length -gt 16000){throw 'Payload too large'}
            $v=$raw|ConvertFrom-Json
            if($null -eq $v -or $null -eq $v.PSObject){throw 'Payload JSON object required'}
            if(-not ($v.PSObject.Properties.Name -contains 'client_action_id') -or -not $v.client_action_id){
                $v|Add-Member -NotePropertyName 'client_action_id' -NotePropertyValue ([guid]::NewGuid().ToString()) -Force
            }
            if($v.PSObject.Properties.Name -contains 'source'){$v.source='chatgpt'}
            else{$v|Add-Member -NotePropertyName 'source' -NotePropertyValue 'chatgpt'}
            $body=$v|ConvertTo-Json -Depth 8
            [IO.File]::WriteAllText($PayloadFile,$body,[Text.UTF8Encoding]::new($false))
            if(-not $Apply){
                $out=@{ok=$true;status='preview_only';operation=$Action;client_action_id=$v.client_action_id;
                    surname=$v.surname;child_given_name=$v.child_given_name;shop=$v.shop;burden_rate=$v.burden_rate;active=$v.active}
                if($Action -eq 'retire'){
                    $out['full_name']=$v.full_name
                    if($v.child_id){
                        $o=Invoke-ChildAdmin ('/child-admin/'+[uri]::EscapeDataString($v.child_id))
                        $out['child_id']=$v.child_id
                        $out['current_name']=$o.child.name
                        $out['current_active']=$o.child.active
                    }else{
                        $out['mode']='archived_only_requires_archive_match'
                    }
                    $out['effective_date']=$v.effective_date
                }elseif($Action -eq 'create'){
                    $o=Invoke-ChildAdmin '/child-admin/options'
                    $out['next_usage_request_no']=$o.next_usage_request_no
                }else{
                    $cid=$v.child_id
                    if($cid -notmatch '^child_[0-9a-f]{32}$'){throw 'Update requires child_id'}
                    $o=Invoke-ChildAdmin ('/child-admin/'+[uri]::EscapeDataString($cid))
                    $out['child_id']=$cid
                    $out['current_name']=$o.child.name
                    $out['current_shop']=$o.child.shop
                    $out['current_active']=$o.child.active
                    $out['revision']=$o.revision
                }
                $out|ConvertTo-Json -Depth 7
            }else{
                $result=Invoke-ChildAdmin ('/child-admin/'+$Action) 'POST' $body
                if(-not $result.ok -or $result.status -notin @('completed','duplicate')){throw 'Unexpected result'}
                if($Action -eq 'retire'){
                    [pscustomobject]@{ok=$result.ok;status=$result.status;child_id=$result.child_id;
                        full_name=$result.full_name;mode=$result.mode;
                        retirement_status=$result.retirement_status;recorded_at=$result.recorded_at;
                        effective_date=$result.effective_date;address_book_refresh=$result.address_book_refresh}|ConvertTo-Json -Depth 6
                }else{
                    [pscustomobject]@{ok=$result.ok;status=$result.status;child_id=$result.child_id;
                    child_name=$result.child.name;usage_request_no=$result.usage_request_no;
                    revision=$result.revision;shop=$result.child.shop;
                    address_book_refresh=$result.address_book_refresh}|ConvertTo-Json -Depth 6
                }
            }
        }finally {
            if($DeletePayload -and $PayloadFile -and (Test-Path -LiteralPath $PayloadFile)){
                Remove-Item -LiteralPath $PayloadFile -Force
            }
        }
    }
}
