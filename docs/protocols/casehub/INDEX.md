# Platform Protocols — casehub

| File | Rule Summary | Applies To |
|------|-------------|------------|
| [cross-repo-source-verification.md](cross-repo-source-verification.md) | Verify source repo before changing foundation tier types | WorkerFunction, WorkerResult, Worker changes |
| [virtual-thread-handler-convention.md](virtual-thread-handler-convention.md) | All @ConsumeEvent handlers: @RunOnVirtualThread + void | Any handler, no Uni/blocking=true |
| [plan-type-module-boundary.md](plan-type-module-boundary.md) | Plan-definition types in engine-api; execution types in engine-common | engine-api, engine-common — new plan/execution types |
