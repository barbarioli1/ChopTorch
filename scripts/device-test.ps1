param(
    [ValidateSet('dev', 'shipping')] [string]$Variant = 'dev',
    [string]$Serial = '',
    [switch]$CleanInstall,
    [string]$TestFilter = '',
    [switch]$PreflightOnly,
    [string]$OutputDir = '',
    [int]$TimeoutMinutes = 30
)

$ErrorActionPreference = 'Continue'

$script:Variant = $Variant
$script:Serial = $Serial
$script:StartedUtc = [DateTime]::UtcNow
$script:DefaultOutputDir = [string]::IsNullOrWhiteSpace($OutputDir)
$script:Tests = New-Object 'System.Collections.Generic.List[object]'
$script:DeviceCandidates = @()
$script:OutputPath = ''
$script:AdbPath = ''
$script:AppId = if ($Variant -eq 'dev') { 'com.example.choptorch.devclassic' } else { 'com.barbarioli1.choptorch' }
$script:TestPackage = $script:AppId + '.test'
$script:ExpectTraining = if ($Variant -eq 'dev') { 'true' } else { 'false' }
$script:Summary = [ordered]@{
    scriptVersion = '1.0'
    gitRevision = ''
    gitDirty = $false
    variant = $script:Variant
    applicationId = $script:AppId
    testPackage = $script:TestPackage
    versionCode = ''
    versionName = ''
    appApkSha256 = ''
    testApkSha256 = ''
    device = [ordered]@{
        serial = $script:Serial
        model = ''
        sdk = ''
        release = ''
        fingerprint = ''
        abi = ''
    }
    deviceCandidates = @()
    startedUtc = $script:StartedUtc.ToString('o')
    endedUtc = ''
    tests = @()
    totals = [ordered]@{ passed = 0; failed = 0; skipped = 0 }
    exitCode = 4
    files = @()
}

function Save-Lines {
    param([string]$Path, [string[]]$Lines)
    @($Lines) | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Complete-Run {
    param([int]$Code)

    $script:Summary.exitCode = $Code
    $script:Summary.endedUtc = [DateTime]::UtcNow.ToString('o')
    $script:Summary.device.serial = $script:Serial
    $script:Summary.deviceCandidates = @($script:DeviceCandidates)
    $script:Summary.tests = @($script:Tests.ToArray())
    $script:Summary.totals = [ordered]@{
        passed = @($script:Tests | Where-Object { $_.status -eq 'PASS' }).Count
        failed = @($script:Tests | Where-Object { $_.status -in @('FAIL', 'ERROR', 'UNKNOWN') }).Count
        skipped = @($script:Tests | Where-Object { $_.status -in @('IGNORED', 'ASSUMPTION_FAILURE') }).Count
    }

    if ($script:OutputPath) {
        $evidenceFiles = @()
        if (Test-Path -LiteralPath $script:OutputPath) {
            $evidenceFiles = @(Get-ChildItem -LiteralPath $script:OutputPath -File -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -ne 'summary.json' } |
                ForEach-Object { $_.Name })
        }
        $script:Summary.files = @($evidenceFiles + 'summary.json')
        $summaryPath = Join-Path $script:OutputPath 'summary.json'
        $script:Summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
        Write-Host "Evidence: $script:OutputPath"
    }
    return $Code
}

function Invoke-Adb {
    param([string]$Target, [string[]]$AdbArgs)
    $prefix = @()
    if ($Target) { $prefix = @('-s', $Target) }
    $out = & $script:AdbPath @prefix @AdbArgs 2>&1
    $code = $LASTEXITCODE
    [pscustomobject]@{ Code = $code; Lines = @($out | ForEach-Object { "$_" }) }
}

