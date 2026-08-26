import os
import sys

# Suppress PyMuPDF messages to stderr
os.environ["PYMUPDF_MESSAGE"] = "fd:2"

import fitz  # PyMuPDF
try:
    fitz.TOOLS.mupdf_display_errors(False)
except Exception:
    pass

from typing import List, Dict, Any, Tuple
from PIL import Image
import io
import uuid
from core.document import DocumentPage, Block, Line, Word, Table, TableCell, BoundingBox

class PdfProcessor:
    """
    PyMuPDF PDF extraction engine:
    - Extracts native digital text layers with scaled pixel coordinates
    - Renders and caches high-res page images for JavaFX Split-View Viewer
    - Detects embedded tables
    """

    @staticmethod
    def process_pdf(pdf_path: str, cache_dir: str = "app-data/cache", dpi: int = 180) -> List[DocumentPage]:
        if not os.path.exists(pdf_path):
            raise FileNotFoundError(f"PDF file not found: {pdf_path}")

        os.makedirs(cache_dir, exist_ok=True)

        doc = fitz.open(pdf_path)
        pages: List[DocumentPage] = []

        for page_idx, page in enumerate(doc):
            page_num = page_idx + 1
            rect = page.rect
            page_w_pt, page_h_pt = rect.width, rect.height

            # Render page image to cache directory with ASCII safe filename
            page_img_filename = f"page_{uuid.uuid4().hex[:12]}_p{page_num}.png"
            page_img_path = os.path.abspath(os.path.join(cache_dir, page_img_filename))
            try:
                pix = page.get_pixmap(dpi=dpi)
                pix.save(page_img_path)
                img_width = float(pix.width)
                img_height = float(pix.height)
            except Exception as render_err:
                sys.stderr.write(f"Failed to render page {page_num} image: {render_err}\n")
                page_img_path = None
                img_width = page_w_pt
                img_height = page_h_pt

            # Compute coordinate scale ratio between PDF points (72 DPI) and rendered image pixels (DPI)
            scale_x = img_width / page_w_pt if page_w_pt > 0 else 1.0
            scale_y = img_height / page_h_pt if page_h_pt > 0 else 1.0

            blocks: List[Block] = []
            page_text = page.get_text("text")

            # Extract structured text blocks with layout positioning scaled to image pixels
            text_dict = page.get_text("dict")
            for b in text_dict.get("blocks", []):
                if b.get("type") == 0:  # Text block
                    lines: List[Line] = []
                    block_bbox = BoundingBox(
                        x0=float(b["bbox"][0]) * scale_x,
                        y0=float(b["bbox"][1]) * scale_y,
                        x1=float(b["bbox"][2]) * scale_x,
                        y1=float(b["bbox"][3]) * scale_y
                    )

                    for l in b.get("lines", []):
                        words: List[Word] = []
                        line_bbox = BoundingBox(
                            x0=float(l["bbox"][0]) * scale_x,
                            y0=float(l["bbox"][1]) * scale_y,
                            x1=float(l["bbox"][2]) * scale_x,
                            y1=float(l["bbox"][3]) * scale_y
                        )
                        line_text_parts = []

                        for s in l.get("spans", []):
                            span_text = s.get("text", "")
                            if span_text.strip():
                                line_text_parts.append(span_text)
                                span_bbox = BoundingBox(
                                    x0=float(s["bbox"][0]) * scale_x,
                                    y0=float(s["bbox"][1]) * scale_y,
                                    x1=float(s["bbox"][2]) * scale_x,
                                    y1=float(s["bbox"][3]) * scale_y
                                )
                                words.append(Word(text=span_text, bbox=span_bbox, confidence=1.0))

                        line_str = " ".join(line_text_parts).strip()
                        if line_str:
                            lines.append(Line(text=line_str, words=words, bbox=line_bbox, confidence=1.0))

                    if lines:
                        block_str = "\n".join([ln.text for ln in lines])
                        tag = "Title" if len(lines) == 1 and (block_bbox.y1 - block_bbox.y0 > 24) else "Text"
                        blocks.append(Block(
                            block_type="text",
                            text=block_str,
                            lines=lines,
                            bbox=block_bbox,
                            confidence=1.0,
                            tag=tag
                        ))

            # Extract tables using PyMuPDF native table finder with scaled bboxes
            tables: List[Table] = []
            try:
                tabs = page.find_tables()
                if tabs and hasattr(tabs, "tables"):
                    for tab in tabs.tables:
                        df_rows = tab.extract()
                        if df_rows and len(df_rows) > 0:
                            headers = [str(col).strip() if col is not None else "" for col in df_rows[0]]
                            rows = []
                            for row in df_rows[1:]:
                                rows.append([str(c).strip() if c is not None else "" for c in row])

                            tb_bbox = BoundingBox(
                                x0=float(tab.bbox[0]) * scale_x,
                                y0=float(tab.bbox[1]) * scale_y,
                                x1=float(tab.bbox[2]) * scale_x,
                                y1=float(tab.bbox[3]) * scale_y
                            )
                            tables.append(Table(
                                headers=headers,
                                rows=rows,
                                bbox=tb_bbox,
                                confidence=0.98
                            ))
            except Exception:
                pass

            pages.append(DocumentPage(
                page_number=page_num,
                width=img_width,
                height=img_height,
                image_path=page_img_path,
                text=page_text,
                blocks=blocks,
                tables=tables
            ))

        doc.close()
        return pages

    @staticmethod
    def render_page_to_image(pdf_path: str, page_number: int = 1, dpi: int = 200) -> Image.Image:
        """Render a PDF page to a PIL Image for OCR processing"""
        doc = fitz.open(pdf_path)
        page = doc.load_page(page_number - 1)
        pix = page.get_pixmap(dpi=dpi)
        img = Image.open(io.BytesIO(pix.tobytes("png")))
        doc.close()
        return img
