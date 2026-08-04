<#
.SYNOPSIS
    Captures the real Crane Remote Control window to a PNG.

.DESCRIPTION
    Rendering bugs that only exist on screen â€” stale pixels, tearing, driver
    quirks â€” are invisible to the app's own snapshot probe, because
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
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(
      IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr param);
  public delegate bool EnumProc(IntPtr h, IntPtr param);
  public struct RECT { public int Left, Top, Right, Bottom; }
}
"@

# MainWindowHandle can point at a stub window (JavaFX creates helper windows), so
# take the largest visible top-level window belonging to the process instead.
$handle = $process.MainWindowHandle
$best = 0
$callback = [CraneCapture+EnumProc] {
    param($h, $param)
    $windowPid = 0
    [CraneCapture]::GetWindowThreadProcessId($h, [ref]$windowPid) | Out-Null
    if ($windowPid -eq $process.Id -and [CraneCapture]::IsWindowVisible($h)) {
        $r = New-Object CraneCapture+RECT
        [CraneCapture]::GetWindowRect($h, [ref]$r) | Out-Null
        $area = ($r.Right - $r.Left) * ($r.Bottom - $r.Top)
        if ($area -gt $script:best) {
            $script:best = $area
            $script:handle = $h
        }
    }
    return $true
}
[CraneCapture]::EnumWindows($callback, [IntPtr]::Zero) | Out-Null

# Always restore: a minimised window is parked off-screen at about 159x27, which
# is the stub rectangle that silently produces a useless capture.
[CraneCapture]::ShowWindow($handle, 9) | Out-Null   # SW_RESTORE
Start-Sleep -Seconds 1

$HWND_TOPMOST = [IntPtr]::new(-1)
$HWND_NOTOPMOST = [IntPtr]::new(-2)
$SWP_NOMOVE_NOSIZE = 0x0003

# Raise it: SetForegroundWindow is refused to background callers, but topmost works.
[CraneCapture]::SetWindowPos($handle, $HWND_TOPMOST,
    0, 0, 0, 0, $SWP_NOMOVE_NOSIZE) | Out-Null
Start-Sleep -Seconds $DelaySeconds

$rect = New-Object CraneCapture+RECT
[CraneCapture]::GetWindowRect($handle, [ref]$rect) | Out-Null

Add-Type -AssemblyName System.Drawing
$width = $rect.Right - $rect.Left
$height = $rect.Bottom - $rect.Top
if ($width -le 0 -or $height -le 0) {
    Write-Error "Window has no size - it probably closed during the delay. Lower -DelaySeconds."
    exit 1
}
$bitmap = New-Object System.Drawing.Bitmap $width, $height
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, $bitmap.Size)
$bitmap.Save($Out)
$graphics.Dispose()
$bitmap.Dispose()

[CraneCapture]::SetWindowPos($handle, $HWND_NOTOPMOST,
    0, 0, 0, 0, $SWP_NOMOVE_NOSIZE) | Out-Null

Write-Host "Captured ${width}x${height} to $Out"
