# Simple Analytics for Kotlin

- Keep `CHANGELOG.md` current: add a one-line entry under `Unreleased` in the same commit as any user-visible change, as `Area: change (#PR, thanks @user)`. Leave out refactors, test-only changes and dependency bumps users cannot feel.
- Releases run through the **Release** workflow in GitHub Actions: pick patch, minor or major. Never edit the version by hand or push release tags. An empty `Unreleased` section stops the release.
- Before handing off changes, run `./gradlew allTests`.
