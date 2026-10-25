# OpenFinalShell Shared Schema

This directory is the language-neutral data contract for the current desktop client: command-library export, the export envelope, LAN sync, monitor snapshots, and port traffic.

`schemas/` holds the JSON Schema documents. `fixtures/` holds protocol sample values. After changing a schema, run `npm run generate-schema`. The script rewrites `src/shared/generated/sharedSchema.ts` and `protocolModels.ts`; do not keep a second registry by hand.

These schemas describe the data the application exchanges. They do not replace the Electron IPC contract in `src/shared/ipc.ts`.
