param(
    [Parameter(Mandatory = $true)][string]$AppDir,
    [Parameter(Mandatory = $true)][string]$OutputDir,
    [Parameter(Mandatory = $true)][string]$AppVersion,
    [Parameter(Mandatory = $true)][string]$SetupIcon,
    [Parameter(Mandatory = $true)][string]$AppIcon,
    [Parameter(Mandatory = $true)][string]$SidebarPng
)

$ErrorActionPreference = "Stop"

function Find-Iscc {
    $candidates = @(
        "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
        "${env:ProgramFiles}\Inno Setup 6\ISCC.exe"
    )

    foreach ($path in $candidates) {
        if ($path -and (Test-Path -LiteralPath $path)) {
            return $path
        }
    }

    $cmd = Get-Command ISCC.exe -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }

    throw "ISCC.exe introuvable. Installe Inno Setup 6 puis relance."
}

$iscc = Find-Iscc
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$wizardImagePath = Join-Path $OutputDir "wizard-image.bmp"
$wizardSmallImagePath = Join-Path $OutputDir "wizard-small-image.bmp"
$smallLogoPath = Join-Path (Split-Path -Parent $SidebarPng) "nuvio-installer-small-logo.png"

Add-Type -AssemblyName System.Drawing
$source = [System.Drawing.Image]::FromFile($SidebarPng)
try {
    $wizardImage = New-Object System.Drawing.Bitmap 164,314,([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $g1 = [System.Drawing.Graphics]::FromImage($wizardImage)
    $g1.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g1.Clear([System.Drawing.Color]::White)
    $g1.DrawImage($source, 0, 0, 164, 314)
    $g1.Dispose()
    $wizardImage.Save($wizardImagePath, [System.Drawing.Imaging.ImageFormat]::Bmp)
    $wizardImage.Dispose()

    $small = New-Object System.Drawing.Bitmap 55,58,([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $g2 = [System.Drawing.Graphics]::FromImage($small)
    $g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g2.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g2.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g2.Clear([System.Drawing.Color]::White)

    if (Test-Path -LiteralPath $smallLogoPath) {
        $smallLogo = [System.Drawing.Image]::FromFile($smallLogoPath)
        try {
            $maxLogoSize = 40
            $scale = [Math]::Min($maxLogoSize / $smallLogo.Width, $maxLogoSize / $smallLogo.Height)
            $drawWidth = [int][Math]::Round($smallLogo.Width * $scale)
            $drawHeight = [int][Math]::Round($smallLogo.Height * $scale)
            $drawX = [int][Math]::Round((55 - $drawWidth) / 2)
            $drawY = [int][Math]::Round((58 - $drawHeight) / 2)
            $g2.DrawImage($smallLogo, $drawX, $drawY, $drawWidth, $drawHeight)
        } finally {
            $smallLogo.Dispose()
        }
    } else {
        $smallIcon = New-Object System.Drawing.Icon $AppIcon, 40, 40
        $smallBitmap = $smallIcon.ToBitmap()
        try {
            $g2.DrawImage($smallBitmap, 8, 9, 40, 40)
        } finally {
            $smallBitmap.Dispose()
            $smallIcon.Dispose()
        }
    }
    $g2.Dispose()
    $small.Save($wizardSmallImagePath, [System.Drawing.Imaging.ImageFormat]::Bmp)
    $small.Dispose()
} finally {
    $source.Dispose()
}

$issFile = Join-Path $OutputDir "nuvio-inno.iss"

$iss = @"
#define MyAppName "Nuvio"
#define MyAppVersion "$AppVersion"
#define MyAppPublisher "Creepso"

[Setup]
AppId={{7E14C1D3-BFA0-45B4-BD5E-0B3D8D6D3C11}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
OutputDir=$($OutputDir.Replace('\', '\\'))
OutputBaseFilename=Nuvio-$AppVersion-x64
Compression=lzma
SolidCompression=yes
WizardStyle=modern
SetupIconFile=$($SetupIcon.Replace('\', '\\'))
WizardImageFile=$($wizardImagePath.Replace('\', '\\'))
WizardSmallImageFile=$($wizardSmallImagePath.Replace('\', '\\'))
UninstallDisplayIcon={app}\Nuvio.exe

[Languages]
Name: "french"; MessagesFile: "compiler:Languages\French.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "$($AppDir.Replace('\', '\\'))\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

[Icons]
Name: "{group}\Nuvio"; Filename: "{app}\Nuvio.exe"; IconFilename: "$($AppIcon.Replace('\', '\\'))"
Name: "{autodesktop}\Nuvio"; Filename: "{app}\Nuvio.exe"; IconFilename: "$($AppIcon.Replace('\', '\\'))"; Tasks: desktopicon

[Run]
Filename: "{app}\Nuvio.exe"; Description: "{cm:LaunchProgram,Nuvio}"; Flags: nowait postinstall skipifsilent
"@

Set-Content -LiteralPath $issFile -Value $iss -Encoding UTF8

& $iscc $issFile
