# General

- Always read files before writing to them

# Documentation

- All `public` Kotlin members must have associated KDoc comments
- All `public` Kotlin functions must have all their parameters, and their return type, included in the KDoc comments, in the proper order

# Kotlin

## Blank Lines

- After the last `val`/`var` in a consecutive group of declarations, add a blank
  line before the first non-declaration statement (method call, loop, conditional,
  or `return`). Do **not** add blank lines between consecutive `val`/`var`
  declarations.
- Add a blank line before a `for` or `while` loop when the immediately preceding
  statement is a non-declaration statement (e.g. `appendLine(...)`, an assignment,
  or any other expression statement).
- Add a blank line between two consecutive `if` blocks at the same indentation
  level.
- After a single-line guard clause (`if (condition) return value`) at the top of a
  function body, add a blank line before the main function body.
- In test functions, add a blank line at each Arrange / Act / Assert phase
  boundary: after subject creation and before setup calls; after the last setup
  call and before the retrieval / act call; after the retrieval / act call and
  before the first assertion.

# Gradle

- Keep all plugins, dependencies, and versions sorted in alphabetical order within their respective TOML tables or Kotlin lambdas
