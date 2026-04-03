Set-Location "c:\Users\M S I\OneDrive\Documents\PFE"
$base = "http://localhost:80"

$loginBody = @{ email = "admin@asm-delivery.com"; password = "Admin@2026" } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$base/api/auth/admin/login" -ContentType 'application/json' -Body $loginBody
if (-not $login.token) { throw 'No token from login' }
$headers = @{ Authorization = "Bearer $($login.token)" }

$deliveryPage = Invoke-RestMethod -Method Get -Uri "$base/api/admin/deliveries?page=0&size=50" -Headers $headers
$deliveries = @($deliveryPage.content)
if ($deliveries.Count -eq 0) { throw 'No deliveries found' }

$candidate = $deliveries | Where-Object { $_.status -notin @('DELIVERED', 'CANCELLED', 'FAILED') } | Select-Object -First 1
if (-not $candidate) { throw 'No eligible delivery for actions (all terminal)' }

$deliveryId = $candidate.deliveryId
Write-Output "CANDIDATE_DELIVERY=$deliveryId status=$($candidate.status)"

$drivers = Invoke-RestMethod -Method Get -Uri "$base/api/admin/deliveries/drivers" -Headers $headers
$driverList = @($drivers)
$targetDriver = $driverList | Where-Object { $_.driverId -and $_.driverId -ne $candidate.driverId } | Select-Object -First 1

if ($targetDriver) {
  try {
    $reassignBody = @{ driverId = $targetDriver.driverId; note = "smoke reassign $(Get-Date -Format s)" } | ConvertTo-Json
    $r = Invoke-RestMethod -Method Post -Uri "$base/api/admin/ops/exceptions/$deliveryId/reassign" -Headers $headers -ContentType 'application/json' -Body $reassignBody
    Write-Output "REASSIGN_OK status=$($r.status) driverId=$($r.driverId)"
  } catch {
    Write-Output "REASSIGN_FAIL=$($_.Exception.Message)"
  }
} else {
  Write-Output 'REASSIGN_SKIPPED no alternate driver'
}

try {
  $escalateBody = @{ note = "smoke escalate $(Get-Date -Format s)"; level = 'L1' } | ConvertTo-Json
  $e = Invoke-RestMethod -Method Post -Uri "$base/api/admin/ops/exceptions/$deliveryId/escalate" -Headers $headers -ContentType 'application/json' -Body $escalateBody
  Write-Output "ESCALATE_OK motif=$($e.motif) severity=$($e.severity)"
} catch {
  Write-Output "ESCALATE_FAIL=$($_.Exception.Message)"
}

try {
  $replanBody = @{ note = "smoke replan $(Get-Date -Format s)" } | ConvertTo-Json
  $p = Invoke-RestMethod -Method Post -Uri "$base/api/admin/ops/exceptions/$deliveryId/replan" -Headers $headers -ContentType 'application/json' -Body $replanBody
  Write-Output "REPLAN_OK status=$($p.status) motif=$($p.motif)"
} catch {
  Write-Output "REPLAN_FAIL=$($_.Exception.Message)"
}

$ops = Invoke-RestMethod -Method Get -Uri "$base/api/admin/ops/exceptions?period=day&limit=5" -Headers $headers
Write-Output "OPS_LIST total=$($ops.total) fetched=$(@($ops.items).Count)"
