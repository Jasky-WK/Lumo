param(
    [string]$OutDir = (Join-Path $PSScriptRoot ".")
)

Add-Type -AssemblyName System.Drawing

# App 图标：品牌蓝渐变圆角底 + 白色 ¥（与 res/drawable 下的矢量图标同一份设计）
# 设计坐标系 108x108，按目标尺寸等比放大

function New-AppIconPng {
    param([int]$Size, [string]$OutFile)

    $s = [double]$Size / 108.0
    $bmp = New-Object System.Drawing.Bitmap($Size, $Size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.Clear([System.Drawing.Color]::Transparent)

    $r = [float](26.0 * $s)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddArc(0, 0, $r * 2, $r * 2, 180, 90)
    $path.AddArc($Size - $r * 2, 0, $r * 2, $r * 2, 270, 90)
    $path.AddArc($Size - $r * 2, $Size - $r * 2, $r * 2, $r * 2, 0, 90)
    $path.AddArc(0, $Size - $r * 2, $r * 2, $r * 2, 90, 90)
    $path.CloseFigure()

    $rect = New-Object System.Drawing.Rectangle(0, 0, $Size, $Size)
    $c1 = [System.Drawing.Color]::FromArgb(255, 108, 153, 242)
    $c2 = [System.Drawing.Color]::FromArgb(255, 58, 100, 214)
    $mode = [System.Drawing.Drawing2D.LinearGradientMode]::ForwardDiagonal
    $grad = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, $c1, $c2, $mode)
    $g.FillPath($grad, $path)

    # 左上柔光（裁切在圆角内）
    $g.SetClip($path)
    $hl = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(22, 255, 255, 255))
    $g.FillEllipse($hl, ([float](-100 * $s)), ([float](-140 * $s)), ([float](330 * $s)), ([float](330 * $s)))
    $g.ResetClip()

    # 白色 ¥
    $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::White, ([float](7.6 * $s)))
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round

    function P([double]$x, [double]$y) {
        New-Object System.Drawing.PointF(([float]($x * $s)), ([float]($y * $s)))
    }
    $g.DrawLine($pen, (P 54 28), (P 39 50))
    $g.DrawLine($pen, (P 54 28), (P 69 50))
    $g.DrawLine($pen, (P 54 46), (P 54 80))
    $g.DrawLine($pen, (P 40 58), (P 68 58))
    $g.DrawLine($pen, (P 40 70), (P 68 70))

    $pen.Dispose(); $hl.Dispose(); $grad.Dispose(); $path.Dispose(); $g.Dispose()
    $bmp.Save($OutFile, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Output ("OK  " + (Split-Path $OutFile -Leaf))
}

$outDir = $OutDir
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

New-AppIconPng -Size 512 -OutFile (Join-Path $outDir "app_icon_512.png")
New-AppIconPng -Size 192 -OutFile (Join-Path $outDir "app_icon_192_xxxhdpi.png")
New-AppIconPng -Size 144 -OutFile (Join-Path $outDir "app_icon_144_xxhdpi.png")
New-AppIconPng -Size 96  -OutFile (Join-Path $outDir "app_icon_96_xhdpi.png")
New-AppIconPng -Size 72  -OutFile (Join-Path $outDir "app_icon_72_hdpi.png")
New-AppIconPng -Size 48  -OutFile (Join-Path $outDir "app_icon_48_mdpi.png")

Get-ChildItem $outDir -Filter *.png | Select-Object Name, Length
