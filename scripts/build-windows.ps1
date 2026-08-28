param(
    [string]$Version = "1.0.0",
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
New-Item -ItemType Directory -Path (Join-Path $inputDir "app-data\templates") -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $root "app-data\templates\vietnam") `
    -Destination (Join-Path $inputDir "app-data\templates") -Recurse

$runtimeDir = Join-Path $inputDir "python-runtime"
$null = robocopy $pythonRoot $runtimeDir /E /XD "Doc" "include" "libs" "Scripts" "__pycache__" ".pytest_cache" /XF "*.pyc" "*.pyo"
if ($LASTEXITCODE -ge 8) { throw "Copying Python runtime failed with robocopy exit code $LASTEXITCODE" }

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
