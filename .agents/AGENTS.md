# Role & Token Preservation Rules

## 1. Token-Efficient Change Proposals (Strict)
* **Permission First:** Never edit, create, or modify files without explicit user approval.
* **No Premature Diffs:** When proposing changes, do NOT output code blocks, full files, or diffs. Provide a 1-line summary instead (e.g., "Plan: Add fetchData() to api.ts to handle timeouts"). Wait for user confirmation.
* **Minimal Edits:** Once approved, output *only* the specific lines changing using targeted search-and-replace blocks. Never rewrite or output unchanged sections of a file.

## 2. Compact Communication
* **Reference, Don't Repeat:** Do not quote existing code back to the user. Reference it strictly by function name, class name, or line numbers.
* **Keep it Brief:** Eliminate conversational filler, pleasantries, and summaries of what you just did. Be concise.

## 3. Code Aesthetics & Emojis
* **Zero UI/Source Emojis:** Never add emojis or graphical icons to any project files (source code, HTML, CSS, assets, or UI text) unless explicitly commanded.

## 4. High-Density Documentation
* **Concise Documentation:** Every new or modified method must include a brief, high-density Javadoc block for Java code, or JSDoc block for JS code.
* **Focus on Intent:** Keep inline comments strictly focused on *why* a complex calculation or logic segment exists, rather than *what* it does. Avoid wordiness to preserve the context window.

## 5. APK Compilation & Versioning
* **Export Path:** When compiling an APK for this project, it MUST be stored in `~/Desktop/VibeStation Versions/`. Create the directory if it does not exist.
* **Versioning Schema:** 
  - `0.0.x`: Very minor changes / bug fixes.
  - `0.x.x`: Ending of minor upgrades / feature additions.
  - `x.x.x`: Major upgrades / overhauls.
* **Agent Context:** All agents must adhere to this file and versioning schema when exporting builds.

## 6. GitHub Commit & PR Explanations
* **Meaningful Context:** Whenever writing to GitHub (e.g., commits, PRs, issue descriptions), make sure to expand on the issue usefully. Provide a concise but comprehensive explanation for all changes without being unnecessarily wordy.

## 7. Code Reviews
* **Review Standard:** When a code review is requested, you MUST read and use `~/Desktop/compReview.md` as the strict guideline and evaluation criteria for the review.
