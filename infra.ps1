[CmdletBinding()]
param (
    [Parameter(Position=0, Mandatory=$true)]
    [ValidateSet('up', 'down', 'status', 'tunnel-start', 'tunnel-stop')]
    [string]$Action
)

$ErrorActionPreference = 'Stop'
$TerraformDir = "E:\aws-bedrock-lab\terraform"
$ProjectDir = "E:\aws-bedrock-lab"
$KeyPath = "$ProjectDir\tunnel_key"

function Load-EnvFile {
    $envPath = Join-Path $ProjectDir ".env"
    if (Test-Path $envPath) {
        Get-Content $envPath | Where-Object {
            $_ -match '^[^#=]+=.+$'
        } | ForEach-Object {
            $parts = $_.Split('=', 2)
            $varName = $parts[0].Trim()
            $varVal = $parts[1].Trim()
            [System.Environment]::SetEnvironmentVariable($varName, $varVal, 'Process')
        }
        if ($env:DOCLAB_DB_PASS) {
            $env:TF_VAR_db_password = $env:DOCLAB_DB_PASS
        } elseif ($env:AEGIS_DB_PASS) {
            $env:TF_VAR_db_password = $env:AEGIS_DB_PASS
        }
        Write-Host ">>> Loaded environment variables from .env" -ForegroundColor DarkGray
    }
}

function Test-PortOpen([string]$HostName, [int]$Port) {
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $asyncResult = $client.BeginConnect($HostName, $Port, $null, $null)
        $wait = $asyncResult.AsyncWaitHandle.WaitOne(1000, $false)
        if ($wait -and $client.Connected) {
            $client.EndConnect($asyncResult)
            $client.Close()
            return $true
        }
        $client.Close()
        return $false
    } catch {
        return $false
    }
}

function Ensure-SshKey {
    if (-not (Test-Path "$KeyPath") -or -not (Test-Path "$KeyPath.pub")) {
        Write-Host ">>> Generating new SSH keypair ($KeyPath)..." -ForegroundColor Cyan
        ssh-keygen -t rsa -b 2048 -f "$KeyPath" -N '""' -q
    }
}

function Get-TfOutputs {
    Push-Location $TerraformDir
    try {
        $raw = terraform output -json
        if (-not $raw) {
            throw "Terraform outputs are empty. Run 'terraform apply' first."
        }
        $json = $raw | ConvertFrom-Json

        return @{
            InstanceId      = $json.instance_id.value
            PublicIp        = $json.public_ip.value
            RdsHost         = $json.rds_endpoint.value
            RdsPort         = if ($json.rds_port) { $json.rds_port.value } else { 5432 }
            RedisHost       = $json.redis_endpoint.value
            S3Bucket        = if ($json.s3_bucket_name) { $json.s3_bucket_name.value } else { "" }
            ParsingQueue    = if ($json.parsing_queue_url) { $json.parsing_queue_url.value } else { "" }
            IndexingQueue   = if ($json.indexing_queue_url) { $json.indexing_queue_url.value } else { "" }
            OperatorSshCidr = if ($json.operator_ssh_cidr) { $json.operator_ssh_cidr.value } else { "" }
        }
    } finally {
        Pop-Location
    }
}

function Start-SshTunnel {
    Ensure-SshKey
    $tf = Get-TfOutputs

    Write-Host ">>> Pushing SSH public key to Bastion ($($tf.InstanceId))..." -ForegroundColor Cyan
    aws ec2-instance-connect send-ssh-public-key `
        --region us-east-1 `
        --instance-id $tf.InstanceId `
        --availability-zone us-east-1c `
        --instance-os-user ec2-user `
        --ssh-public-key "file://$KeyPath.pub" | Out-Null

    Write-Host ">>> Spawning SSH background tunnel..." -ForegroundColor Cyan
    $sshArgs = @(
        "-i", "`"$KeyPath`"",
        "-o", "StrictHostKeyChecking=no",
        "-o", "UserKnownHostsFile=/dev/null",
        "-o", "ServerAliveInterval=15",
        "-o", "ServerAliveCountMax=6",
        "-N",
        "-L", "6379:$($tf.RedisHost):6379",
        "-L", "5432:$($tf.RdsHost):$($tf.RdsPort)",
        "ec2-user@$($tf.PublicIp)"
    )

    Start-Process -FilePath "ssh" -ArgumentList ($sshArgs -join " ") -WindowStyle Hidden

    Write-Host ">>> Waiting for local forward ports 5432 & 6379 to bind..." -ForegroundColor Yellow
    $retries = 10
    while ($retries -gt 0) {
        Start-Sleep -Seconds 1
        if ((Test-PortOpen "127.0.0.1" 5432) -and (Test-PortOpen "127.0.0.1" 6379)) {
            Write-Host ">>> SSH Tunnel active and ports bound successfully!" -ForegroundColor Green
            return
        }
        $retries--
    }
    Write-Warning "Tunnel forward took longer than 10 seconds to verify. Run '.\infra.ps1 status' to check."
}

