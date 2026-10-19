# PowerShell 原生静态服务器
param(
    [string]$Root = "d:\workspace\ShopServer\src\main\resources",
    [int]$Port = 8081
)

$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add("http://localhost:$Port/")
$listener.Start()
Write-Host "服务器启动: http://localhost:$Port/"
Write-Host "根目录: $Root"

$mime = @{
    ".html" = "text/html; charset=utf-8"
    ".css"  = "text/css; charset=utf-8"
    ".js"   = "application/javascript; charset=utf-8"
    ".jpg"  = "image/jpeg"
    ".jpeg" = "image/jpeg"
    ".png"  = "image/png"
    ".svg"  = "image/svg+xml"
    ".gif"  = "image/gif"
    ".ico"  = "image/x-icon"
}

while ($listener.IsListening) {
    try {
        $ctx = $listener.GetContext()
        $req = $ctx.Request
        $resp = $ctx.Response
        
        # URL 解码
        $urlPath = [System.Uri]::UnescapeDataString($req.Url.AbsolutePath.TrimStart('/'))
        
        # 根路径重定向
        if ($urlPath -eq "" -or $urlPath -eq "app" -or $urlPath -eq "app/") {
            $urlPath = "app/index.html"
        }
        if ($urlPath -eq "admin" -or $urlPath -eq "admin/") {
            $urlPath = "admin/index.html"
        }
        
        $filePath = Join-Path $Root $urlPath
        
        if (Test-Path $filePath -PathType Leaf) {
            $ext = [System.IO.Path]::GetExtension($filePath).ToLower()
            $resp.ContentType = if ($mime.ContainsKey($ext)) { $mime[$ext] } else { "application/octet-stream" }
            $bytes = [System.IO.File]::ReadAllBytes($filePath)
            $resp.ContentLength64 = $bytes.Length
            $resp.OutputStream.Write($bytes, 0, $bytes.Length)
        } else {
            $resp.StatusCode = 404
            $bytes = [System.Text.Encoding]::UTF8.GetBytes("404 Not Found: $urlPath")
            $resp.OutputStream.Write($bytes, 0, $bytes.Length)
        }
        $resp.Close()
    } catch {
        Write-Host "Error: $_"
    }
}
