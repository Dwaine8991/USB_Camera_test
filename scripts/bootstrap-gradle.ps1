param(
    [string]$Version = "8.10.2"
)

$root = Split-Path -Parent $PSScriptRoot
$toolsDir = Join-Path $root ".tools"
$targetDir = Join-Path $toolsDir "gradle-$Version"
$zipPath = Join-Path $toolsDir "gradle-$Version-bin.zip"
$downloadUrl = "https://services.gradle.org/distributions/gradle-$Version-bin.zip"

if (Test-Path (Join-Path $targetDir "bin\\gradle.bat")) {
    Write-Host "Gradle $Version already present."
    exit 0
}

New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null
Write-Host "Downloading Gradle $Version ..."
Invoke-WebRequest -Uri $downloadUrl -OutFile $zipPath
Write-Host "Extracting Gradle ..."
Expand-Archive -Path $zipPath -DestinationPath $toolsDir -Force
Write-Host "Gradle ready at $targetDir"
