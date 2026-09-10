# JavaScript ecosystems

## Classify the failing stage

Separate registry metadata, package tarball, Git dependency, lifecycle script, runtime binary, browser bundle, Electron/Cypress/Playwright asset, and native-addon prebuild. A registry mirror may solve only the first two stages.

## Per-command routing

- Pass the registry explicitly to npm, pnpm, Yarn, or Bun for the current command where supported by the installed version.
- Avoid writing user-level `.npmrc` or global client settings. Use a project-owned config only when the user requests persistent project behavior.
- Inspect lockfile URLs and integrity values before and after changing the registry. Do not regenerate a lockfile merely to hide an unreachable tarball.
- Treat package-specific binary mirror environment variables as separate source changes; verify their official support and scope before use.

## Verification

- Preserve and enforce lockfile integrity hashes.
- Confirm native binaries match OS, CPU architecture, runtime ABI, libc, and accelerator requirements.
- Run the project's normal install/build acceptance check after download recovery.

## Catalog fields needed

Record registry base URL, tarball-host behavior, scoped-package support, authentication support, lockfile implications, synchronization caveats, official documentation, and verification date.
