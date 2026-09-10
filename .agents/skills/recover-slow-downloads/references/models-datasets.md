# Models and datasets

Use ecosystem-native clients when manifests, revisions, shards, caching, gated access, or resumable transfer are involved.

## Source classes

- Hugging Face Hub repositories and CDN/object-storage files.
- ModelScope repositories and SDK downloads.
- OpenXLab or other institutionally operated model hubs.
- Git and Git LFS repositories.
- Direct object-storage URLs and publisher archives.

## Identity mapping

Before switching hubs, record owner/repository, revision or commit, filenames, shard index, total size, model architecture, dtype, quantization, tokenizer/config files, dataset subset/split, license, and gating terms. An alternate hub copy is a substitution unless immutable identity is proven.

## Routing rules

- Prefer the official hub client and its resume/cache behavior over manually downloading individual shards.
- Use an alternate endpoint only when the client officially supports it and keep endpoint variables process-scoped.
- Do not send a Hugging Face or other hub token to a public relay. Gated or private artifacts must remain on an authorized official route.
- ModelScope or another official hub can be preferred when it independently hosts the intended public artifact, but repository mapping and revision equality must be checked.
- For Git LFS, verify that pointer files were replaced by objects and compare each LFS OID and declared size.

## Completion checks

- Verify every file referenced by the model or dataset manifest exists.
- Check cryptographic hashes or LFS OIDs where available.
- Load configuration and tokenizer, open representative dataset metadata, and perform the smallest non-destructive framework load appropriate to the artifact.
- Report if byte identity with the original source could not be proven.
