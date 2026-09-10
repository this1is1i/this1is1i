# Mirror catalog

This file owns provider facts and verified endpoints. Ecosystem references own command syntax. Do not add an endpoint until its official service page and a representative public artifact have been checked.

## Entry schema

Record each verified service using this structure:

| Field | Required content |
|---|---|
| Provider | Legal/institutional operator and service name |
| Trust tier | Publisher, institutional, equivalent official ecosystem, or community proxy |
| Ecosystem | PyPI, Conda, npm, Maven, crates, container, model hub, OS repo, generic file, etc. |
| Endpoint/template | Exact endpoint or rewrite pattern, without credentials |
| Geographic purpose | Mainland China, global, regional, or publisher-specific |
| Authentication | Unsupported, native credentials, separate credentials, or public-only |
| Integrity behavior | Hash/signature/digest behavior and redirect hosts |
| Synchronization | Known delay, exclusions, or retention limits |
| Official documentation | Direct provider documentation or status URL |
| Last verified | ISO date plus the tested artifact/metadata probe |
| Status | Verified, degraded, quarantined, or retired |

## Candidate providers awaiting verification

The following names define research coverage, not approved endpoints:

| Provider/service | Expected coverage to investigate | Current status |
|---|---|---|
| Tsinghua University TUNA | Language registries, Linux distributions, scientific packages | Awaiting source verification |
| USTC Open Source Software Mirror | Language registries, Linux distributions, generic mirrors | Awaiting source verification |
| Beijing Foreign Studies University mirror | Language registries and Linux distributions | Awaiting source verification |
| Shanghai Jiao Tong University mirror | Language registries, Linux distributions, container/model-related services | Awaiting source verification |
| Nanjing University mirror | Language registries and Linux distributions | Awaiting source verification |
| Alibaba Cloud mirror services | Package ecosystems, OS repositories, containers | Awaiting source verification |
| Tencent Cloud mirror services | Package ecosystems, OS repositories, containers | Awaiting source verification |
| Huawei Cloud mirror services | Package ecosystems, OS repositories, containers | Awaiting source verification |
| ModelScope | Public models and datasets hosted by ModelScope | Awaiting repository and revision mapping |
| OpenXLab | Public models and datasets hosted by the service | Awaiting repository and revision mapping |
| Hugging Face community relays | Public Hub artifacts only | Untrusted until operator and integrity behavior are verified |
| GitHub community proxies | Public clones, archives, or release assets only | Last-resort; no credentials; awaiting verification |

## Quarantine criteria

Mark an entry quarantined when ownership is unclear, HTTPS validation fails, redirects reach unrelated operators, content is injected, status is persistently unreliable, credentials would cross a trust boundary, or artifact identity cannot be checked. Retain the reason and date; do not recommend a quarantined entry.
