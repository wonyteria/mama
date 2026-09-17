param(
    [string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug'),
    [string]$ToolchainRoot = (Join-Path $PSScriptRoot '..\..\..\.toolchains\mom-android')
)

$ErrorActionPreference = 'Stop'
$ToolchainRoot = [IO.Path]::GetFullPath($ToolchainRoot)
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$javaPath = Join-Path $ToolchainRoot 'java\jdk-17.0.20.1+1'
$sdkPath = Join-Path $ToolchainRoot 'sdk'
$gradle = Join-Path $ToolchainRoot 'gradle-8.13\bin\gradle.bat'
foreach ($required in @((Join-Path $javaPath 'bin\java.exe'), $gradle, (Join-Path $sdkPath 'platforms\android-36\android.jar'))) {
    if (!(Test-Path -LiteralPath $required)) {
        throw "Missing build tool: $required. Run scripts/bootstrap.ps1 first."
    }
}

$savedEnvironment = @{}
foreach ($key in @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'ANDROID_USER_HOME', 'ANDROID_AVD_HOME', 'GRADLE_USER_HOME', 'JAVA_TOOL_OPTIONS')) {
    $savedEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
try {
    $env:JAVA_HOME = $javaPath
    $env:ANDROID_HOME = $sdkPath
    $env:ANDROID_SDK_ROOT = $sdkPath
    $env:ANDROID_USER_HOME = Join-Path $ToolchainRoot 'android-user'
    $env:ANDROID_AVD_HOME = Join-Path $ToolchainRoot 'avd'
    $env:GRADLE_USER_HOME = Join-Path $ToolchainRoot 'gradle-user'
    $env:JAVA_TOOL_OPTIONS = "-Duser.home=$projectRoot\.build-user"
    $gradleArgs = @(
        '--project-dir', $projectRoot,
        '--no-daemon',
        '--console=plain',
        '-Dorg.gradle.native=false',
        '-Pkotlin.compiler.execution.strategy=in-process',
        '--no-parallel',
        '--max-workers=1'
    ) + $Tasks
    & $gradle @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed ($LASTEXITCODE)." }
} finally {
    foreach ($key in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($key, $savedEnvironment[$key], 'Process')
    }
}
