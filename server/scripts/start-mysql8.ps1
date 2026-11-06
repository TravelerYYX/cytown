param(
    [string]$ServiceName = 'MySQL80'
)
$ErrorActionPreference = 'Stop'
$service = Get-Service -Name $ServiceName -ErrorAction Stop
if ($service.Status -ne 'Running') {
    Start-Service -Name $ServiceName
    $service.WaitForStatus('Running', [TimeSpan]::FromSeconds(15))
}
$listener = Get-NetTCPConnection -LocalPort 3306 -State Listen -ErrorAction SilentlyContinue
if (-not $listener) {
    throw "Service $ServiceName is running, but MySQL is not listening on port 3306."
}
Write-Output "System MySQL 8 is running on 127.0.0.1:3306. Service=$ServiceName"
