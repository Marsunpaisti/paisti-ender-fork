# IDE Index MCP Reference

This reference only applies in sessions where the `intellij-index_ide_*` tools are exposed.

It is adapted to this OpenCode environment's current wrapper signatures, not the older Claude MCP `ide_*` names.

## Common Rules

| Rule | Notes |
|---|---|
| `project_path` | Use the project root path |
| `file` | Usually relative to project root |
| `line`, `column` | 1-based |
| Symbol lookups | Put the column on the symbol itself |
| Pagination | Keep following `cursor` when returned |

## Navigation

### `intellij-index_ide_find_definition`
- Use for: go-to-definition from a usage site or by Java FQN
- Inputs: `project_path` plus either `file`/`line`/`column` or `language`/`symbol`
- Useful options: `fullElementPreview`, `maxPreviewLines`

### `intellij-index_ide_find_references`
- Use for: all semantic usages of a symbol
- Inputs: `project_path` plus position or Java `language`/`symbol`
- Useful options: `pageSize`, `cursor`

### `intellij-index_ide_find_implementations`
- Use for: concrete implementations of interfaces, abstract classes, or abstract methods
- Inputs: `project_path` plus position or Java `language`/`symbol`
- Useful options: `pageSize`, `cursor`

### `intellij-index_ide_find_super_methods`
- Use for: walking from an override to the parent declaration
- Inputs: `project_path` plus position or Java `language`/`symbol`

### `intellij-index_ide_call_hierarchy`
- Use for: callers or callees of a method
- Inputs: `project_path` plus position or Java `language`/`symbol`
- Required: `direction` = `callers` or `callees`
- Useful option: `depth`

### `intellij-index_ide_type_hierarchy`
- Use for: supertypes and subtypes of a class/interface
- Inputs: `project_path` plus either `className` or `file`/`line`/`column`

## Search

### `intellij-index_ide_find_class`
- Use for: fast class/interface lookup by name
- Inputs: `project_path`, `query`
- Useful options: `includeLibraries`, `language`, `matchMode`, `pageSize`, `cursor`

### `intellij-index_ide_find_file`
- Use for: fast file-name lookup via IDE index
- Inputs: `project_path`, `query`
- Useful options: `includeLibraries`, `pageSize`, `cursor`

### `intellij-index_ide_search_text`
- Use for: exact-word search via IDE word index
- Inputs: `project_path`, `query`
- Useful options: `context`, `caseSensitive`, `pageSize`, `cursor`
- Do not use for regex. Use `Grep` instead.

## Refactoring And Editing

### `intellij-index_ide_refactor_rename`
- Use for: renaming symbols or files and updating references
- Inputs: `project_path`, `file`, `newName`
- Symbol rename also needs `line` and `column`
- Useful options: `overrideStrategy`, `relatedRenamingStrategy`

### `intellij-index_ide_move_file`
- Use for: moving files while updating imports, references, and package declarations
- Inputs: `project_path`, `file`, `destination`
- Useful option: `update_references`

### `intellij-index_ide_refactor_safe_delete`
- Use for: checking usages before deleting a symbol or file
- Inputs: `project_path`, `file`
- Symbol delete also needs `line` and `column`
- Useful options: `target_type`, `force`

### `intellij-index_ide_optimize_imports`
- Use for: removing unused imports and organizing remaining imports
- Inputs: `project_path`, `file`

### `intellij-index_ide_reformat_code`
- Use for: formatting code to IDE style
- Inputs: `project_path`, `file`
- Useful options: `startLine`, `endLine`, `optimizeImports`, `rearrangeCode`

### `intellij-index_ide_open_file`
- Use for: opening a file in the IDE, optionally at a line/column
- Inputs: `project_path`, `file`

### `intellij-index_ide_get_active_file`
- Use for: seeing which files are open and where the cursor is
- Inputs: `project_path`

## Diagnostics And Build

### `intellij-index_ide_diagnostics`
- Use for: file analysis, build errors, and open test results
- Inputs: `project_path`
- Common modes:
  - file-only analysis: `file`
  - build output: `includeBuildErrors: true`
  - test results: `includeTestResults: true`

### `intellij-index_ide_build_project`
- Use for: IDE-backed compile/build verification
- Inputs: `project_path`
- Useful options: `rebuild`, `includeRawOutput`, `timeoutSeconds`

## Java-Specific Helpers

### `intellij-index_ide_convert_java_to_kotlin`
- Use for: converting one or more Java files to Kotlin using IntelliJ's converter
- Inputs: `project_path`, `files`

## Practical Selection Guide

| Need | Best tool |
|---|---|
| Where is this used? | `intellij-index_ide_find_references` |
| What is this? | `intellij-index_ide_find_definition` |
| What implements this? | `intellij-index_ide_find_implementations` |
| Who calls this? | `intellij-index_ide_call_hierarchy` with `callers` |
| What does this call? | `intellij-index_ide_call_hierarchy` with `callees` |
| Which class is this? | `intellij-index_ide_find_class` |
| Which file is this? | `intellij-index_ide_find_file` |
| Can I rename this safely? | `intellij-index_ide_refactor_rename` |
| Can I delete this safely? | `intellij-index_ide_refactor_safe_delete` |
| What errors does the IDE see? | `intellij-index_ide_diagnostics` |

## Avoid These Mistakes

1. Do not emulate semantic refactors with text replacement.
2. Do not use `Grep` when you need symbol references, overrides, or call graphs.
3. Do not use `intellij-index_ide_search_text` when the requirement is regex.
4. Do not assume the first page is complete when a `cursor` is returned.
5. Do not point `column` at whitespace or punctuation.
