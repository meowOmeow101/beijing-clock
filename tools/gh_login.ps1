# Start the GitHub device-code login and publish the code where the user can see it.
# gh is interactive, so its stdin is kept open and fed the code once known.
$ErrorActionPreference = 'Stop'
$gh = Join-Path $env:ProgramFiles 'GitHub CLI\gh.exe'
if (-not (Test-Path $gh)) { throw "gh not found: $gh" }

$codeFile = 'D:\My\文档\Workspace\BeijingClock\tools\github_login_code.txt'
$logFile = 'D:\My\文档\Workspace\BeijingClock\tools\github_login_log.txt'
Remove-Item $codeFile, $logFile -ErrorAction SilentlyContinue

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $gh
$psi.Arguments = 'auth login --hostname github.com --git-protocol https --web'
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true

$p = [System.Diagnostics.Process]::Start($psi)
$sb = New-Object System.Text.StringBuilder
$buf = New-Object char[] 512
$deadline = (Get-Date).AddMinutes(10)
$code = $null

while ((Get-Date) -lt $deadline -and -not $p.HasExited) {
    while ($p.StandardOutput.Peek() -ge 0) {
        $n = $p.StandardOutput.Read($buf, 0, $buf.Length)
        if ($n -gt 0) {
            $chunk = -join $buf[0..($n - 1)]
            [void]$sb.Append($chunk)
            Write-Host -NoNewline $chunk
        }
    }
    $text = $sb.ToString()
    if (-not $code) {
        $m = [regex]::Match($text, '(?m)^\s*([A-Z0-9]{4}-[A-Z0-9]{4})\s*$')
        if ($m.Success) {
            $code = $m.Groups[1].Value
            $body = @(
                "GitHub device code: $code",
                "",
                "Open: https://github.com/login/device",
                "and enter the code above, then authorize GitHub CLI.",
                "",
                "The code was also copied to your clipboard by gh."
            ) -join "`r`n"
            [System.IO.File]::WriteAllText($codeFile, $body)
            Write-Output ""
            Write-Output "=== 设备码: $code ==="
        }
    }
    Start-Sleep -Milliseconds 400
}

# gh waits for Enter, then asks whether to authenticate Git with this account.
if (-not $p.HasExited) {
    $p.StandardInput.Write("`n")
    Start-Sleep -Seconds 2
    $p.StandardInput.Write("y`n")
    $p.StandardInput.Flush()
    $p.WaitForExit(60000) | Out-Null
}
$tail = $p.StandardError.ReadToEnd()
if ($tail) { Write-Output $tail }
$sb.ToString() | Set-Content -Path $logFile -Encoding UTF8
Write-Output "=== gh exit code: $($p.ExitCode) ==="
