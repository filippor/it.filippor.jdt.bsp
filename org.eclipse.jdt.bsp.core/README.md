# org.eclipse.jdt.bsp.core

Headless BSP implementation module for Eclipse and future reuse by `jdtls`.

## Scope

- BSP transport and JSON-RPC framing utilities.
- Source-set model extraction from BSP targets.
- BSP4J dependency wiring (`ch.epfl.scala:bsp4j:2.2.0-M4.TEST`).
- No persistent workspace/project synchronization by default.

## Why this module exists

This bundle intentionally has no UI contributions. UI actions and Eclipse menu integration live in `org.eclipse.jdt.bsp.client`.

## Notes for future jdtls integration

- Keep this module free from `org.eclipse.ui` dependencies.
- Add protocol request/response orchestration APIs here first.
- Let IDE/UI or LSP layers call this core API.
