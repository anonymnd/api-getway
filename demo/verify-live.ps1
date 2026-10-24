# Runs real requests and briefly stops replica 9202, restoring it even on failure.
param([string]$BaseUrl='http://127.0.0.1:18180')
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Net.Http
$projectRoot=Split-Path $PSScriptRoot -Parent
$secrets=Get-Content -LiteralPath (Join-Path $projectRoot '.runtime\secrets.json') -Raw | ConvertFrom-Json
$login=Invoke-RestMethod "$BaseUrl/auth/login" -Method Post -ContentType 'application/json' `
    -Body (@{username='learner';password=$secrets.GW_DEMO_PASSWORD}|ConvertTo-Json)
$handler=New-Object System.Net.Http.HttpClientHandler
$handler.UseCookies=$false
$client=New-Object System.Net.Http.HttpClient($handler)
$client.Timeout=[TimeSpan]::FromSeconds(8)
$results=New-Object Collections.Generic.List[string]
function Send-Request([string]$Path,[string]$Method='GET',[bool]$Authenticated=$true) {
    $request=New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::new($Method),"$BaseUrl$Path")
    if($Authenticated){$request.Headers.Authorization=New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer',$login.accessToken)}
    if($Method -ne 'GET'){$request.Content=New-Object System.Net.Http.StringContent('{"item":"verification"}',[Text.Encoding]::UTF8,'application/json')}
    try{
        $response=$client.SendAsync($request).GetAwaiter().GetResult()
        try{
            $headers=@{};foreach($entry in $response.Headers){$headers[$entry.Key]=$entry.Value -join ', '}
            return [pscustomobject]@{status=[int]$response.StatusCode;body=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult();headers=$headers}
        }finally{$response.Dispose()}
    }finally{$request.Dispose()}
}
function Assert([bool]$Condition,[string]$Name){if(!$Condition){throw "FAIL: $Name"};$results.Add($Name);Write-Host "PASS: $Name"}
function Snapshot { return (Send-Request '/_gateway/status').body | ConvertFrom-Json }
try {
    Assert ((Send-Request '/api/catalog/items' 'GET' $false).status -eq 401) 'missing JWT is rejected'
    $path='/api/catalog/items?verification='+[Guid]::NewGuid().ToString('N')
    $first=Send-Request $path;$hit=Send-Request $path
    Assert ($first.headers['X-Gateway-Outcome'] -eq 'CACHE_MISS' -and $hit.headers['X-Gateway-Outcome'] -eq 'CACHE_HIT' -and $hit.headers['X-Gateway-Attempts'] -eq '0' -and $first.body -eq $hit.body) 'cache hit reuses a real response without an upstream attempt'
    Send-Request '/api/catalog/items' 'POST' | Out-Null
    Assert ((Send-Request $path).headers['X-Gateway-Outcome'] -eq 'CACHE_MISS') 'successful mutation invalidates cached GETs'
    $a=Send-Request '/api/orders/items';$b=Send-Request '/api/orders/items'
    Assert ($a.headers['X-Gateway-Backend'] -ne $b.headers['X-Gateway-Backend']) 'round robin selects distinct order replicas'
    1..3 | ForEach-Object { $failure=Send-Request '/api/orders/fail';Assert ($failure.status -eq 503 -and $failure.headers['X-Gateway-Attempts'] -eq '2') "safe request makes two attempts (failure $_)" }
    $rejected=Send-Request '/api/orders/items'
    Assert ($rejected.status -eq 503 -and $rejected.headers['X-Gateway-Attempts'] -eq '0') 'open circuits fail fast without upstream requests'
    $orders=(Snapshot).routes | Where-Object id -eq 'orders'
    Assert (@($orders.backends | Where-Object circuit -eq 'OPEN').Count -eq 2) 'both real order replica circuits are open'
    Start-Sleep -Seconds 8
    $a=Send-Request '/api/orders/items';$b=Send-Request '/api/orders/items'
    Assert ($a.status -eq 200 -and $b.status -eq 200) 'real half-open trials recover the order service'
    $mutation=Send-Request '/api/orders/fail' 'POST'
    Assert ($mutation.status -eq 503 -and $mutation.headers['X-Gateway-Attempts'] -eq '1') 'a failed POST is not retried'
    $stopped=$false
    try {
        & (Join-Path $PSScriptRoot 'run.ps1') -Action stop -Port 9202
        $stopped=$true;$deadline=[DateTime]::UtcNow.AddSeconds(8)
        do{
            $snapshot=Snapshot;$catalog=$snapshot.routes | Where-Object id -eq 'catalog'
            $replica=$catalog.backends | Where-Object origin -eq 'http://127.0.0.1:9202'
            if(!$replica.healthy){break};Start-Sleep -Milliseconds 200
        }while([DateTime]::UtcNow -lt $deadline)
        Assert (!$replica.healthy) 'a stopped process is detected by a scheduled readiness probe'
        $before=$replica.attempts
        1..4 | ForEach-Object { Assert ((Send-Request "/api/catalog/items?failover=$_").status -eq 200) "healthy catalog replica handles request $_" }
        $catalog=(Snapshot).routes | Where-Object id -eq 'catalog'
        Assert (($catalog.backends | Where-Object origin -eq 'http://127.0.0.1:9202').attempts -eq $before) 'stopped replica receives no further allocations'
    }finally{if($stopped){& (Join-Path $PSScriptRoot 'run.ps1') -Action start -Port 9202}}
    $deadline=[DateTime]::UtcNow.AddSeconds(10)
    do{$catalog=(Snapshot).routes | Where-Object id -eq 'catalog';if(@($catalog.backends | Where-Object healthy).Count -eq 2){break};Start-Sleep -Milliseconds 200}while([DateTime]::UtcNow -lt $deadline)
    Assert (@($catalog.backends | Where-Object healthy).Count -eq 2) 'restarted catalog replica recovers through a real probe'
    $limited=0
    1..24 | ForEach-Object {$response=Send-Request '/api/catalog/items?verification=budget';if($response.status -eq 429 -and $response.headers['Retry-After']){$limited++}}
    Assert ($limited -gt 0) 'burst exhausts the real user bucket and returns Retry-After'
    $report=[pscustomobject]@{checkedAt=[DateTimeOffset]::UtcNow.ToString('o');checks=$results.ToArray();passed=$true;rateLimitRejections=$limited}
    $report | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $projectRoot 'target\live-verification.json')
} finally {$client.Dispose();$handler.Dispose()}
