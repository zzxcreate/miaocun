<#
.SYNOPSIS
    秒存（MiaoCun）一键构建脚本。

.DESCRIPTION
    自动定位 JDK 17 与 Android SDK，生成 local.properties；
    如果项目里还没有 Gradle Wrapper，会下载 Gradle 8.9 并生成；
    最后执行 assembleDebug / assembleRelease，并打印 APK 路径。

.EXAMPLE
    .\build.ps1
    .\build.ps1 -Release
    .\build.ps1 -SdkPath "D:\Android\Sdk"
#>
[CmdletBinding()]
param(
    [switch]$Release,
    [string]$SdkPath = ""
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

function Step([string]$text) {
    Write-Host ""
    Write-Host "==> $text" -ForegroundColor Cyan
}

function Info([string]$text) {
    Write-Host "    $text"
}

function Fail([string]$text) {
    Write-Host ""
    Write-Host "!! $text" -ForegroundColor Red
    exit 1
}

Write-Host "秒存 build.ps1  |  项目目录: $root" -ForegroundColor DarkGray

# ---------------------------------------------------------------- 1. JDK
Step "1/4 检查 JDK"

$javaExe = $null
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME "bin\java.exe"
    if (Test-Path $candidate) { $javaExe = $candidate }
}
if (-not $javaExe) {
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) { $javaExe = $cmd.Source }
}
if (-not $javaExe) {
    Fail "没有找到 JDK。请安装 JDK 17（https://adoptium.net），设置 JAVA_HOME 或把 java 加入 PATH。"
}

$versionLine = (& $javaExe -version 2>&1 | Select-Object -First 1)
$major = 0
if ($versionLine -match '"(\d+)') {
    $major = [int]$Matches[1]
}
Info "$versionLine"
if ($major -lt 17) {
    Fail "需要 JDK 17 或更高版本（当前主版本 $major）。"
}

# ------------------------------------------------------- 2. Android SDK
Step "2/4 检查 Android SDK"

$candidates = @()
if ($SdkPath) { $candidates += $SdkPath }
if ($env:ANDROID_HOME) { $candidates += $env:ANDROID_HOME }
if ($env:ANDROID_SDK_ROOT) { $candidates += $env:ANDROID_SDK_ROOT }
if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk") }

$sdk = $null
foreach ($dir in $candidates) {
    if ($dir -and (Test-Path (Join-Path $dir "platforms"))) {
        $sdk = $dir
        break
    }
}
if (-not $sdk) {
    Fail @"
没有找到 Android SDK。请任选一种方式：
    1) 安装 Android Studio，首次启动后 SDK 会自动装到 %LOCALAPPDATA%\Android\Sdk
    2) 设置环境变量 ANDROID_HOME 指向 SDK 目录
    3) 用 .\build.ps1 -SdkPath "你的 SDK 路径"
"@
}

Info "SDK: $sdk"
$sdkForProps = $sdk -replace '\\', '/'
$localProps = Join-Path $root "local.properties"
"sdk.dir=$sdkForProps" | Set-Content -Path $localProps -Encoding ASCII
Info "已写入 local.properties"

# ----------------------------------------------------------- 3. Gradle
Step "3/4 准备 Gradle"

$wrapperJar = Join-Path $root "gradle\wrapper\gradle-wrapper.jar"
$gradlewBat = Join-Path $root "gradlew.bat"
$gradleExe = $null

if ((Test-Path $gradlewBat) -and (Test-Path $wrapperJar)) {
    $gradleExe = $gradlewBat
    Info "使用项目自带的 Gradle Wrapper"
} else {
    $cmd = Get-Command gradle -ErrorAction SilentlyContinue
    if ($cmd) {
        $gradleExe = $cmd.Source
        Info "使用 PATH 中的 Gradle: $gradleExe"
    }
}

if (-not $gradleExe) {
    Info "未找到 Gradle，开始下载 8.9（仅首次，约 130MB）..."
    $tools = Join-Path $root ".tools"
    $distDir = Join-Path $tools "gradle-8.9"
    $distGradle = Join-Path $distDir "bin\gradle.bat"
    if (-not (Test-Path $distGradle)) {
        New-Item -ItemType Directory -Force -Path $tools | Out-Null
        $zip = Join-Path $tools "gradle-8.9-bin.zip"
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        try {
            Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-8.9-bin.zip" -OutFile $zip -UseBasicParsing
        } catch {
            Fail "下载 Gradle 失败：$($_.Exception.Message)`n请手动安装 Gradle 8.9 加入 PATH，或用 Android Studio 打开本工程。"
        }
        Info "解压中 ..."
        Expand-Archive -Path $zip -DestinationPath $tools -Force
    }
    $gradleExe = $distGradle
}

if (-not (Test-Path $wrapperJar)) {
    Info "生成 Gradle Wrapper ..."
    Push-Location $root
    try {
        & $gradleExe wrapper --gradle-version 8.9 --distribution-type bin
        $code = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($code -ne 0 -or -not (Test-Path $wrapperJar)) {
        Fail "生成 Gradle Wrapper 失败（退出码 $code）。"
    }
    $gradleExe = $gradlewBat
    Info "Wrapper 已生成"
}

# ------------------------------------------------------------ 4. Build
Step "4/4 开始构建"

$task = "assembleDebug"
if ($Release) { $task = "assembleRelease" }
Info "执行: $task"

Push-Location $root
try {
    & $gradleExe $task --no-daemon
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($code -ne 0) {
    Fail "构建失败（退出码 $code），请查看上面的 Gradle 输出。"
}

$apkRoot = Join-Path $root "app\build\outputs\apk"
$apks = @()
if (Test-Path $apkRoot) {
    $apks = @(Get-ChildItem -Path $apkRoot -Recurse -Filter *.apk -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)
}

Write-Host ""
Write-Host "构建成功。" -ForegroundColor Green
if ($apks.Count -gt 0) {
    foreach ($apk in $apks) {
        Write-Host "    APK: $($apk.FullName)" -ForegroundColor Green
    }
    if ($Release) {
        Write-Host "    注意：release 包尚未签名，需要配置 signingConfigs 或手动 apksigner。" -ForegroundColor Yellow
    }
} else {
    Write-Host "    没有找到 APK，请检查上面的输出。" -ForegroundColor Yellow
}
