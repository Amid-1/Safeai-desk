param(
    [string]$BaseUrl = "http://127.0.0.1:8080"
)

$ErrorActionPreference = "Stop"

$frontendResponse = Invoke-WebRequest `
    -Uri "$BaseUrl/admin/audit" `
    -Method Get `
    -SkipHttpErrorCheck

if ($frontendResponse.StatusCode -ne 200) {
    throw "Expected /admin/audit to return 200, got $($frontendResponse.StatusCode)"
}

if (
    -not $frontendResponse.Headers["Content-Type"] `
        -or $frontendResponse.Headers["Content-Type"] -notmatch "text/html"
) {
    throw "/admin/audit did not return text/html"
}

if ($frontendResponse.Content -notmatch '<div id="root"></div>') {
    throw "/admin/audit did not return frontend index.html"
}

$apiResponse = Invoke-WebRequest `
    -Uri "$BaseUrl/api/__frontend-routing-smoke__" `
    -Method Get `
    -SkipHttpErrorCheck

if ($apiResponse.Content -match '<div id="root"></div>') {
    throw "/api/** incorrectly returned frontend index.html"
}

if (
    $apiResponse.Headers["Content-Type"] `
        -and $apiResponse.Headers["Content-Type"] -match "text/html"
) {
    throw "/api/** unexpectedly returned text/html"
}

Write-Host "Frontend Nginx routing smoke test passed."
