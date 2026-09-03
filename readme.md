# Office Studio AI

Ứng dụng desktop local-first dùng JavaFX và Python để OCR tài liệu, bóc tách dữ liệu,
mapping vào template và xuất Word/Excel. Dữ liệu, SQLite và tài liệu xử lý được lưu
trên máy; ứng dụng không yêu cầu gửi tài liệu lên dịch vụ đám mây.

## Tải bản Windows

- [Tải Office Studio AI v1.0.3 (.exe)](https://github.com/GiaoDungCursor/Doc_export/releases/download/v1.0.3/Office.Studio.AI-1.0.3.exe)
- [Xem tất cả bản phát hành](https://github.com/GiaoDungCursor/Doc_export/releases)
- Windows 10/11 x64
- Bản cài đã kèm Java 21, Python và thư viện OCR; người dùng thông thường không cần cài môi trường lập trình.

SHA-256 của bản v1.0.3:

```text
DCA5BFD70DF75D90D97E8071231CD9BFBF5C875137EC3E0A0B027DDEA7FBE4E2
```

Ứng dụng chưa ký chứng thư số. Nếu Windows SmartScreen cảnh báo, chọn
**More info → Run anyway** sau khi kiểm tra đúng checksum ở trên.

## Chức năng chính

### Kết nối MCP với Antigravity

Khi Office Studio AI đang mở, ứng dụng tự khởi động MCP Streamable HTTP tại
`http://127.0.0.1:8765/mcp`; health check nằm tại `http://127.0.0.1:8765/health`.
Server cung cấp tool `ocr_map_export` để Gemini gửi đường dẫn ảnh/PDF, bổ sung bản chép
`vision_text` nếu cần, tự chọn template Việt Nam và xuất Word/Excel trong một lần gọi.

Trong Antigravity, mở **MCP Servers → Manage MCP Servers → View raw config**, sau đó chép
cấu hình từ `mcp/antigravity.workspace.mcp_config.json` khi chạy source. Nếu dùng bản cài,
dùng `mcp/antigravity.mcp_config.example.json`. Cấu hình chỉ dùng `serverUrl`, không khởi
chạy Python từ Antigravity. Hãy mở Office Studio AI trước rồi bấm **Refresh** trong mục MCP.
Ảnh đính kèm cần được lưu thành file local để agent truyền `source_path` cho tool.

Có thể cài cấu hình tự động và vẫn giữ nguyên các MCP server đang có bằng lệnh:

```powershell
.\scripts\install-antigravity-mcp.ps1
```

Prompt gợi ý:

```text
Đọc chính xác ảnh này, gọi office-studio-ai.ocr_map_export với source_path của ảnh,
truyền phần chữ đã đọc vào vision_text, tự chọn mẫu Việt Nam phù hợp và xuất Word.
Nếu review_required=true, báo tôi trước khi dùng file chính thức.
```

- **Bộ Icon nhận diện chuyên nghiệp**: Icon đa kích cỡ (.ico và .png) tích hợp cho trình cài đặt, desktop shortcut, thanh tiêu đề và taskbar.
- **Hộp Hướng dẫn tương tác (Interactive Guide Box)**: Hướng dẫn nhanh quy trình 4 bước ngay trên màn hình xử lý bóc tách và trang Sổ tay hướng dẫn chuyên biệt.
- **Gỡ cài đặt siêu tốc**: Kịch bản dọn dẹp sạch sẽ và gỡ cài đặt chỉ trong 3 giây.
- OCR PDF và ảnh bằng Python sidecar.
- Hiển thị trang tài liệu và các khối OCR liên kết với phần Document Parsing.
- Chuẩn hóa dòng OCR, tiêu đề, mục đánh số và trường văn bản hành chính Việt Nam.
- Catalog template Word/Excel Việt Nam: công văn, báo cáo, quyết định, tờ trình,
  kế hoạch, thông báo, biên bản, giấy mời, hóa đơn và phiếu thu.
- Tự chuyển biểu mẫu Word tĩnh có dòng dấu chấm/bảng thành placeholder.
- Cho phép sửa mapping, duyệt schema và xuất dữ liệu theo template.
- Lưu tài liệu, tác vụ và cấu hình bằng SQLite cục bộ.

## Hướng dẫn sử dụng

### 1. Hộp Hướng dẫn nhanh & Sổ tay làm việc

- **Hộp Hướng dẫn nhanh tại Workbench**: Nhấn nút **💡 Hướng dẫn làm việc** ở góc trên màn hình *Xử lý bóc tách* để xem hoặc thu gọn tóm tắt 4 bước thực hiện.
- **Trang Hướng dẫn chi tiết**: Chọn mục **📖 Hướng dẫn sử dụng** trên thanh điều hướng bên trái để tra cứu danh mục placeholder chuẩn (`{{document_number}}`, `{{issuing_authority}}`...), cách tạo template tùy biến và mẹo tối ưu OCR.

### 2. Thêm và bóc tách tài liệu

1. Mở **Quản lý tài liệu** và thêm PDF hoặc ảnh.
2. Vào **Xử lý bóc tách** và chọn tài liệu.
3. Nhấn **Bóc tách lại**.
4. Chọn một trường trong **Document Parsing** để xem vùng tương ứng trên ảnh.
5. Nhấp vào giá trị trường để chỉnh lại nếu OCR nhận sai, sau đó nhấn **Lưu thay đổi**.

### 3. Xuất Word hoặc Excel

1. Sau khi bóc tách, kiểm tra các trường quan trọng như số văn bản, cơ quan ban hành,
   tiêu đề, nơi nhận, địa danh và ngày tháng.
2. Chọn template trong ô **Mẫu xuất**.
3. Nhấn **Xuất theo mẫu**, **Xuất Word** hoặc **Xuất Excel**.
4. Dùng **Mở file** hoặc **Thư mục** để xem kết quả.

### 4. Thêm template Word/Excel

1. Vào **Quản lý Mẫu biểu**.
2. Nhấn **＋ Thêm dạng template** và chọn `.docx`, `.xlsx` hoặc `.xlsm`.
3. Hệ thống quét placeholder, dòng dấu chấm và các ô bảng có thể nhập dữ liệu.
4. Chọn template vừa thêm rồi nhấn **Quét & map lại** nếu muốn phân tích lại.
5. Kiểm tra bảng **Placeholder và nguồn dữ liệu**; sửa cột **Nguồn dữ liệu** khi cần.
6. Nhấn **Lưu template**. Template sẽ xuất hiện trong danh sách **Mẫu xuất**.

### 5. Gỡ cài đặt siêu tốc (Fast Uninstaller)

Nếu cần gỡ ứng dụng nhanh chóng mà không cần chờ Windows Installer duyệt file:
1. Chạy file `gỡ_cài_đặt_nhanh.bat` hoặc `fast-uninstall.bat` trong thư mục cài đặt ứng dụng.
2. Hoặc chạy lệnh PowerShell: `.\scripts\uninstall-app.ps1`.
3. Quá trình sẽ đóng các tiến trình nền và gỡ sạch sẽ trong khoảng 3 giây.

Có thể chép file thủ công vào:

```text
app-data/templates/vietnam/custom
```

Sau đó nhấn **Quét lại**. Nút **Mở thư mục mẫu** sẽ mở đúng thư mục này.

Placeholder Word/Excel thông dụng:

```text
{{parent_authority}}     {{issuing_authority}}
{{document_number}}      {{title}}
{{place}}                {{day}} / {{month}} / {{year}}
{{recipient}}            {{summary}}
{{content}}              {{conclusion}}
{{signer_title}}         {{signer_name}}
```

Ví dụ trong Word:

```text
{{issuing_authority}}
Số: {{document_number}}

{{place}}, ngày {{day}} tháng {{month}} năm {{year}}

{{title}}
Kính gửi: {{recipient}}

{{content}}
```

## Chạy từ source trên Windows

### Yêu cầu

- [Git for Windows](https://git-scm.com/download/win)
- JDK 21, ví dụ [Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21)
- [Python cho Windows](https://www.python.org/downloads/windows/) 3.11 trở lên
- Maven không bắt buộc vì repository đã có Maven Wrapper. Nếu muốn cài riêng, xem
  [hướng dẫn Apache Maven](https://maven.apache.org/install).

Kiểm tra môi trường trong PowerShell:

```powershell
git --version
java -version
python --version
```

### Cài đặt

```powershell
git clone https://github.com/GiaoDungCursor/Doc_export.git
cd Doc_export

python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r .\python\requirements.txt
```

Nếu PowerShell chặn script kích hoạt môi trường ảo:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\.venv\Scripts\Activate.ps1
```

### Chạy ứng dụng

Giữ môi trường ảo đang được kích hoạt:

```powershell
cd .\java
.\mvnw.cmd javafx:run
```

Nếu nhận lỗi `'.\mvnw.cmd' is not recognized`, hãy kiểm tra đang đứng trong thư mục
`Doc_export\java`, không phải thư mục gốc repository.

### Chạy kiểm thử

```powershell
cd .\java
.\mvnw.cmd test
```

Kiểm tra Python:

```powershell
cd ..
python -m compileall -q python
python -m pip check
```

## Build installer Windows

Ngoài JDK 21 và môi trường Python đã cài dependencies, quá trình tạo `.exe` cần
[WiX Toolset 3.14.1](https://github.com/wixtoolset/wix3/releases/tag/wix3141rtm).
Tải `wix314-binaries.zip` và giải nén sao cho có:

```text
java/.tools/wix314/candle.exe
java/.tools/wix314/light.exe
```

Từ thư mục gốc repository chạy:

```powershell
.\scripts\build-windows.ps1 -Version 1.0.3
```

Installer được tạo tại:

```text
release/Office Studio AI-1.0.3.exe
```

Script build đóng gói Java runtime, source Python, thư viện OCR trong môi trường
Python hiện tại và catalog template. Vì vậy hãy chạy `pip check` trước khi build.

## Cấu trúc dự án

```text
java/                       JavaFX UI, SQLite, mapping và điều phối sidecar
python/                     OCR, parsing, template inspector và exporter
app-data/templates/vietnam  Catalog template chuẩn và template tùy chỉnh
docs/                       Tài liệu thiết kế/catalog
scripts/                    Script build và đóng gói
```

Kiến trúc xử lý template:

```text
Template → Inspect → Fingerprint → Schema/AI Mapping
         → Human Review → Approved Schema → Render/Fill
```

## Xử lý lỗi thường gặp

### Sidecar Offline

- Đảm bảo môi trường ảo đang được kích hoạt trước khi chạy Maven.
- Chạy `python -m pip install -r python/requirements.txt`.
- Kiểm tra `python --version` và `python -m pip check`.

### Template thêm vào nhưng chưa xuất được

- Chọn template và nhấn **Quét & map lại**.
- Điền các ô còn trống trong cột **Nguồn dữ liệu**.
- Nhấn **Lưu template** trước khi quay lại màn hình bóc tách.

### OCR sai dấu hoặc xuống dòng

- Chọn đúng tài liệu và nhấn **Bóc tách lại**.
- Sửa trường trong Document Parsing rồi nhấn **Lưu thay đổi**.
- Với bản scan mờ, nên dùng ảnh/PDF tối thiểu khoảng 200–300 DPI.

## Tài liệu tham khảo

- [Nghị định 30/2020/NĐ-CP về công tác văn thư](https://vanban.chinhphu.vn/?docid=199378&pageid=27160)
- [Ghi chú catalog template Việt Nam](docs/vietnam-template-catalog.md)
- [JavaFX](https://openjfx.io/)
- [Python trên Windows](https://docs.python.org/3/using/windows.html)
- [python-docx](https://python-docx.readthedocs.io/)
- [openpyxl](https://openpyxl.readthedocs.io/)

## Repository

- [Source code](https://github.com/GiaoDungCursor/Doc_export)
- [Issues](https://github.com/GiaoDungCursor/Doc_export/issues)
- [Releases](https://github.com/GiaoDungCursor/Doc_export/releases)
