variable "aws_region" {
  description = "AWS region to deploy the sample data generator Lambda into."
  type        = string
  default     = "us-east-1"
}

variable "project_name" {
  description = "Prefix used to name AWS resources for this project."
  type        = string
  default     = "mootmaker-sample-data-generator"
}

variable "environment" {
  description = "Name of the mootmaker-api environment this tool targets (e.g. \"test\", \"production\", or a developer's name for a personal sandbox). Combined with project_name to keep multiple environments' AWS resources from colliding in the same account. Required - no default, so an environment is always chosen deliberately."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.environment))
    error_message = "environment must contain only lowercase letters, digits, and hyphens (it's used in AWS resource names and S3 state keys)."
  }
}

# The five variables below are read from the target mootmaker-api environment's Terraform outputs
# by deploy.sh (via mootmaker-api's authenticate.sh) rather than looked up here directly, so this
# project stays a standalone Terraform root with no cross-project state coupling beyond that one
# shell hand-off - the same approach mootmaker-webapp's deploy.sh already uses. They default to
# empty rather than being required so that undeploy.sh (which only ever deletes resources and
# never needs their real values) doesn't also have to resolve the target environment's
# mootmaker-api outputs - useful if that environment's mootmaker-api deployment is itself already
# gone by the time this tool is undeployed.

variable "graphql_api_url" {
  description = "GraphQL endpoint of the target mootmaker-api environment."
  type        = string
  default     = ""
}

variable "cognito_token_url" {
  description = "OAuth2 token endpoint of the target environment's Cognito user pool."
  type        = string
  default     = ""
}

variable "cognito_test_client_id" {
  description = "App client id used to obtain a client_credentials access token - the same client mootmaker-api's own acceptance tests use."
  type        = string
  default     = ""
}

variable "cognito_test_client_secret" {
  description = "App client secret for cognito_test_client_id."
  type        = string
  sensitive   = true
  default     = ""
}

variable "cognito_test_scope" {
  description = "OAuth2 scope requested when fetching a client_credentials access token."
  type        = string
  default     = ""
}
