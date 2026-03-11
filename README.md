# OpenCreativePlus Engine

A high-performance visual scripting engine for Minecraft that enables players to create interactive experiences by physically placing blocks in a coding world.

## Project Structure

This is a Gradle multi-module project with the following modules:

### ocp-api
Public interfaces and contracts. This module has no dependencies and defines the core API that all other modules depend on.

**Key Interfaces:**
- `INode`, `IAction`, `ICondition`, `IValue`, `IEvent` - Node type hierarchy
- `ExecutionContext`, `VariableScope` - Execution runtime
- `NodeRegistry` - Node registration system
- `PlotManager`, `ModeManager` - Plot management
- `Plot`, `PlotSettings`, `PlotMetadata`, `PlotMode` - Data models

### ocp-core
Core execution engine implementing the AST compiler, coroutine-based execution, and persistence layer.

**Dependencies:**
- ocp-api
- Kotlin coroutines
- MongoDB driver

### ocp-plugin
Paper/Bukkit integration layer providing world management, event listeners, and commands.

**Dependencies:**
- ocp-api
- ocp-core
- Paper API
- SlimeWorldManager API (optional)

### ocp-editor
Visual editor features for enhanced development experience.

**Dependencies:**
- ocp-api
- ocp-core
- ocp-plugin

## Module Dependency Graph

```
ocp-api (no dependencies)
   ↑
   ├── ocp-core (depends on: ocp-api)
   ↑
   ├── ocp-plugin (depends on: ocp-api, ocp-core, Paper API)
   ↑
   └── ocp-editor (depends on: ocp-api, ocp-core, ocp-plugin)
```

## Building

```bash
./gradlew build
```

## Requirements

- Java 17 or higher
- Kotlin 1.9.20
- Paper 1.20.1 or compatible

## Architecture

The engine uses:
- **Multi-module architecture** for clean separation of concerns
- **Kotlin coroutines** for non-blocking script execution
- **MongoDB** for persistence
- **AST-based compilation** for fast execution
- **Watchdog system** for anti-lag protection

## License

TBD
