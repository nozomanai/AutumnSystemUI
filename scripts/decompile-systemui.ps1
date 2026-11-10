# Pull SystemUI (and in-process plugin packages, when installed) off the
# authorized device, decompile them to Java, and copy the hook-relevant
# files into decompiled/browse. The whole decompiled directory is gitignored.

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Adb = @("D:\SOFT\adb\adb.exe", "D:\SOFT\platform-tools\adb.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $Adb) { $Adb = "adb" }
$Jadx = @("D:\SOFT\jadx-1.5.6\bin\jadx.bat", "D:\PROGRAM\SOFT\jadx-1.5.6\bin\jadx.bat") | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $Jadx) { throw "找不到 jadx" }

$Out = Join-Path $Root "decompiled"
$ApkDir = Join-Path $Out "apk"
$JavaDir = Join-Path $Out "java"
$Browse = Join-Path $Out "browse"

$deviceLines = & $Adb devices
$ready = @($deviceLines | Where-Object { $_ -match "`tdevice$" })
if ($ready.Count -eq 0) {
    throw @"
没有已授权的设备。请在手机上点允许 USB 调试后重新运行本脚本。
$($deviceLines -join [Environment]::NewLine)
"@
}

function Invoke-Adb {
    & $Adb @args
    if ($LASTEXITCODE -ne 0) { throw "adb 失败: $($args -join ' ')" }
}

$packages = New-Object System.Collections.Generic.List[string]
$packages.Add("com.android.systemui")
$installed = Invoke-Adb shell pm list packages
foreach ($extra in @("miui.systemui.plugin", "com.miui.systemui.plugin")) {
    if ($installed -match [regex]::Escape("package:$extra")) {
        $packages.Add($extra)
    }
}

if (Test-Path $Out) { Remove-Item -Recurse -Force $Out }
New-Item -ItemType Directory -Force -Path $ApkDir, $JavaDir, $Browse | Out-Null

foreach ($package in $packages) {
    Write-Host "拉取 $package"
    $pathLines = Invoke-Adb shell pm path $package
    $apkIndex = 0
    foreach ($line in $pathLines) {
        if ($line -notmatch "^package:(.+)$") { continue }
        $remote = $Matches[1].Trim()
        $leaf = Split-Path $remote -Leaf
        $local = Join-Path $ApkDir "$package-$apkIndex-$leaf"
        Write-Host "  $remote"
        Invoke-Adb pull $remote $local
        $decoded = Join-Path $JavaDir "$package-$apkIndex"
        Write-Host "反编译到 $decoded"
        & $Jadx -d $decoded --show-bad-code -j 4 --no-debug-info $local
        # jadx 退出码 1 表示部分类反编译失败，源码目录仍然可用。
        if ($LASTEXITCODE -gt 1) { throw "jadx 失败: $local (exit $LASTEXITCODE)" }
        if ($LASTEXITCODE -ne 0) { Write-Host "jadx 有反编译错误，已保留可阅读源码: $local" }
        $apkIndex++
    }
}

$groups = [ordered]@{
    "volte"           = '(?i)volte|vonr'
    "control-center"  = '(?i)controlcenter|controlpanel'
    "status-bar"      = '(?i)statusbar'
    "mobile"          = '(?i)mobile|cellular|signal'
    "shade"           = '(?i)shade|notificationpanel|quicksettings'
}

$javaFiles = Get-ChildItem -Path $JavaDir -Recurse -Filter *.java -File -ErrorAction SilentlyContinue
$copied = @{}
foreach ($name in $groups.Keys) { $copied[$name] = 0 }

foreach ($file in $javaFiles) {
    $relative = $file.FullName.Substring($JavaDir.Length).TrimStart('\')
    foreach ($name in $groups.Keys) {
        if ($relative -notmatch $groups[$name]) { continue }
        $dest = Join-Path (Join-Path $Browse $name) $relative
        New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
        Copy-Item $file.FullName $dest
        $copied[$name]++
    }
}

$mentionDir = Join-Path $Browse "volte-mentions"
$mentionCount = 0
if ($javaFiles.Count -gt 0) {
    $hits = $javaFiles | Select-String -Pattern 'volte|vonr' -CaseSensitive:$false -List
    foreach ($hit in $hits) {
        $relative = $hit.Path.Substring($JavaDir.Length).TrimStart('\')
        $dest = Join-Path $mentionDir $relative
        if (Test-Path $dest) { continue }
        New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
        Copy-Item $hit.Path $dest
        $mentionCount++
    }
}

$resCount = 0
$resFiles = Get-ChildItem -Path $JavaDir -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match '\\resources\\' -and $_.Name -match '(?i)volte|vonr' }
foreach ($file in $resFiles) {
    $relative = $file.FullName.Substring($JavaDir.Length).TrimStart('\')
    $dest = Join-Path (Join-Path $Browse "volte-res") $relative
    New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
    Copy-Item $file.FullName $dest
    $resCount++
}

$index = New-Object System.Collections.Generic.List[string]
$index.Add("decompiled $(Get-Date -Format 'yyyy-MM-dd HH:mm')")
$index.Add("packages: $($packages -join ', ')")
$index.Add("java files: $($javaFiles.Count)")
foreach ($name in $groups.Keys) { $index.Add("browse/$name : $($copied[$name])") }
$index.Add("browse/volte-mentions : $mentionCount")
$index.Add("browse/volte-res : $resCount")
$index.Add("")
$index.Add("java/ 是 jadx 的完整源码。browse/ 按文件名分成 volte、control-center、status-bar、mobile、shade。")
$index.Add("volte-mentions 是正文里出现 VoLTE/VoNR、但路径名没有这两个词的类。")
$indexText = $index -join [Environment]::NewLine
Set-Content -Path (Join-Path $Browse "INDEX.txt") -Value $indexText -Encoding UTF8
Write-Host $indexText
