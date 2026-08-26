import openpyxl
from openpyxl.styles import Font, PatternFill, Alignment, Border, Side
from openpyxl.utils import get_column_letter
from typing import Dict, Any, List, Optional
import os
import re

class ExcelExporter:
    """
    Excel Exporter:
    1. Template Export: Fills {{placeholders}} in pre-designed .xlsx templates
    2. Full Document Export: Exports all structured fields, blocks, and tables into a clean multi-sheet workbook
    """

    PLACEHOLDER_REGEX = re.compile(r'\{\{([a-zA-Z0-9_\.]+)\}\}')

    @classmethod
    def export(cls, template_path: str, output_path: str, context: Dict[str, Any]) -> str:
        if not os.path.exists(template_path):
            raise FileNotFoundError(f"Excel template not found: {template_path}")

        os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
        wb = openpyxl.load_workbook(template_path)

        for sheet in wb.worksheets:
            cls._fill_sheet(sheet, context)

        wb.save(output_path)
        wb.close()
        return output_path

    @classmethod
    def export_full_document(cls, document_data: Dict[str, Any], output_path: str) -> str:
        os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
        wb = openpyxl.Workbook()

        # Styles
        header_fill = PatternFill(start_color="1D4ED8", end_color="1D4ED8", fill_type="solid")
        header_font = Font(name="Segoe UI", size=11, bold=True, color="FFFFFF")
        title_font = Font(name="Segoe UI", size=14, bold=True, color="0F172A")
        bold_font = Font(name="Segoe UI", size=10, bold=True)
        normal_font = Font(name="Segoe UI", size=10)
        thin_border = Border(
            left=Side(style='thin', color='CBD5E1'),
            right=Side(style='thin', color='CBD5E1'),
            top=Side(style='thin', color='CBD5E1'),
            bottom=Side(style='thin', color='CBD5E1')
        )

        # ---------------- SHEET 1: TỔNG QUAN & CÁC TRƯỜNG THÔNG TIN ----------------
        ws_fields = wb.active
        ws_fields.title = "Thông tin bóc tách"
        ws_fields.views.sheetView[0].showGridLines = True

        ws_fields["A1"] = "BÁO CÁO DỮ LIỆU BÓC TÁCH TỰ ĐỘNG (PADDLEOCR)"
        ws_fields["A1"].font = title_font
        ws_fields["A2"] = f"Tài liệu: {document_data.get('filename', '')} • Tổng số trang: {len(document_data.get('pages', []))}"
        ws_fields["A2"].font = Font(name="Segoe UI", size=10, italic=True, color="64748B")

        ws_fields.append([])
        ws_fields.append(["STT", "Tên trường thông tin", "Giá trị bóc tách", "Độ tin cậy"])
        for col_idx in range(1, 5):
            cell = ws_fields.cell(row=4, column=col_idx)
            cell.fill = header_fill
            cell.font = header_font
            cell.alignment = Alignment(horizontal="center", vertical="center")

        fields = document_data.get("fields", {})
        row_num = 5
        stt = 1
        for f_name, f_val in fields.items():
            ws_fields.append([stt, f_name.replace("_", " ").title(), str(f_val) if f_val is not None else "", "95%"])
            for c in range(1, 5):
                cell = ws_fields.cell(row=row_num, column=c)
                cell.font = normal_font
                cell.border = thin_border
            stt += 1
            row_num += 1

        ws_fields.column_dimensions['A'].width = 8
        ws_fields.column_dimensions['B'].width = 28
        ws_fields.column_dimensions['C'].width = 50
        ws_fields.column_dimensions['D'].width = 15

        # ---------------- SHEET 2: TOÀN BỘ VĂN BẢN OCR (BLOCKS) ----------------
        ws_blocks = wb.create_sheet(title="Đoạn văn bản OCR")
        ws_blocks.views.sheetView[0].showGridLines = True

        ws_blocks.append(["Trang", "Khối #", "Phân loại", "Nội dung văn bản trích xuất", "Độ tin cậy"])
        for col_idx in range(1, 6):
            cell = ws_blocks.cell(row=1, column=col_idx)
            cell.fill = header_fill
            cell.font = header_font
            cell.alignment = Alignment(horizontal="center", vertical="center")

        b_row = 2
        pages = document_data.get("pages", [])
        for page in pages:
            p_num = page.get("page_number", 1)
            blocks = page.get("blocks", [])
            for idx, b in enumerate(blocks):
                text = b.get("text", "").strip()
                if not text:
                    continue
                tag = b.get("tag", "Text")
                conf = b.get("confidence", 0.9)
                ws_blocks.append([p_num, idx + 1, tag, text, f"{conf * 100:.0f}%"])
                for c in range(1, 6):
                    cell = ws_blocks.cell(row=b_row, column=c)
                    cell.font = bold_font if tag == "Title" else normal_font
                    cell.border = thin_border
                    if c == 4:
                        cell.alignment = Alignment(wrap_text=True)
                b_row += 1

        ws_blocks.column_dimensions['A'].width = 10
        ws_blocks.column_dimensions['B'].width = 10
        ws_blocks.column_dimensions['C'].width = 14
        ws_blocks.column_dimensions['D'].width = 80
        ws_blocks.column_dimensions['E'].width = 14

        wb.save(output_path)
        wb.close()
        return output_path

    @classmethod
    def _fill_sheet(cls, sheet, context: Dict[str, Any]):
        for row in sheet.iter_rows():
            for cell in row:
                if cell.value and isinstance(cell.value, str):
                    val_str = cell.value
                    matches = cls.PLACEHOLDER_REGEX.findall(val_str)
                    for key in matches:
                        repl = context.get(key, "")
                        if repl is None:
                            repl = ""
                        if val_str.strip() == f"{{{{{key}}}}}":
                            cell.value = repl
                            break
                        else:
                            val_str = val_str.replace(f"{{{{{key}}}}}", str(repl))
                            cell.value = val_str
