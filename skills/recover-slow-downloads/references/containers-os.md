# Containers and operating-system repositories

## Containers

- Distinguish registry authentication, manifest retrieval, platform selection, and individual layer transfer.
- Prefer an official alternate registry or publisher mirror and pull by immutable digest when possible.
- Compare image manifest digest, selected platform, config digest, and layer digests after substitution.
- Container-daemon registry mirrors, insecure registries, certificate stores, and system services are machine-level configuration. Do not change them without separate explicit permission, backup, rollback, and validation.

## Operating-system repositories

- Confirm distribution, release, architecture, repository component, and signing key before switching.
- Prefer a one-command repository override or project/container build argument when supported.
- Do not edit machine package-source files, keyrings, certificate stores, or system proxy settings by default.
- Preserve signature verification and repository metadata checks. A faster unsigned source is not an acceptable fallback.

## Completion checks

- Containers: inspect the final repo digest and platform.
- OS packages: verify signed metadata, exact package version/architecture, and the package manager's transaction result.
- Container builds: rerun the relevant build stage with cache behavior made explicit.
