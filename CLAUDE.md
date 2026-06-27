## MCP & Context Optimization

> **ƯU TIÊN HÀNG ĐẦU**: Với yêu cầu cần **khám phá / đọc hiểu** code (tìm bug, tìm symbol, trace usage, hiểu cấu trúc), hãy dùng `serena` TRƯỚC — **kể cả khi đã biết file nằm đâu**. Chỉ fallback sang `Read`/`Glob`/`Grep` khi serena không đủ (file binary, file config/JSON, file rất nhỏ, hoặc serena trả về không đủ thông tin).

**Bẫy thường gặp:** Biết tên file → tự động Glob + Read toàn bộ file. Đây là sai lầm. Biết file không có nghĩa là đã biết nội dung cần tìm — `get_symbols_overview` + `find_symbol` vẫn nhanh hơn và ít context hơn đọc cả file.

### Discovery (đọc hiểu) — ưu tiên Serena
- `initial_instructions` ở đầu mỗi task mới.
- `get_symbols_overview` để nắm cấu trúc file mà không đọc toàn bộ — **dùng ngay cả khi đã biết tên file**.
- `find_symbol` / `find_declaration` để nhảy thẳng tới method/field cần — keyword trong bug report (vd: "Auto Play Next" → `find_symbol("autoPlay")`) là gợi ý trực tiếp.
- `find_referencing_symbols` để trace usage thay vì grep thủ công.

### Editing (sửa code) — chọn công cụ theo rủi ro, KHÔNG ép buộc Serena
Serena symbol-edit (`replace_symbol_body`, `insert_*_symbol`, `safe_delete_symbol`) định vị bằng **line range** từ index. Sau **nhiều edit liên tiếp làm dịch dòng** trong cùng một file, index có thể **lệch (drift)** → symbol-edit cắt nhầm vùng, **làm hỏng/nuốt method khác**. Đây là điều đã thực sự xảy ra với `FakeFly.java`.

Quy tắc thực dụng:
1. **1–2 edit nhỏ, độc lập** trên một file → Serena symbol-edit hoặc `replace_content` đều ổn.
2. **Sửa nhiều method trong cùng file / nhiều edit dịch dòng liên tiếp** → ƯU TIÊN `replace_content` (regex/literal, định vị bằng nội dung nên không bị drift), HOẶC viết lại trọn file bằng `Write`.
3. Sau chuỗi symbol-edit, nếu nghi index lệch → **xác minh bằng `Read`** vùng vừa sửa, hoặc `get_diagnostics_for_file` (lưu ý diagnostics có thể stale — `gradle build` mới là chuẩn cuối).
4. Khi Serena symbol-edit **đã làm hỏng file**, dừng dùng symbol-edit cho file đó; sửa bằng `replace_content` hoặc `Write` trọn file. Đây là fallback hợp lệ, không vi phạm quy tắc.

### Build & verify
- `gradle build` (`.\gradlew build`) là nguồn sự thật cuối cùng cho lỗi biên dịch — không tin tuyệt đối vào diagnostics LSP (có thể stale sau edit).

### Minecraft & Boze API version — BẮT BUỘC tra đúng phiên bản

**Minecraft:** Project chạy **Minecraft 26.1.2** (snapshot/tên nội bộ Boze, tương đương build mới nhất). KHÔNG dùng kiến thức từ 1.21.1, 1.21.4, 1.21.11 — API đổi thường xuyên.

**Boze API:** Phiên bản **3.2.2** (`boze_api_version` trong `gradle.properties`). Khi cần biết method/class nào available trong Boze API — **BẮT BUỘC tra trực tiếp** file sources jar tại:
```
.gradle/loom-cache/remapped_mods/remapped/dev/boze/boze-api-4b5d3c0a/3.2.2+1.21.11/boze-api-4b5d3c0a-3.2.2+1.21.11-sources.jar
```
Cách tra: dùng `PowerShell` + `System.IO.Compression.ZipFile` để đọc entry trong jar, KHÔNG đoán từ trí nhớ. KHÔNG dùng jar `3.2.1+1.21.10` — sai version.

**Minecraft API:** Nguồn chuẩn là decompiled sources trong loom cache (`minecraft-merged-*-sources.jar` hoặc sources jar của remapped mods). Tra trực tiếp, không đoán.

- Sau khi viết code dùng API MC hoặc Boze, luôn `.\gradlew build` để xác nhận signature đúng.

### Caveman mode — BẮT BUỘC dùng full mode
- Mọi response trong project này phải dùng **caveman full mode**: drop articles, fragments OK, không filler/hedging/pleasantries.
- Tương đương `/caveman full` — áp dụng ngay cả khi không có lệnh explicit từ user.
