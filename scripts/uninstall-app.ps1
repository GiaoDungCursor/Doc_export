<#
.SYNOPSIS
    Gỡ cài đặt siêu tốc ứng dụng Office Studio AI (Fast Uninstaller).
.DESCRIPTION
    Tự động đóng tất cả các tiến trình nền bị khóa (Office Studio AI, Python Sidecar),
    thực thi gỡ cài đặt MSI với MSIFASTINSTALL=7 và dọn dẹp sạch sẽ trong 3 giây.
#>

param(
    [switch]$Silent = $false
)

$appName = "Office Studio AI"
Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host "   Trình Gỡ Cài Đặt Siêu Tốc: $appName" -ForegroundColor Yellow
Write-Host "=====================================================" -ForegroundColor Cyan

# 1. Đóng tiến trình đang chạy để tránh khóa file
Write-Host "[1/3] Đang kiểm tra và đóng tiến trình liên quan..." -ForegroundColor Green
$processesToKill = @("Office Studio AI", "python", "pythonw")
foreach ($procName in $processesToKill) {
    Get-Process -Name $procName -ErrorAction SilentlyContinue | ForEach-Object {
        try {
            $path = $_.Path
            if ($path -like "*Office Studio AI*" -or $procName -eq "Office Studio AI") {
                Write-Host "      -> Đang dừng tiến trình $($_.Name) (PID: $($_.Id))..." -ForegroundColor Gray
                Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue
            }
        } catch {}
    }
}
Start-Sleep -Milliseconds 400

# 2. Tìm Product Code trong Registry
Write-Host "[2/3] Đang tìm thông tin gói cài đặt MSI trong Windows Registry..." -ForegroundColor Green
$uninstallKeys = @(
    "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall",
    "HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall",
    "HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall"
)

$productCode = $null
foreach ($regPath in $uninstallKeys) {
    if (Test-Path $regPath) {
        Get-ChildItem -Path $regPath -ErrorAction SilentlyContinue | ForEach-Object {
            $displayName = (Get-ItemProperty -Path $_.PsPath -Name DisplayName -ErrorAction SilentlyContinue).DisplayName
            if ($displayName -and $displayName -like "*$appName*") {
                $productCode = $_.PSChildName
                Write-Host "      -> Tìm thấy gói: $displayName ($productCode)" -ForegroundColor Gray
            }
        }
    }
}

# 3. Gỡ cài đặt MSI
Write-Host "[3/3] Đang thực thi gỡ cài đặt tốc độ cao..." -ForegroundColor Green
if ($productCode) {
    $msiArgs = "/x `"$productCode`" /qn /norestart MSIFASTINSTALL=7"
    Write-Host "      -> Chạy msiexec $msiArgs" -ForegroundColor Gray
    $proc = Start-Process -FilePath "msiexec.exe" -ArgumentList $msiArgs -PassThru -Wait
    Write-Host "      -> Gỡ MSI hoàn tất với mã thoát: $($proc.ExitCode)" -ForegroundColor Green
} else {
    Write-Host "      -> Không tìm thấy mã MSI, chuyển sang dọn dẹp thư mục cài đặt." -ForegroundColor Yellow
}

# Dọn dẹp thư mục cài đặt nếu còn sót
$installDirs = @(
    "$env:LOCALAPPDATA\Programs\$appName",
    "$env:ProgramFiles\$appName",
    "$env:ProgramFiles(x86)\$appName"
)

foreach ($dir in $installDirs) {
    if (Test-Path $dir) {
        try {
            Write-Host "      -> Đang dọn dẹp thư mục: $dir" -ForegroundColor Gray
            Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction SilentlyContinue
        } catch {}
    }
}

# Xóa Shortcut Desktop & Start Menu
$desktopShortcut = Join-Path ([Environment]::GetFolderPath("Desktop")) "$appName.lnk"
if (Test-Path $desktopShortcut) {
    Remove-Item -LiteralPath $desktopShortcut -Force -ErrorAction SilentlyContinue
}

$startMenuShortcut = Join-Path ([Environment]::GetFolderPath("Programs")) "$appName.lnk"
if (Test-Path $startMenuShortcut) {
    Remove-Item -LiteralPath $startMenuShortcut -Force -ErrorAction SilentlyContinue
}

Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host " Gỡ cài đặt $appName hoàn tất thành công và siêu tốc!" -ForegroundColor Green
Write-Host "=====================================================" -ForegroundColor Cyan

if (-not $Silent) {
    Start-Sleep -Seconds 2
}
