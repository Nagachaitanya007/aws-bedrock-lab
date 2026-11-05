$ErrorActionPreference = "Stop"

Write-Host ">>> Fetching Terraform outputs..." -ForegroundColor Cyan
Push-Location "E:\aws-bedrock-lab\terraform"
$InstanceId    = (terraform output -raw instance_id).Trim()
$PublicIp      = (terraform output -raw public_ip).Trim()
$RedisEndpoint = (terraform output -raw redis_endpoint).Trim()
Pop-Location

Write-Host "EC2 Instance   : $InstanceId"
Write-Host "EC2 Public IP  : $PublicIp"
Write-Host "Redis Endpoint : $RedisEndpoint"

# 1. Check if tunnel is active
$test = Test-NetConnection -ComputerName 127.0.0.1 -Port 6379 -WarningAction SilentlyContinue
if (-not $test.TcpTestSucceeded) {
    Write-Host ">>> Port 6379 closed. Establishing SSH tunnel..." -ForegroundColor Yellow

    if (-not (Test-Path "E:\aws-bedrock-lab\tunnel_key")) {
        ssh-keygen -t rsa -f "E:\aws-bedrock-lab\tunnel_key" -N '""'
    }

    # Push ephemeral public key via EC2 Instance Connect
    aws ec2-instance-connect send-ssh-public-key `
        --instance-id $InstanceId `
        --instance-os-user "ec2-user" `
        --ssh-public-key file://E:/aws-bedrock-lab/tunnel_key.pub | Out-Null

    # Launch background SSH process
    Start-Process ssh -ArgumentList "-i `"E:\aws-bedrock-lab\tunnel_key`" -N -L 6379:${RedisEndpoint}:6379 ec2-user@${PublicIp} -o StrictHostKeyChecking=no -o ServerAliveInterval=15"

    # Wait for loopback port to bind
    $retries = 10
    do {
        Start-Sleep -Seconds 1
        $test = Test-NetConnection -ComputerName 127.0.0.1 -Port 6379 -WarningAction SilentlyContinue
        $retries--
    } while ((-not $test.TcpTestSucceeded) -and ($retries -gt 0))

    if (-not $test.TcpTestSucceeded) {
        Write-Error "Failed to bind tunnel on 127.0.0.1:6379."
    }
}

Write-Host ">>> Tunnel confirmed on 127.0.0.1:6379. Launching Bedrock Chat..." -ForegroundColor Green
Set-Location "E:\aws-bedrock-lab"
.\gradlew.bat run -q --console=plain