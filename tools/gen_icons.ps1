# 生成 launcher 图标 PNG（API < 26 使用），不依赖任何第三方库。
# PNG 采用手工拼装：IHDR + IDAT(未压缩 deflate 存储块) + IEND，CRC32 自行计算。
Add-Type -AssemblyName System.Drawing

function Get-Crc32Table {
    $table = New-Object 'uint32[]' 256
    for ($n = 0; $n -lt 256; $n++) {
        $c = [uint32]$n
        for ($k = 0; $k -lt 8; $k++) {
            if (($c -band 1) -ne 0) { $c = [uint32](0xEDB88320 -bxor ($c -shr 1)) }
            else { $c = [uint32]($c -shr 1) }
        }
        $table[$n] = $c
    }
    return $table
}
$script:CrcTable = Get-Crc32Table

function Get-Crc32([byte[]]$bytes) {
    $c = [uint32]0xFFFFFFFF
    foreach ($b in $bytes) {
        $c = [uint32]($script:CrcTable[($c -bxor $b) -band 0xFF] -bxor ($c -shr 8))
    }
    return [uint32]($c -bxor 0xFFFFFFFF)
}

function Get-Adler32([byte[]]$bytes) {
    $a = [uint32]1
    $b = [uint32]0
    foreach ($x in $bytes) {
        $a = [uint32](($a + $x) % 65521)
        $b = [uint32](($b + $a) % 65521)
    }
    return [uint32](($b -shl 16) -bor $a)
}

function Write-Png([System.Drawing.Bitmap]$bmp, [string]$path) {
    $w = $bmp.Width
    $h = $bmp.Height
    $rect = New-Object System.Drawing.Rectangle 0, 0, $w, $h
    $data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $stride = $data.Stride
    $raw = New-Object byte[] ($h * (1 + $w * 4))
    $row = New-Object byte[] ($w * 4)
    for ($y = 0; $y -lt $h; $y++) {
        [System.Runtime.InteropServices.Marshal]::Copy([IntPtr]::Add($data.Scan0, $y * $stride), $row, 0, $row.Length)
        $raw[$y * (1 + $w * 4)] = 0
        [Array]::Copy($row, 0, $raw, $y * (1 + $w * 4) + 1, $row.Length)
    }
    $bmp.UnlockBits($data)

    # zlib 流：0x78 0x9C + 若干未压缩 deflate 存储块 + adler32
    $ms = New-Object System.IO.MemoryStream
    $ms.WriteByte(0x78)
    $ms.WriteByte(0x9C)
    $chunkSize = 32768
    $pos = 0
    while ($pos -lt $raw.Length) {
        $len = [Math]::Min($chunkSize, $raw.Length - $pos)
        $final = if (($pos + $len) -ge $raw.Length) { 1 } else { 0 }
        $ms.WriteByte([byte]$final)
        $lenLo = [byte]($len -band 0xFF)
        $lenHi = [byte](($len -shr 8) -band 0xFF)
        $ms.WriteByte($lenLo); $ms.WriteByte($lenHi)
        $ms.WriteByte([byte](0xFF - $lenLo)); $ms.WriteByte([byte](0xFF - $lenHi))
        $ms.Write($raw, $pos, $len)
        $pos += $len
    }
    $adler = Get-Adler32 $raw
    $ms.WriteByte([byte](($adler -shr 24) -band 0xFF))
    $ms.WriteByte([byte](($adler -shr 16) -band 0xFF))
    $ms.WriteByte([byte](($adler -shr 8) -band 0xFF))
    $ms.WriteByte([byte]($adler -band 0xFF))
    $z = $ms.ToArray()
    $ms.Dispose()

    $fs = [System.IO.File]::Create($path)
    $fs.Write([byte[]](0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), 0, 8)

    $ihdr = New-Object System.IO.MemoryStream
    $bw = [System.BitConverter]::GetBytes([int]$w); [Array]::Reverse($bw); $ihdr.Write($bw, 0, 4)
    $bh = [System.BitConverter]::GetBytes([int]$h); [Array]::Reverse($bh); $ihdr.Write($bh, 0, 4)
    $ihdr.Write([byte[]](8, 6, 0, 0, 0), 0, 5)
    $ihdrBytes = $ihdr.ToArray(); $ihdr.Dispose()

    foreach ($pair in @(@('IHDR', $ihdrBytes), @('IDAT', $z))) {
        $type = [System.Text.Encoding]::ASCII.GetBytes($pair[0])
        $body = $pair[1]
        $lenBytes = [System.BitConverter]::GetBytes([int]$body.Length); [Array]::Reverse($lenBytes)
        $fs.Write($lenBytes, 0, 4)
        $fs.Write($type, 0, 4)
        $fs.Write($body, 0, $body.Length)
        $crcInput = New-Object byte[] (4 + $body.Length)
        [Array]::Copy($type, 0, $crcInput, 0, 4)
        [Array]::Copy($body, 0, $crcInput, 4, $body.Length)
        $crc = Get-Crc32 $crcInput
        $crcBytes = [System.BitConverter]::GetBytes([uint32]$crc); [Array]::Reverse($crcBytes)
        $fs.Write($crcBytes, 0, 4)
    }
    $fs.Write([byte[]](0, 0, 0, 0), 0, 4)
    $iend = [System.Text.Encoding]::ASCII.GetBytes('IEND')
    $fs.Write($iend, 0, 4)
    $crc = Get-Crc32 $iend
    $crcBytes = [System.BitConverter]::GetBytes([uint32]$crc); [Array]::Reverse($crcBytes)
    $fs.Write($crcBytes, 0, 4)
    $fs.Close()
}

