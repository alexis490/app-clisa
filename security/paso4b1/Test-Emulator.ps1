param([Parameter(Mandatory=$true)][string]$FirebaseCli,[string]$JavaHome='C:\Program Files\Android\Android Studio1\jbr',[string]$TestCommand='npm.cmd test')
$ErrorActionPreference='Stop'
$saved=@{}
foreach($name in @('JAVA_HOME','PATH','GCLOUD_PROJECT','FIRESTORE_EMULATOR_HOST','FIREBASE_AUTH_EMULATOR_HOST')){$saved[$name]=[Environment]::GetEnvironmentVariable($name)}
Push-Location (Join-Path $PSScriptRoot '../../tools/inventory-sync')
try {
  $env:JAVA_HOME=$JavaHome; $env:PATH="$JavaHome\bin;$env:PATH"
  $env:GCLOUD_PROJECT='demo-clisa-sync-4b1';$env:FIRESTORE_EMULATOR_HOST='127.0.0.1:8086';$env:FIREBASE_AUTH_EMULATOR_HOST='127.0.0.1:9098'
  & node $FirebaseCli emulators:exec --only firestore,auth --project demo-clisa-sync-4b1 --config (Join-Path $PSScriptRoot 'firebase.json') $TestCommand
  if($LASTEXITCODE -ne 0){throw "Emulator tests failed ($LASTEXITCODE)"}
} finally {
  Pop-Location
  foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name])}
}
