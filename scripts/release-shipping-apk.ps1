[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^v\d+\.\d+(\.\d+)?$')]
    [string]$Tag,

    [Parameter(Mandatory = $true)]
    [string]$ReleaseNotesPath,

    [ValidateSet('main', 'master')]
    [string]$Ref = 'main'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$workflowPath = Join-Path $repositoryRoot '.github\workflows\release-shipping-apk.yml'
if (-not (Test-Path $workflowPath -PathType Leaf)) {
    throw 'The shipping APK release workflow is missing from this checkout.'
}

$gh = Get-Command gh -ErrorAction SilentlyContinue
if (-not $gh) {
    throw 'GitHub CLI (gh) is required. Install it and run gh auth login first.'
}

$notesPath = if ([System.IO.Path]::IsPathRooted($ReleaseNotesPath)) {
    $ReleaseNotesPath
} else {
    Join-Path $repositoryRoot $ReleaseNotesPath
}
if (-not (Test-Path $notesPath -PathType Leaf)) {
    throw "Release notes file not found: $notesPath"
}
$releaseNotes = [System.IO.File]::ReadAllText((Resolve-Path $notesPath).Path)
if ([string]::IsNullOrWhiteSpace($releaseNotes)) {
    throw 'Release notes cannot be empty.'
}
if ($releaseNotes.Length -gt 30000) {
    throw 'Release notes exceed the supported 30,000-character limit.'
}

& $gh.Source auth status
if ($LASTEXITCODE -ne 0) {
    throw 'GitHub CLI is not authenticated. Run gh auth login and try again.'
}

Push-Location $repositoryRoot
try {
    $workflowArguments = @(
        'workflow', 'run', 'release-shipping-apk.yml',
        '--ref', $Ref,
        '--field', "tag=$Tag",
        '--field', "release_notes=$releaseNotes"
    )
    & $gh.Source @workflowArguments
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to dispatch the shipping APK release workflow.'
    }
} finally {
    Pop-Location
}

Write-Host "Submitted $Tag to the GitHub Actions workflow on $Ref. Monitor the run in the repository Actions tab."