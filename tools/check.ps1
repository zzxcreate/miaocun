<#
.SYNOPSIS
    秒存（MiaoCun）静态自检脚本。

.DESCRIPTION
    不需要 Android SDK / Gradle，用来提前发现"编译前就该发现"的问题：
      1. 所有 XML 是否 well-formed（按 XML 声明里的编码读取，不受 PowerShell 默认编码影响）；
      2. Kotlin 中引用的 R.id / R.layout / R.drawable / R.string / R.color / R.mipmap / R.style / R.xml 是否真实存在；
      3. XML 中引用的 @drawable/ @color/ @string/ @mipmap/ @style/ 是否存在；
      4. Kotlin 去掉注释与字符串后，(){}[] 是否平衡；
      5. AndroidManifest 中以 "." 开头的组件是否有对应 .kt 文件。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\tools\check.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

$toolsDir = $PSScriptRoot
$root = Split-Path -Parent $toolsDir
$main = Join-Path $root "app\src\main"
$res = Join-Path $main "res"
$java = Join-Path $main "java"

$script:errors = New-Object 'System.Collections.Generic.List[string]'
$script:warnings = New-Object 'System.Collections.Generic.List[string]'

function Add-Error([string]$text) { $script:errors.Add($text) }
function Add-Warning([string]$text) { $script:warnings.Add($text) }

# 显式按 UTF-8 读取，避免 PS 5.1 默认用 ANSI(GBK) 把中文读成乱码
function Read-Utf8([string]$path) {
    return [System.IO.File]::ReadAllText($path, (New-Object System.Text.UTF8Encoding($false)))
}

# XmlDocument.Load 会遵循 XML 声明中的 encoding，比 [xml] 强转安全
function Load-Xml([string]$path) {
    $doc = New-Object System.Xml.XmlDocument
    $doc.Load($path)
    return $doc
}

Write-Host "秒存 静态自检  |  $main" -ForegroundColor DarkGray

# ------------------------------------------------------------ 1. XML 语法
Write-Host ""
Write-Host "[1/5] XML well-formed ..." -ForegroundColor Cyan

$xmlFiles = @(Get-ChildItem -Path $main -Recurse -Filter *.xml -File)
foreach ($file in $xmlFiles) {
    try {
        $null = Load-Xml $file.FullName
    } catch {
        Add-Error ("XML 解析失败: {0} :: {1}" -f $file.FullName, $_.Exception.Message)
    }
}
Write-Host "    检查了 $($xmlFiles.Count) 个 XML 文件"

# --------------------------------------------------------- 2. 收集资源名
Write-Host ""
Write-Host "[2/5] 收集已定义的资源 ..." -ForegroundColor Cyan

$layoutNames = @{}
$idNames = @{}
$drawableNames = @{}
$colorNames = @{}
$stringNames = @{}
$styleNames = @{}
$xmlNames = @{}

$layoutDir = Join-Path $res "layout"
if (Test-Path $layoutDir) {
    $layoutFiles = @(Get-ChildItem -Path $layoutDir -Filter *.xml -File)
    foreach ($f in $layoutFiles) {
        $layoutNames[$f.BaseName] = $true
        $text = Read-Utf8 $f.FullName
        foreach ($m in [regex]::Matches($text, '@\+id/([A-Za-z0-9_]+)')) {
            $idNames[$m.Groups[1].Value] = $true
        }
    }
}

