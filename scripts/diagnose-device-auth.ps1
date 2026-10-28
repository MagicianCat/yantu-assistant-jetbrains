param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^https?://[^/]+/api/v1/?$')]
    [string]$ApiBaseUrl
)

$ErrorActionPreference = 'Stop'
$verifierBytes = New-Object byte[] 48
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($verifierBytes)
$rng.Dispose()
$verifier = [Convert]::ToBase64String($verifierBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$sha = [System.Security.Cryptography.SHA256]::Create()
$challengeBytes = $sha.ComputeHash([System.Text.Encoding]::ASCII.GetBytes($verifier))
$sha.Dispose()
$challenge = [Convert]::ToBase64String($challengeBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$body = @{ clientName = 'IDEA route diagnostic'; codeChallenge = $challenge; codeChallengeMethod = 'S256' } | ConvertTo-Json -Compress
$bodyFile = [System.IO.Path]::GetTempFileName()
$headerFile = [System.IO.Path]::GetTempFileName()
try {
    [System.IO.File]::WriteAllText($bodyFile, $body, (New-Object System.Text.UTF8Encoding($false)))
    $url = $ApiBaseUrl.TrimEnd('/') + '/auth/ide/authorizations'
    Write-Output 'Testing direct POST /auth/ide/authorizations without a system proxy...'
    & curl.exe --noproxy '*' --connect-timeout 8 --max-time 25 --silent --show-error `
        --output NUL --dump-header $headerFile `
        --write-out 'HTTP %{http_code}; elapsed %{time_total}s; remote_ip %{remote_ip}' `
        --header 'Content-Type: application/json' --data-binary "@$bodyFile" $url
    Write-Output ''
    Get-Content $headerFile | Where-Object { $_ -match '^(HTTP/|Server:|Via:|X-Request-Id:)' }
    Write-Output 'Response body, device code, and PKCE verifier were not printed.'
} finally {
    Remove-Item $bodyFile, $headerFile -ErrorAction SilentlyContinue
}
