variable "aws_region" {
  description = "AWS region to deploy the demo-data Lambda into."
  type        = string
  default     = "us-east-1"
}

variable "project_name" {
  description = "Prefix used to name AWS resources for this project."
  type        = string
  default     = "mootmaker-demo-data"
}

variable "environment" {
  description = "Name of the mootmaker-api environment this component targets (e.g. \"production\", or an ephemeral environment name). Combined with project_name to keep multiple environments' AWS resources from colliding in the same account, and used to locate this environment's credentials in SSM Parameter Store. Required - no default, so an environment is always chosen deliberately."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.environment))
    error_message = "environment must contain only lowercase letters, digits, and hyphens (it's used in AWS resource names and S3 state keys)."
  }
}

# Unlike its predecessors, this project reads NO Terraform outputs from mootmaker-api. The GraphQL
# URL, token endpoint, client id/secret and scopes all live in SSM Parameter Store at paths derived
# from the environment name (written by mootmaker-api/deploy/terraform/demo-data-credentials.tf),
# and the Lambda reads them at runtime. The environment name above is the only input this deploy
# needs about the target environment.

variable "target_people" {
  description = "How many people the environment should have. A run creates the shortfall and nothing if already met. Counted against the TOTAL number of people - Person exposes no Cognito linkage through the GraphQL API, so this tool cannot distinguish demo people from real signed-up ones (see the design)."
  type        = number
  default     = 40
}

variable "target_rooms" {
  description = "How many meeting rooms the environment should have. A run creates the shortfall and nothing if already met."
  type        = number
  default     = 10
}

variable "days_in_past" {
  description = "How many days behind today the meeting window reaches, so a freshly-seeded environment has calendar history rather than starting empty. A past day is topped up at most once."
  type        = number
  default     = 7
}

variable "weeks_ahead" {
  description = "How many weeks ahead of today the meeting window reaches."
  type        = number
  default     = 6
}

variable "schedule_expression" {
  description = "EventBridge schedule expression controlling how often this runs automatically. Daily at 06:00 UTC: each run tops up the one newly-uncovered day at the far edge of the window, so the calendar fills evenly rather than in five-day steps. Cost is not a factor - see the design's cost appendix."
  type        = string
  default     = "cron(0 6 * * ? *)"
}

variable "schedule_enabled" {
  description = "Whether the EventBridge schedule is active. Defaults to true only for production: an ephemeral environment that outlives its work should not also sit there invoking a Lambda daily against an API that may be half-torn-down. Set explicitly to true to exercise the schedule in an ephemeral environment."
  type        = bool
  default     = null
}

variable "reserved_concurrency" {
  description = "Reserved concurrent executions for the Lambda. -1 means unreserved (the AWS default), which is what this deployment uses. Setting 1 would make overlapping runs structurally impossible, but AWS rejects any reservation leaving the account with fewer than 10 unreserved executions and this account's TOTAL quota is 10, so no value is settable. Running unreserved is an accepted risk, not an outstanding problem - see lambda.tf. Set to 1 if that quota is ever raised."
  type        = number
  default     = -1
}
