param(
    [ValidateSet('run','test','package','compile')][string]$Action = 'run',
    [string]$CredentialsPath = 'D:/ciyuantown-tools/credentials.xml',
    [string]$JavaHome = 'D:/JDK/jdk21',
    [string]$Maven = 'D:/apache-maven-3.9.16/bin/mvn.cmd'
)
$ErrorActionPreference = 'Stop'
# 凭据为当前 Windows 账户 DPAPI 加密文件，位于仓库外。
$settings = Import-Clixml -LiteralPath $CredentialsPath
$env:DB_USERNAME = 'ciyuantown_app'
$env:DB_PASSWORD = [Net.NetworkCredential]::new('', $settings.AppPassword).Password
$env:JWT_SECRET = [Net.NetworkCredential]::new('', $settings.JwtSecret).Password
# application.yml 会读取项目 .env；不要用旧的 DPAPI 邮件凭据覆盖它。
# 优先保留调用方环境变量，其次使用 .env，仅在两处均未配置时回退历史凭据。
$localEnvPath = Join-Path $PSScriptRoot '../.env'
$localEnvLines = if (Test-Path -LiteralPath $localEnvPath) { Get-Content -LiteralPath $localEnvPath } else { @() }
if (-not (Test-Path Env:SMTP_USERNAME) -and -not ($localEnvLines -match '^\s*SMTP_USERNAME\s*=')) {
    $env:SMTP_USERNAME = [string]$settings.SmtpUsername
}
if (-not (Test-Path Env:SMTP_PASSWORD) -and -not ($localEnvLines -match '^\s*SMTP_PASSWORD\s*=')) {
    $env:SMTP_PASSWORD = [Net.NetworkCredential]::new('', $settings.SmtpPassword).Password
}
$env:JAVA_HOME = $JavaHome
Push-Location (Join-Path $PSScriptRoot '..')
try {
    if ($Action -eq 'run') { & $Maven spring-boot:run }
    else { & $Maven $Action }
    if ($LASTEXITCODE -ne 0) { throw "Maven 执行失败：$Action" }
} finally { Pop-Location }
