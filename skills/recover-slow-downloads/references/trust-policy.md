# Trust and integrity policy

Read this file before routing through a third party, changing artifact origin, or handling an authenticated source.

## Trust tiers

1. **Publisher-controlled:** original registry, CDN, release page, object store, or official mirror.
2. **Institutional mirror:** identified university, public research network, cloud provider, or ecosystem foundation with an official service page.
3. **Equivalent official ecosystem:** a second official hub that hosts an explicitly mapped copy of the same public artifact.
4. **Community proxy:** independently operated relay or URL-rewriting service. Use only as a last resort for public data.

## Credential boundary

- Send credentials only to the hostname and service for which they were issued.
- Do not embed tokens, cookies, authorization headers, signed query strings, private repository names, or internal paths in public proxy requests.
- Do not copy an authenticated URL into logs or the final response. Redact secrets and signature parameters.
- If an official mirror requires separate authentication, obtain and use credentials for that mirror rather than forwarding credentials from the original service.

## Integrity requirements

Prefer verification evidence in this order:

1. Publisher signature or transparency record.
2. Publisher-supplied cryptographic hash.
3. Immutable ecosystem identifier: container digest, Git commit, Git LFS OID, package lock hash, or model manifest plus shard hashes.
4. Size and functional load test when no stronger evidence exists. Explicitly state that byte identity was not independently proven.

Never disable TLS verification, signature checks, package hash enforcement, or checksum databases. A failed integrity check invalidates the artifact; do not repeatedly fetch from increasingly untrusted sources without revisiting the expected identity.

## Substitution boundary

Before using an alternate hub or mirror, match all applicable fields: owner, repository, package name, version, revision or commit, filename, architecture, operating system, accelerator, dtype, quantization, tokenizer, license, and shard manifest. Similar naming is not evidence of equivalence.

## Configuration boundary

Use command flags first, then temporary process environment, then files stored inside the current project. User-level and system-level proxy, DNS, certificate, package-manager, container-daemon, shell-profile, registry, and environment-variable changes require separate explicit permission.
