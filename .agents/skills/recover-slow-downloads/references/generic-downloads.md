# Generic downloads

Use this reference for plain HTTP(S), object storage, release assets, archives, installers, and large files not owned by a package manager.

## Diagnose

- Resolve the final URL and record every redirect hostname.
- Check status, content length, content type, ETag or last-modified value, time to first byte, and `Accept-Ranges`.
- Confirm destination free space and whether a partial file belongs to the same URL and identity.
- Treat HTML error pages saved with an artifact filename as failures.

## Choose a transfer method

| Tool | Prefer when | Important boundary |
|---|---|---|
| `curl`/`curl.exe` | Portable one-off transfer, redirects, headers, resume | Verify the installed version supports every selected retry flag. |
| `wget` | Recursive or conventional Unix download workflows | Keep output path explicit and do not rely on implicit filename parsing. |
| `aria2c` | Large public file and server supports byte ranges | Keep concurrency modest; multiple connections do not repair a slow origin. |
| PowerShell BITS | Stable Windows background transfer | Confirm actual job state after wrapper timeout; a job may still be running. |
| Ecosystem-native client | Artifact has manifests, shards, cache rules, or authentication | Prefer this over a generic downloader when it preserves integrity metadata. |

## Resume rules

- Resume only when the remote object identity is stable. Compare ETag, last-modified, expected size, checksum, or immutable URL where possible.
- Check Range support before segmented download. Some servers ignore Range and return the complete body.
- Use a separate temporary filename and rename only after verification.
- Bound retries and add backoff. Repeated immediate retries can trigger throttling and corrupt operational diagnosis.

## Command templates

Treat these as patterns and check the local tool's `--help` before use:

```text
curl.exe --fail --location --continue-at - --output <partial-path> <url>
wget --continue --output-document=<partial-path> <url>
aria2c --continue=true --split=4 --max-connection-per-server=4 --out=<name> <url>
```

Add retry flags only after distinguishing transient network errors from authentication, quota, missing files, and checksum failures.
