variable "aws_region" {
  type        = string
  default     = "us-east-1"
  description = "Target AWS Region"
}

variable "db_password" {
  type        = string
  sensitive   = true
  description = "Master password for the RDS PostgreSQL database (injected via TF_VAR_db_password)"
}

variable "allowed_ssh_cidr" {
  type        = string
  default     = null
  description = "Optional override for CIDR allowed to SSH to bastion. Defaults to dynamically detected operator IP."
}
