param(
    [string]$Version = "1.0.5",
    [string]$WixDir = "$PSScriptRoot\..\java\.tools\wix314"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$javaDir = Join-Path $root "java"
$buildDir = Join-Path $root "release-build"
$inputDir = Join-Path $buildDir "input"
$outputDir = Join-Path $root "release"
$pythonRoot = Split-Path (Get-Command python).Source -Parent

if (Test-Path $buildDir) { Remove-Item -LiteralPath $buildDir -Recurse -Force }
New-Item -ItemType Directory -Path $inputDir, $outputDir -Force | Out-Null

Push-Location $javaDir
try {
    & .\mvnw.cmd clean package dependency:copy-dependencies "-DoutputDirectory=target/dependency"
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
} finally { Pop-Location }

Copy-Item -LiteralPath (Join-Path $javaDir "target\office-automation-$Version.jar") -Destination $inputDir
Copy-Item -Path (Join-Path $javaDir "target\dependency\*.jar") -Destination $inputDir
Copy-Item -LiteralPath (Join-Path $root "python") -Destination $inputDir -Recurse
$latinModelDir = Join-Path $root "models\latin_rec_v3"
$latinModelPath = Join-Path $latinModelDir "inference.onnx"
$latinDictPath = Join-Path $latinModelDir "dict.txt"
$latinModelSha256 = "e9d7a33667e8aaa702862975186adf2012e3f390cc0f9422865957125f8071cf"
if (-not (Test-Path $latinModelPath) -or -not (Test-Path $latinDictPath)) {
    Write-Host "Downloading pinned Vietnamese OCR model for the installer..."
    New-Item -ItemType Directory -Path $latinModelDir -Force | Out-Null
    Invoke-WebRequest `
        -Uri "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/onnx/PP-OCRv4/rec/latin_PP-OCRv3_rec_mobile.onnx" `
        -OutFile $latinModelPath
    Invoke-WebRequest `
        -Uri "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/paddle/PP-OCRv4/rec/latin_PP-OCRv3_rec_mobile/latin_dict.txt" `
        -OutFile $latinDictPath
}
$actualLatinModelSha256 = (Get-FileHash -LiteralPath $latinModelPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualLatinModelSha256 -ne $latinModelSha256) {
    throw "Vietnamese OCR model checksum mismatch. Expected $latinModelSha256, got $actualLatinModelSha256"
}
New-Item -ItemType Directory -Path (Join-Path $inputDir "models") -Force | Out-Null
Copy-Item -LiteralPath $latinModelDir -Destination (Join-Path $inputDir "models") -Recurse
New-Item -ItemType Directory -Path (Join-Path $inputDir "app-data\templates") -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $root "app-data\templates\vietnam") `
    -Destination (Join-Path $inputDir "app-data\templates") -Recurse

# Copy fast uninstall utilities
Copy-Item -LiteralPath (Join-Path $root "scripts\fast-uninstall.bat") -Destination $inputDir -ErrorAction SilentlyContinue
Copy-Item -LiteralPath (Join-Path $root "scripts\gỡ_cài_đặt_nhanh.bat") -Destination $inputDir -ErrorAction SilentlyContinue
Copy-Item -LiteralPath (Join-Path $root "scripts\uninstall-app.ps1") -Destination $inputDir -ErrorAction SilentlyContinue
Copy-Item -LiteralPath (Join-Path $root "scripts\install-antigravity-mcp.ps1") -Destination $inputDir -ErrorAction SilentlyContinue

# Ensure icons exist
$iconPath = Join-Path $root "scripts\icons\app-icon.ico"
if (-not (Test-Path $iconPath)) {
    & python (Join-Path $root "scripts\generate_icons.py")
}

$runtimeDir = Join-Path $inputDir "python-runtime"
$null = robocopy $pythonRoot $runtimeDir /E /XD "Doc" "include" "libs" "Scripts" "__pycache__" ".pytest_cache" /XF "*.pyc" "*.pyo"
if ($LASTEXITCODE -ge 8) { throw "Copying Python runtime failed with robocopy exit code $LASTEXITCODE" }

# Python native OCR dependencies require a newer MSVC runtime than the Java
# runtime bundled by jpackage. App-local deployment prevents Windows from
# resolving MSVCP140.dll from Java's runtime\bin when Java launches Python.
$msvcRuntimeFiles = @("MSVCP140.dll", "MSVCP140_1.dll", "MSVCP140_2.dll", "CONCRT140.dll")
foreach ($dllName in $msvcRuntimeFiles) {
    $systemDll = Join-Path $env:SystemRoot "System32\$dllName"
    if (Test-Path $systemDll) {
        Copy-Item -LiteralPath $systemDll -Destination $runtimeDir -Force
    }
}
if (-not (Test-Path (Join-Path $runtimeDir "MSVCP140.dll"))) {
    throw "MSVCP140.dll is required for the bundled Python OCR runtime"
}

# Slim down python runtime to remove bloat, drastically speeding up MSI install/uninstall
Write-Host "Slimming Python runtime for fast installation & uninstallation..."
$bloatDirs = @(
    "Doc", "include", "libs", "Scripts", "Tools", "tcl",
    "Lib\idlelib", "Lib\test", "Lib\turtledemo", "Lib\ensurepip", "Lib\pydoc_data",
    "Lib\tkinter\test", "Lib\unittest\test", "Lib\ctypes\test", "Lib\distutils\tests",
    "Lib\site-packages\pip", "Lib\site-packages\setuptools", "Lib\site-packages\wheel",
    "Lib\site-packages\pkg_resources"
)
foreach ($rel in $bloatDirs) {
    $target = Join-Path $runtimeDir $rel
    if (Test-Path $target) {
        Remove-Item -LiteralPath $target -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# Remove all __pycache__, *.dist-info, *.egg-info, *.pyc, *.pdb
Get-ChildItem -Path $runtimeDir -Include "__pycache__", "*.dist-info", "*.egg-info" -Recurse -Directory -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
Get-ChildItem -Path $runtimeDir -Include "*.pyc", "*.pyo", "*.pdb", "*.chm" -Recurse -File -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

$env:PATH = "$WixDir;$env:PATH"
if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue)) {
    throw "WiX 3 binaries not found at $WixDir"
}

& jpackage `
    --type exe `
    --name "Office Studio AI" `
    --app-version $Version `
    --vendor "GiaoDungCursor" `
    --description "Vietnamese document OCR, parsing and template export desktop application" `
    --icon $iconPath `
    --input $inputDir `
    --dest $outputDir `
    --main-jar "office-automation-$Version.jar" `
    --main-class "com.company.office.Launcher" `
    --win-per-user-install `
    --win-dir-chooser `
    --win-menu `
    --win-shortcut
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

Get-ChildItem -LiteralPath $outputDir -Filter "*.exe" | Select-Object FullName, Length, LastWriteTime
