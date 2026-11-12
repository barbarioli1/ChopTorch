[CmdletBinding()]
param(
    [switch]$GenerateUploadKey,
    [switch]$ConfigureGoogleCloud,
    [switch]$SkipGitHubSecretSetup,
    [switch]$RunPublishWorkflow
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$script:Config = @{}
$script:EnvironmentName = ''
$script:Repository = ''
$script:GhPath = ''

$envFile = Join-Path $script:RepositoryRoot '.env'
if (-not (Test-Path $envFile -PathType Leaf)) {
    throw 'Missing .env. Copy .env.example to .env and fill in the required values.'
}

foreach ($line in Get-Content -Path $envFile) {
    $trimmed = $line.Trim()
    if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
    $separator = $trimmed.IndexOf('=')
    if ($separator -lt 1) { throw "Invalid .env line: $trimmed" }
    $name = $trimmed.Substring(0, $separator).Trim()
    $value = $trimmed.Substring($separator + 1).Trim()
    if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    $script:Config[$name] = $value
}

function Get-ConfigValue {
    param([Parameter(Mandatory)][string]$Name)
    if (-not $script:Config.ContainsKey($Name) -or [string]::IsNullOrWhiteSpace($script:Config[$Name])) {
        throw "Set $Name in .env before running this script."
    }
    return $script:Config[$Name]
}

function Resolve-LocalPath {
    param([Parameter(Mandatory)][string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return Join-Path $script:RepositoryRoot $Path
}

function Set-SecretFromFile {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Path,
        [string]$Environment
    )
    if (-not (Test-Path $Path -PathType Leaf)) { throw "Required local file not found: $Path" }
    $value = [System.IO.File]::ReadAllText($Path)
    if ([string]::IsNullOrWhiteSpace($value)) { throw "Required file is empty: $Path" }
    if ($Environment) {
        $value | & $script:GhPath secret set $Name --repo $script:Repository --env $Environment
    } else {
        $value | & $script:GhPath secret set $Name --repo $script:Repository
    }
    if ($LASTEXITCODE -ne 0) { throw "Failed to set GitHub secret $Name." }
    $value = $null
}

function Set-SecretFromValue {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Value,
        [Parameter(Mandatory)][string]$Environment
    )
    $Value | & $script:GhPath secret set $Name --repo $script:Repository --env $Environment
    if ($LASTEXITCODE -ne 0) { throw "Failed to set GitHub environment secret $Name." }
}

function Convert-SecureStringToPlainText {
    param([Parameter(Mandatory)][System.Security.SecureString]$Value)
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Invoke-Gcloud {
    param([Parameter(Mandatory)][string[]]$Arguments)
    & $script:GcloudPath @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Google Cloud command failed: gcloud $($Arguments -join ' ')" }
}

function Test-GcloudResource {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $script:GcloudPath @Arguments *> $null
        return $LASTEXITCODE -eq 0
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

$script:Repository = Get-ConfigValue 'GITHUB_REPOSITORY'
$script:EnvironmentName = Get-ConfigValue 'GITHUB_ENVIRONMENT'
$branch = Get-ConfigValue 'GITHUB_BRANCH'
if ($script:Repository -notmatch '^[^/]+/[^/]+$') { throw 'GITHUB_REPOSITORY must use OWNER/REPO format.' }

$ghCommand = Get-Command gh -ErrorAction SilentlyContinue
if ($ghCommand) {
    $script:GhPath = $ghCommand.Source
} else {
    $ghCandidates = @(
        "$env:LOCALAPPDATA\Microsoft\WinGet\Links\gh.exe",
        "$env:ProgramFiles\GitHub CLI\gh.exe"
    )
    $ghCommand = $ghCandidates | Where-Object { Test-Path $_ -PathType Leaf } | Select-Object -First 1
    if (-not $ghCommand) {
        $wingetPackages = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages'
        if (Test-Path $wingetPackages -PathType Container) {
            foreach ($package in Get-ChildItem $wingetPackages -Directory -ErrorAction SilentlyContinue | Where-Object Name -Like 'GitHub.cli*') {
                $ghCommand = Get-ChildItem $package.FullName -Recurse -Filter gh.exe -ErrorAction SilentlyContinue | Select-Object -First 1
                if ($ghCommand) { break }
            }
        }
    }
    if (-not $ghCommand) { throw 'GitHub CLI (gh) is required. Install it, then rerun this script.' }
    if ($ghCommand -is [string]) { $script:GhPath = $ghCommand } else { $script:GhPath = $ghCommand.FullName }
}

& $script:GhPath auth status --hostname github.com *> $null
if ($LASTEXITCODE -ne 0) {
    & $script:GhPath auth login --hostname github.com --web --git-protocol https
    if ($LASTEXITCODE -ne 0) { throw 'GitHub authentication did not complete.' }
}

$gcloudCommand = Get-Command gcloud -ErrorAction SilentlyContinue
if (-not $gcloudCommand) {
    $gcloudCandidates = @(
        "$env:LOCALAPPDATA\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd",
        "$env:ProgramFiles\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd",
        "${env:ProgramFiles(x86)}\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
    )
    $gcloudCommand = $gcloudCandidates | Where-Object { Test-Path $_ -PathType Leaf } | Select-Object -First 1
}
if ($ConfigureGoogleCloud -and -not $gcloudCommand) {
    throw 'Google Cloud CLI is required for -ConfigureGoogleCloud. Install it and run gcloud auth login first.'
}
if ($ConfigureGoogleCloud) {
    if ($gcloudCommand -is [string]) { $script:GcloudPath = $gcloudCommand } else { $script:GcloudPath = $gcloudCommand.Source }
}

$modelPath = Resolve-LocalPath (Get-ConfigValue 'MODEL_JSON_PATH')
$samplesPath = Resolve-LocalPath (Get-ConfigValue 'SEED_EXAMPLES_JSON_PATH')
$keyStorePath = Resolve-LocalPath (Get-ConfigValue 'UPLOAD_KEYSTORE_PATH')
$keyAlias = Get-ConfigValue 'UPLOAD_KEY_ALIAS'

if (-not (Test-Path $keyStorePath -PathType Leaf) -and $GenerateUploadKey) {
    $keytool = Get-Command keytool -ErrorAction SilentlyContinue
    if (-not $keytool) { throw 'keytool was not found. Install JDK 17 or add keytool to PATH.' }
    $parent = Split-Path -Parent $keyStorePath
    if ($parent -and -not (Test-Path $parent)) { New-Item -ItemType Directory -Force $parent | Out-Null }
    & $keytool.Source -genkeypair -v -keystore $keyStorePath -alias $keyAlias -keyalg RSA -keysize 2048 -validity 10000
    if ($LASTEXITCODE -ne 0) { throw 'Upload-key generation failed.' }
}
if (-not (Test-Path $keyStorePath -PathType Leaf)) {
    throw "Upload keystore not found at $keyStorePath. Create it from the tutorial or rerun with -GenerateUploadKey."
}

& $script:GhPath api --silent --method PUT "repos/$($script:Repository)/environments/$($script:EnvironmentName)"
if ($LASTEXITCODE -ne 0) { throw "Could not create or update GitHub environment $($script:EnvironmentName)." }

if (-not $SkipGitHubSecretSetup) {
    Write-Host 'Setting repository model and sample secrets from local files...'
    Set-SecretFromFile -Name 'CHOPTORCH_MODEL_JSON' -Path $modelPath
    Set-SecretFromFile -Name 'CHOPTORCH_SEED_EXAMPLES_JSON' -Path $samplesPath

    Write-Host 'Encoding the upload keystore in memory and setting its environment secret...'
    $keyBytes = [System.IO.File]::ReadAllBytes($keyStorePath)
    $keyStoreBase64 = [Convert]::ToBase64String($keyBytes)
    [Array]::Clear($keyBytes, 0, $keyBytes.Length)
    Set-SecretFromValue -Name 'ANDROID_UPLOAD_KEYSTORE_BASE64' -Value $keyStoreBase64 -Environment $script:EnvironmentName
    $keyStoreBase64 = $null
    Set-SecretFromValue -Name 'ANDROID_UPLOAD_KEY_ALIAS' -Value $keyAlias -Environment $script:EnvironmentName

    $storePasswordSecure = Read-Host 'Upload keystore password (input hidden)' -AsSecureString
    $keyPasswordSecure = Read-Host 'Upload key password (input hidden)' -AsSecureString
    $storePassword = Convert-SecureStringToPlainText $storePasswordSecure
    $keyPassword = Convert-SecureStringToPlainText $keyPasswordSecure
    try {
        Set-SecretFromValue -Name 'ANDROID_UPLOAD_KEYSTORE_PASSWORD' -Value $storePassword -Environment $script:EnvironmentName
        Set-SecretFromValue -Name 'ANDROID_UPLOAD_KEY_PASSWORD' -Value $keyPassword -Environment $script:EnvironmentName
    } finally {
        $storePassword = $null
        $keyPassword = $null
        $storePasswordSecure.Dispose()
        $keyPasswordSecure.Dispose()
    }
}

if ($ConfigureGoogleCloud) {
    $projectId = Get-ConfigValue 'GCP_PROJECT_ID'
    $projectNumber = Get-ConfigValue 'GCP_PROJECT_NUMBER'
    $poolId = Get-ConfigValue 'GCP_WORKLOAD_IDENTITY_POOL_ID'
    $providerId = Get-ConfigValue 'GCP_WORKLOAD_IDENTITY_PROVIDER_ID'
    $serviceAccountId = Get-ConfigValue 'GCP_SERVICE_ACCOUNT_ID'
    if ($projectId -eq 'FILL_ME' -or $projectNumber -notmatch '^\d+$') {
        throw 'Fill GCP_PROJECT_ID and the numeric GCP_PROJECT_NUMBER in .env.'
    }

    $serviceAccount = "$serviceAccountId@$projectId.iam.gserviceaccount.com"
    $poolDescribe = @('iam', 'workload-identity-pools', 'describe', $poolId, '--location=global', "--project=$projectId", '--format=value(name)')
    if (-not (Test-GcloudResource -Arguments $poolDescribe)) {
        Invoke-Gcloud @('iam', 'workload-identity-pools', 'create', $poolId, '--location=global', '--display-name=GitHub Actions', "--project=$projectId")
    }

    $providerDescribe = @('iam', 'workload-identity-pools', 'providers', 'describe', $providerId, "--workload-identity-pool=$poolId", '--location=global', "--project=$projectId", '--format=value(name)')
    if (-not (Test-GcloudResource -Arguments $providerDescribe)) {
        Invoke-Gcloud @('iam', 'workload-identity-pools', 'providers', 'create-oidc', $providerId,
            "--workload-identity-pool=$poolId", '--location=global', '--issuer-uri=https://token.actions.githubusercontent.com',
            '--attribute-mapping=google.subject=assertion.sub,attribute.repository=assertion.repository',
            "--attribute-condition=assertion.repository=='$($script:Repository)'", "--project=$projectId")
    }

    $serviceAccountDescribe = @('iam', 'service-accounts', 'describe', $serviceAccount, "--project=$projectId", '--format=value(email)')
    if (-not (Test-GcloudResource -Arguments $serviceAccountDescribe)) {
        Invoke-Gcloud @('iam', 'service-accounts', 'create', $serviceAccountId, '--display-name=ChopTorch Play publisher', "--project=$projectId")
    }

    $member = "principalSet://iam.googleapis.com/projects/$projectNumber/locations/global/workloadIdentityPools/$poolId/attribute.repository/$($script:Repository)"
    Invoke-Gcloud @('iam', 'service-accounts', 'add-iam-policy-binding', $serviceAccount,
        '--role=roles/iam.workloadIdentityUser', "--member=$member", "--project=$projectId")
    Invoke-Gcloud @('services', 'enable', 'androidpublisher.googleapis.com', 'iamcredentials.googleapis.com', "--project=$projectId", '--quiet')

    $providerResource = "projects/$projectNumber/locations/global/workloadIdentityPools/$poolId/providers/$providerId"
    & $script:GhPath variable set GCP_WORKLOAD_IDENTITY_PROVIDER --repo $script:Repository --env $script:EnvironmentName --body $providerResource
    if ($LASTEXITCODE -ne 0) { throw 'Failed to set GCP_WORKLOAD_IDENTITY_PROVIDER.' }
    & $script:GhPath variable set GCP_SERVICE_ACCOUNT --repo $script:Repository --env $script:EnvironmentName --body $serviceAccount
    if ($LASTEXITCODE -ne 0) { throw 'Failed to set GCP_SERVICE_ACCOUNT.' }
}

if ($SkipGitHubSecretSetup) {
    Write-Host 'Skipped GitHub secrets; existing secret values were left unchanged.'
} else {
    Write-Host "GitHub secrets configured for $($script:Repository); environment: $($script:EnvironmentName)."
}
if ($ConfigureGoogleCloud) {
    Write-Host 'Google Workload Identity Federation is configured and its GitHub environment variables are set.'
    Write-Warning "Still grant $serviceAccount access to only this app and the required testing track in Google Play Console."
} else {
    Write-Host 'Run with -ConfigureGoogleCloud after filling the GCP fields in .env to configure federation and its GitHub variables.'
}

if ($RunPublishWorkflow) {
    $remoteWorkflowRef = "origin/${branch}:.github/workflows/publish-play-internal.yml"
    & git -C $script:RepositoryRoot fetch origin $branch
    if ($LASTEXITCODE -ne 0) { throw "Could not fetch origin/$branch." }
    $workflowLines = & git -C $script:RepositoryRoot show $remoteWorkflowRef 2>$null
    if ($LASTEXITCODE -ne 0) { throw "Could not read the publishing workflow from origin/$branch." }
    $workflowText = $workflowLines -join "`n"
    if ($workflowText -notmatch 'CHOPTORCH_MODEL_JSON' -or $workflowText -notmatch 'Restore private shipping model') {
        throw "origin/$branch does not contain the secure model-restore workflow. Merge and push the updated workflow before publishing."
    }

    $variableNames = & $script:GhPath variable list --repo $script:Repository --env $script:EnvironmentName --json name --jq '.[].name'
    if ($LASTEXITCODE -ne 0) { throw "Could not inspect variables in GitHub environment $($script:EnvironmentName)." }
    foreach ($requiredVariable in @('GCP_WORKLOAD_IDENTITY_PROVIDER', 'GCP_SERVICE_ACCOUNT')) {
        if ($variableNames -notcontains $requiredVariable) {
            throw "Missing $requiredVariable in GitHub environment $($script:EnvironmentName). Configure Google Cloud first or set the variable manually."
        }
    }

    $confirmation = Read-Host 'This dispatches a real internal Play release. The workflow will look up the next versionCode in Google Play. Type PUBLISH to continue'
    if ($confirmation -cne 'PUBLISH') { throw 'Publish dispatch canceled.' }
    & $script:GhPath workflow run 'publish-play-internal.yml' --repo $script:Repository --ref $branch
    if ($LASTEXITCODE -ne 0) { throw 'GitHub Actions workflow dispatch failed.' }
    Write-Host 'Publish workflow dispatched. Monitor it in GitHub Actions.'
}

Write-Host 'Complete the one-time Play Console service-account invitation and first-upload setup before dispatching a release.'
