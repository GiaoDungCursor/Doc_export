@echo off
chcp 65001 >nul
title Gỡ Cài Đặt Nhanh - Office Studio AI
echo =====================================================
echo    Trình Gỡ Cài Đặt Nhanh: Office Studio AI
echo =====================================================
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0uninstall-app.ps1"
if %ERRORLEVEL% NEQ 0 (
    echo [Lỗi] Quá trình gỡ cài đặt gặp sự cố.
    pause
)