$drawableDirs = @("drawable", "drawable-nodpi", "drawable-anydpi", "mipmap", "mipmap-anydpi-v26", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi")
foreach ($sub in $drawableDirs) {
    $dir = Join-Path $res $sub
    if (Test-Path $dir) {
        foreach ($f in @(Get-ChildItem -Path $dir -File)) {
            $ext = $f.Extension.ToLower()
            if ($ext -eq ".xml" -or $ext -eq ".png" -or $ext -eq ".webp" -or $ext -eq ".jpg") {
                $drawableNames[$f.BaseName] = $true
            }
        }
    }
}

$valuesDir = Join-Path $res "values"
$colorsFile = Join-Path $valuesDir "colors.xml"
if (Test-Path $colorsFile) {
    $doc = Load-Xml $colorsFile
    foreach ($n in $doc.SelectNodes("/resources/color")) { $colorNames[$n.GetAttribute("name")] = $true }
}
$stringsFile = Join-Path $valuesDir "strings.xml"
if (Test-Path $stringsFile) {
    $doc = Load-Xml $stringsFile
    foreach ($n in $doc.SelectNodes("/resources/string")) { $stringNames[$n.GetAttribute("name")] = $true }
}
$themesFile = Join-Path $valuesDir "themes.xml"
if (Test-Path $themesFile) {
    $doc = Load-Xml $themesFile
    foreach ($n in $doc.SelectNodes("/resources/*")) { $styleNames[$n.GetAttribute("name")] = $true }
}

$xmlResDir = Join-Path $res "xml"
if (Test-Path $xmlResDir) {
    foreach ($f in @(Get-ChildItem -Path $xmlResDir -Filter *.xml -File)) { $xmlNames[$f.BaseName] = $true }
}

Write-Host ("    layout={0}  id={1}  drawable/mipmap={2}  color={3}  string={4}  style={5}  xml={6}" -f `
    $layoutNames.Count, $idNames.Count, $drawableNames.Count, $colorNames.Count, $stringNames.Count, $styleNames.Count, $xmlNames.Count)

# ------------------------------------------------- 3. Kotlin 里的 R.* 引用
Write-Host ""
Write-Host "[3/5] 检查 Kotlin 中的 R.* 引用 ..." -ForegroundColor Cyan

$ktFiles = @(Get-ChildItem -Path $java -Recurse -Filter *.kt -File)
foreach ($file in $ktFiles) {
    $text = Read-Utf8 $file.FullName
    foreach ($m in [regex]::Matches($text, 'R\.(id|layout|drawable|string|color|mipmap|style|xml)\.([A-Za-z0-9_]+)')) {
        $type = $m.Groups[1].Value
        $name = $m.Groups[2].Value
        $exists = $false
        switch ($type) {
            "id" { $exists = $idNames.ContainsKey($name) }
            "layout" { $exists = $layoutNames.ContainsKey($name) }
            "drawable" { $exists = $drawableNames.ContainsKey($name) }
            "mipmap" { $exists = $drawableNames.ContainsKey($name) }
            "string" { $exists = $stringNames.ContainsKey($name) }
            "color" { $exists = $colorNames.ContainsKey($name) }
            "style" { $exists = $styleNames.ContainsKey($name) }
            "xml" { $exists = $xmlNames.ContainsKey($name) }
        }
        if (-not $exists) {
            Add-Error ("缺少资源 R.{0}.{1} （{2}）" -f $type, $name, $file.Name)
        }
    }
}

# ------------------------------------------------ 4. XML 里的 @xxx 引用
Write-Host ""
Write-Host "[4/5] 检查 XML 中的资源引用 ..." -ForegroundColor Cyan

foreach ($file in $xmlFiles) {
    $text = Read-Utf8 $file.FullName
    foreach ($m in [regex]::Matches($text, '@(drawable|color|string|mipmap|style)/([A-Za-z0-9_.]+)')) {
        $type = $m.Groups[1].Value
        $name = $m.Groups[2].Value
        $exists = $false
        switch ($type) {
            "drawable" { $exists = $drawableNames.ContainsKey($name) }
            "mipmap" { $exists = $drawableNames.ContainsKey($name) }
            "color" { $exists = $colorNames.ContainsKey($name) }
            "string" { $exists = $stringNames.ContainsKey($name) }
            "style" { $exists = $styleNames.ContainsKey($name) }
        }
        if (-not $exists) {
            Add-Error ("缺少资源 @{0}/{1} （{2}）" -f $type, $name, $file.Name)
        }
    }
}

# -------------------------------------------- 5. Kotlin 括号 + Manifest 类
Write-Host ""
Write-Host "[5/5] 检查 Kotlin 括号平衡与 Manifest 组件 ..." -ForegroundColor Cyan

foreach ($file in $ktFiles) {
    $text = Read-Utf8 $file.FullName
    # Kotlin 词法扫描：跳过注释 / 字符串 / 字符字面量后统计括号
    $counts = @{ '{' = 0; '}' = 0; '(' = 0; ')' = 0; '[' = 0; ']' = 0 }
    $state = 'code'
    $i = 0
    $n = $text.Length
    while ($i -lt $n) {
        $c = $text[$i]
        $c2 = ''
        if (($i + 1) -lt $n) { $c2 = $text[$i + 1] }
        if ($state -eq 'code') {
            if ($c -eq '/' -and $c2 -eq '/') { $state = 'line'; $i += 2; continue }
            if ($c -eq '/' -and $c2 -eq '*') { $state = 'block'; $i += 2; continue }
            if ($c -eq '"' -and $c2 -eq '"' -and ($i + 2) -lt $n -and $text[$i + 2] -eq '"') {
                $state = 'raw'; $i += 3; continue
            }
            if ($c -eq '"') { $state = 'str'; $i++; continue }
            if ($c -eq "'") { $state = 'char'; $i++; continue }
            $cs = [string]$c
            if ($counts.ContainsKey($cs)) { $counts[$cs] = $counts[$cs] + 1 }
            $i++
            continue
        }
        if ($state -eq 'line') {
            if ($c -eq "`n") { $state = 'code' }
            $i++
            continue
        }
        if ($state -eq 'block') {
            if ($c -eq '*' -and $c2 -eq '/') { $state = 'code'; $i += 2; continue }
            $i++
            continue
        }
        if ($state -eq 'raw') {
            if ($c -eq '"' -and $c2 -eq '"' -and ($i + 2) -lt $n -and $text[$i + 2] -eq '"') {
                $state = 'code'; $i += 3; continue
            }
            $i++
            continue
        }
        # str / char
        if ($c -eq '\') { $i += 2; continue }
        if ($state -eq 'str' -and $c -eq '"') { $state = 'code'; $i++; continue }
        if ($state -eq 'char' -and $c -eq "'") { $state = 'code'; $i++; continue }
        $i++
    }

    foreach ($pair in @(@('{', '}'), @('(', ')'), @('[', ']'))) {
        $open = $counts[$pair[0]]
        $close = $counts[$pair[1]]
        if ($open -ne $close) {
            Add-Error ("括号不平衡: {0}  '{1}'={2}  '{3}'={4}" -f $file.Name, $pair[0], $open, $pair[1], $close)
        }
    }
}

