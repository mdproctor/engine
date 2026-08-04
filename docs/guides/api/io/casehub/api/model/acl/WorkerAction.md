# io.casehub.api.model.acl.WorkerAction

**Package:** `io.casehub.api.model.acl`

**Kind:** `enum`

## Fields

### `aclAction` (`AclAction`)

## Enum Constants

### `ADMIN` (`io.casehub.api.model.acl.WorkerAction`)

### `READ_CONTEXT` (`io.casehub.api.model.acl.WorkerAction`)

### `READ_EVENT_LOG` (`io.casehub.api.model.acl.WorkerAction`)

### `READ_PLAN_ITEMS` (`io.casehub.api.model.acl.WorkerAction`)

### `SIGNAL_CASE` (`io.casehub.api.model.acl.WorkerAction`)

### `SPAWN_SUB_CASE` (`io.casehub.api.model.acl.WorkerAction`)

### `WRITE_CONTEXT` (`io.casehub.api.model.acl.WorkerAction`)

## Constructors

### `private WorkerAction(AclAction aclAction)`

#### Parameters

- `aclAction` (`AclAction`)

## Methods

### `public AclAction aclAction()`

### `public io.casehub.api.model.acl.AclGrant toAclGrant()`

### `public static io.casehub.api.model.acl.WorkerAction valueOf(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public static io.casehub.api.model.acl.WorkerAction[] values()`
