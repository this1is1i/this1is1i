# Python ecosystems

## Classify the failing stage

Separate index metadata, source distribution, wheel, build dependency, Conda package, and post-install binary/model downloads. Changing the PyPI index does not accelerate binaries fetched later by packages such as browser, accelerator, or model tooling.

## Per-command routing

- For pip, prefer `python -m pip` and use `--index-url` for one operation. Avoid adding an untrusted `--extra-index-url`; mixed indexes can create dependency-confusion risk.
- For uv, Poetry, pip-tools, pipx, Conda, and Mamba, inspect the installed version's help before applying a remembered flag. Keep the chosen source command-scoped or in project-owned configuration.
- For Conda/Mamba, prefer an explicit channel with channel override for the current operation. Do not mutate the user's `.condarc` by default.
- Preserve lock files and exact versions. A mirror change must not silently change dependency resolution.

## Verification

- Prefer locked hashes, wheel hashes, package signatures where supported, and exact package/version/platform tags.
- Confirm a downloaded wheel matches Python ABI, OS, architecture, and accelerator requirements.
- After source substitution, run the intended import or build check in the project environment.

## Catalog fields needed

For every Python or Conda mirror record: index kind, simple-index URL, artifact host behavior, supported channels, synchronization delay, TLS status, authentication support, official documentation, and verification date.
