# mootmaker-tools

Admin/support tools for the [mootmaker](https://github.com/geoffweatherall/mootmaker) project. Unlike [mootmaker-api](https://github.com/geoffweatherall/mootmaker-api) and [mootmaker-webapp](https://github.com/geoffweatherall/mootmaker-webapp), nothing here is deployed - each tool is run locally against an already-deployed environment (see the [mootmaker project README](https://github.com/geoffweatherall/mootmaker#multi-environment-deployments) for how environments work) to help set up, exercise, or maintain that environment.

This checkout expects `mootmaker-api` to be a sibling directory - tools authenticate against a deployed environment by reading its Terraform outputs, the same way `mootmaker-webapp` does.

## Tools

| Tool | Purpose |
|---|---|
| [sample-data-generator](sample-data-generator/README.md) | Resets an environment (including production, which is itself a demo) and populates it with realistic sample people, rooms, and meetings |
| [database-repair](database-repair/README.md) | Runs one-off maintenance repairs directly against Cognito/DynamoDB - currently, creating a Person for every confirmed Cognito user that doesn't have one |
