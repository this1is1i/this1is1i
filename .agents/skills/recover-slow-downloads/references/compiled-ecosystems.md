# Compiled-language ecosystems

Use this file for Maven, Gradle, Go modules, Cargo/crates, NuGet, RubyGems, Composer, and similar resolvers until a dedicated reference is justified.

## Shared rules

- Identify metadata, source archive, binary package, toolchain, plugin, and post-install asset as separate download stages.
- Preserve exact versions, lock files, checksums, signatures, module databases, and repository priority.
- Prefer per-command options or project-owned configuration: Maven alternate settings file, Gradle project repository declaration, project `.cargo/config.toml`, NuGet project config, or process-scoped Go environment.
- Do not use an insecure HTTP repository or disable signature/checksum services.

## Ecosystem-specific boundaries

- **Maven/Gradle:** distinguish plugin repositories from dependency repositories; mirrors may need both. Verify POM/module metadata and artifact checksums.
- **Go:** keep checksum database validation unless the module is intentionally private. A `GOPROXY` fallback list changes routing, not module identity.
- **Cargo:** keep the crates index and crate download endpoint consistent. Verify `Cargo.lock` checksums.
- **NuGet:** keep package source mapping and signatures intact; avoid broadening a private package name to public feeds.
- **Ruby/Composer:** preserve lock data and distinguish Git/VCS sources from registry archives.

Before adding exact commands, verify them against the installed client version and record the relevant source in `mirror-catalog.md`.
