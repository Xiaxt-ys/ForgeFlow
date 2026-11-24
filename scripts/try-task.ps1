param(
    [string]$Prompt = "SEARCH 一下 redis 租约",
    [string]$BaseUrl = "http://localhost:18080",
    [string]$Tenant = "tenant-a",
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"

$body = @{ tenantId = $Tenant; prompt = $Prompt } | ConvertTo-Json -Compress
$bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
$headers = @{ "Idempotency-Key" = [guid]::NewGuid().ToString() }

$task = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/tasks" `
    -ContentType "application/json; charset=utf-8" -Body $bytes -Headers $headers
Write-Host "taskId = $($task.id)"
Write-Host "prompt = $Prompt"

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$lastStatus = ""
do {
    Start-Sleep -Milliseconds 500
    $current = Invoke-RestMethod -Uri "$BaseUrl/api/tasks/$($task.id)"
    if ($current.status -ne $lastStatus) {
        Write-Host ("status  = {0}" -f $current.status)
        $lastStatus = $current.status
    }
} while ($current.status -notin @("SUCCEEDED", "FAILED", "CANCELLED") -and (Get-Date) -lt $deadline)

Write-Host "----------------------------------------"
Write-Host "final   = $($current.status)"
if ($current.result) {
    Write-Host "result  = $($current.result)"
}
if ($current.errorMessage) {
    Write-Host "error   = $($current.errorCode): $($current.errorMessage)"
}
