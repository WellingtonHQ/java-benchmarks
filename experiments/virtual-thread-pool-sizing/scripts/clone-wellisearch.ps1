param(
    [string]$SourceAppContainer = "wellisearch",
    [string]$SourceDbContainer = "postgres",
    [string]$DumpPath = (Join-Path $env:TEMP ("wellisearch-index-{0}.dump" -f [guid]::NewGuid().ToString("N")))
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $projectRoot "docker-compose.wellisearch-copy.yml"
$composeProject = "wellisearch-benchmark-copy"
$containerDumpPath = "/tmp/wellisearch-index.dump"
$cloneContainer = $null

$dumpDirectory = Split-Path -Parent $DumpPath
if (-not (Test-Path -LiteralPath $dumpDirectory)) {
    throw "Dump directory does not exist: $dumpDirectory"
}
if (Test-Path -LiteralPath $DumpPath) {
    throw "Dump file already exists; choose a new path: $DumpPath"
}

try {
    # Read database name and role from the running app container; never echo its environment.
    $sourceEnvLines = docker inspect $SourceAppContainer --format '{{json .Config.Env}}'
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect the Wellisearch app container '$SourceAppContainer'."
    }
    $sourceEnv = @{}
    foreach ($entry in ($sourceEnvLines | ConvertFrom-Json)) {
        $name, $value = $entry -split '=', 2
        $sourceEnv[$name] = $value
    }

    $sourceDb = $sourceEnv["POSTGRES_DB"]
    $sourceUser = $sourceEnv["POSTGRES_USER"]
    if ([string]::IsNullOrWhiteSpace($sourceDb) -or [string]::IsNullOrWhiteSpace($sourceUser)) {
        throw "The Wellisearch container does not expose its database name and user."
    }

    # The clone uses its own Docker project, volume, port, and constrained resources.
    docker compose -p $composeProject -f $composeFile up -d --wait wellisearch-copy
    if ($LASTEXITCODE -ne 0) {
        throw "Could not start the resource-limited Wellisearch clone container."
    }

    # Export only indexed page/chunk data and their indexes; source DB access is read-only.
    $dumpArgs = @(
        "exec", $SourceDbContainer, "pg_dump",
        "--format=custom", "--no-owner", "--no-privileges",
        "--table=public.pages", "--table=public.chunks",
        "--username=$sourceUser", "--dbname=$sourceDb", "--file=$containerDumpPath"
    )
    & docker @dumpArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Filtered pg_dump failed; the live database was not modified."
    }

    docker cp "${SourceDbContainer}:$containerDumpPath" $DumpPath
    if ($LASTEXITCODE -ne 0) {
        throw "Could not copy the index archive out of the source container."
    }

    $cloneContainer = (docker compose -p $composeProject -f $composeFile ps -q wellisearch-copy).Trim()
    if ([string]::IsNullOrWhiteSpace($cloneContainer)) {
        throw "Could not find the Wellisearch clone container."
    }
    docker cp $DumpPath "${cloneContainer}:$containerDumpPath"
    if ($LASTEXITCODE -ne 0) {
        throw "Could not copy the index archive into the clone container."
    }

    $cloneEnvLines = docker inspect $cloneContainer --format '{{json .Config.Env}}'
    $cloneEnv = @{}
    foreach ($entry in ($cloneEnvLines | ConvertFrom-Json)) {
        $name, $value = $entry -split '=', 2
        $cloneEnv[$name] = $value
    }

    $restoreArgs = @(
        "exec", $cloneContainer, "pg_restore",
        "--exit-on-error", "--jobs=2", "--no-owner", "--no-privileges",
        "--username=$($cloneEnv['POSTGRES_USER'])",
        "--dbname=$($cloneEnv['POSTGRES_DB'])",
        $containerDumpPath
    )
    & docker @restoreArgs
    if ($LASTEXITCODE -ne 0) {
        throw "pg_restore failed; inspect the clone logs before retrying."
    }

    docker exec $cloneContainer psql -v ON_ERROR_STOP=1 `
        -U $cloneEnv["POSTGRES_USER"] -d $cloneEnv["POSTGRES_DB"] `
        -c "ANALYZE public.pages; ANALYZE public.chunks;" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "ANALYZE failed in the isolated clone."
    }

    $counts = docker exec $cloneContainer psql -X -A -t `
        -U $cloneEnv["POSTGRES_USER"] -d $cloneEnv["POSTGRES_DB"] `
        -c "SELECT (SELECT count(*) FROM public.pages), (SELECT count(*) FROM public.chunks);"
    if ($LASTEXITCODE -ne 0) {
        throw "Could not verify the cloned table row counts."
    }
    "Cloned pages/chunks row counts: $($counts.Trim())"

    $limits = docker inspect $cloneContainer --format 'nano_cpus={{.HostConfig.NanoCpus}} memory_bytes={{.HostConfig.Memory}}'
    "Clone resource limits: $limits"

    $copyPort = if ([string]::IsNullOrWhiteSpace($env:WELLISEARCH_COPY_DB_PORT)) {
        "5434"
    } else {
        $env:WELLISEARCH_COPY_DB_PORT
    }
    $env:WELLISEARCH_JDBC_URL = "jdbc:postgresql://localhost:$copyPort/$($cloneEnv['POSTGRES_DB'])"
    $env:WELLISEARCH_DB_USER = $cloneEnv["POSTGRES_USER"]
    $env:WELLISEARCH_DB_PASSWORD = $cloneEnv["POSTGRES_PASSWORD"]
    "Ready. WELLISEARCH_JDBC_URL now points at the isolated clone for this PowerShell session."
} finally {
    & docker exec $SourceDbContainer rm -f $containerDumpPath 2>$null | Out-Null
    if ($cloneContainer) {
        & docker exec $cloneContainer rm -f $containerDumpPath 2>$null | Out-Null
    }
    if (Test-Path -LiteralPath $DumpPath) {
        Remove-Item -LiteralPath $DumpPath -Force
    }
}
