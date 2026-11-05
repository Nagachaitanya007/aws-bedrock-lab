terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    time = {
      source  = "hashicorp/time"
      version = "~> 0.9"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

# --- VPC & Subnets ---
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

data "aws_ssm_parameter" "al2023_ami" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

# --- Security Group ---
resource "aws_security_group" "redis_sg" {
  name        = "bedrock-redis-tf-sg"
  description = "Security group for Bedrock Redis and SSH tunnel"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "Allow Redis from VPC CIDR"
    from_port   = 6379
    to_port     = 6379
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.default.cidr_block]
  }

  ingress {
    description = "Allow SSH for Tunneling"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name    = "bedrock-redis-tf-sg"
    Project = "Bedrock-Lab"
  }
}

# --- ElastiCache Subnet Group & Cluster ---
resource "aws_elasticache_subnet_group" "cache_subnets" {
  name       = "bedrock-tf-cache-subnet-group"
  subnet_ids = slice(data.aws_subnets.default.ids, 0, 3)
}

resource "aws_elasticache_cluster" "redis" {
  cluster_id           = "bedrock-tf-redis"
  engine               = "redis"
  node_type            = "cache.t4g.micro"
  num_cache_nodes      = 1
  parameter_group_name = "default.redis7"
  subnet_group_name    = aws_elasticache_subnet_group.cache_subnets.name
  security_group_ids   = [aws_security_group.redis_sg.id]

  tags = {
    Name    = "bedrock-tf-redis"
    Project = "Bedrock-Lab"
  }
}

# --- EC2 Tunnel Instance ---
resource "aws_instance" "tunnel_host" {
  ami                         = data.aws_ssm_parameter.al2023_ami.value
  instance_type               = "t3.micro"
  subnet_id                   = data.aws_subnets.default.ids[0]
  vpc_security_group_ids      = [aws_security_group.redis_sg.id]
  associate_public_ip_address = true

  tags = {
    Name    = "bedrock-tf-tunnel-host"
    Project = "Bedrock-Lab"
  }
}

# --- IAM Role for SageMaker ---
resource "aws_iam_role" "sagemaker_role" {
  name = "bedrock-lab-sagemaker-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "sagemaker.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "sagemaker_full_access" {
  role       = aws_iam_role.sagemaker_role.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSageMakerFullAccess"
}

# Explicit delay to allow IAM role and policies to propagate across AWS STS endpoints
resource "time_sleep" "wait_for_iam" {
  depends_on      = [aws_iam_role_policy_attachment.sagemaker_full_access]
  create_duration = "20s"
}

# --- SageMaker Model (Hugging Face Deep Learning Container) ---
resource "aws_sagemaker_model" "reranker_model" {
  name               = "bge-reranker-base-model"
  execution_role_arn = aws_iam_role.sagemaker_role.arn
  depends_on         = [time_sleep.wait_for_iam]

  primary_container {
    # Official Hugging Face PyTorch CPU Inference Container in us-east-1
    image = "763104351884.dkr.ecr.us-east-1.amazonaws.com/huggingface-pytorch-inference:2.6.0-transformers4.51.3-cpu-py312-ubuntu22.04"
    mode  = "SingleModel"

    environment = {
      HF_MODEL_ID = "BAAI/bge-reranker-base"
      HF_TASK     = "text-classification"
    }
  }

  tags = {
    Name    = "bge-reranker-model"
    Project = "Bedrock-Lab"
  }
}

# --- SageMaker Endpoint Configuration ---
resource "aws_sagemaker_endpoint_configuration" "reranker_config" {
  name = "bge-reranker-config"

  production_variants {
    variant_name           = "AllTraffic"
    model_name             = aws_sagemaker_model.reranker_model.name
    initial_instance_count = 1
    initial_variant_weight = 1
    instance_type          = "ml.m5.large"
  }

  tags = {
    Name    = "bge-reranker-config"
    Project = "Bedrock-Lab"
  }
}

# --- SageMaker Real-Time Endpoint ---
resource "aws_sagemaker_endpoint" "reranker_endpoint" {
  name                 = "bge-reranker-endpoint"
  endpoint_config_name = aws_sagemaker_endpoint_configuration.reranker_config.name

  tags = {
    Name    = "bge-reranker-endpoint"
    Project = "Bedrock-Lab"
  }
}