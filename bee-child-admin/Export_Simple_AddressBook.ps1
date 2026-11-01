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
$statusFile = Join-Path $root '児童利用状態.json'
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
$previewBook = $null
$csvPath = $null
$viewerPath = $null

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
function SourceDigest($master, $basics, $status) {
    $data = @(
        ($master.children | Select-Object child_id,name,child_name,short_name,no,shop,active,active_basis | ConvertTo-Json -Depth 5 -Compress),
        ($basics.children | ConvertTo-Json -Depth 8 -Compress),
        ($status.children | ConvertTo-Json -Depth 8 -Compress)
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
    $status = Get-Content -LiteralPath $statusFile -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($master.schema_version -ne 1 -or $null -eq $master.children -or $master.children.Count -eq 0) {
        throw 'Formal child master schema is invalid or empty'
    }
    if ($status.schema_version -ne 1 -or $null -eq $status.children) { throw 'Formal utilization status is invalid' }
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

    $digest = SourceDigest $master $basic $status
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

    # Remove only our generated sheet. All other legacy sheets remain intact.
    for ($j=[int]$book.Worksheets.Count; $j -ge 1; $j--) {
        $oldSheet = $book.Worksheets.Item($j)
        if ($oldSheet.Name -eq $sheetName) { $oldSheet.Delete(); break }
    }

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
        $statusEntry = $status.children.PSObject.Properties[$id]
        $effectiveActive = if($statusEntry -and $statusEntry.Value -is [bool]) { [bool]$statusEntry.Value } else { [bool]$child.active }
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
        $rows[$i,2] = if($effectiveActive){'利用中'}else{'停止'}
        $rows[$i,3] = TextValue $name
        $rows[$i,4] = TextValue $surname
        $rows[$i,5] = TextValue $given
        $rows[$i,6] = TextValue $guardian
        $rows[$i,7] = TextValue $child.short_name
        $rows[$i,8] = FirstOf $b.postal_code $(if($legacy){$legacy.postal}else{''})
        $rows[$i,9] = FirstOf $b.address $(if($legacy){$legacy.address}else{''})
        $rows[$i,10] = FirstOf $b.building_name $(if($legacy){$legacy.building}else{''})
        $rows[$i,11] = FirstOf $b.phone $(if($legacy){$legacy.phone}else{''})
        $rows[$i,12] = TextValue $b.school_name
        $rows[$i,13] = FirstOf $b.birth_date $(if($legacy){$legacy.birthday}else{''})
        $rows[$i,14] = FirstOf $b.recipient_number $(if($legacy){$legacy.recipient}else{''})
        $rows[$i,15] = if($b -and $null -ne $b.usage_request_no){[int]$b.usage_request_no}else{$num}
        $rows[$i,16] = if($b -and $null -ne $b.burden_rate){[double]$b.burden_rate}else{$null}
        $rows[$i,17] = if($b -and $null -ne $b.day_service_days){[int]$b.day_service_days}else{$null}
        $rows[$i,18] = if($b -and $null -ne $b.daytime_days){[int]$b.daytime_days}else{$null}
        $rows[$i,19] = TextValue $id
    }

    # Bulk generation with openpyxl, then Excel copies one complete worksheet.
    # This avoids slow/unstable PowerShell 5.1 COM Value2 calls for every row.
    $csvPath = Join-Path $stateDir ('simple_book_rows_' + $token + '.csv')
    $viewerPath = Join-Path $stateDir ('simple_book_view_' + $token + '.xlsx')
    $viewerPy = Join-Path $stateDir 'Generate_Simple_AddressBook_View.py'
    if (-not (Test-Path -LiteralPath $viewerPy)) {
        throw 'Viewer generator Python module is not deployed'
    }
    $csvRows = @(
        for ($ri=0; $ri -lt $n; $ri++) {
            $entry = [ordered]@{}
            for ($ci=0; $ci -lt $width; $ci++) {
                $v = $rows[$ri,$ci]
                $entry[$columns[$ci]] = if ($null -eq $v) { '' } else { [string]$v }
            }
            [pscustomobject]$entry
        }
    )
    $csvRows | Export-Csv -LiteralPath $csvPath -NoTypeInformation -Encoding UTF8
    $now = Get-Date -Format 'yyyy/MM/dd HH:mm:ss'
    & python.exe $viewerPy --csv $csvPath --out $viewerPath --source ([IO.Path]::GetFileName($masterPath)) --timestamp $now
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $viewerPath)) {
        throw 'Viewer sheet Python generation failed'
    }
    $previewBook = $excel.Workbooks.Open($viewerPath, 0, $true)
    $sourceSheet = $previewBook.Worksheets.Item(1)
    $sourceSheet.Copy($book.Worksheets.Item(1))
    if ($book.Worksheets.Item(1).Name -ne $sheetName) {
        throw 'Excel did not copy the address-book sheet into the legacy workbook'
    }
    $book.Worksheets.Item(1).Activate()
    $previewBook.Close($false)
    $previewBook = $null
    $book.Save()
    $book.Close($false)
    $book = $null

    # No stale output if JSON changed during Excel processing.
    $masterCheck = Get-Content -LiteralPath $masterPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $basicCheck = Get-Content -LiteralPath $basicFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $statusCheck = Get-Content -LiteralPath $statusFile -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($digest -ne (SourceDigest $masterCheck $basicCheck $statusCheck)) {
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
    if ($previewBook) { try { $previewBook.Close($false) } catch {} }
    if ($book) { try { $book.Close($false) } catch {} }
    if ($excel) { try { $excel.Quit() } catch {} }
    foreach ($temp in @($csvPath,$viewerPath,$candidate)) {
        if ($temp -and (Test-Path -LiteralPath $temp)) {
            try { Remove-Item -LiteralPath $temp -Force } catch {}
        }
    }
    if ($lease) { $lease.Dispose() }
}
