# SentinelPulse End-to-End Test Suite & Verification Script
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ApiKey  = "sentinel-dev-key-001"
)

$ErrorActionPreference = "Continue"

Write-Host "================================================================" -ForegroundColor Cyan
Write-Host " SENTINELPULSE END-TO-END VERIFICATION SUITE" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan

$passed = 0
$failed = 0

function Test-Endpoint {
    param(
        [string]$Name,
        [string]$Method,
        [string]$Path,
        [hashtable]$Headers = @{},
        [string]$Body = $null,
        [int]$ExpectedStatus
    )

    $url = "$BaseUrl$Path"
    Write-Host "`n[TEST] $Name" -ForegroundColor Yellow
    Write-Host "  -> $Method $url"

    $h = @{}
    foreach ($k in $Headers.Keys) {
        $h[$k] = $Headers[$k]
    }

    try {
        $params = @{
            Uri = $url
            Method = $Method
            Headers = $h
            ContentType = "application/json"
        }
        if ($Body) {
            $params["Body"] = $Body
        }

        $resp = Invoke-WebRequest @params -UseBasicParsing -TimeoutSec 10
        $statusCode = [int]$resp.StatusCode
        $content = $resp.Content

        Write-Host "  Status Code: $statusCode (Expected: $ExpectedStatus)"
        Write-Host "  Response: $content" -ForegroundColor DarkGray

        if ($statusCode -eq $ExpectedStatus) {
            Write-Host "  [PASS] $Name" -ForegroundColor Green
            $script:passed++
        } else {
            Write-Host "  [FAIL] $Name (Expected $ExpectedStatus, got $statusCode)" -ForegroundColor Red
            $script:failed++
        }
        return @{ StatusCode = $statusCode; Content = $content; Response = $resp }
    } catch {
        $statusCode = 0
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $content = $reader.ReadToEnd()
        } else {
            $content = $_.Exception.Message
        }

        Write-Host "  Status Code: $statusCode (Expected: $ExpectedStatus)"
        Write-Host "  Response: $content" -ForegroundColor DarkGray

        if ($statusCode -eq $ExpectedStatus) {
            Write-Host "  [PASS] $Name" -ForegroundColor Green
            $script:passed++
        } else {
            Write-Host "  [FAIL] $Name (Expected $ExpectedStatus, got $statusCode)" -ForegroundColor Red
            $script:failed++
        }
        return @{ StatusCode = $statusCode; Content = $content }
    }
}

# 1. Health Endpoint
Test-Endpoint -Name "Actuator Health Check (Bypass Auth)" `
              -Method "GET" `
              -Path "/actuator/health" `
              -ExpectedStatus 200

# 2. Prometheus Endpoint
Test-Endpoint -Name "Prometheus Metrics (Bypass Auth)" `
              -Method "GET" `
              -Path "/actuator/prometheus" `
              -ExpectedStatus 200

# 3. Auth Filter: Missing API Key
Test-Endpoint -Name "Auth Filter: Reject Missing X-API-Key (401)" `
              -Method "GET" `
              -Path "/api/v1/orders" `
              -ExpectedStatus 401

# 4. Auth Filter: Invalid API Key
Test-Endpoint -Name "Auth Filter: Reject Invalid X-API-Key (401)" `
              -Method "GET" `
              -Path "/api/v1/orders" `
              -Headers @{ "X-API-Key" = "invalid-dummy-key" } `
              -ExpectedStatus 401

# 5. Register Webhook Subscriber (New -> 201)
$uniqueSubId = "sub-e2e-" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$subPayloadNew = '{"subscriber_id":"' + $uniqueSubId + '","target_url":"http://localhost:9999/webhook","event_type":"order.completed"}'
Test-Endpoint -Name "Register Webhook Subscriber (201 Created)" `
              -Method "POST" `
              -Path "/api/v1/subscribers" `
              -Headers @{ "X-API-Key" = $ApiKey } `
              -Body $subPayloadNew `
              -ExpectedStatus 201

