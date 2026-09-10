---
name: recover-slow-downloads
description: Diagnose stalled, extremely slow, or unreachable dependency, model, dataset, container, Git LFS, release, and other large-file downloads and choose safe resumable alternatives. Use when a user requests a mirror, proxy, acceleration, or resumable download; a download makes no observable progress; or GitHub, Hugging Face, Docker Hub, package registries, and similar sources are unreliable from mainland China. Prefer official sources and trusted mirrors, treat public proxies as a last resort, and do not invoke for healthy routine installs.
---

# Recover Slow Downloads

Recover downloads by identifying the failing boundary, selecting the safest viable route, preserving partial progress, and verifying the resulting artifact. Keep every change command-scoped, temporary, or project-scoped.

## Core workflow

1. Inspect before retrying.
   - Identify the original command, runtime, shell, working directory, destination, expected artifact, and authentication requirements.
   - Check whether a process is still active and whether a partial file, package cache, Git object, container layer, or model shard is growing.
   - Treat wrapper timeouts and missing terminal output as unknown state until the actual process and target are checked.
2. Classify the download.
   - Separate metadata/index access from artifact transfer.
   - Identify the protocol and ecosystem: HTTP file, package manager, Git, Git LFS, model hub, object storage, container registry, or OS repository.
   - Distinguish network failure from authentication, authorization, quota, missing revision, insufficient disk, file locking, or checksum failure.
3. Probe the route with the smallest safe request.
   - Prefer HEAD, metadata, manifest, or a small ranged GET where supported.
   - Observe DNS, TCP/TLS, redirects, HTTP status, time to first byte, content length, Range support, and rate-limit headers.
   - Do not send credentials to a candidate mirror or proxy during probing.
4. Choose a fallback using this order:
   1. Original official source with resume, bounded retry, or lower concurrency.
   2. Official alternate CDN, registry, archive, or vendor-provided mirror.
   3. Trusted university, cloud-provider, or ecosystem mirror.
   4. Equivalent official ecosystem source after repository, revision, file, and license mapping.
   5. Public community proxy only for public artifacts, without credentials, and with strong integrity verification.
5. Execute narrowly.
   - Prefer per-command flags, temporary environment variables, project configuration, and explicit destination paths.
   - Preserve valid partial downloads when the server supports resume.
   - Avoid changing user-level or system-level package-manager, proxy, DNS, container-daemon, certificate, or shell settings.
6. Verify completion.
   - Re-run the original acceptance scenario or load the artifact through its intended tool.
   - Verify publisher checksum or signature when available. For Git LFS, compare the object ID and size. For containers, compare the digest. For sharded models, verify the manifest and every referenced shard.
   - Report the selected source, revision/version, destination, verification evidence, temporary configuration, and remaining trust limitations.

## Safety invariants

- Never place tokens, cookies, signed URLs, private package names, or private repository paths into a public proxy URL.
- Never weaken TLS validation, signature checks, package hash enforcement, `GOSUMDB`, or equivalent integrity mechanisms merely to make a download succeed.
- Never silently substitute a similarly named model, package, tag, branch, architecture, quantization, or dataset revision.
- Never mix multiple package indexes without evaluating dependency-confusion risk. Prefer one authoritative index for a single operation.
- Never assume a mirror contains the same bytes because names and versions match. Verify immutable identifiers or hashes.
- Do not persist a mirror after the task unless the user explicitly requests a project-level configuration change.
- After any timeout or interruption, inspect process and destination state before retrying.

Read [references/trust-policy.md](references/trust-policy.md) before using any third-party mirror, proxy, alternate model hub, or source that requires credentials.

## Reference routing

Load only the references needed for the current download:

| Download class | Reference |
|---|---|
| Plain HTTP(S), resume, segmented transfer, Windows BITS | [references/generic-downloads.md](references/generic-downloads.md) |
| pip, uv, Poetry, Conda, Mamba | [references/python.md](references/python.md) |
| npm, pnpm, Yarn, Bun | [references/javascript.md](references/javascript.md) |
| Maven, Gradle, Go, Cargo, NuGet and other compiled ecosystems | [references/compiled-ecosystems.md](references/compiled-ecosystems.md) |
| Hugging Face, ModelScope, OpenXLab, Git LFS models and datasets | [references/models-datasets.md](references/models-datasets.md) |
| Git repositories, source archives, and release assets | [references/git-releases.md](references/git-releases.md) |
| Container registries and operating-system repositories | [references/containers-os.md](references/containers-os.md) |
| Candidate Chinese mirrors and provider capabilities | [references/mirror-catalog.md](references/mirror-catalog.md) |

## Catalog maintenance

Treat mirror endpoints as time-sensitive data. Before adding or recommending one:

1. Verify it against the provider's official documentation or status page.
2. Record the provider, ecosystem, endpoint or command template, trust tier, geographic scope, authentication support, synchronization caveats, official documentation URL, and verification date.
3. Test with public metadata or a small public artifact before relying on it for a large transfer.
4. Remove or quarantine entries that redirect unexpectedly, inject content, require disabled TLS checks, have unclear ownership, or cannot be verified.
5. Keep commands in the ecosystem reference and provider facts in `mirror-catalog.md`; do not duplicate the same endpoint across files.

## Current implementation boundary

The initial version defines workflow, safety policy, reference ownership, and catalog schema. Mirror endpoints and automated probing scripts must be added only after source verification and representative testing.
