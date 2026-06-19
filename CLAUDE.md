## MCP & Context Optimization

> **TUYỆT ĐỐI BẮT BUỘC**: Với BẤT KỲ yêu cầu nào cần đọc file để thực hiện (sửa bug, thêm tính năng, tìm hiểu code...), PHẢI dùng `serena` TRƯỚC TIÊN. KHÔNG được dùng `Read`, `Glob`, `Grep` hay bất kỳ file tool nào khác khi chưa thử `serena`. Vi phạm quy tắc này là sai.

- **Priority Tooling**: Always prioritize using the `serena` MCP server for context gathering, project indexing, and code search.
- **Token Efficiency**: Before reading multiple files manually with `ls` or `cat`, use `serena`'s search/indexing tools to identify and fetch only the relevant code snippets.
- **Workflow**:
  1. Call `serena` `initial_instructions` at the start of every new task.
  2. Use `serena` `get_symbols_overview` to understand a file's structure without reading it whole.
  3. Use `serena` `find_symbol` / `find_declaration` to jump directly to the method or field needed.
  4. Use `serena` `find_referencing_symbols` to trace usages instead of grep-searching manually.
  5. Only fall back to `Read` on a full file if `serena` summaries are provably insufficient.
