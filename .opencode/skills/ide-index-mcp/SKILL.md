---
name: ide-index-mcp
description: Use when `intellij-index_ide_*` JetBrains IDE index and refactoring tools are available and work requires semantic code navigation, reference analysis, hierarchy tracing, safe refactors, IDE diagnostics, or project-aware symbol lookup.
---

# IDE Index MCP

JetBrains IDE tools understand symbols, imports, overrides, inheritance, and refactors. Prefer them for semantic operations; use `Glob`, `Grep`, and `Read` for file-pattern, regex, and raw-text work.

## Availability Gate

Only use this skill when the tool manifest exposes `intellij-index_ide_*` tools such as `intellij-index_ide_find_definition` or `intellij-index_ide_find_references`.

If those tools are not present, fall back to `Glob`, `Grep`, `Read`, and careful manual edits instead of inventing IDE calls.

## Core Rule

Prefer `intellij-index_ide_*` tools for:
- definitions, references, implementations, super methods
- type hierarchy, call hierarchy, diagnostics, build errors
- rename, move, safe delete, optimize imports, reformat
- class and exact-word IDE index lookups

Prefer built-in tools for:
- `Glob`: file-name patterns
- `Grep`: regex or broad text search
- `Read`: reading known files

If notes from another client mention `ide_find_references`-style names, use the matching `intellij-index_ide_*` wrapper name in this environment.

## Quick Choice

| Task | Preferred tool | Why |
|---|---|---|
| Find all semantic usages | `intellij-index_ide_find_references` | Finds real references, not text matches |
| Jump to definition | `intellij-index_ide_find_definition` | Resolves imports and symbols |
| Find implementations | `intellij-index_ide_find_implementations` | Handles interfaces and abstract members |
| Trace callers/callees | `intellij-index_ide_call_hierarchy` | Gives project-aware call trees |
| Understand inheritance | `intellij-index_ide_type_hierarchy` | Shows supertypes and subtypes |
| Rename safely | `intellij-index_ide_refactor_rename` | Updates references project-wide |
| Move a source file | `intellij-index_ide_move_file` | Updates imports and references |
| Delete safely | `intellij-index_ide_refactor_safe_delete` | Checks usages before delete |
| Check code or build problems | `intellij-index_ide_diagnostics` | Includes IDE analysis and build/test results |
| Find a class by name | `intellij-index_ide_find_class` | Uses IDE class index |
| Find an exact word | `intellij-index_ide_search_text` | Uses IDE word index |
| Regex search | `Grep` | IDE text search is exact-word, not regex |
| Read a file you already know | `Read` | Faster and simpler than IDE navigation |

## Parameter Rules

1. In this environment's wrapper, pass `project_path` as the project root.
2. Use project-relative paths for `file` unless the tool explicitly allows absolute paths.
3. `line` and `column` are 1-based.
4. Put `column` on the symbol, not whitespace.
5. Many lookup tools support pagination via `cursor`; keep paging if results are incomplete.

## Working Patterns

### Understand a symbol
1. `intellij-index_ide_find_definition`
2. `intellij-index_ide_find_references`
3. `intellij-index_ide_call_hierarchy` or `intellij-index_ide_type_hierarchy` when behavior depends on callers or inheritance

### Refactor safely
1. `intellij-index_ide_find_references`
2. `intellij-index_ide_refactor_rename`, `intellij-index_ide_move_file`, or `intellij-index_ide_refactor_safe_delete`
3. `intellij-index_ide_diagnostics` or `intellij-index_ide_build_project` to verify the result

### Search efficiently
1. `intellij-index_ide_find_class` for classes/interfaces
2. `intellij-index_ide_find_file` for known filenames
3. `intellij-index_ide_search_text` for exact words
4. `Grep` when you need regex, fuzzy content search, or non-indexed text

## Common Mistakes

1. Using `Grep` for references. Text search misses semantic usages and adds false positives.
2. Using manual find/replace instead of `intellij-index_ide_refactor_rename`.
3. Moving Java files outside the IDE refactor tool and breaking imports or package declarations.
4. Passing absolute paths to `file` for tools that expect project-relative paths.
5. Using `intellij-index_ide_search_text` like regex search. It is exact-word indexed search.
6. Forgetting pagination when references or search results are truncated.

## Java Notes

- This repo is Java-heavy, so `intellij-index_ide_find_class`, `intellij-index_ide_type_hierarchy`, `intellij-index_ide_find_super_methods`, and `intellij-index_ide_refactor_safe_delete` are especially useful.
- For Java symbol-based lookups, use fully qualified names when the tool supports `language` plus `symbol`.
- `intellij-index_ide_optimize_imports` is a cheap cleanup step after targeted edits.

## Reference

Full parameter summaries and tool coverage: `references/tools-reference.md`
