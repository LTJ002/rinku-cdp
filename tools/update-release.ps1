# Update a GitHub release body with guaranteed UTF-8 encoding.
#
# PowerShell's Invoke-WebRequest re-encodes string bodies using the console code
# page, which turned every CJK character into '?'. HttpWebRequest with an
# explicit UTF-8 byte array avoids that.

param(
    [Parameter(Mandatory=$true)][string]$Token,
    [Parameter(Mandatory=$true)][string]$Owner,
    [Parameter(Mandatory=$true)][string]$Repo,
    [Parameter(Mandatory=$true)][long]$ReleaseId,
    [Parameter(Mandatory=$true)][string]$BodyFile,
    [Parameter(Mandatory=$true)][string]$Name,
    [string]$Proxy = 'http://127.0.0.1:7897'
)

$body = [System.IO.File]::ReadAllText($BodyFile, [System.Text.Encoding]::UTF8)
Write-Output ("body chars: " + $body.Length)
Write-Output ("has CJK: " + ($body -match '[\u4e00-\u9fff]'))

# Hand-roll the JSON so nothing re-encodes the text.
function Esc([string]$s) {
    $sb = New-Object System.Text.StringBuilder
    foreach ($ch in $s.ToCharArray()) {
        switch ($ch) {
            '"'  { [void]$sb.Append('\"') }
            '\'  { [void]$sb.Append('\\') }
            "`n" { [void]$sb.Append('\n') }
            "`r" { [void]$sb.Append('\r') }
            "`t" { [void]$sb.Append('\t') }
            default {
                if ([int]$ch -lt 32) { [void]$sb.Append(('\u{0:x4}' -f [int]$ch)) }
                else { [void]$sb.Append($ch) }
            }
        }
    }
    return $sb.ToString()
}

$json = '{"name":"' + (Esc $Name) + '","body":"' + (Esc $body) + '"}'
$bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
Write-Output ("json bytes: " + $bytes.Length)

$url = "https://api.github.com/repos/$Owner/$Repo/releases/$ReleaseId"
$req = [System.Net.HttpWebRequest]::Create($url)
$req.Method = 'PATCH'
$req.Proxy = New-Object System.Net.WebProxy($Proxy)
$req.UserAgent = 'dsh'
$req.Accept = 'application/vnd.github+json'
$req.Headers.Add('Authorization', "token $Token")
$req.ContentType = 'application/json; charset=utf-8'
$req.ContentLength = $bytes.Length

$stream = $req.GetRequestStream()
$stream.Write($bytes, 0, $bytes.Length)
$stream.Close()

try {
    $resp = $req.GetResponse()
    $reader = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
    $text = $reader.ReadToEnd()
    $reader.Close()
    $resp.Close()
    $obj = $text | ConvertFrom-Json
    Write-Output 'HTTP 200'
    Write-Output ("html_url: " + $obj.html_url)
    Write-Output ("server body chars: " + $obj.body.Length)
    Write-Output ("CJK survived: " + ($obj.body -match '[\u4e00-\u9fff]'))
    Write-Output '--- first 300 chars ---'
    Write-Output $obj.body.Substring(0, [Math]::Min(300, $obj.body.Length))
} catch [System.Net.WebException] {
    $err = $_.Exception.Response
    if ($err) {
        $reader = New-Object System.IO.StreamReader($err.GetResponseStream(), [System.Text.Encoding]::UTF8)
        Write-Output ("HTTP " + [int]$err.StatusCode)
        Write-Output $reader.ReadToEnd()
        $reader.Close()
    } else {
        Write-Output $_.Exception.Message
    }
}
