#requires -Version 5.1
<#
Bee Simple Address Book - one-way display export.
Reads formal child JSON and basic-profile JSON.
Never writes back to any JSON master or underlying attendance records.
Preserves original workbook, nine legacy sheets, links, and formulas via native Excel COM.
Only the first worksheet "最新住所録（自動）" is replaced.
#>
[CmdletBinding()]
param([switch]$Force, [switch]$PreviewOnly)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$root = Join-Path $env:USERPROFILE 'Dropbox\AI用\ローカルAI'
$usage = Join-Path $root '利用希望JSON'
$basicFile = Join-Path $root '児童基本情報.json'
$bookFile = Join-Path $env:USERPROFILE ('Dropbox\BU\利用希望\' + (Get-Date -Format 'yyyy') + '年\簡易住所録.xlsx')
$stateDir = Join-Path $env:LOCALAPPDATA 'BeeSimpleAddressBook'
$stateFile = Join-Path $stateDir 'last_source_hash.txt'
$backupDir = Join-Path (Split-Path -Parent $bookFile) '簡易住所録_バックアップ'
$sheetName = '最新住所録（自動）'

[void](New-Item -ItemType Directory -Force -Path $stateDir)
$lockFile = Join-Path $stateDir 'export.lock'
$lease = [System.IO.File]::Open($lockFile, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
$excel = $null
$book = $null
$candidate = $null

function CurrentSource {
    $nowMonth = (Get-Date).ToString('yyyy-MM')
    $thisMonth = Join-Path $usage ($nowMonth + '.json')
    if (Test-Path -LiteralPath $thisMonth) { return $thisMonth }
    $files = @(Get-ChildItem -LiteralPath $usage -Filter '????-??.json' -File | Sort-Object Name -Descending)
    if ($files.Count -eq 0) { throw 'No formal child JSON month is available' }
    return $files[0].FullName
}
function TextValue($v) {
    if ($null -eq $v) { return '' }
    $s = ([string]$v).Trim()
    if ($s.Length -eq 0) { return '' }
    if ($s[0] -in @('=', '+', '-', '@')) { return "'" + $s }
    return $s
}
function Norm($value) { return [regex]::Replace([string]$value, '\s+', '') }
function FirstOf($primary, $fallback) {
    if ($null -ne $primary -and [string]$primary -ne '') { return TextValue $primary }
    return TextValue $fallback
}
function SourceDigest($master, $basics) {
    $data = @(
        ($master.children | Select-Object child_id,name,child_name,short_name,no,shop,active,active_basis | ConvertTo-Json -Depth 5 -Compress),
        ($basics.children | ConvertTo-Json -Depth 8 -Compress)
    ) -join '|'
    $bytes = [Text.Encoding]::UTF8.GetBytes($data)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($sha.ComputeHash($bytes)).Replace('-','').ToLowerInvariant() }
    finally { $sha.Dispose() }
}

try {
    if (-not (Test-Path -LiteralPath $bookFile)) { throw "Existing workbook missing: $bookFile" }
    if (-not (Test-Path -LiteralPath $basicFile)) { throw 'Basic profile master missing' }
    $masterPath = CurrentSource
    $master = Get-Content -LiteralPath $masterPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $basic = Get-Content -LiteralPath $basicFile -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($master.schema_version -ne 1 -or $null -eq $master.children -or $master.children.Count -eq 0) {
        throw 'Formal child master schema is invalid or empty'
    }
    if ($basic.schema_version -ne 1 -or $null -eq $basic.children) {
        throw 'Basic-profile schema is invalid'
    }
    $children = @($master.children | Sort-Object @{Expression={[int]$_.no}}, @{Expression={$_.child_id}})
    $ids = @{}
    $numbers = @{}
    foreach ($c in $children) {
        if (-not $c.child_id -or -not $c.name -or [int]$c.no -lt 1) { throw 'Invalid child identity in master' }
        if ($ids.ContainsKey([string]$c.child_id) -or $numbers.ContainsKey([string]$c.no)) {
            throw 'Duplicate child identity/number in formal master'
        }
        $ids[[string]$c.child_id] = $true
        $numbers[[string]$c.no] = $true
        if ([string]$c.shop -notin @('今','福成')) { throw 'Unknown shop in formal master' }
    }

    $digest = SourceDigest $master $basic
    $previousDigest = if (Test-Path $stateFile) { (Get-Content -LiteralPath $stateFile -Raw).Trim() } else { '' }
    if (-not $Force -and -not $PreviewOnly -and $digest -eq $previousDigest) {
        Write-Output ('NO_CHANGE rows=' + $children.Count + ' digest=' + $digest)
        return
    }

    $token = [guid]::NewGuid().ToString('N')
    $candidate = Join-Path $stateDir ('simple_address_book_' + $token + '.xlsx')
    Copy-Item -LiteralPath $bookFile -Destination $candidate -ErrorAction Stop

    $excel = New-Object -ComObject Excel.Application
    $excel.Visible = $false
    $excel.DisplayAlerts = $false
    $excel.AskToUpdateLinks = $false
    $excel.EnableEvents = $false
    $book = $excel.Workbooks.Open($candidate, 0, $false)
    if ($book.ReadOnly) { throw 'Workbook COM copy was opened read-only' }

    $legacyByNumber = @{}
    foreach ($wsOld in @($book.Worksheets)) {
        if ($wsOld.Name -eq '名簿ろく') {
            $limit = [math]::Min([int]$wsOld.UsedRange.Rows.Count + 1, 1200)
            for ($r=2; $r -le $limit; $r++) {
                $no = [string]$wsOld.Cells.Item($r,1).Value2
                $name = [string]$wsOld.Cells.Item($r,2).Value2
                if (-not $no -or -not $name) { continue }
                $key = $no.Trim()
                if ($legacyByNumber.ContainsKey($key)) { continue }
                $legacyByNumber[$key] = @{
                    name=$name
                    guardian=$wsOld.Cells.Item($r,3).Value2
                    postal=$wsOld.Cells.Item($r,6).Value2
                    address=$wsOld.Cells.Item($r,7).Value2
                    building=$wsOld.Cells.Item($r,8).Value2
                    birthday=$wsOld.Cells.Item($r,9).Value2
                    recipient=$wsOld.Cells.Item($r,5).Value2
                    phone=$wsOld.Cells.Item($r,18).Value2
                }
            }
            break
        }
    }

    # Remove only the auto-generated sheet; leave legacy tabs unchanged.
    foreach ($old in @($book.Worksheets)) {
        if ($old.Name -eq $sheetName) { $old.Delete(); break }
    }
    $first = $book.Worksheets.Item(1)
    $ws = $book.Worksheets.Add($first)
    $ws.Name = $sheetName

    $columns = @(
        'No', '店舗', '利用状態', '児童氏名', '苗字', '児童名',
        '保護者名', '略称', '郵便番号', '住所', '建物名', '電話番号',
        '学校名', '生年月日', '受給者番号', '利用希望No',
        '負担率', 'デイ契約日数', '日中契約日数', '児童ID'
    )
    $n = $children.Count
    $width = $columns.Count
    $rows = New-Object 'object[,]' $n,$width
    for ($i=0; $i -lt $n; $i++) {
        $child = $children[$i]
        $id = [string]$child.child_id
        $name = [string]$child.name
        $parts = @([regex]::Split($name.Trim(), '[\s\u3000]+', 2))
        $surname = if($parts.Count -ge 1){$parts[0]}else{''}
        $given = if($parts.Count -ge 2){$parts[1]}else{''}
        $bprop = $basic.children.PSObject.Properties[$id]
        $b = if($null -ne $bprop){$bprop.Value}else{$null}
        $legacy = $null
        $key = [string]$child.no
        if ($legacyByNumber.ContainsKey($key)) {
            $v = $legacyByNumber[$key]
            if ((Norm $v.name) -eq (Norm $name)) { $legacy = $v }
        }
        $guardian = ''
        if ($null -ne $b -and -not [string]::IsNullOrWhiteSpace([string]$b.guardian_given_name)) {
            $guardian = $surname + ' ' + ([string]$b.guardian_given_name).Trim()
        }
        elseif ($legacy) { $guardian = [string]$legacy.guardian }

        $num = [int]$child.no
        $rows[$i,0] = $num
        $rows[$i,1] = TextValue $child.shop
        $rows[$i,2] = if($child.active){'利用中'}else{'停止'}
        $rows[$i,3] = TextValue $name
        $rows[$i,4] = TextValue $surname
        $rows[$i,5] = TextValue $given
        $rows[$i,6] = TextValue $guardian
        $rows[$i,7] = TextValue $child.short_name
        $rows[$i,8] = if($legacy){TextValue $legacy.postal}else{''}
        $rows[$i,9] = FirstOf $b.address $(if($legacy){$legacy.address}else{''})
        $rows[$i,10] = if($legacy){TextValue $legacy.building}else{''}
        $rows[$i,11] = FirstOf $b.phone $(if($legacy){$legacy.phone}else{''})
        $rows[$i,12] = TextValue $b.school_name
        $rows[$i,13] = if($legacy){TextValue $legacy.birthday}else{''}
        $rows[$i,14] = FirstOf $b.recipient_number $(if($legacy){$legacy.recipient}else{''})
        $rows[$i,15] = if($b -and $null -ne $b.usage_request_no){[int]$b.usage_request_no}else{$num}
        $rows[$i,16] = if($b -and $null -ne $b.burden_rate){[double]$b.burden_rate}else{$null}
        $rows[$i,17] = if($b -and $null -ne $b.day_service_days){[int]$b.day_service_days}else{$null}
        $rows[$i,18] = if($b -and $null -ne $b.daytime_days){[int]$b.daytime_days}else{$null}
        $rows[$i,19] = TextValue $id
    }

    $ws.Range('A1:T1').Merge()
    $ws.Range('A1').Value2 = 'びーふらっと　利用者住所録'
    $ws.Range('A1:T1').Font.Bold = $true
    $ws.Range('A1:T1').Font.Size = 16
    $ws.Range('A1:T1').Interior.Color = 4663579
    $ws.Range('A1:T1').Font.Color = 16777215
    $ws.Range('A2:T2').Merge()
    $ws.Range('A2').Value2 = ('更新: ' + (Get-Date -Format 'yyyy/MM/dd HH:mm:ss') +
            '　参照マスタ: ' + [IO.Path]::GetFileName($masterPath) + '　総数: ' + $n)
    $ws.Range('A3:T3').Merge()
    $ws.Range('A3').Value2 = '閲覧専用: Excelの変更はマスタへ反映されません。基本JSONにない旧連絡先などは元の名簿ろくから表示のみ補完しています。'
    $ws.Range('A2:T3').Font.Size = 10
    $ws.Range('A5:T5').Font.Bold = $true
    $ws.Range('A5:T5').Interior.Color = 11119017
    $ws.Range('A5:T5').Font.Color = 16777215
    $ws.Range('A5:T5').RowHeight = 26
    for ($col=0; $col -lt $width; $col++) {
        $ws.Cells.Item(5,$col+1).Value2 = $columns[$col]
    }
    $last = 5 + $n
    $ws.Range('I6:I' + $last).NumberFormat = '@'
    $ws.Range('L6:L' + $last).NumberFormat = '@'
    $ws.Range('O6:O' + $last).NumberFormat = '@'
    $ws.Range('T6:T' + $last).NumberFormat = '@'
    # PS 5.1 COM binder can reject a multi-dimensional SAFEARRAY on Value2.
    # Individual cells are used deliberately (small master, at most a few hundred rows).
    for ($ri=0; $ri -lt $n; $ri++) {
        for ($ci=0; $ci -lt $width; $ci++) {
            $v = $rows[$ri,$ci]
            if ($null -ne $v) {
                $ws.Cells.Item($ri+6,$ci+1).Value2 = $v
            }
        }
    }
    $ws.Range('A6:T' + $last).RowHeight = 23
    $ws.Range('A6:T' + $last).Font.Name = 'Yu Gothic'
    $ws.Range('A5:T' + $last).VerticalAlignment = -4108
    $ws.Range('Q6:Q' + $last).NumberFormat = '0.0'
    $ws.Range('A5:T' + $last).AutoFilter()
    foreach ($col in @(@(1,7),@(2,11),@(3,12),@(4,24),@(5,14),@(6,14),@(7,22),@(8,14),@(9,13),
                        @(10,42),@(11,18),@(12,19),@(13,22),@(14,17),@(15,20),
                        @(16,14),@(17,11),@(18,14),@(19,14),@(20,42))) {
        $ws.Columns.Item($col[0]).ColumnWidth = $col[1]
    }
    $ws.Columns.Item(20).Hidden = $true
    for ($i=0; $i -lt $n; $i++) {
        if (-not $children[$i].active) {
            $r = $i + 6
            $ws.Range('A' + $r + ':T' + $r).Interior.Color = 15132390
        }
    }
    $ws.Activate()
    $excel.ActiveWindow.SplitRow = 5
    $excel.ActiveWindow.FreezePanes = $true
    $ws.PageSetup.Orientation = 2
    $ws.PageSetup.PrintTitleRows = '$1:$5'
    $book.Save()
    $book.Close($false)
    $book = $null

    # No stale output if JSON changed during Excel processing.
    $masterCheck = Get-Content -LiteralPath $masterPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $basicCheck = Get-Content -LiteralPath $basicFile -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($digest -ne (SourceDigest $masterCheck $basicCheck)) {
        throw 'Master changed during export; retry will build fresh listing'
    }
    if ($PreviewOnly) {
        Write-Output ('PREVIEW_READY=' + $candidate + ' rows=' + $n)
        $candidate = $null
        return
    }
    [void](New-Item -ItemType Directory -Force -Path $backupDir)
    $backup = Join-Path $backupDir ('簡易住所録_' + (Get-Date -Format 'yyyyMMdd_HHmmss_fff') + '.xlsx')
    [System.IO.File]::Replace($candidate, $bookFile, $backup)
    $candidate = $null
    [IO.File]::WriteAllText($stateFile, $digest, [Text.UTF8Encoding]::new($false))
    Write-Output ('ADDRESS_BOOK_UPDATED rows=' + $n + ' month=' + [IO.Path]::GetFileName($masterPath))
    Write-Output ('FILE=' + $bookFile)
    Write-Output ('BACKUP=' + $backup)
    Write-Output ('DIGEST=' + $digest)
}
finally {
    if ($book) { try { $book.Close($false) } catch {} }
    if ($excel) { try { $excel.Quit() } catch {} }
    if ($candidate -and (Test-Path -LiteralPath $candidate)) {
        try { Remove-Item -LiteralPath $candidate -Force } catch {}
    }
    if ($lease) { $lease.Dispose() }
}
