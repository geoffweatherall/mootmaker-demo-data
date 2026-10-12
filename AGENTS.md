# mootmaker-demo-data

One Java Lambda per environment that keeps the environment populated with demo people, rooms and
meetings, through the API. One of the three deployable components, with mootmaker-api and mootmaker-webapp.

**Read [README.md](README.md) first**, and [testing-strategy.md](testing-strategy.md) before changing tests.
Keep both true in the same pull request as any change that affects them.

## Working here

- **It is part of the product.** It fills the public demo at www.mootmaker.com and runs there daily.
- **It never deletes anything, and must not learn how.** A request to "just add a reset flag" should be
  read against the
  [demo-data-component design](https://github.com/geoffweatherall/mootmaker/blob/main/designs/archive/demo-data-component.md)
  first. Clearing an environment is mootmaker-api's `database-reset`, run deliberately.
- **Every write goes through the GraphQL API**, never DynamoDB or S3. That is what proves generated data
  passes the API's validation, and why no storage model is shared with mootmaker-api.
- **Every concern must stay idempotent**: people and rooms create only the shortfall, meetings skip any day
  that has one, guaranteed meetings skip any day the person already has one. The acceptance test "a
  second run changes nothing" guards this.
- **Every operation is checked against mootmaker-api's schema** in the unit tests, which read the sibling
  `../mootmaker-api` checkout and fail if it is missing.
- **Formatting is google-java-format; run `mvn -f impl/pom.xml spotless:apply`.** The `code-style` check
  also runs Checkstyle and fails on unused imports and deprecated calls. Editor setup, and what to check
  when formatting silently does nothing:
  [java-code-style.md](https://github.com/geoffweatherall/mootmaker/blob/main/docs/process/java-code-style.md).
  Deviations from Google's rules are in `config/checkstyle-suppressions.xml`, each with its reason.
- **`deploy.sh` needs only the environment name**; endpoints and credentials come from SSM at run time.
  Keep it that way, so this repository never depends on mootmaker-api's Terraform state. Deploy
  mootmaker-api to an environment first.
- **Any caller needs a 900-second client timeout** (`--cli-read-timeout 900`), matching the Lambda's.
- **`release-build.yml` is called by mootmaker-release's `release.yml`.** Dispatch it directly only to
  prove the pipeline itself.

---

## Project-wide rules

This repository is part of **Mootmaker**. The hub repository, checked out as a sibling at
`../mootmaker`, holds what applies everywhere:

- How work is done: [`../mootmaker/docs/process/README.md`](https://github.com/geoffweatherall/mootmaker/blob/main/docs/process/README.md).
  Read it before any non-trivial work.
- How the system fits together: [`../mootmaker/docs/overview/`](https://github.com/geoffweatherall/mootmaker/tree/main/docs/overview).
- What to do when something is wrong: [`../mootmaker/docs/runbooks/`](https://github.com/geoffweatherall/mootmaker/blob/main/docs/runbooks/README.md).

The short version:

- Work of any real size starts with a **design** (`../mootmaker/designs/`); a bug or small change starts
  with a **GitHub issue in this repository**, so `Closes #N` works.
- Everything lands on a **branch** through a **pull request**. Reading the diff is the review; merging is
  the approval.
- **A green acceptance run against a real deployed environment** is the definition of working.
- **`test` and `production` change only through a release** (`release.yml` in mootmaker-release). Use an
  ephemeral environment for your own work, and tear it down when you finish.
- **Say what something costs in AWS before creating it.** Under NZ$1 one-off, go ahead and state it;
  more, or anything recurring, ask first.
- **Keep documentation and comments true and current.** If a change makes a document wrong, fixing it is
  part of the change. Describe what is, not how it got here; a comment says what and why.
- **Verify against reality, not your own output**, and **say what actually happened**, including
  failures and skipped steps.

Something not installed? [`../mootmaker/tools/workstation/check.sh`](https://github.com/geoffweatherall/mootmaker/blob/main/tools/workstation/check.sh).

`CLAUDE.md` in this repository is a symlink to this file.
