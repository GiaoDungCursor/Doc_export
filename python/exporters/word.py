import docx
from docx.shared import Inches, Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.text.paragraph import Paragraph
from typing import Dict, Any, List, Optional
import os
import re

class WordExporter:
    """
    Word Exporter:
    1. Template Export: Fills {{placeholders}} in pre-designed templates
    2. Full Document Export: Converts extracted OCR pages, titles, blocks, and tables into a clean .docx document
    """

    PLACEHOLDER_REGEX = re.compile(r'\{\{([a-zA-Z0-9_\.]+)\}\}')

    @classmethod
    def export(cls, template_path: str, output_path: str, context: Dict[str, Any]) -> str:
        """Fill a template .docx with context data"""
        if not os.path.exists(template_path):
            raise FileNotFoundError(f"Word template not found: {template_path}")

        os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
        doc = docx.Document(template_path)

        # 1. Replace in paragraphs
        for p in doc.paragraphs:
            cls._replace_in_paragraph(p, context)

        # 2. Replace in tables
        for table in doc.tables:
            cls._process_table(table, context)

        doc.save(output_path)
        return output_path

    @classmethod
    def export_full_document(cls, document_data: Dict[str, Any], output_path: str) -> str:
        """Export full OCR parsed document into an authentic, beautifully formatted Word document."""
        from docx.enum.table import WD_TABLE_ALIGNMENT
        os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
        doc = docx.Document()

        # Page setup: Standard A4 with Vietnamese administrative margins (Nghị định 30/2020/NĐ-CP)
        for section in doc.sections:
            section.page_width = Inches(8.27)
            section.page_height = Inches(11.69)
            section.top_margin = Inches(0.79)      # 2.0 cm
            section.bottom_margin = Inches(0.79)   # 2.0 cm
            section.left_margin = Inches(0.98)     # 2.5 cm
            section.right_margin = Inches(0.79)    # 2.0 cm

        # Set default styles
        style = doc.styles['Normal']
        font = style.font
        font.name = 'Times New Roman'
        font.size = Pt(12)
        font.color.rgb = RGBColor(0x00, 0x00, 0x00)

        pages = document_data.get("pages", [])
        tables_root = document_data.get("tables", [])

        for p_idx, page in enumerate(pages):
            if p_idx > 0:
                doc.add_page_break()

            tables = page.get("tables", [])
            if not tables and p_idx == 0 and tables_root:
                tables = tables_root

            # Collect all table cell text so we never duplicate table text as loose paragraphs
            table_cell_texts = set()
            for tb in tables:
                for h in tb.get("headers", []):
                    if h:
                        table_cell_texts.add(str(h).strip().upper())
                for r in tb.get("rows", []):
                    for c in r:
                        if c:
                            table_cell_texts.add(str(c).strip().upper())

            blocks = page.get("blocks", [])

            # Filter out watermark noise tokens
            noise_tokens = {"SOT", "DRL", "THEEGBYDINHHIEOHAHCAEOT", "SOLUTIDN", "GIA", "CHUY", "THANH"}

            for b in blocks:
                text = b.get("text", "").strip()
                if not text:
                    continue

                text_upper = text.upper()
                if text_upper in noise_tokens:
                    continue

                # If this text is already part of the table, skip it!
                if table_cell_texts:
                    if text_upper in table_cell_texts or any(text_upper in c for c in table_cell_texts if len(c) > 5):
                        continue

                tag = b.get("tag", "Text")
                p = doc.add_paragraph()
                p.paragraph_format.line_spacing = 1.15

                # Detect Main Document Title
                is_main_title = any(k in text_upper for k in [
                    "HỢP ĐỒNG LAO ĐỘNG", "HOP DONG LAO DONG",
                    "DANH MỤC BẢN VẼ", "DANH MUC BAN VE",
                    "BIÊN BẢN", "BÁO CÁO", "GIẤY MỜI", "THÔNG BÁO", "QUYẾT ĐỊNH"
                ]) and len(text) < 60

                # Detect Sub-title or Contract number: (Số: ...)
                is_sub_num = bool(re.match(r'^\(?\s*(?:Số|So)\s*[:#]', text, re.I))

                # Detect Section Headers (BÊN A, BÊN B, I., II., v.v.)
                is_section_header = bool(re.match(r'^(?:BÊN\s+[AB]|BEN\s+[AB]|[IVXLCDM]+\.|\d+\.)', text, re.I))

                # Detect National Motto or Agency Header
                is_national_header = "CỘNG HÒA" in text_upper or "CONG HOA" in text_upper or "ĐỘC LẬP" in text_upper

                if is_main_title:
                    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
                    p.paragraph_format.space_before = Pt(12)
                    p.paragraph_format.space_after = Pt(4)
                    run = p.add_run(text.upper())
                    run.bold = True
                    run.font.size = Pt(14)
                elif is_sub_num:
                    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
                    p.paragraph_format.space_after = Pt(8)
                    run = p.add_run(text)
                    run.italic = True
                    run.font.size = Pt(11)
                elif is_section_header:
                    p.alignment = WD_ALIGN_PARAGRAPH.LEFT
                    p.paragraph_format.space_before = Pt(8)
                    p.paragraph_format.space_after = Pt(2)
                    run = p.add_run(text)
                    run.bold = True
                    run.font.size = Pt(12)
                elif is_national_header:
                    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
                    p.paragraph_format.space_after = Pt(2)
                    run = p.add_run(text)
                    run.bold = True
                    run.font.size = Pt(11)
                else:
                    p.paragraph_format.space_after = Pt(4)
                    # If multiple columns in a row (e.g. separated by 4+ spaces)
                    cols = [c.strip() for c in re.split(r'\s{4,}|\t', text) if c.strip()]
                    if len(cols) >= 2:
                        formatted_text = "        ".join(cols)
                        run = p.add_run(formatted_text)
                    else:
                        run = p.add_run(text)

            # Tables in page
            for tb in tables:
                headers = tb.get("headers", [])
                rows = tb.get("rows", [])
                if headers or rows:
                    cols_count = max(len(headers), max((len(r) for r in rows), default=1))
                    w_table = doc.add_table(rows=1 if headers else 0, cols=cols_count)
                    w_table.style = 'Table Grid'
                    w_table.alignment = WD_TABLE_ALIGNMENT.CENTER
                    
                    if headers:
                        hdr_cells = w_table.rows[0].cells
                        for i, h in enumerate(headers):
                            if i < len(hdr_cells):
                                hdr_cells[i].text = str(h)
                                p_h = hdr_cells[i].paragraphs[0]
                                p_h.alignment = WD_ALIGN_PARAGRAPH.CENTER
                                for run in p_h.runs:
                                    run.bold = True
                                    run.font.size = Pt(11)
                                cls._set_cell_background(hdr_cells[i], "F1F5F9")
                    for r in rows:
                        row_cells = w_table.add_row().cells
                        for i, val in enumerate(r):
                            if i < len(row_cells):
                                row_cells[i].text = str(val)
                                p_c = row_cells[i].paragraphs[0]
                                if i == 0 or i == 2:  # STT or Code column -> Center
                                    p_c.alignment = WD_ALIGN_PARAGRAPH.CENTER
                                else:
                                    p_c.alignment = WD_ALIGN_PARAGRAPH.LEFT
                                for run in p_c.runs:
                                    run.font.size = Pt(10.5)
                    doc.add_paragraph()

        doc.save(output_path)
        return output_path

    @staticmethod
    def _set_cell_background(cell, color_hex: str):
        from docx.oxml import parse_xml
        from docx.oxml.ns import nsdecls
        shading_elm = parse_xml(f'<w:shd {nsdecls("w")} w:fill="{color_hex}"/>')
        cell._tc.get_or_add_tcPr().append(shading_elm)

    @classmethod
    def _replace_in_paragraph(cls, paragraph, context: Dict[str, Any]):
        if not paragraph.text:
            return

        full_text = paragraph.text
        matches = cls.PLACEHOLDER_REGEX.findall(full_text)
        if not matches:
            return

        if full_text.strip() == "{{content}}" and context.get("content"):
            cls._expand_formatted_content(paragraph, str(context["content"]))
            return

        for k in matches:
            val = str(context[k]) if (k in context and context[k] is not None) else ""
            full_text = full_text.replace(f"{{{{{k}}}}}", val)

        if paragraph.runs:
            paragraph.runs[0].text = full_text
            for r in paragraph.runs[1:]:
                r.text = ""
        else:
            paragraph.text = full_text

    @classmethod
    def _expand_formatted_content(cls, paragraph, content: str):
        """Turn OCR line wraps into real Word paragraphs and preserve document hierarchy."""
        from core.text_layout import normalize_block_text
        content = normalize_block_text(content)
        logical = []
        pending = []

        def flush():
            if pending:
                logical.append(" ".join(pending).strip())
                pending.clear()

        heading_re = re.compile(r'^(?:[IVXLCDM]+\.|\d+(?:\.\d+)*\.|[a-zđ]\))\s+', re.IGNORECASE)
        bullet_re = re.compile(r'^[-–•]\s+')
        for raw_line in content.splitlines():
            line = re.sub(r'\s+', ' ', raw_line).strip()
            if not line:
                flush()
                continue
            is_heading = bool(heading_re.match(line)) or (line.isupper() and len(line) <= 100)
            is_bullet = bool(bullet_re.match(line))
            if is_heading:
                flush()
                logical.append(line)
            elif is_bullet:
                flush()
                pending.append(line)
                if line.endswith(('.', ':', ';', '?', '!')):
                    flush()
            else:
                pending.append(line)
                if line.endswith(('.', ':', ';', '?', '!')):
                    flush()
        flush()
        if not logical:
            logical = [""]

        paragraph.clear()
        paragraph.add_run(logical[0])
        cls._format_content_paragraph(paragraph, logical[0], heading_re, bullet_re)
        current_xml = paragraph._p

        for text in logical[1:]:
            new_xml = OxmlElement("w:p")
            current_xml.addnext(new_xml)
            new_paragraph = Paragraph(new_xml, paragraph._parent)
            new_paragraph.style = paragraph.style
            new_paragraph.add_run(text)
            cls._format_content_paragraph(new_paragraph, text, heading_re, bullet_re)
            current_xml = new_xml

    @staticmethod
    def _format_content_paragraph(paragraph, text, heading_re, bullet_re):
        fmt = paragraph.paragraph_format
        fmt.space_after = Pt(4)
        fmt.line_spacing = 1.25
        is_heading = bool(heading_re.match(text)) or (text.isupper() and len(text) <= 100)
        is_bullet = bool(bullet_re.match(text))
        if is_heading:
            fmt.space_before = Pt(8)
            fmt.first_line_indent = Pt(0)
            for run in paragraph.runs:
                run.bold = True
        elif is_bullet:
            fmt.left_indent = Pt(22)
            fmt.first_line_indent = Pt(-12)
            paragraph.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
        else:
            fmt.first_line_indent = Pt(28)
            paragraph.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
        for run in paragraph.runs:
            run.font.name = "Times New Roman"
            run.font.size = Pt(13)

    @classmethod
    def _process_table(cls, table, context: Dict[str, Any]):
        for row in table.rows:
            for cell in row.cells:
                for p in cell.paragraphs:
                    cls._replace_in_paragraph(p, context)