$manifestPath = Join-Path $main "AndroidManifest.xml"
if (Test-Path $manifestPath) {
    $manifest = Load-Xml $manifestPath
    $androidNs = "http://schemas.android.com/apk/res/android"
    foreach ($node in $manifest.SelectNodes("//*")) {
        $name = $node.GetAttribute("name", $androidNs)
        if ($name -and $name.StartsWith(".")) {
            $pkgName = $manifest.DocumentElement.GetAttribute("namespace")
            if (-not $pkgName) { $pkgName = $manifest.DocumentElement.GetAttribute("package") }
            if (-not $pkgName) {
                $gradleFile = Join-Path $root "app\build.gradle.kts"
                if (Test-Path $gradleFile) {
                    $gradleText = Read-Utf8 $gradleFile
                    $mm = [regex]::Match($gradleText, 'namespace\s*=\s*"([^"]+)"')
                    if ($mm.Success) { $pkgName = $mm.Groups[1].Value }
                }
            }
            if (-not $pkgName) { $pkgName = "com.miaocun.app" }
            $pkgPath = $pkgName -replace '\.', '\'
            $relative = $name.Substring(1) -replace '\.', '\'
            $kt = Join-Path (Join-Path $java $pkgPath) "$relative.kt"
            if (-not (Test-Path $kt)) {
                Add-Warning ("Manifest 组件 {0} 没有对应的 {1}.kt" -f $name, $relative)
            }
        }
    }
}

Write-Host "    Kotlin 文件: $($ktFiles.Count)"

# ------------------------------------------------------------- 结果汇总
Write-Host ""
if ($script:warnings.Count -gt 0) {
    Write-Host ("警告 ({0})" -f $script:warnings.Count) -ForegroundColor Yellow
    foreach ($w in $script:warnings) { Write-Host "  - $w" -ForegroundColor Yellow }
}

if ($script:errors.Count -gt 0) {
    Write-Host ""
    Write-Host ("发现 {0} 个问题：" -f $script:errors.Count) -ForegroundColor Red
    foreach ($e in $script:errors) { Write-Host "  x $e" -ForegroundColor Red }
    exit 1
}

Write-Host "自检通过，没有发现问题。" -ForegroundColor Green
exit 0
