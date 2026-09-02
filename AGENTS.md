# mootmaker-demo-data

Demo-data Lambdas, each deployed per environment. Renamed from `mootmaker-tools` on 2026-08-29,
when `database-reset` and `database-repair` split out by blast radius — this repository now holds
only tooling that ships as part of the production demo, never anything that can destroy data.

**Start by reading [README.md](README.md).** It lists the tools and the cross-repo dependency on
`database-reset`. Keep it up to date when tools are added, removed, or change behaviour.

## Working here

- **Both tools here are part of the product, not test-only tooling.** `sample-data-generator` and
  `sample-data-topup` seed the public demo — `sample-data-topup` runs weekly in production on an
  EventBridge schedule.
- **`sample-data-generator` depends on `database-reset`, which lives in `mootmaker-api`.** It
  invokes it Lambda-to-Lambda as the first step of every run, via a deterministic function name —
  not by reading `mootmaker-api`'s Terraform state. `database-reset` must be deployed to an
  environment before `sample-data-generator` is deployed or run there. This dependency briefly
  crossed a third repository, `mootmaker-admin-tools` (2026-08-29 to 2026-09-02), before
  `database-reset`/`database-repair` moved into `mootmaker-api` itself — see
  [`mootmaker/designs/admin-tools-into-api.md`](https://github.com/geoffweatherall/mootmaker/blob/main/designs/admin-tools-into-api.md).
  `mootmaker-admin-tools` no longer exists.
- **Neither tool here can destroy data.** That is the whole point of the original split — see
  `../mootmaker-api` for `database-reset`/`database-repair`, the tools that can.
- **Expect `../mootmaker-api` as a sibling checkout.** Deploying and invoking both read the target
  environment's Terraform outputs through its `authenticate.sh`, and `database-reset` itself is now
  part of that same checkout.

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
