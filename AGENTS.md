# mootmaker-tools

Admin and demo-data Lambdas, each deployed per environment.

**Start by reading [README.md](README.md).** It lists the tools, each with its own README, and the
deploy ordering. Keep it up to date when tools are added, removed, or change behaviour.

## Working here

- **Two of these can destroy data.** `database-reset` deletes all rooms and meetings and every
  person not linked to a Cognito account; `database-repair` writes directly to Cognito and DynamoDB.
  Both work against `production`. Know which environment you are pointed at.
- **The other two are part of the product.** `sample-data-generator` and `sample-data-topup` seed the
  public demo — `sample-data-topup` runs weekly in production on an EventBridge schedule. They are
  not test-only tooling.
- **Deploy order matters.** `database-reset` must exist in an environment before
  `sample-data-generator` is deployed or run there, because the generator invokes it
  Lambda-to-Lambda as the first step of every run.
- **Expect `../mootmaker-api` as a sibling checkout.** Deploying and invoking both read the target
  environment's Terraform outputs through its `authenticate.sh`.
- **This repository is being split by blast radius** — the demo-data tools and the destructive admin
  tools are moving apart. See `../mootmaker/designs/project-reorganisation.md`.

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
