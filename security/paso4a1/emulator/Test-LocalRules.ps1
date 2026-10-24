param([string]$FirebaseCli = 'firebase', [string]$JavaHome = $env:JAVA_HOME,
    [ValidateSet('npm.cmd test','npm.cmd run test:4a2')][string]$TestCommand = 'npm.cmd test')
$ErrorActionPreference = 'Stop'
$previousJava = $env:JAVA_HOME
$previousPath = $env:PATH
if ($JavaHome) {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = "$JavaHome\bin;$env:PATH"
}
$previousProject = $env:GCLOUD_PROJECT
$previousHost = $env:FIRESTORE_EMULATOR_HOST
Push-Location $PSScriptRoot
try {
    $env:GCLOUD_PROJECT = 'demo-clisa-4a1'
    $env:FIRESTORE_EMULATOR_HOST = '127.0.0.1:8087'
    $arguments = @('emulators:exec', '--only', 'firestore', '--project', 'demo-clisa-4a1', '--config', '../firebase.json', $TestCommand)
    if ($FirebaseCli.EndsWith('.js')) { & node $FirebaseCli @arguments }
    else { & $FirebaseCli @arguments }
    if ($LASTEXITCODE -ne 0) { throw "Local Emulator tests failed ($LASTEXITCODE)." }
} finally {
    $env:GCLOUD_PROJECT = $previousProject
    $env:FIRESTORE_EMULATOR_HOST = $previousHost
    $env:JAVA_HOME = $previousJava
    $env:PATH = $previousPath
    Pop-Location
}
