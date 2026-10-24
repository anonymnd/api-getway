param(
    [ValidateSet('start','stop','status','restart-gateway')][string]$Action='start',
    [ValidateSet(0,18180,9201,9202,9301,9302)][int]$Port=0,
    [ValidateSet('ROUND_ROBIN','LEAST_CONNECTIONS')][string]$Strategy='ROUND_ROBIN',
    [switch]$SkipBuild
)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$runtime=Join-Path $projectRoot '.runtime'
$jar=Join-Path $projectRoot 'target\api-gateway-1.0.0.jar'
New-Item -ItemType Directory -Force -Path $runtime | Out-Null

function New-Secret {
    $bytes=New-Object byte[] 32
    $rng=[Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes); return [Convert]::ToBase64String($bytes) } finally { $rng.Dispose() }
}
function Initialize-Secrets {
    $secretFile=Join-Path $runtime 'secrets.json'
    if (!(Test-Path -LiteralPath $secretFile)) {
        @{GW_JWT_SECRET=(New-Secret); GW_DB_PASSWORD=(New-Secret); GW_DEMO_PASSWORD=(New-Secret)} |
            ConvertTo-Json | Set-Content -LiteralPath $secretFile
    }
    $secrets=Get-Content -LiteralPath $secretFile -Raw | ConvertFrom-Json
    @("Username: learner", "Password: $($secrets.GW_DEMO_PASSWORD)", 'Dashboard: http://127.0.0.1:18180/') |
        Set-Content -LiteralPath (Join-Path $runtime 'demo-credentials.txt')
    # Keep signing keys, database and credentials readable only by this Windows user and SYSTEM.
    $acl=New-Object Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true,$false)
    $identity=[Security.Principal.WindowsIdentity]::GetCurrent().User
    foreach ($sid in @($identity, [Security.Principal.SecurityIdentifier]'S-1-5-18')) {
        $rule=New-Object Security.AccessControl.FileSystemAccessRule($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
        $acl.AddAccessRule($rule)
    }
    [IO.Directory]::SetAccessControl($runtime,$acl)
    return $secrets
}
function Get-OwnedProcess([int]$ServicePort) {
    $statePath=Join-Path $runtime "$ServicePort.json"
    if (!(Test-Path -LiteralPath $statePath)) { return $null }
    $state=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    $serviceProcess=Get-Process -Id $state.pid -ErrorAction SilentlyContinue
    if ($serviceProcess -and $serviceProcess.ProcessName -eq 'java' -and
        $serviceProcess.StartTime.ToUniversalTime().Ticks.ToString() -eq $state.started) { return $serviceProcess }
    return $null
}
function Stop-ServiceProcess([int]$ServicePort) {
    $owned=Get-OwnedProcess $ServicePort
    if ($owned) { Stop-Process -Id $owned.Id; $owned.WaitForExit(5000) | Out-Null; Write-Host "Stopped :$ServicePort (PID $($owned.Id))" }
    else { Write-Host ":$ServicePort has no running process owned by this launcher." }
    $statePath=Join-Path $runtime "$ServicePort.json"
    if (Test-Path -LiteralPath $statePath) { Remove-Item -LiteralPath $statePath }
}
function Start-ServiceProcess([int]$ServicePort) {
    $owned=Get-OwnedProcess $ServicePort
    if ($owned) { Write-Host ":$ServicePort already running (PID $($owned.Id))"; return }
    if (Get-NetTCPConnection -LocalPort $ServicePort -State Listen -ErrorAction SilentlyContinue) { throw "Port $ServicePort is occupied by an unrelated process. It was preserved." }
    $javaArgs=if ($ServicePort -eq 18180) { @('-jar',('"{0}"' -f $jar),"--gateway.strategy=$Strategy") }
        else { @(('"{0}"' -f (Join-Path $PSScriptRoot 'Service.java')), "$ServicePort") }
    $previousEnv=@{}
    try {
        if ($ServicePort -eq 18180) {
            $secrets=Initialize-Secrets
            foreach ($name in @('GW_JWT_SECRET','GW_DB_PASSWORD','GW_DEMO_PASSWORD')) {
                $previousEnv[$name]=[Environment]::GetEnvironmentVariable($name,'Process')
                [Environment]::SetEnvironmentVariable($name,$secrets.$name,'Process')
            }
        }
        $serviceProcess=Start-Process -FilePath (Get-Command java).Source -ArgumentList $javaArgs -WorkingDirectory $projectRoot `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime "$ServicePort.out.log") `
            -RedirectStandardError (Join-Path $runtime "$ServicePort.err.log")
    } finally { foreach ($name in $previousEnv.Keys) { [Environment]::SetEnvironmentVariable($name,$previousEnv[$name],'Process') } }
    @{pid=$serviceProcess.Id;started=$serviceProcess.StartTime.ToUniversalTime().Ticks.ToString()} |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtime "$ServicePort.json")
    $deadline=[DateTime]::UtcNow.AddSeconds(25)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($serviceProcess.HasExited) { throw "Service :$ServicePort exited. See .runtime/$ServicePort.out.log and .err.log" }
        if (Get-NetTCPConnection -LocalPort $ServicePort -State Listen -ErrorAction SilentlyContinue) { Write-Host "Running :$ServicePort (PID $($serviceProcess.Id))"; return }
        Start-Sleep -Milliseconds 200
    }
    throw "Service :$ServicePort did not become ready. Inspect .runtime logs."
}
$ports=if($Port) { @($Port) } else { @(9201,9202,9301,9302,18180) }
if($Action -eq 'status') { foreach($servicePort in $ports) { $owned=Get-OwnedProcess $servicePort; Write-Host (":{0} {1}" -f $servicePort,$(if($owned){"running (PID $($owned.Id))"}else{'stopped'})) }; exit 0 }
if($Action -eq 'stop') { foreach($servicePort in $ports) { Stop-ServiceProcess $servicePort }; exit 0 }
if($Action -eq 'restart-gateway') { Stop-ServiceProcess 18180; $ports=@(18180) }
if($ports -contains 18180 -and !$SkipBuild -and !(Get-OwnedProcess 18180)) {
    Push-Location $projectRoot
    try { & mvn -q '-DskipTests' package; if($LASTEXITCODE -ne 0) { throw 'Maven build failed' } } finally { Pop-Location }
}
if($ports -contains 18180 -and !(Test-Path -LiteralPath $jar)) { throw 'Build first with mvn verify.' }
foreach($servicePort in $ports) { Start-ServiceProcess $servicePort }
if($ports -contains 18180) { Write-Host 'Dashboard: http://127.0.0.1:18180/'; Write-Host 'Sign-in details: .runtime/demo-credentials.txt' }
