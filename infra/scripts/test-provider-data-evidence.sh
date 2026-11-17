#!/usr/bin/env bash
set -Eeuo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -r -- "$tmp_dir"' EXIT
package_dir=ru/safeai/gateway/ai/provider
compiler=(javac --release 21)
if ! command -v javac >/dev/null; then
  compiler=(java -m jdk.compiler/com.sun.tools.javac.Main -source 21 -target 21)
fi
"${compiler[@]}" -d "$tmp_dir" \
  "$repo_root/backend/src/main/java/$package_dir/ProviderDataEvidence.java" \
  "$repo_root/backend/src/test/java/$package_dir/ProviderDataEvidenceSelfCheck.java"
binding="$(SAFEAI_ASSURANCE_TEST_KEY=test-key python3 "$repo_root/infra/scripts/provider-data-fingerprint.py" \
  openai-chat --endpoint https://api.openai.com/v1 --model test-model --store false \
  --key-env SAFEAI_ASSURANCE_TEST_KEY)"
java -cp "$tmp_dir" ru.safeai.gateway.ai.provider.ProviderDataEvidenceSelfCheck "$binding"
