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
        """Export full OCR parsed document into a formatted Word file"""
        os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
        doc = docx.Document()

        # Set default styles
        style = doc.styles['Normal']
        font = style.font
        font.name = 'Times New Roman'
        font.size = Pt(12)
        font.color.rgb = RGBColor(0x1e, 0x29, 0x3b)

        # Title
        filename = document_data.get("filename", "VĂN BẢN TRÍCH XUẤT OCR")
        title_p = doc.add_paragraph()
        title_p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        title_run = title_p.add_run(filename.upper().replace(".PDF", "").replace(".DOCX", ""))
        title_run.bold = True
        title_run.font.size = Pt(16)
        title_run.font.color.rgb = RGBColor(0x1d, 0x4e, 0xd8)

        # Subtitle info
        sub_p = doc.add_paragraph()
        sub_p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        sub_run = sub_p.add_run(f"(Bóc tách tự động bởi PaddleOCR Engine • Tổng số trang: {len(document_data.get('pages', []))})")
        sub_run.italic = True
        sub_run.font.size = Pt(10)
        sub_run.font.color.rgb = RGBColor(0x64, 0x74, 0x8b)

        doc.add_paragraph()  # Spacing

        # Structured Fields Summary (if any)
        fields = document_data.get("fields", {})
        if fields and any(v for v in fields.values()):
            h2 = doc.add_heading("THÔNG TIN TRÍCH XUẤT QUAN TRỌNG", level=2)
            for f_name, f_val in fields.items():
                if f_val:
                    fp = doc.add_paragraph()
                    r1 = fp.add_run(f"• {f_name.replace('_', ' ').title()}: ")
                    r1.bold = True
                    fp.add_run(str(f_val))
            doc.add_paragraph()

        # Pages and Blocks Content
        pages = document_data.get("pages", [])
        for p_idx, page in enumerate(pages):
            page_num = page.get("page_number", p_idx + 1)
            
            # Page separator header
            h_page = doc.add_heading(f"--- TRANG {page_num} ---", level=3)
            h_page.alignment = WD_ALIGN_PARAGRAPH.LEFT

            blocks = page.get("blocks", [])
            for b in blocks:
                text = b.get("text", "").strip()
                if not text:
                    continue

                tag = b.get("tag", "Text")
                p = doc.add_paragraph()
                
                if tag == "Title":
                    p.alignment = WD_ALIGN_PARAGRAPH.CENTER if len(text) < 40 else WD_ALIGN_PARAGRAPH.LEFT
                    run = p.add_run(text)
                    run.bold = True
                    run.font.size = Pt(13)
                    run.font.color.rgb = RGBColor(0x0f, 0x17, 0x2a)
                else:
                    p.paragraph_format.line_spacing = 1.15
                    p.paragraph_format.space_after = Pt(4)
                    run = p.add_run(text)

            # Tables in page (if any)
            tables = page.get("tables", [])
            for tb in tables:
                headers = tb.get("headers", [])
                rows = tb.get("rows", [])
                if headers or rows:
                    cols_count = max(len(headers), max((len(r) for r in rows), default=1))
                    w_table = doc.add_table(rows=1 if headers else 0, cols=cols_count)
                    w_table.style = 'Table Grid'
                    
                    if headers:
                        hdr_cells = w_table.rows[0].cells
                        for i, h in enumerate(headers):
                            if i < len(hdr_cells):
                                hdr_cells[i].text = str(h)
                                for run in hdr_cells[i].paragraphs[0].runs:
                                    run.bold = True
                    for r in rows:
                        row_cells = w_table.add_row().cells
                        for i, val in enumerate(r):
                            if i < len(row_cells):
                                row_cells[i].text = str(val)
                    doc.add_paragraph()

        doc.save(output_path)
        return output_path

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
