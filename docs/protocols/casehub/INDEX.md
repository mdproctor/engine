# Platform Protocols — casehub

| File | Rule Summary | Applies To |
|------|-------------|------------|
| [cross-repo-source-verification.md](cross-repo-source-verification.md) | Verify source repo before changing foundation tier types | WorkerFunction, WorkerResult, Worker changes |
| [virtual-thread-handler-convention.md](virtual-thread-handler-convention.md) | All @ConsumeEvent handlers: @RunOnVirtualThread + void | Any handler, no Uni/blocking=true |
