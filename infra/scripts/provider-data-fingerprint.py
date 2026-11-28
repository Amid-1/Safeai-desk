#!/usr/bin/env python3
"""Compute the deployment attestation binding without printing the API key."""
import argparse
import hashlib
import os
import struct
from pathlib import Path
from urllib.parse import urlsplit


def fingerprint(parts):
    digest = hashlib.sha256()
    for part in parts:
        data = part.encode("utf-8")
        digest.update(struct.pack(">I", len(data)))
        digest.update(data)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scope", choices=["openai-chat", "anthropic-chat", "openai-embedding", "http-ocr"])
    parser.add_argument("--endpoint", required=True, help="Exact effective HTTPS base URL/endpoint")
    parser.add_argument("--model")
    parser.add_argument("--store", choices=["true", "false"])
    parser.add_argument("--version", help="Effective Anthropic API version")
    parser.add_argument("--dimensions", type=int)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--key-env", help="Name of an existing environment variable containing the API key")
    source.add_argument("--key-file", type=Path, help="Existing local secret file")
    args = parser.parse_args()
    try:
        endpoint = urlsplit(args.endpoint)
        if endpoint.scheme != "https" or not endpoint.hostname or endpoint.username or endpoint.password:
            parser.error("endpoint must be HTTPS without embedded credentials")
        credential = (os.environ[args.key_env] if args.key_env else args.key_file.read_text(encoding="utf-8")).strip()
    except (KeyError, OSError, UnicodeError, ValueError):
        parser.error("cannot read the supplied credential or endpoint")
    if not credential:
        parser.error("empty credential")
    if args.scope == "http-ocr":
        parts = ["ocr", "http", args.endpoint, credential]
    else:
        if not args.model or args.model.strip() != args.model:
            parser.error("the exact effective model is required")
        if args.scope == "openai-chat":
            if args.store is None:
                parser.error("--store is required")
            parts = ["chat", "openai", args.endpoint, args.model, credential, args.store]
        elif args.scope == "anthropic-chat":
            if not args.version:
                parser.error("--version is required")
            parts = ["chat", "anthropic", args.endpoint, args.model, credential, args.version]
        else:
            if args.dimensions is None or args.dimensions <= 0:
                parser.error("--dimensions is required")
            parts = ["embedding", "openai", args.endpoint, args.model, credential, str(args.dimensions)]
    print(fingerprint(parts))


if __name__ == "__main__":
    main()
