# setup_android_sdk.ps1
# Automates the setup of Android Command-Line Tools & ADB without Android Studio

$ErrorActionPreference = "Stop"

$sdkDir = "$env:LOCALAPPDATA\Android\Sdk"
if (-not (Test-Path $sdkDir)) {
    New-Item -ItemType Directory -Path $sdkDir -Force | Out-Null
}

Write-Host "=== Setting up Android CLI Toolchain at $sdkDir ===" -ForegroundColor Cyan

# 1. Download and install Platform-Tools (provides adb.exe)
$platformToolsZip = "$env:TEMP\platform-tools.zip"
$platformToolsUrl = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"

if (-not (Test-Path "$sdkDir\platform-tools\adb.exe")) {
    Write-Host "[1/3] Downloading Android Platform Tools (adb)..." -ForegroundColor Yellow
    Invoke-WebRequest -Uri $platformToolsUrl -OutFile $platformToolsZip
    Write-Host "      Extracting to $sdkDir..."
    Expand-Archive -Path $platformToolsZip -DestinationPath $sdkDir -Force
    Remove-Item -Path $platformToolsZip -Force
} else {
    Write-Host "[1/3] Platform-tools (adb) already installed." -ForegroundColor Green
}

# 2. Download and install Command-Line Tools (sdkmanager)
$cmdlineZip = "$env:TEMP\cmdline-tools.zip"
$cmdlineUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
$cmdlineDir = "$sdkDir\cmdline-tools\latest"

if (-not (Test-Path "$cmdlineDir\bin\sdkmanager.bat")) {
    Write-Host "[2/3] Downloading Android Command-Line Tools (sdkmanager)..." -ForegroundColor Yellow
    Invoke-WebRequest -Uri $cmdlineUrl -OutFile $cmdlineZip
    Write-Host "      Extracting..."
    $tempExtract = "$env:TEMP\cmdline-temp"
    if (Test-Path $tempExtract) { Remove-Item -Recurse -Force $tempExtract }
    Expand-Archive -Path $cmdlineZip -DestinationPath $tempExtract -Force
    
    New-Item -ItemType Directory -Path "$sdkDir\cmdline-tools" -Force | Out-Null
    Move-Item -Path "$tempExtract\cmdline-tools" -Destination $cmdlineDir -Force
    Remove-Item -Path $cmdlineZip -Force
    if (Test-Path $tempExtract) { Remove-Item -Recurse -Force $tempExtract }
} else {
    Write-Host "[2/3] Command-line tools already installed." -ForegroundColor Green
}

# 3. Add to user PATH and environment
Write-Host "[3/3] Configuring Environment Variables..." -ForegroundColor Yellow
[System.Environment]::SetEnvironmentVariable("ANDROID_HOME", $sdkDir, [System.EnvironmentVariableTarget]::User)
$env:ANDROID_HOME = $sdkDir

$currentPath = [System.Environment]::GetEnvironmentVariable("Path", [System.EnvironmentVariableTarget]::User)
$toolsPaths = @("$sdkDir\platform-tools", "$cmdlineDir\bin")

foreach ($p in $toolsPaths) {
    if ($currentPath -notlike "*$p*") {
        $currentPath = "$p;$currentPath"
        Write-Host "      Added $p to user PATH"
    }
    if ($env:Path -notlike "*$p*") {
        $env:Path = "$p;$env:Path"
    }
}
[System.Environment]::SetEnvironmentVariable("Path", $currentPath, [System.EnvironmentVariableTarget]::User)

Write-Host "`n=== Android CLI Setup Completed Successfully! ===" -ForegroundColor Green
Write-Host "Verification:"
& "$sdkDir\platform-tools\adb.exe" version
