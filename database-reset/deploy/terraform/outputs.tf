output "function_name" {
  description = "Name of the deployed Lambda function - matches what run.sh (and sample-data-generator's DatabaseResetInvoker) compute themselves, so this output exists only for manual verification (e.g. `terraform output function_name` or in the AWS Console)."
  value       = aws_lambda_function.database_reset.function_name
}

output "function_arn" {
  description = "ARN of the deployed Lambda function - matches what sample-data-generator's Terraform computes itself to grant lambda:InvokeFunction, so this output exists only for manual verification."
  value       = aws_lambda_function.database_reset.arn
}
