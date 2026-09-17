param(
    [string]$ToolchainRoot = (Join-Path $PSScriptRoot '..\..\..\.toolchains\mom-android'),
    [switch]$WithEmulator
)

$ErrorActionPreference = 'Stop'
$ToolchainRoot = [IO.Path]::GetFullPath($ToolchainRoot)
$downloads = Join-Path $ToolchainRoot 'downloads'
New-Item -ItemType Directory -Force -Path $downloads | Out-Null

# Official sources, checked 2026-09-13:
# https://learn.microsoft.com/en-us/java/openjdk/download
# https://gradle.org/release-checksums/
# https://developer.android.com/studio
function Get-VerifiedArchive([string]$Name, [string]$Url, [string]$Sha256) {
    $archive = Join-Path $downloads $Name
    if (!(Test-Path -LiteralPath $archive) -or (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $Sha256) {
        Write-Host "Downloading $Name from the official distribution..."
        Invoke-WebRequest -Uri $Url -OutFile $archive -UseBasicParsing
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $Sha256) {
        throw "Checksum mismatch: $archive. Do not execute this archive."
    }
    return $archive
}

$javaPath = Join-Path $ToolchainRoot 'java\jdk-17.0.20.1+1'
$gradlePath = Join-Path $ToolchainRoot 'gradle-8.13'
$sdkPath = Join-Path $ToolchainRoot 'sdk'
$sdkManager = Join-Path $sdkPath 'cmdline-tools\latest\bin\sdkmanager.bat'

if (!(Test-Path -LiteralPath (Join-Path $javaPath 'bin\java.exe'))) {
    $zip = Get-VerifiedArchive 'jdk.zip' 'https://aka.ms/download-jdk/microsoft-jdk-17.0.20.1-windows-x64.zip' '3d9006956fc8af5601cd24ffc4f468bef48279c7ebd8171b9bdf90d0aabfbf1f'
    Expand-Archive -LiteralPath $zip -DestinationPath (Join-Path $ToolchainRoot 'java') -Force
}
if (!(Test-Path -LiteralPath (Join-Path $gradlePath 'bin\gradle.bat'))) {
    $zip = Get-VerifiedArchive 'gradle.zip' 'https://services.gradle.org/distributions/gradle-8.13-bin.zip' '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78'
    Expand-Archive -LiteralPath $zip -DestinationPath $ToolchainRoot -Force
}
if (!(Test-Path -LiteralPath $sdkManager)) {
    $zip = Get-VerifiedArchive 'cmdline-tools.zip' 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
    $staging = Join-Path $ToolchainRoot 'sdk-extraction'
    Expand-Archive -LiteralPath $zip -DestinationPath $staging -Force
    $destination = Join-Path $sdkPath 'cmdline-tools\latest'
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    Copy-Item -Path (Join-Path $staging 'cmdline-tools\*') -Destination $destination -Recurse -Force
}

$savedEnvironment = @{}
foreach ($key in @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_USER_HOME', 'ANDROID_AVD_HOME', 'GRADLE_USER_HOME')) {
    $savedEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
try {
    $env:JAVA_HOME = $javaPath
    $env:ANDROID_HOME = $sdkPath
    $env:ANDROID_USER_HOME = Join-Path $ToolchainRoot 'android-user'
    $env:ANDROID_AVD_HOME = Join-Path $ToolchainRoot 'avd'
    $env:GRADLE_USER_HOME = Join-Path $ToolchainRoot 'gradle-user'
    New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME, $env:ANDROID_AVD_HOME | Out-Null
    $packages = @('platforms;android-36', 'build-tools;35.0.0', 'platform-tools')
    if ($WithEmulator) { $packages += @('emulator', 'system-images;android-35;google_apis;x86_64') }
    # Accept only the licenses requested for these development packages.
    1..20 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$sdkPath" @packages
    if ($LASTEXITCODE -ne 0) { throw "Android SDK installation failed ($LASTEXITCODE)." }
    if ($WithEmulator -and !(Test-Path -LiteralPath (Join-Path $env:ANDROID_AVD_HOME 'mom_probe.ini'))) {
        'no' | & (Join-Path $sdkPath 'cmdline-tools\latest\bin\avdmanager.bat') create avd --name mom_probe --package 'system-images;android-35;google_apis;x86_64' --device pixel_6 --path (Join-Path $env:ANDROID_AVD_HOME 'mom_probe.avd')
        if ($LASTEXITCODE -ne 0) { throw 'Android virtual device creation failed.' }
    }
    & (Join-Path $javaPath 'bin\java.exe') -version
    & (Join-Path $gradlePath 'bin\gradle.bat') --version
    if ($LASTEXITCODE -ne 0) { throw 'Gradle verification failed.' }
    & (Join-Path $sdkPath 'platform-tools\adb.exe') version
    if ($LASTEXITCODE -ne 0) { throw 'ADB verification failed.' }
    Write-Host "Toolchain ready: $ToolchainRoot"
} finally {
    foreach ($key in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($key, $savedEnvironment[$key], 'Process')
    }
}
