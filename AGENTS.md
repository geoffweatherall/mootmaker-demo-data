# mootmaker-demo-data

One Lambda, deployed per environment, that keeps an environment populated with demo people, rooms
and meetings. One of the project's three deployable components, alongside `mootmaker-api` and
`mootmaker-webapp`.

**Start by reading [README.md](README.md).** Keep it up to date when behaviour changes.

## Working here

- **This is part of the product, not test-only tooling.** It fills the public demo at
  www.mootmaker.com, and runs there daily on an EventBridge schedule. Always deployed to
  `production`; opt-in (`--with-demo-data`) for ephemeral environments.
- **This component never deletes anything, and must not learn how.** There is no reset path and no
  way to reach one. Its predecessor `sample-data-generator` reset the database as the first step of
  every run, behind a script called `run.sh`; removing that is the single most important property of
  the current design. A request to "just add a reset flag" should be read against
  [`mootmaker/designs/demo-data-component.md`](https://github.com/geoffweatherall/mootmaker/blob/main/designs/demo-data-component.md)
  before it is acted on. Clearing an environment is a separate, deliberate invocation of
  `mootmaker-api`'s `database-reset`.
- **Every write goes through the GraphQL API**, never DynamoDB directly. That is what makes
  generated data proof the API's own validation accepts it, and it is why there is no shared storage
  model between this repo and `mootmaker-api`.
- **All three concerns must stay idempotent.** People and rooms create `max(0, target - current)`;
  meetings skip any day that already has one. The acceptance suite's "a second run changes nothing"
  test is what guards this.
- **`deploy.sh` reads nothing from `mootmaker-api`'s Terraform state** - only the environment name.
  Credentials and endpoints come from SSM Parameter Store at runtime. Keep it that way; the
  deploy-time state hand-off it replaced is what coupled the two repos' releases.
- **`mootmaker-api` must be deployed to an environment first**, since its Terraform creates the SSM
  parameters this component reads.
- **The Lambda's timeout is the AWS maximum (900s) deliberately.** Any caller needs a matching
  client-side timeout - `--cli-read-timeout 900` for the AWS CLI - or a long run is reported as a
  failure while the Lambda completes regardless.

---

## Project-wide rules

This repository is part of the **mootmaker** project. The workflow rules that apply everywhere live
in the hub repository, which you should find checked out as a sibling directory:

    ../mootmaker/docs/process/README.md

On GitHub: <https://github.com/geoffweatherall/mootmaker/blob/main/docs/process/README.md>

**Read it before doing any non-trivial work here.** The short version:

- Work of any real size starts with a **design document** (`../mootmaker/designs/`), not with code.
- Bugs and small changes start with a **GitHub issue in this repository**, so `Closes #N` works.
- All work happens on a **branch** and lands via a **pull request**. There is no approval step —
  reading the diff is the review, merging is the approval.
- **A green acceptance run against a real deployed environment** is the definition of working — not
  a passing unit suite, and not a successful deploy.
- **Environments are `production` or ephemeral.** Tear down any ephemeral environment you create;
  that is part of finishing, not a tidy-up afterwards.
- **If your change makes a document wrong, fixing it is part of the change.**
- **Verify against reality, not your own output.** A script exiting zero is not evidence that the
  thing it was meant to do happened.
- **Say what actually happened.** Failing tests get reported with their output; skipped steps get
  named.

Also useful: [`../mootmaker/docs/roles/`](https://github.com/geoffweatherall/mootmaker/blob/main/docs/roles/)
for which kind of work you are doing, and
[`../mootmaker/tools/workstation/check.sh`](https://github.com/geoffweatherall/mootmaker/blob/main/tools/workstation/check.sh)
if something is not installed.

`CLAUDE.md` in this repository is a symlink to this file.
