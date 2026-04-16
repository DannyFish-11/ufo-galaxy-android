# UGCP Android Alignment (PR-2 counterpart)

This folder freezes the Android-side alignment to the shared UGCP control-plane vocabulary.

- [`ANDROID_UGCP_CONSTITUTION.md`](./ANDROID_UGCP_CONSTITUTION.md): Android runtime-profile constitution, canonical vocabulary, identity/session freeze, phase mapping, and cross-repo control semantics.
- [`CROSS_REPO_HOMOMORPHIC_MAPPING.md`](./CROSS_REPO_HOMOMORPHIC_MAPPING.md): official cross-repository homomorphic mapping for the most important architectural concepts shared between `ufo-galaxy-android` and `ufo-galaxy-realization-v2`. Distinguishes canonical match, partial match, transitional alias, and unresolved divergence for participant/device/runtime/capability, device-domain vs node-domain, session families, delegated execution signals, protocol alignment, registry/facade/cache/adapter/authority surfaces, and runtime identity.
- [`../../app/src/main/java/com/ufo/galaxy/protocol/UgcpSharedSchemaAlignment.kt`](../../app/src/main/java/com/ufo/galaxy/protocol/UgcpSharedSchemaAlignment.kt): lightweight Android runtime schema-family alignment registry for canonical identity/control/runtime/coordination/truth mapping, canonical concept boundary glossary (`canonicalConceptVocabulary`), authoritative-vs-observational truth/event semantics, and replay/recovery-facing alignment notes.
