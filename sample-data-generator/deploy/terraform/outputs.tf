output "function_name" {
  description = "Name of the deployed Lambda function - matches what run.sh computes itself, so this output exists only for manual verification (e.g. `terraform output function_name` or in the AWS Console)."
  value       = aws_lambda_function.generate_sample_data.function_name
}
