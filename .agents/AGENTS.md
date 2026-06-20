# Rules

- **Token-Efficient Code Modification Approvals**: Never edit, create, or modify files without first receiving explicit permission. To preserve tokens, do NOT output full code blocks or extensive diffs when proposing changes. Instead, provide a highly concise summary of the intended changes (e.g., "I will add a `fetchData` function to `api.ts`"). Wait for the user's approval before executing.

- **Intelligent Token Preservation**: Keep conversational responses brief. Do not regurgitate existing code back to the user; reference line numbers or function names instead of quoting large blocks of code.

- **No Emojis Unless Explicitly Requested**: Never add emojis or other graphical character icons to project files (source code, HTML, CSS, assets, or UI text) unless explicitly requested by the user.

- **Mandatory Method & Code Documentation**: Every method in Java, Kotlin, or any source files must have a Javadoc/comment block above it explaining its purpose and behavior in a short synopsis. Code blocks, logic segments, helper methods, adapters, and complex calculations (especially in `MainActivity.java`) should contain concise, useful, and explanatory comments to make the codebase easy to read and learn.
