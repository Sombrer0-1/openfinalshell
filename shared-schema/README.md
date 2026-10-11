# OpenFinalShell Shared Schema

This directory is the language-neutral data contract for the desktop client.
The existing desktop wire formats remain authoritative: changes here must be
backwards compatible with the TypeScript parsers in `src/main`.

`schemas/` contains JSON Schema documents and `fixtures/` contains protocol
golden values. Run `npm run generate-schema` after changing a schema to refresh
the generated TypeScript metadata in `src/shared/generated/`.

The schemas describe data exchanged between clients. They do not replace the
existing Electron IPC contract in `src/shared/ipc.ts`.
