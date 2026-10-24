param([string]$FirebaseCli = 'firebase.cmd', [string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$oldJava = $env:JAVA_HOME
$oldPath = $env:PATH
$oldProject = $env:GCLOUD_PROJECT
$oldHost = $env:FIRESTORE_EMULATOR_HOST
Push-Location (Join-Path $PSScriptRoot '../paso4a1/emulator')
try {
    if ($JavaHome) { $env:JAVA_HOME = $JavaHome; $env:PATH = "$JavaHome\bin;$env:PATH" }
    $env:GCLOUD_PROJECT = 'demo-clisa-formalizacion'
    $env:FIRESTORE_EMULATOR_HOST = '127.0.0.1:8088'
    $arguments = @('emulators:exec','--only','firestore','--project','demo-clisa-formalizacion','--config',
        (Join-Path $PSScriptRoot 'firebase.json'),'npm.cmd run test:formalizacion')
    if ($FirebaseCli.EndsWith('.js')) { & node $FirebaseCli @arguments } else { & $FirebaseCli @arguments }
    if ($LASTEXITCODE -ne 0) { throw "Local formalization tests failed ($LASTEXITCODE)." }
} finally {
    $env:JAVA_HOME = $oldJava; $env:PATH = $oldPath; $env:GCLOUD_PROJECT = $oldProject; $env:FIRESTORE_EMULATOR_HOST = $oldHost
    Pop-Location
}