# 6. Update Webhook Subscriber (Existing -> 200)
$subPayloadUpdate = '{"subscriber_id":"' + $uniqueSubId + '","target_url":"http://localhost:9999/webhook-updated","event_type":"order.completed"}'
Test-Endpoint -Name "Update Webhook Subscriber (200 OK)" `
              -Method "POST" `
              -Path "/api/v1/subscribers" `
              -Headers @{ "X-API-Key" = $ApiKey } `
              -Body $subPayloadUpdate `
              -ExpectedStatus 200

# 7. Webhook Event Intake & Dispatch (New Unique Event -> 202)
$uniqueOrderId = "ORD-" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$eventPayload = '{"event_type":"order.completed","source":"order-service","data":{"order_id":"' + $uniqueOrderId + '","amount":250.00}}'
Test-Endpoint -Name "Event Intake & Fan-out (202 Accepted)" `
              -Method "POST" `
              -Path "/api/v1/events" `
              -Headers @{ "X-API-Key" = $ApiKey } `
              -Body $eventPayload `
              -ExpectedStatus 202

# 8. Idempotency Check (Duplicate Event Dropped -> 409)
Test-Endpoint -Name "Idempotency Enforcement: Duplicate Event (409 Conflict)" `
              -Method "POST" `
              -Path "/api/v1/events" `
              -Headers @{ "X-API-Key" = $ApiKey } `
              -Body $eventPayload `
              -ExpectedStatus 409

# 9. Reverse Proxy: Route Not Found
Test-Endpoint -Name "Reverse Proxy: Unregistered Route (404 Not Found)" `
              -Method "GET" `
              -Path "/api/v1/unknown-service" `
              -Headers @{ "X-API-Key" = $ApiKey } `
              -ExpectedStatus 404

# 10. Reverse Proxy: Registered Route Forwarding (Downstream offline -> 502 Bad Gateway)
Test-Endpoint -Name "Reverse Proxy: Downstream Unavailable (502 Bad Gateway)" `
              -Method "GET" `
              -Path "/api/v1/orders" `
              -Headers @{ "X-API-Key" = $ApiKey } `
              -ExpectedStatus 502

# 11. Rate Limiting: Burst Consumption triggering 429
Write-Host "`n[TEST] Rate Limiting: Firing Burst Requests to exhaust token bucket" -ForegroundColor Yellow
$burstTriggered429 = $false
for ($i = 1; $i -le 15; $i++) {
    Write-Host "`n[TEST] Burst Request #$i" -ForegroundColor Yellow
    Write-Host "  -> GET http://localhost:8080/api/v1/orders"
    try {
        $resp = Invoke-WebRequest -Uri "http://localhost:8080/api/v1/orders" -Method "GET" -Headers @{ "X-API-Key" = $ApiKey } -UseBasicParsing -TimeoutSec 5
        $code = [int]$resp.StatusCode
        Write-Host "  Status Code: $code (Token consumed from bucket)" -ForegroundColor DarkGray
        $passed++
    } catch {
        $code = 0
        if ($_.Exception.Response) {
            $code = [int]$_.Exception.Response.StatusCode
        }
        if ($code -eq 429) {
            $burstTriggered429 = $true
            Write-Host "  Status Code: 429 (Token bucket exhausted -> HTTP 429 Too Many Requests)" -ForegroundColor Green
            Write-Host "  [PASS] Rate Limiter correctly enforced 429 on request #$i!" -ForegroundColor Green
            $passed++
            break
        } elseif ($code -eq 502) {
            Write-Host "  Status Code: 502 (Token consumed, downstream offline)" -ForegroundColor DarkGray
            $passed++
        } else {
            Write-Host "  Unexpected status: $code" -ForegroundColor Red
            $failed++
            break
        }
    }
}
if (-not $burstTriggered429) {
    Write-Host "  [FAIL] Rate limit 429 was not triggered within 15 requests" -ForegroundColor Red
    $failed++
}

Write-Host "`n================================================================" -ForegroundColor Cyan
Write-Host " SUMMARY: $passed PASSED | $failed FAILED" -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })
Write-Host "================================================================" -ForegroundColor Cyan

