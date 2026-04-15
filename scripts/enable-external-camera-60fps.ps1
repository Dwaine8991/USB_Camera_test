param(
    [string]$Serial = ""
)

$ErrorActionPreference = "Stop"

function Get-AdbArgs {
    param(
        [string[]]$ExtraArgs
    )

    if ([string]::IsNullOrWhiteSpace($Serial)) {
        return $ExtraArgs
    }

    return @("-s", $Serial) + $ExtraArgs
}

function Invoke-Adb {
    param(
        [string[]]$ExtraArgs
    )

    $arguments = Get-AdbArgs -ExtraArgs $ExtraArgs
    & adb @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed: adb $($arguments -join ' ')"
    }
}

function Invoke-AdbCapture {
    param(
        [string[]]$ExtraArgs
    )

    $arguments = Get-AdbArgs -ExtraArgs $ExtraArgs
    $output = & adb @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed: adb $($arguments -join ' ')"
    }
    return $output
}

$xmlContent = @'
<ExternalCamera>
    <Provider>
        <ignore> <!-- Internal video devices to be ignored by external camera HAL -->
            <id>0</id>
            <id>1</id>
        </ignore>
    </Provider>
    <Device>
        <MaxJpegBufferSize bytes="3145728"/>
        <NumVideoBuffers count="4"/>
        <NumStillBuffers count="2"/>
        <FpsList>
            <Limit width="4096" height="3112" fpsBound="60.0"/>
        </FpsList>
    </Device>
</ExternalCamera>
'@

$rootDir = Split-Path -Parent $PSScriptRoot
$tmpDir = Join-Path $rootDir ".tmp"
$localConfig = Join-Path $tmpDir "external_camera_config.60fps.xml"

New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
Set-Content -LiteralPath $localConfig -Value $xmlContent -Encoding ascii

Write-Host "Switching adbd to root..."
Invoke-Adb -ExtraArgs @("root")
Invoke-Adb -ExtraArgs @("wait-for-device")

Write-Host "Uploading 60fps external camera config..."
Invoke-Adb -ExtraArgs @("push", $localConfig, "/data/local/tmp/external_camera_config.xml")

$bindCommand = "set -e; " +
    "chcon u:object_r:vendor_configs_file:s0 /data/local/tmp/external_camera_config.xml; " +
    "umount /vendor/etc/external_camera_config.xml >/dev/null 2>&1 || true; " +
    "mount -o bind /data/local/tmp/external_camera_config.xml /vendor/etc/external_camera_config.xml; " +
    "grep -F 60.0 /vendor/etc/external_camera_config.xml >/dev/null; " +
    "am force-stop com.example.multicameratest >/dev/null 2>&1; " +
    "killall cameraserver camerahalserver android.hardware.camera.provider-V1-external-service >/dev/null 2>&1"

Write-Host "Binding config and restarting camera services..."
Invoke-Adb -ExtraArgs @("shell", $bindCommand)
Start-Sleep -Seconds 3

$mountState = Invoke-AdbCapture -ExtraArgs @("shell", "mount | grep /vendor/etc/external_camera_config.xml")
$cameraDump = Invoke-AdbCapture -ExtraArgs @("shell", "dumpsys media.camera")
$fpsRangeMatches = $cameraDump | Select-String "30 60"
$frameDurationMatches = $cameraDump | Select-String "16666666"

if ($mountState -and $fpsRangeMatches -and $frameDurationMatches) {
    Write-Host ""
    Write-Host "60fps override is active."
    Write-Host "Camera2 now reports the 30-60 fps range and 16.666 ms min frame duration."
} else {
    Write-Host ""
    Write-Warning "Override was applied, but Camera2 verification did not clearly show 60fps yet."
    Write-Host "Review with: adb $(if ($Serial) { "-s $Serial " })shell dumpsys media.camera"
}
