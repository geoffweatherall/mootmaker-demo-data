variable "aws_region" {
  description = "AWS region to deploy the database-repair Lambda into."
  type        = string
  default     = "us-east-1"
}

variable "project_name" {
  description = "Prefix used to name AWS resources for this project."
  type        = string
  default     = "mootmaker-database-repair"
}

variable "environment" {
  description = "Name of the mootmaker-api environment this tool targets (e.g. \"test\", \"production\", or a developer's name for a personal sandbox). Combined with project_name to keep multiple environments' AWS resources from colliding in the same account. Required - no default, so an environment is always chosen deliberately."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.environment))
    error_message = "environment must contain only lowercase letters, digits, and hyphens (it's used in AWS resource names and S3 state keys)."
  }
}

# The four variables below are read from the target mootmaker-api environment's Terraform outputs
# by deploy.sh (via mootmaker-api's authenticate.sh) rather than looked up here directly, so this
# project stays a standalone Terraform root with no cross-project state coupling beyond that one
# shell hand-off - the same approach mootmaker-webapp's deploy.sh already uses. They default to
# empty rather than being required so that undeploy.sh (which only ever deletes resources and
# never needs their real values) doesn't also have to resolve the target environment's
# mootmaker-api outputs - useful if that environment's mootmaker-api deployment is itself already
# gone by the time this tool is undeployed. iam.tf builds this Lambda's IAM policy resource ARNs
# from the table names/user pool id here plus this account/region, rather than needing the actual
# ARNs - mootmaker-api's Terraform outputs only expose names/ids, since within its own state it
# never needs the ARNs spelled out explicitly.

variable "cognito_user_pool_id" {
  description = "Id of the target environment's Cognito user pool."
  type        = string
  default     = ""
}

variable "people_table_name" {
  description = "DynamoDB table name for Person records in the target environment."
  type        = string
  default     = ""
}

variable "meetings_table_name" {
  description = "DynamoDB table name for Meeting records in the target environment."
  type        = string
  default     = ""
}

variable "meeting_participants_table_name" {
  description = "DynamoDB table name for the meeting-participants join index in the target environment."
  type        = string
  default     = ""
}