function Get-AdbPath {
    $candidates = @()
    if ($env:ANDROID_HOME) { $candidates += Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' }
    if ($env:ANDROID_SDK_ROOT) { $candidates += Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe' }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return (Resolve-Path -LiteralPath $candidate).Path }
    }
    $where = Get-Command where.exe -ErrorAction SilentlyContinue
    if ($where) {
        $found = & $where.Source adb 2>$null
        if ($LASTEXITCODE -eq 0) {
            foreach ($path in @($found)) {
                if ($path -and (Test-Path -LiteralPath $path -PathType Leaf)) { return $path }
            }
        }
    }
    return ''
}

function Resolve-JavaHome {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }

    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand -and $javaCommand.Source) {
        $javaBin = Split-Path -Parent $javaCommand.Source
        $candidates += Split-Path -Parent $javaBin
    }

    if ($env:LOCALAPPDATA) {
        $candidates += Join-Path $env:LOCALAPPDATA 'Programs\ChopTorchToolchain\jdk-17'
    }

    foreach ($candidate in $candidates) {
        $javaExecutable = Join-Path $candidate 'bin\java.exe'
        if (Test-Path -LiteralPath $javaExecutable -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return ''
}

function Get-AdbText {
    param([string[]]$AdbArgs)
    $result = Invoke-Adb -Target $script:Serial -AdbArgs $AdbArgs
    if ($result.Code -ne 0) { throw "adb $($AdbArgs -join ' ') failed with exit code $($result.Code): $($result.Lines -join ' ')" }
    return ,@($result.Lines)
}

function Write-PreflightFailure {
    param([string]$Message)
    Write-Host $Message
    return (Complete-Run 2)
}

function Get-Property {
    param([string]$Name)
    $lines = Get-AdbText -AdbArgs @('shell', 'getprop', $Name)
    return (@($lines) -join "`n").Trim()
}

function Invoke-DeviceTest {
    $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
    Push-Location $repoRoot
    try {
        $timestamp = $script:StartedUtc.ToString('yyyyMMdd-HHmmss')
        if (-not $OutputDir) {
            $folderSerial = if ($Serial) { $Serial } else { 'unresolved' }
            $folderSerial = $folderSerial -replace ':', '_'
            $OutputDir = Join-Path 'test-results\device' "$timestamp-$folderSerial-$Variant"
        }
        if (-not [System.IO.Path]::IsPathRooted($OutputDir)) { $OutputDir = Join-Path $repoRoot $OutputDir }
        $script:OutputPath = [System.IO.Path]::GetFullPath($OutputDir)
        New-Item -ItemType Directory -Path $script:OutputPath -Force | Out-Null

        $script:AdbPath = Get-AdbPath
        if (-not $script:AdbPath) { return (Write-PreflightFailure 'adb.exe was not found. Set ANDROID_HOME or ANDROID_SDK_ROOT, or add adb to PATH.') }

        $startServer = Invoke-Adb -Target '' -AdbArgs @('start-server')
        Save-Lines -Path (Join-Path $script:OutputPath 'adb-start-server.txt') -Lines $startServer.Lines
        if ($startServer.Code -ne 0) { return (Write-PreflightFailure 'adb start-server failed.') }

        $deviceList = Invoke-Adb -Target '' -AdbArgs @('devices', '-l')
        Save-Lines -Path (Join-Path $script:OutputPath 'adb-devices.txt') -Lines $deviceList.Lines
        if ($deviceList.Code -ne 0) { return (Write-PreflightFailure 'adb devices -l failed.') }
        $entries = @()
        foreach ($line in $deviceList.Lines) {
            if ($line -match '^\s*(\S+)\s+(\S+)(?:\s|$)' -and $Matches[1] -ne 'List') {
                $entries += [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2]; Detail = $line.Trim() }
            }
        }
        $script:DeviceCandidates = @($entries | Where-Object { $_.State -ne 'device' } | ForEach-Object { $_.Detail })
        $available = @($entries | Where-Object { $_.State -eq 'device' })
        if ($Serial) {
            $selected = @($available | Where-Object { $_.Serial -eq $Serial })
            if ($selected.Count -ne 1) { return (Write-PreflightFailure "Requested serial '$Serial' is not in the device state.") }
            $script:Serial = $selected[0].Serial
        } elseif ($available.Count -eq 1) {
            $script:Serial = $available[0].Serial
        } else {
            return (Write-PreflightFailure "Expected exactly one connected device; found $($available.Count). Specify -Serial when more than one device is connected.")
        }

        if ($script:DefaultOutputDir -and -not $Serial) {
            $folderSerial = $script:Serial -replace ':', '_'
            $selectedOutput = Join-Path (Join-Path $repoRoot 'test-results\device') "$timestamp-$folderSerial-$Variant"
            if ($script:OutputPath -ne $selectedOutput) {
                Move-Item -LiteralPath $script:OutputPath -Destination $selectedOutput
                $script:OutputPath = $selectedOutput
            }
        }

        $script:Summary.device.serial = $script:Serial
        $propertyMap = @{
            model = 'ro.product.model'
            sdk = 'ro.build.version.sdk'
            release = 'ro.build.version.release'
            fingerprint = 'ro.build.fingerprint'
            abi = 'ro.product.cpu.abi'
        }
        foreach ($key in $propertyMap.Keys) {
            try { $script:Summary.device[$key] = Get-Property -Name $propertyMap[$key] }
            catch { return (Write-PreflightFailure $_.Exception.Message) }
        }
        $sdkNumber = 0
        if (-not [int]::TryParse([string]$script:Summary.device.sdk, [ref]$sdkNumber) -or $sdkNumber -lt 26) {
            return (Write-PreflightFailure "Android SDK level '$($script:Summary.device.sdk)' is invalid or below the minimum 26.")
        }

        try { $powerLines = Get-AdbText -AdbArgs @('shell', 'dumpsys', 'power') }
        catch { return (Write-PreflightFailure $_.Exception.Message) }
        Save-Lines -Path (Join-Path $script:OutputPath 'device-power.txt') -Lines $powerLines
        $wakefulness = @($powerLines | Where-Object { $_ -match 'mWakefulness=' })
        if ($wakefulness.Count -eq 0 -or -not ($wakefulness -match 'mWakefulness=Awake')) {
            return (Write-PreflightFailure 'Device is not awake (could not confirm mWakefulness=Awake).')
        }

        try { $windowLines = Get-AdbText -AdbArgs @('shell', 'dumpsys', 'window') }
        catch { return (Write-PreflightFailure $_.Exception.Message) }
        Save-Lines -Path (Join-Path $script:OutputPath 'device-window.txt') -Lines $windowLines
        if ($windowLines -match 'isKeyguardShowing=true|mShowingLockscreen=true') {
            return (Write-PreflightFailure 'Device keyguard is showing; unlock it and retry.')
        }
        if ($PreflightOnly) { return (Complete-Run 0) }

        $javaHome = Resolve-JavaHome
        if (-not $javaHome) {
            Write-Host 'A JDK was not found. Set JAVA_HOME to a JDK 17 installation before running device tests.'
            return (Complete-Run 3)
        }
        $env:JAVA_HOME = $javaHome
        $javaBinPath = Join-Path $javaHome 'bin'
        if (($env:Path -split ';') -notcontains $javaBinPath) {
            $env:Path = "$javaBinPath;$env:Path"
        }

        $gradlePath = Join-Path $repoRoot 'gradlew.bat'
        $gradleTasks = @('assembleDebug', 'assembleDebugAndroidTest')
        if ($Variant -eq 'shipping') { $gradleTasks += '-Pshipping=true' }
        $buildOutput = & $gradlePath @gradleTasks 2>&1
        $buildCode = $LASTEXITCODE
        Save-Lines -Path (Join-Path $script:OutputPath 'build.log') -Lines @($buildOutput | ForEach-Object { "$_" })
        if ($buildCode -ne 0) {
            Write-Host "Gradle build failed with exit code $buildCode. See build.log."
            return (Complete-Run 3)
        }

        $apkSources = @(
            [pscustomobject]@{ Source = 'app\build\outputs\apk\debug\app-debug.apk'; Name = 'app.apk' },
            [pscustomobject]@{ Source = 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'; Name = 'test.apk' }
        )
        foreach ($apk in $apkSources) {
            $sourcePath = Join-Path $repoRoot $apk.Source
            if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
                Write-Host "Expected APK was not created: $($apk.Source)"
                return (Complete-Run 3)
            }
            Copy-Item -LiteralPath $sourcePath -Destination (Join-Path $script:OutputPath $apk.Name) -Force
            $hash = (Get-FileHash -LiteralPath (Join-Path $script:OutputPath $apk.Name) -Algorithm SHA256).Hash
            if ($apk.Name -eq 'app.apk') { $script:Summary.appApkSha256 = $hash }
            else { $script:Summary.testApkSha256 = $hash }
        }

        $revision = & git rev-parse HEAD 2>&1
        if ($LASTEXITCODE -eq 0) { $script:Summary.gitRevision = (@($revision) -join '').Trim() }
        $gitStatus = & git status --porcelain 2>&1
        if ($LASTEXITCODE -eq 0) { $script:Summary.gitDirty = (@($gitStatus).Count -gt 0) }

        if ($CleanInstall) {
            Invoke-Adb -Target $script:Serial -AdbArgs @('uninstall', $script:AppId) | Out-Null
            Invoke-Adb -Target $script:Serial -AdbArgs @('uninstall', $script:TestPackage) | Out-Null
        }
        $appInstall = Invoke-Adb -Target $script:Serial -AdbArgs @('install', '-r', (Join-Path $script:OutputPath 'app.apk'))
        Save-Lines -Path (Join-Path $script:OutputPath 'install-app.txt') -Lines $appInstall.Lines
        if ($appInstall.Code -ne 0 -or -not ($appInstall.Lines -contains 'Success')) {
            Write-Host 'App install failed. See install-app.txt.'
            return (Complete-Run 3)
        }
        $testInstall = Invoke-Adb -Target $script:Serial -AdbArgs @('install', '-r', (Join-Path $script:OutputPath 'test.apk'))
        Save-Lines -Path (Join-Path $script:OutputPath 'install-test.txt') -Lines $testInstall.Lines
        if ($testInstall.Code -ne 0 -or -not ($testInstall.Lines -contains 'Success')) {
            Write-Host 'Instrumentation APK install failed. See install-test.txt.'
            return (Complete-Run 3)
        }

        try { $instrumentations = Get-AdbText -AdbArgs @('shell', 'pm', 'list', 'instrumentation') }
        catch {
            Write-Host "Unable to read installed instrumentation packages: $($_.Exception.Message)"
            return (Complete-Run 3)
        }
        Save-Lines -Path (Join-Path $script:OutputPath 'instrumentation-list.txt') -Lines $instrumentations
        if (-not ($instrumentations -match [regex]::Escape("$($script:TestPackage)/androidx.test.runner.AndroidJUnitRunner"))) {
            Write-Host 'Installed instrumentation runner was not found. See instrumentation-list.txt.'
            return (Complete-Run 3)
        }

        try { $packageInfo = Get-AdbText -AdbArgs @('shell', 'dumpsys', 'package', $script:AppId) }
        catch {
            Write-Host "Unable to read installed app metadata: $($_.Exception.Message)"
            return (Complete-Run 3)
        }
        $versionCodeLine = @($packageInfo | Where-Object { $_ -match 'versionCode=' } | Select-Object -First 1)
        $versionNameLine = @($packageInfo | Where-Object { $_ -match 'versionName=' } | Select-Object -First 1)
        if ($versionCodeLine.Count -gt 0 -and $versionCodeLine[0] -match 'versionCode=([^\s]+)') { $script:Summary.versionCode = $Matches[1] }
        if ($versionNameLine.Count -gt 0 -and $versionNameLine[0] -match 'versionName=([^\s]+)') { $script:Summary.versionName = $Matches[1] }

        $clearLog = Invoke-Adb -Target $script:Serial -AdbArgs @('logcat', '-c')
        if ($clearLog.Code -ne 0) {
            Write-Host 'Unable to clear logcat before the instrumentation run.'
            return (Complete-Run 4)
        }

        $runArguments = @('-s', $script:Serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'expectTraining', $script:ExpectTraining)
        if ($TestFilter) { $runArguments += @('-e', 'class', $TestFilter) }
        $runArguments += "$($script:TestPackage)/androidx.test.runner.AndroidJUnitRunner"
        $quotedArguments = foreach ($argument in $runArguments) { '"' + $argument.Replace('"', '\"') + '"' }
        $argumentString = $quotedArguments -join ' '
        $runStdout = Join-Path $script:OutputPath 'run.txt'
        $runStderr = Join-Path $script:OutputPath 'run.err.txt'
        $process = Start-Process -FilePath $script:AdbPath -ArgumentList $argumentString `
            -RedirectStandardOutput $runStdout -RedirectStandardError $runStderr -NoNewWindow -PassThru
        if (-not $process.WaitForExit($TimeoutMinutes * 60 * 1000)) {
            try { $process.Kill() } catch { }
            Write-Host "Instrumentation timed out after $TimeoutMinutes minute(s)."
            Capture-FailureEvidence
            Invoke-Adb -Target $script:Serial -AdbArgs @('shell', 'am', 'force-stop', $script:AppId) | Out-Null
            return (Complete-Run 4)
        }
        $instrumentationProcessCode = $process.ExitCode

        $rawOutput = @()
        if (Test-Path -LiteralPath $runStdout) { $rawOutput = @(Get-Content -LiteralPath $runStdout) }
        $completionLine = @($rawOutput | Where-Object { $_ -match '^INSTRUMENTATION_CODE:\s*(-?\d+)' } | Select-Object -Last 1)
        $instrumentationResultCode = $null
        if ($completionLine.Count -gt 0 -and $completionLine[0] -match '^INSTRUMENTATION_CODE:\s*(-?\d+)') {
            $instrumentationResultCode = [int]$Matches[1]
        }
        $currentClass = ''
        $currentTest = ''
        $parsed = 0
        foreach ($line in $rawOutput) {
            if ($line -match '^INSTRUMENTATION_STATUS:\s*class=(.*)$') { $currentClass = $Matches[1].Trim() }
            elseif ($line -match '^INSTRUMENTATION_STATUS:\s*test=(.*)$') { $currentTest = $Matches[1].Trim() }
            elseif ($line -match '^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)') {
                $statusCode = [int]$Matches[1]
                if ($statusCode -eq 1) { $currentClass = ''; $currentTest = ''; continue }
                if (-not $currentClass -or -not $currentTest) { continue }
                $status = switch ($statusCode) {
                    0 { 'PASS' }
                    -1 { 'ERROR' }
                    -2 { 'FAIL' }
                    -3 { 'IGNORED' }
                    -4 { 'ASSUMPTION_FAILURE' }
                    default { 'UNKNOWN' }
                }
                $script:Tests.Add([pscustomobject]@{ class = $currentClass; name = $currentTest; status = $status })
                $parsed++
                $currentClass = ''
                $currentTest = ''
            }
        }
        $resultLines = @($script:Tests | ForEach-Object { "$($_.class)#$($_.name)  $($_.status)" })
        Save-Lines -Path (Join-Path $script:OutputPath 'results.txt') -Lines $resultLines

        $testFailures = @($script:Tests | Where-Object { $_.status -ne 'PASS' }).Count -gt 0
        $resultCode = 0
        if ($null -eq $instrumentationResultCode -or $instrumentationResultCode -ne -1 -or $parsed -eq 0) {
            Write-Host 'Instrumentation crashed, timed out, or produced no complete parseable result.'
            $resultCode = 4
        } elseif ($testFailures) {
            Write-Host 'One or more instrumentation tests failed, errored, or were skipped.'
            $resultCode = 1
        }

        if ($resultCode -ne 0) { Capture-FailureEvidence }
        Invoke-Adb -Target $script:Serial -AdbArgs @('shell', 'am', 'force-stop', $script:AppId) | Out-Null
        return (Complete-Run $resultCode)
    } catch {
        Write-Host $_.Exception.Message
        if ($script:Serial -and $script:AdbPath -and $script:OutputPath) {
            Capture-FailureEvidence
            Invoke-Adb -Target $script:Serial -AdbArgs @('shell', 'am', 'force-stop', $script:AppId) | Out-Null
        }
        return (Complete-Run 4)
    } finally {
        Pop-Location
    }
}

function Capture-FailureEvidence {
    if (-not $script:Serial -or -not $script:AdbPath -or -not $script:OutputPath) { return }
    $remoteScreenshot = '/sdcard/choptorch-fail.png'
    $screenshot = Invoke-Adb -Target $script:Serial -AdbArgs @('shell', 'screencap', '-p', $remoteScreenshot)
    if ($screenshot.Code -eq 0) {
        $localScreenshot = Join-Path $script:OutputPath 'failure.png'
        $pull = Invoke-Adb -Target $script:Serial -AdbArgs @('pull', $remoteScreenshot, $localScreenshot)
        if ($pull.Code -ne 0) { Remove-Item -LiteralPath $localScreenshot -Force -ErrorAction SilentlyContinue }
    }
    Invoke-Adb -Target $script:Serial -AdbArgs @('shell', 'rm', $remoteScreenshot) | Out-Null

    $pidLines = Invoke-Adb -Target $script:Serial -AdbArgs @('shell', 'pidof', '-s', $script:AppId)
    $appPid = if ($pidLines.Code -eq 0 -and $pidLines.Lines.Count -gt 0) { $pidLines.Lines[0].Trim() } else { '' }
    $logResult = Invoke-Adb -Target $script:Serial -AdbArgs @('logcat', '-d', '-v', 'threadtime')
    $temporaryLog = Join-Path $script:OutputPath 'logcat-full.tmp'
    Save-Lines -Path $temporaryLog -Lines $logResult.Lines
    if ($appPid) { $filtered = @($logResult.Lines | Where-Object { $_.Contains($appPid) }) }
    else { $filtered = @($logResult.Lines | Where-Object { $_.Contains($script:AppId) }) }
    Save-Lines -Path (Join-Path $script:OutputPath 'app-logcat.txt') -Lines $filtered
    Remove-Item -LiteralPath $temporaryLog -Force -ErrorAction SilentlyContinue
}

$exitCode = Invoke-DeviceTest
Write-Host "Device test exit code: $exitCode"
exit $exitCode
