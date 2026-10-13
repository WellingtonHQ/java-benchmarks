param(
    [int]$Trials = 2,
    [string[]]$Modes = @('virtual', 'platform1000', 'platform200', 'platform56'),
    [int[]]$Delays = @(0, 100),
    [string]$OutputDirectory = $env:TEMP
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $OutputDirectory)) {
    throw 'Output directory must already exist.'
}
$projectRoot = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $projectRoot 'target/virtual-thread-pool-sizing-1.0.0.jar'
if (-not (Test-Path -LiteralPath $jar)) {
    throw 'Build the benchmark with mvn clean package first.'
}
# Source credentials are not needed: all trials target only the isolated clone.
$clone = (docker compose -p wellisearch-benchmark-copy `
    -f (Join-Path $projectRoot 'docker-compose.wellisearch-copy.yml') ps -q wellisearch-copy).Trim()
if (-not $clone) {
    throw 'Start and restore the isolated clone first.'
}
$cloneEnv = @{}
foreach ($entry in (docker inspect $clone --format '{{json .Config.Env}}' | ConvertFrom-Json)) {
    $name, $value = $entry -split '=', 2
    $cloneEnv[$name] = $value
}
$port = if ($env:WELLISEARCH_COPY_DB_PORT) { $env:WELLISEARCH_COPY_DB_PORT } else { '5434' }
$env:WELLISEARCH_JDBC_URL = "jdbc:postgresql://localhost:$port/$($cloneEnv['POSTGRES_DB'])"
$env:WELLISEARCH_DB_USER = $cloneEnv['POSTGRES_USER']
$env:WELLISEARCH_DB_PASSWORD = $cloneEnv['POSTGRES_PASSWORD']

for ($trial = 1; $trial -le $Trials; $trial++) {
    # Reverse executor order in the second trial to reduce ordering bias.
    $runModes = @($Modes)
    if ($trial % 2 -eq 0) { [array]::Reverse($runModes) }
    foreach ($delay in $Delays) {
        foreach ($mode in $runModes) {
            $prefix = Join-Path $OutputDirectory "request-comparison-$trial-$delay-$mode"
            $process = Start-Process java.exe -ArgumentList @(
                '-Xms64m', '-Xmx256m', '-Xss1m', '-XX:+UseG1GC',
                '-XX:NativeMemoryTracking=summary', '-cp', "`"$jar`"",
                'org.wellingtonhq.benchmarks.RequestComparison', $mode, $delay
            ) -RedirectStandardOutput "$prefix.stdout.log" -RedirectStandardError "$prefix.stderr.log" -PassThru
            $samples = [System.Collections.Generic.List[object]]::new()
            $nmtSample = $null
            $measurementStarted = $null
            while (-not $process.HasExited) {
                Start-Sleep -Milliseconds 1000
                $process.Refresh()
                if ($process.HasExited) { break }
                if (-not $measurementStarted -and (Get-Content "$prefix.stdout.log" -Raw) -match 'MEASUREMENT_START') {
                    $measurementStarted = Get-Date
                }
                if ($measurementStarted) {
                    $age = ((Get-Date) - $measurementStarted).TotalSeconds
                    if ($age -lt 29) {
                        $samples.Add([pscustomobject]@{
                            WorkingMiB = $process.WorkingSet64 / 1MB
                            PrivateMiB = $process.PrivateMemorySize64 / 1MB
                            OsThreads = $process.Threads.Count
                        })
                    }
                    if ($age -ge 14 -and -not $nmtSample) {
                        # Print only the JVM thread-memory accounting, not any process environment.
                        $nmt = & jcmd $process.Id VM.native_memory summary scale=KB
                        $nmtSample = ($nmt | Where-Object { $_ -match '^\s*-\s+Thread \(' }) -join ''
                    }
                }
            }
            $process.WaitForExit()
            if ($process.ExitCode -ne 0) {
                throw "Trial failed ($mode); inspect $prefix.stderr.log"
            }
            $meanWorking = ($samples | Measure-Object WorkingMiB -Average).Average
            $meanPrivate = ($samples | Measure-Object PrivateMiB -Average).Average
            $meanThreads = ($samples | Measure-Object OsThreads -Average).Average
            "trial=$trial working_mib=$([math]::Round($meanWorking,1)) private_mib=$([math]::Round($meanPrivate,1)) os_threads=$([math]::Round($meanThreads,1))"
            Get-Content "$prefix.stdout.log"
            $nmtSample.Trim()
        }
    }
}
