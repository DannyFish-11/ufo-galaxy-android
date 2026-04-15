# UGCP Android Alignment (PR-2 counterpart)

This folder freezes the Android-side alignment to the shared UGCP control-plane vocabulary.

- [`ANDROID_UGCP_CONSTITUTION.md`](./ANDROID_UGCP_CONSTITUTION.md): Android runtime-profile constitution, canonical vocabulary, identity/session freeze, phase mapping, and cross-repo control semantics.
- [`../../app/src/main/java/com/ufo/galaxy/protocol/UgcpSharedSchemaAlignment.kt`](../../app/src/main/java/com/ufo/galaxy/protocol/UgcpSharedSchemaAlignment.kt): lightweight Android runtime schema-family alignment registry for canonical identity/control/runtime/coordination/truth mapping, canonical concept boundary glossary (`canonicalConceptVocabulary`), authoritative-vs-observational truth/event semantics, and replay/recovery-facing alignment notes.
