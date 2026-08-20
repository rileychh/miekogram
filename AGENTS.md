## Build and Test Workflow

- Prefer the local remote build: push the working branch to the configured SSH remote with `ssh miekogram`, build in `/root/src/miekogram`, and download the resulting APK artifact for installation/testing.
- Create a dedicated build branch from `main`; do not push build commits directly to `main`.
