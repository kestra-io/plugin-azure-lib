# AGENTS.md

## What

- Provides plugin components under `io.kestra.plugin.azure.shared`.
- Includes classes such as `BatchService`, `DefaultJobResumeStrategy`, `JobResumeStrategy`, `BlobOutput`.

## Why

- What user problem does this solve? Teams need a reliable way to operate azure lib from orchestrated workflows instead of relying on manual console work, ad hoc scripts, or disconnected schedulers.
- Why would a team adopt this plugin in a workflow? It keeps azure lib steps in the same Kestra flow as upstream preparation, approvals, retries, notifications, and downstream systems.
- What operational/business outcome does it enable? It reduces manual handoffs and fragmented tooling while improving reliability, traceability, and delivery speed for processes that depend on azure lib.

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
