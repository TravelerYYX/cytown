param(
 [Parameter(Mandatory=$true)][ValidateSet('prepare','apply','migrate')][string]$Action,
 [string]$Path,
 [string]$CredentialsPath='D:/ciyuantown-tools/credentials.xml',
 [string]$Python='D:/anaconda3/python.exe',
 [string]$MySQL='C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe',
 [string]$MySQLDump='C:/Program Files/MySQL/MySQL Server 8.0/bin/mysqldump.exe'
)
$ErrorActionPreference='Stop'
$mediaSettings=Import-Clixml -LiteralPath $CredentialsPath
$env:DB_USERNAME=if($Action -eq 'migrate'){'root'}else{'ciyuantown_app'}
$mediaPassword=if($Action -eq 'migrate'){$mediaSettings.RootPassword}else{$mediaSettings.AppPassword}
$env:DB_PASSWORD=[Net.NetworkCredential]::new('', $mediaPassword).Password
$env:JWT_SECRET=[Net.NetworkCredential]::new('', $mediaSettings.JwtSecret).Password
Push-Location (Join-Path $PSScriptRoot '..')
try {
 if($Action -eq 'migrate') {
  $env:MEDIA_MIGRATION_CLIENT=$MySQL
  @'
import argparse,importlib.util,os,subprocess
from pathlib import Path
spec=importlib.util.spec_from_file_location('history',Path('scripts/cleanup-history.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
try:
 client=module.Cleanup(argparse.Namespace(mysql=os.environ['MEDIA_MIGRATION_CLIENT'],mysqldump=None))
 client.sql(Path('doc/sql/p12-media-storage.sql').read_text(encoding='utf-8'))
 print('P12 media migration complete.')
except subprocess.CalledProcessError as error:
 print(error.stderr.strip());raise SystemExit(1)
'@ | & $Python -
 } else {
  if(!$Path){throw 'Specify a new backup directory for prepare or manifest.json for apply'}
  & $Python scripts/cleanup-history.py $Action $Path --mysql $MySQL --mysqldump $MySQLDump
 }
 if($LASTEXITCODE -ne 0){throw "Media maintenance failed: $Action"}
} finally {Pop-Location}
