$ErrorActionPreference = "Stop"

$ProjectRoot = "E:\aws-bedrock-lab"
$TfDir = Join-Path $ProjectRoot "terraform"
$KeyPath = Join-Path $ProjectRoot "tunnel_key"

Write-Host ">>> Fetching connection outputs from Terraform..." -ForegroundColor Cyan
Set-Location $TfDir

$tfJson = (terraform output -json | Out-String | ConvertFrom-Json)

$BastionIp         = $tfJson.public_ip.value
$BastionInstanceId = $tfJson.instance_id.value
$RedisHost         = $tfJson.redis_endpoint.value
$RdsHost           = $tfJson.rds_endpoint.value

if (-not $BastionIp -or -not $RedisHost -or -not $RdsHost) {
    Write-Host "[Error] Missing Terraform outputs. Ensure terraform apply completed successfully." -ForegroundColor Red
    Set-Location $ProjectRoot
    exit 1
}

Write-Host "Bastion IP       : $BastionIp" -ForegroundColor Green
Write-Host "Bastion ID       : $BastionInstanceId" -ForegroundColor Green
Write-Host "ElastiCache Host : $RedisHost" -ForegroundColor Green
Write-Host "RDS Host         : $RdsHost" -ForegroundColor Green

# 1. Fetch the exact Availability Zone of the running instance
Write-Host ">>> Detecting EC2 instance Availability Zone..." -ForegroundColor Cyan
$InstanceAz = (aws ec2 describe-instances `
    --instance-ids $BastionInstanceId `
    --region us-east-1 `
    --query "Reservations[0].Instances[0].Placement.AvailabilityZone" `
    --output text).Trim()

Write-Host "Instance AZ      : $InstanceAz" -ForegroundColor Green

# 2. Generate temporary SSH key if missing
if (-not (Test-Path $KeyPath)) {
    Write-Host ">>> Generating temporary SSH key pair..." -ForegroundColor Cyan
    ssh-keygen -t rsa -b 2048 -f $KeyPath -N "" -q
}

# 3. Clean any existing background SSH sessions
Get-Process ssh -ErrorAction SilentlyContinue | Stop-Process -Force

# 4. Push public key using the detected AZ
Write-Host ">>> Pushing public key to EC2 Bastion via EC2 Instance Connect..." -ForegroundColor Cyan
aws ec2-instance-connect send-ssh-public-key `
    --region us-east-1 `
    --instance-id $BastionInstanceId `
    --availability-zone $InstanceAz `
    --instance-os-user ec2-user `
    --ssh-public-key "file://$KeyPath.pub" | Out-Null

# 5. Launch dual tunnel
Write-Host ">>> Opening dual SSH tunnel (6379:Redis, 5432:RDS)..." -ForegroundColor Cyan
$sshArgs = @(
    "-i", $KeyPath,
    "-o", "StrictHostKeyChecking=no",
    "-o", "UserKnownHostsFile=/dev/null",
    "-N",
    "-L", "6379:${RedisHost}:6379",
    "-L", "5432:${RdsHost}:5432",
    "ec2-user@$BastionIp"
)
$tunnelJob = Start-Process ssh -ArgumentList $sshArgs -PassThru -WindowStyle Hidden

Start-Sleep -Seconds 4

# 6. Verify local port bindings
$redisTest = Test-NetConnection -ComputerName 127.0.0.1 -Port 6379 -WarningAction SilentlyContinue
$rdsTest   = Test-NetConnection -ComputerName 127.0.0.1 -Port 5432 -WarningAction SilentlyContinue

if ($redisTest.TcpTestSucceeded -and $rdsTest.TcpTestSucceeded) {
    Write-Host ">>> Tunnels confirmed:" -ForegroundColor Green
    Write-Host "    - 127.0.0.1:6379 -> Redis" -ForegroundColor Green
    Write-Host "    - 127.0.0.1:5432 -> PostgreSQL" -ForegroundColor Green
} else {
    Write-Host "[Warning] Port verification incomplete. Redis: $($redisTest.TcpTestSucceeded), RDS: $($rdsTest.TcpTestSucceeded)" -ForegroundColor Yellow
}

Set-Location $ProjectRoot
Write-Host ">>> Infrastructure ready." -ForegroundColor Cyan