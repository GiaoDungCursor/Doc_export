import os
import sys
import re
import numpy as np
from typing import List, Dict, Any, Optional
from core.document import Block, Line, Word, BoundingBox
from ocr.preprocess import ImagePreprocessor
from ocr.table import TableDetector

class OcrEngine:
    """
    PaddleOCR Multi-language & Vietnamese Engine:
    - Configured with PP-OCR Latin/Vietnamese ONNX model (836 diacritic characters)
    - Sensitive text detection (unclip_ratio=2.0, limit_side_len=1600) to prevent missed lines
    - Post-processing text normalizer for Vietnamese administrative and legal vocabulary
    """
    _instance = None
    _engine = None

    def __init__(self):
        self._init_engine()

    def _init_engine(self):
        latin_model = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "models", "latin_rec", "inference.onnx"))
        latin_dict = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "models", "latin_rec", "dict.txt"))

        try:
            from rapidocr_onnxruntime import RapidOCR
            if os.path.exists(latin_model) and os.path.exists(latin_dict):
                self._engine = RapidOCR(
                    Rec={'model_path': latin_model, 'keys_path': latin_dict},
                    Det={'box_thresh': 0.22, 'thresh': 0.15, 'unclip_ratio': 2.0, 'limit_side_len': 1600}
                )
                sys.stderr.write("[OcrEngine] Loaded Vietnamese-optimized Latin PP-OCR ONNX Engine.\n")
            else:
                self._engine = RapidOCR(
                    Det={'box_thresh': 0.22, 'thresh': 0.15, 'unclip_ratio': 2.0, 'limit_side_len': 1600}
                )
                sys.stderr.write("[OcrEngine] Loaded RapidOCR Default Engine.\n")
            return
        except Exception as e:
            sys.stderr.write(f"[OcrEngine] Failed to initialize RapidOCR: {e}\n")

        self._engine = None

    def process_image(self, image_input) -> List[Block]:
        """Process image, run OCR, and return clean structured Blocks"""
        blocks, _ = self.analyze_image(image_input)
        return blocks

    def analyze_image(self, image_input):
        """Run OCR once and return both text blocks and ruled-table structure."""
        preprocessed = ImagePreprocessor.preprocess_for_ocr(image_input)

        if self._engine is not None:
            try:
                if hasattr(self._engine, '__call__'):
                    result, elapse = self._engine(preprocessed)
                    blocks = self._parse_rapidocr_result(result)
                    return blocks, TableDetector.extract_tables(preprocessed, blocks)
            except Exception as e:
                sys.stderr.write(f"[OcrEngine] OCR processing error: {e}\n")

        return [], []

    def _parse_rapidocr_result(self, result) -> List[Block]:
        blocks = []
        if not result:
            return blocks

        current_lines = []
        for item in result:
            box = item[0]
            raw_text = str(item[1]).strip()
            conf = float(item[2])

            cleaned_text = self._clean_vietnamese_ocr(raw_text)

            x_coords = [p[0] for p in box]
            y_coords = [p[1] for p in box]
            bbox = BoundingBox(
                x0=float(min(x_coords)),
                y0=float(min(y_coords)),
                x1=float(max(x_coords)),
                y1=float(max(y_coords))
            )

            word_obj = Word(text=cleaned_text, bbox=bbox, confidence=conf)
            line_obj = Line(text=cleaned_text, words=[word_obj], bbox=bbox, confidence=conf)
            current_lines.append(line_obj)

        if current_lines:
            for line in current_lines:
                tag = "Title" if len(line.text) < 45 and any(k in line.text.lower() for k in [
                    "hóa đơn", "hoa don", "biên bản", "báo cáo", "hợp đồng", "cộng hòa", "cong hoa", "căn cứ", "can cu"
                ]) else "Text"

                blocks.append(Block(
                    block_type="text",
                    text=line.text,
                    lines=[line],
                    bbox=line.bbox,
                    confidence=line.confidence,
                    tag=tag
                ))
        return blocks

    def _clean_vietnamese_ocr(self, text: str) -> str:
        """Correct OCR character errors for standard Vietnamese legal & business terms"""
        if not text:
            return ""

        # Normalize spaces
        t = re.sub(r'\s+', ' ', text).strip()

        # Common OCR corrections for Vietnamese administrative templates
        corrections = [
            (r'\bCan\s*c[iï]\b', 'Căn cứ'),
            (r'\bCan\s*cu\b', 'Căn cứ'),
            (r'\bNghi\s*ainh\b', 'Nghị định'),
            (r'\bNghi\s*dinh\b', 'Nghị định'),
            (r'\bChinh\s*phi\b', 'Chính phủ'),
            (r'\bChinh\s*phu\b', 'Chính phủ'),
            (r'\bLudt\b', 'Luật'),
            (r'\bLuat\b', 'Luật'),
            (r'\bGido\s*duc\b', 'Giáo dục'),
            (r'\bgiao\s*duc\b', 'giáo dục'),
            (r'\bdao\s*tao\b', 'đào tạo'),
            (r'\bDao\s*tao\b', 'Đào tạo'),
            (r'\bphdt\s*tri[eé]n\b', 'phát triển'),
            (r'\bHop\s*dong\b', 'Hợp đồng'),
            (r'\bHOP\s*DONG\b', 'HỢP ĐỒNG'),
            (r'\bCong\s*ty\b', 'Công ty'),
            (r'\bCONG\s*TY\b', 'CÔNG TY'),
            (r'\bHoa\s*don\b', 'Hóa đơn'),
            (r'\bHOA\s*DON\b', 'HÓA ĐƠN'),
            (r'\bMa\s*so\s*thue\b', 'Mã số thuế'),
            (r'\bTong\s*tien\b', 'Tổng tiền'),
            (r'\bsia\s*doi\b', 'sửa đổi'),
            (r'\bsira\s*aoi\b', 'sửa đổi'),
            (r'\bbo\s*sung\b', 'bổ sung'),
            (r'\bduroc\b', 'được'),
            (r'\btai\s*sdn\b', 'tài sản'),
            (r'\bQudn\s*ly\b', 'Quản lý'),
            (r'\bsit\s*dung\b', 'sử dụng')
        ]

        for pattern, replacement in corrections:
            t = re.sub(pattern, replacement, t, flags=re.IGNORECASE)

        return t