function New-ClockBitmap([int]$size, [bool]$round, [bool]$squareBg) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.Clear([System.Drawing.Color]::Transparent)

    $s = [double]$size
    $cx = $s / 2.0
    $cy = $s / 2.0

    if ($squareBg) {
        $r = [double]($s * 0.20)
        $path = New-Object System.Drawing.Drawing2D.GraphicsPath
        $path.AddArc(0, 0, $r * 2, $r * 2, 180, 90)
        $path.AddArc($s - $r * 2, 0, $r * 2, $r * 2, 270, 90)
        $path.AddArc($s - $r * 2, $s - $r * 2, $r * 2, $r * 2, 0, 90)
        $path.AddArc(0, $s - $r * 2, $r * 2, $r * 2, 90, 90)
        $path.CloseFigure()
        $bgBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
            (New-Object System.Drawing.Point 0, 0),
            (New-Object System.Drawing.Point 0, $size),
            [System.Drawing.Color]::FromArgb(255, 21, 92, 160),
            [System.Drawing.Color]::FromArgb(255, 8, 44, 88))
        $g.FillPath($bgBrush, $path)
        $bgBrush.Dispose()
        $path.Dispose()
    }

    # 表盘半径
    $d = [double]($s * $(if ($squareBg) { 0.60 } else { 0.94 }))
    $x0 = $cx - $d / 2.0
    $y0 = $cy - $d / 2.0
    $ringWidth = [double]($d * 0.075)
    $white = [System.Drawing.Color]::White
    $ringPen = New-Object System.Drawing.Pen $white, ([float]$ringWidth)
    $g.DrawEllipse($ringPen, [float]$x0, [float]$y0, [float]$d, [float]$d)
    $ringPen.Dispose()

    # 刻度
    $tickBrush = New-Object System.Drawing.SolidBrush $white
    $tickW = [double]($d * 0.055)
    $tickLen = [double]($d * 0.14)
    $r2 = $d / 2.0
    for ($i = 0; $i -lt 12; $i++) {
        $angle = $i * 30.0 * [Math]::PI / 180.0
        $inner = $r2 - $ringWidth * 1.35
        $outer = $inner - $tickLen
        $sx = $cx + [Math]::Sin($angle) * $outer
        $sy = $cy - [Math]::Cos($angle) * $outer
        $ex = $cx + [Math]::Sin($angle) * $inner
        $ey = $cy - [Math]::Cos($angle) * $inner
        $pen = New-Object System.Drawing.Pen $white, ([float]$tickW)
        $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
        $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
        $g.DrawLine($pen, [float]$sx, [float]$sy, [float]$ex, [float]$ey)
        $pen.Dispose()
    }

    # 指针：时针指向 10 点，分针指向 2 点
    $handBrush = New-Object System.Drawing.SolidBrush $white
    $hourLen = [double]($r2 * 0.46)
    $hourW = [double]($d * 0.085)
    $hourAngle = ((10.0 + 10.0 / 60.0) / 12.0) * 2.0 * [Math]::PI
    $hx = $cx + [Math]::Sin($hourAngle) * $hourLen
    $hy = $cy - [Math]::Cos($hourAngle) * $hourLen
    $hourPen = New-Object System.Drawing.Pen $white, ([float]$hourW)
    $hourPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $hourPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $g.DrawLine($hourPen, [float]$cx, [float]$cy, [float]$hx, [float]$hy)
    $hourPen.Dispose()

    $minLen = [double]($r2 * 0.68)
    $minW = [double]($d * 0.062)
    $minAngle = ((10.0 / 60.0) * 2.0 * [Math]::PI)
    $mx = $cx + [Math]::Sin($minAngle) * $minLen
    $my = $cy - [Math]::Cos($minAngle) * $minLen
    $minPen = New-Object System.Drawing.Pen $white, ([float]$minW)
    $minPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $minPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $g.DrawLine($minPen, [float]$cx, [float]$cy, [float]$mx, [float]$my)
    $minPen.Dispose()

    # 中心圆点
    $dotR = [double]($d * 0.055)
    $g.FillEllipse($handBrush, [float]($cx - $dotR), [float]($cy - $dotR), [float]($dotR * 2), [float]($dotR * 2))
    $handBrush.Dispose()
    $tickBrush.Dispose()

    $g.Dispose()
    return $bmp
}

$res = 'D:\My\文档\Workspace\BeijingClock\app\src\main\res'
$densities = @{ 'mipmap-mdpi' = 48; 'mipmap-hdpi' = 72; 'mipmap-xhdpi' = 96; 'mipmap-xxhdpi' = 144; 'mipmap-xxxhdpi' = 192 }

foreach ($k in $densities.Keys) {
    $size = $densities[$k]
    $dir = Join-Path $res $k
    $b1 = New-ClockBitmap $size $false $true
    Write-Png $b1 (Join-Path $dir 'ic_launcher.png')
    $b1.Dispose()
    $b2 = New-ClockBitmap $size $true $false
    Write-Png $b2 (Join-Path $dir 'ic_launcher_round.png')
    $b2.Dispose()
    Write-Output "$k -> $size px done"
}

# 验证：重新加载生成的 PNG
foreach ($k in $densities.Keys) {
    $p = Join-Path (Join-Path $res $k) 'ic_launcher.png'
    $img = [System.Drawing.Image]::FromFile($p)
    Write-Output ("verify {0}: {1}x{2}, {3} bytes" -f $k, $img.Width, $img.Height, (Get-Item $p).Length)
    $img.Dispose()
}