function Stop-SshTunnel {
    Write-Host ">>> Stopping any active background SSH tunnel processes..." -ForegroundColor Cyan
    Get-Process -Name "ssh" -ErrorAction SilentlyContinue | Where-Object {
        $_.CommandLine -like "*ec2-user@*" -or $_.CommandLine -like "*tunnel_key*"
    } | Stop-Process -Force
    Start-Sleep -Seconds 1
    Write-Host ">>> SSH tunnel processes terminated." -ForegroundColor Green
}

# Always load configuration and export TF_VAR_db_password
Load-EnvFile

switch ($Action) {
    'up' {
        Write-Host "`n==========================================" -ForegroundColor Cyan
        Write-Host "   PROVISIONING DOCLAB INFRASTRUCTURE" -ForegroundColor Cyan
        Write-Host "==========================================" -ForegroundColor Cyan

        Push-Location $TerraformDir
        try {
            Write-Host ">>> Initializing Terraform..." -ForegroundColor Cyan
            terraform init -upgrade
            Write-Host ">>> Applying Terraform configuration..." -ForegroundColor Cyan
            terraform apply -auto-approve
        } finally {
            Pop-Location
        }

        Start-SshTunnel

        Write-Host "`n>>> Initializing PostgreSQL Schema..." -ForegroundColor Cyan
        Push-Location $ProjectDir
        try {
            .\gradlew.bat run "-PmainClass=com.aws.lab.DatabaseInitializer" --console=plain -q
        } finally {
            Pop-Location
        }

        Write-Host "`n========================================================" -ForegroundColor Green
        Write-Host "  INFRASTRUCTURE IS FULLY DEPLOYED AND READY TO USE" -ForegroundColor Green
        Write-Host "========================================================" -ForegroundColor Green
    }

    'down' {
        Write-Host "`n==========================================" -ForegroundColor Yellow
        Write-Host "   DECOMMISSIONING DOCLAB INFRASTRUCTURE" -ForegroundColor Yellow
        Write-Host "==========================================" -ForegroundColor Yellow

        Stop-SshTunnel

        Push-Location $TerraformDir
        try {
            Write-Host ">>> Destroying Terraform resources..." -ForegroundColor Yellow
            terraform destroy -auto-approve
        } finally {
            Pop-Location
        }

        Write-Host "`n========================================================" -ForegroundColor Green
        Write-Host "  INFRASTRUCTURE SUCCESSFULLY DESTROYED" -ForegroundColor Green
        Write-Host "========================================================" -ForegroundColor Green
    }

    'status' {
        Write-Host "`n--- Connection Port Health ---" -ForegroundColor Cyan
        $p5432 = Test-PortOpen "127.0.0.1" 5432
        $p6379 = Test-PortOpen "127.0.0.1" 6379
        Write-Host "PostgreSQL (127.0.0.1:5432) : $(if ($p5432) {'ONLINE'} else {'OFFLINE'})" -ForegroundColor $(if ($p5432) {'Green'} else {'Red'})
        Write-Host "Redis      (127.0.0.1:6379) : $(if ($p6379) {'ONLINE'} else {'OFFLINE'})" -ForegroundColor $(if ($p6379) {'Green'} else {'Red'})

        try {
            $tf = Get-TfOutputs
            Write-Host "`n--- Active Cloud Resources ---" -ForegroundColor Cyan
            Write-Host "Bastion Instance ID : $($tf.InstanceId)"
            Write-Host "Bastion Public IP   : $($tf.PublicIp)"
            Write-Host "Allowed SSH Ingress : $($tf.OperatorSshCidr)"
            Write-Host "RDS Endpoint        : $($tf.RdsHost):$($tf.RdsPort)"
            Write-Host "Redis Endpoint      : $($tf.RedisHost)"
            if ($tf.S3Bucket)     { Write-Host "S3 Bucket           : $($tf.S3Bucket)" }
            if ($tf.ParsingQueue) { Write-Host "Parsing Queue       : $($tf.ParsingQueue)" }
        } catch {
            Write-Host "`n[Note: $($_.Exception.Message)]" -ForegroundColor Gray
        }
    }

    'tunnel-start' {
        Start-SshTunnel
    }

    'tunnel-stop' {
        Stop-SshTunnel
    }
}
