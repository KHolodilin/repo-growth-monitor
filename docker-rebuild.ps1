# Rebuild images and restart the local Compose stack.
# No host-specific paths or secrets are hardcoded — pass them in.

param(
    [Parameter(Mandatory = $false)]
    [string]$ProjectDir = $PSScriptRoot,

    [Parameter(Mandatory = $false)]
    [string[]]$ComposeFile = @("docker-compose.yml"),

    [Parameter(Mandatory = $false)]
    [string]$EnvFile = ".env",

    [Parameter(Mandatory = $false)]
    [string]$ProjectName,

    [Parameter(Mandatory = $false)]
    [string[]]$Service,

    [switch]$NoBuild,
    [switch]$ForceRecreate
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $ProjectDir -PathType Container)) {
    throw "ProjectDir does not exist: $ProjectDir"
}

$resolvedDir = (Resolve-Path -LiteralPath $ProjectDir).Path
Push-Location $resolvedDir
try {
    $composeArgs = @()

    foreach ($file in $ComposeFile) {
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
            throw "Compose file not found under ProjectDir: $file"
        }
        $composeArgs += @("-f", $file)
    }

    if ($EnvFile) {
        if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
            throw "Env file not found under ProjectDir: $EnvFile"
        }
        $composeArgs += @("--env-file", $EnvFile)
    }

    if ($ProjectName) {
        $composeArgs += @("-p", $ProjectName)
    }

    $upArgs = @("up", "-d")
    if (-not $NoBuild) {
        $upArgs += "--build"
    }
    if ($ForceRecreate) {
        $upArgs += "--force-recreate"
    }
    if ($Service -and $Service.Count -gt 0) {
        $upArgs += $Service
    }

    Write-Host "cwd: $resolvedDir"
    Write-Host "docker compose $($composeArgs -join ' ') $($upArgs -join ' ')"
    & docker compose @composeArgs @upArgs
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}
