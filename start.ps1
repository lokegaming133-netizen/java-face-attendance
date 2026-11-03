$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$secret = Read-Host "Set teacher password for this run (12+ characters)" -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
try { $env:ADMIN_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
if ($env:ADMIN_PASSWORD.Length -lt 12) { throw "Password must contain at least 12 characters" }
try { java -jar target/attendance-1.0.0.jar } finally { Remove-Item Env:ADMIN_PASSWORD -ErrorAction SilentlyContinue }
