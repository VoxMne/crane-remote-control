<#
.SYNOPSIS
    Captures the real Crane Remote Control window to a PNG.

.DESCRIPTION
    Rendering bugs that only exist on screen — stale pixels, tearing, driver
    quirks — are invisible to the app's own snapshot probe, because
    Scene.snapshot() renders a fresh image offscreen. This script photographs
    the actual window instead, which is how the v2.0.3 frame-accumulation bug
    was finally caught.

    Start the app first, ideally with the stress probe driving it:
        .\gradlew.bat :crane-ui:run "-Dcrane.devStress=true"

.EXAMPLE
    .\scripts\capture-window.ps1 -Out shot.png -DelaySeconds 8
#>
param(
    [string] $Out = "window.png",
    [int] $DelaySeconds = 5,
    [int] $TimeoutSeconds = 60
)

$process = $null
for ($i = 0; $i -lt $TimeoutSeconds -and -not $process; $i++) {
    Start-Sleep -Seconds 1
    $process = Get-Process |
        Where-Object { $_.MainWindowTitle -like "Crane Remote Control*" } |
        Select-Object -First 1
}
if (-not $process) {
    Write-Error "Crane Remote Control window not found - is the app running?"
    exit 1
}
Write-Host "Found window: $($process.MainWindowTitle)"

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class CraneCapture {
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(
      IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
  public struct RECT { public int Left, Top, Right, Bottom; }
}
"@

$HWND_TOPMOST = [IntPtr]::new(-1)
$HWND_NOTOPMOST = [IntPtr]::new(-2)
$SWP_NOMOVE_NOSIZE = 0x0003

# Raise it: SetForegroundWindow is refused to background callers, but topmost works.
[CraneCapture]::SetWindowPos($process.MainWindowHandle, $HWND_TOPMOST,
    0, 0, 0, 0, $SWP_NOMOVE_NOSIZE) | Out-Null
Start-Sleep -Seconds $DelaySeconds

$rect = New-Object CraneCapture+RECT
[CraneCapture]::GetWindowRect($process.MainWindowHandle, [ref]$rect) | Out-Null

Add-Type -AssemblyName System.Drawing
$width = $rect.Right - $rect.Left
$height = $rect.Bottom - $rect.Top
$bitmap = New-Object System.Drawing.Bitmap $width, $height
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, $bitmap.Size)
$bitmap.Save($Out)
$graphics.Dispose()
$bitmap.Dispose()

[CraneCapture]::SetWindowPos($process.MainWindowHandle, $HWND_NOTOPMOST,
    0, 0, 0, 0, $SWP_NOMOVE_NOSIZE) | Out-Null

Write-Host "Captured ${width}x${height} to $Out"
