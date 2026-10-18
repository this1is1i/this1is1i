"""Independently check a v1 export's compact, sorted-key UTF-8 payload digest (not a signature)."""
import hashlib
import json
from pathlib import Path
import sys


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON object key")
        result[key] = value
    return result


def reject_number(value):
    raise ValueError("Audit v1 requires integer metrics and versions")


def canonical_object(value):
    if isinstance(value, dict):
        return {key: canonical_object(value[key]) for key in sorted(value, key=lambda key: key.encode("utf-16-be"))}
    if isinstance(value, list):
        return [canonical_object(item) for item in value]
    return value


def verify(path):
    if path.stat().st_size > 8 * 1024 * 1024:
        raise ValueError("Report exceeds v1 export budget")
    report = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object,
                        parse_float=reject_number, parse_constant=reject_number)
    if report["schemaVersion"] != "refactor-control-plane.audit.v1":
        raise ValueError("Unsupported audit schema")
    manifest = report["integrity"]
    if manifest["algorithm"] != "SHA-256" or manifest["canonicalization"] != "SORTED_OBJECT_KEYS_UTF8_COMPACT_JSON" or manifest["signature"] is not False:
        raise ValueError("Unsupported integrity manifest")
    payload = report["payload"]
    canonical = json.dumps(canonical_object(payload), ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
    digest = hashlib.sha256(canonical).hexdigest()
    if digest != manifest["payloadSha256"]:
        raise ValueError("Payload digest mismatch")
    print(json.dumps({"payloadSha256": digest, "planScope": payload["plan"]["scope"],
                      "tasks": len(payload["tasks"]), "executions": len(payload["executions"]),
                      "evidence": len(payload["evidence"]), "integrations": len(payload["integrations"])}))


if __name__ == "__main__":
    verify(Path(sys.argv[1]))
