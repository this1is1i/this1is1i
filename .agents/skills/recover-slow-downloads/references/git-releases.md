# Git repositories and release assets

## Choose the smallest artifact

- Use a release asset when the user needs a published binary.
- Use a source archive when Git history, submodules, and LFS objects are unnecessary.
- Use shallow or filtered clone when the required revision and server support allow it.
- Treat submodules and Git LFS as separate downloads with separate origins and credentials.

## Recovery rules

- Inspect `.git`, refs, object count, active Git/LFS processes, and partial packs before retrying an interrupted clone or fetch.
- Preserve an existing repository unless its ownership and partial state are understood.
- Prefer official Git host archives, release endpoints, or documented mirrors before URL-rewriting proxies.
- Never place private clone URLs, embedded credentials, or signed release URLs into public acceleration services.

## Verification

- Verify the requested commit/tag and object availability.
- Verify submodule commits and LFS OIDs.
- For release assets, use publisher hashes or signatures rather than trusting the release filename.
- For an archive substituted for a clone, state that Git history and possibly submodules/LFS content are absent.
