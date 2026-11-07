output "redis_endpoint" {
  description = "Private Redis DNS Endpoint"
  value       = aws_elasticache_cluster.redis.cache_nodes[0].address
}

output "instance_id" {
  description = "EC2 Tunnel Instance ID"
  value       = aws_instance.tunnel_host.id
}

output "public_ip" {
  description = "EC2 Tunnel Public IP"
  value       = aws_instance.tunnel_host.public_ip
}

output "sagemaker_endpoint_name" {
  description = "Name of the SageMaker Reranker Endpoint"
  value       = aws_sagemaker_endpoint.reranker_endpoint.name
}

output "rds_endpoint" {
  description = "Private endpoint of the RDS PostgreSQL instance"
  value       = aws_db_instance.postgres.address
}

output "rds_port" {
  description = "PostgreSQL port"
  value       = aws_db_instance.postgres.port
}
