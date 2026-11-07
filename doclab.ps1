[CmdletBinding()]
param (
    [Parameter(Position = 0)]
    [ValidateSet('server', 'pipeline', 'console', 'status')]
    [string]$Action = 'server'
)

$ErrorActionPreference = 'Stop'
$ProjectDir = "E:\aws-bedrock-lab"

function Load-EnvFile {
    $envPath = Join-Path $ProjectDir ".env"

    if (Test-Path $envPath) {
        Get-Content $envPath | Where-Object {
            $_ -match '^[^#=]+=.+$'
        } | ForEach-Object {
            $parts = $_.Split('=', 2)
            $varName = $parts[0].Trim()
            $varVal = $parts[1].Trim()

            [System.Environment]::SetEnvironmentVariable(
                $varName,
                $varVal,
                'Process'
            )
        }

        Write-Host ">>> Loaded environment variables from .env" -ForegroundColor DarkGray
    }
    else {
        Write-Warning ".env file not found at $envPath. Ensure DOCLAB_DB_USER and password are set."
    }
}

function Test-PortOpen([string]$HostName, [int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient

    try {
        $asyncResult = $client.BeginConnect($HostName, $Port, $null, $null)
        $wait = $asyncResult.AsyncWaitHandle.WaitOne(1000, $false)

        if ($wait -and $client.Connected) {
            $client.EndConnect($asyncResult)
            return $true
        }

        return $false
    }
    catch {
        return $false
    }
    finally {
        $client.Close()
    }
}

function Ensure-TunnelActive {
    $p5432 = Test-PortOpen "127.0.0.1" 5432
    $p6379 = Test-PortOpen "127.0.0.1" 6379

    if (-not ($p5432 -and $p6379)) {
        throw "SSH Tunnel ports (5432/6379) are OFFLINE. Run '.\infra.ps1 tunnel-start' or '.\infra.ps1 up' first."
    }
}

# Load configuration before running any application.
Load-EnvFile

Push-Location $ProjectDir

try {
    switch ($Action) {
        'server' {
            Ensure-TunnelActive
            Write-Host "`n>>> Starting DocLab Spring Boot REST Platform on http://localhost:8080..." -ForegroundColor Green
            .\gradlew.bat bootRun --console=plain
        }

        'pipeline' {
            Ensure-TunnelActive

            Write-Host "`n=== [1/2] Draining Parsing Queue via UniversalParserWorker ===" -ForegroundColor Cyan
            .\gradlew.bat run "-PmainClass=com.aws.lab.UniversalParserWorker" --console=plain -q

            Write-Host "`n=== [2/2] Draining Indexing Queue via SemanticIndexerWorker ===" -ForegroundColor Cyan
            .\gradlew.bat run "-PmainClass=com.aws.lab.SemanticIndexerWorker" --console=plain -q

            Write-Host "`n>>> Ingestion pipeline complete!" -ForegroundColor Green
        }

        'console' {
            Ensure-TunnelActive
            .\gradlew.bat run "-PmainClass=com.aws.lab.DocLabEngine" --console=plain -q
        }

        'status' {
            $p5432 = Test-PortOpen "127.0.0.1" 5432
            $p6379 = Test-PortOpen "127.0.0.1" 6379

            Write-Host "PostgreSQL Tunnel (5432): $(if ($p5432) {'ONLINE'} else {'OFFLINE'})" `
                -ForegroundColor $(if ($p5432) {'Green'} else {'Red'})

            Write-Host "Redis Tunnel      (6379): $(if ($p6379) {'ONLINE'} else {'OFFLINE'})" `
                -ForegroundColor $(if ($p6379) {'Green'} else {'Red'})
        }
    }
}
finally {
    Pop-Location
}
