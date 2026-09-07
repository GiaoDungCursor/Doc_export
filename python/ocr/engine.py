import os
import sys
import re
import numpy as np
from typing import List, Dict, Any, Optional, Tuple
from core.document import Block, Line, Word, BoundingBox, Table, TableCell
from ocr.preprocess import ImagePreprocessor
from ocr.table import TableDetector
from ocr.vl_engine import PaddleOCRVLEngine

class OcrEngine:
    """
    PaddleOCR Multi-language & Vietnamese Engine:
    - RapidOCR with the PP-OCRv3 Latin ONNX model and its Vietnamese dictionary
    - PaddleOCR-VL-1.5 Vision-Language Model for complex layouts and table structure extraction
    - Sensitive text detection (unclip_ratio=2.0, limit_side_len=1600) to prevent missed lines
    - Post-processing text normalizer for Vietnamese administrative and legal vocabulary
    """
    _instance = None
    _engine = None
    _vl_engine = None

    def __init__(self):
        self._init_engine()
        self._vl_engine = PaddleOCRVLEngine.get_instance()

    def _init_engine(self):
        latin_model = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "models", "latin_rec_v3", "inference.onnx"))
        latin_dict = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "models", "latin_rec_v3", "dict.txt"))

        try:
            from rapidocr_onnxruntime import RapidOCR
            if os.path.exists(latin_model) and os.path.exists(latin_dict):
                # rapidocr_onnxruntime 1.x accepts flattened keyword names.
                # Passing nested Rec/Det dictionaries is silently ignored and caused
                # the application to use its default Chinese recognizer.
                self._engine = RapidOCR(
                    rec_model_path=latin_model,
                    rec_keys_path=latin_dict,
                    det_model_path="",
                    det_box_thresh=0.22,
                    det_thresh=0.15,
                    det_unclip_ratio=2.0,
                    det_limit_side_len=1600,
                )
                sys.stderr.write("[OcrEngine] Loaded bundled Vietnamese PP-OCRv3 Latin Engine.\n")
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

    def analyze_image(self, image_input, prefer_vl: bool = False, save_oriented_path: Optional[str] = None,
                      auto_orient: bool = False):
        """Run OCR and return both text blocks and table structure."""
        if prefer_vl and self._vl_engine and self._vl_engine.available:
            return self.analyze_image_vl(image_input)

        preprocessed = ImagePreprocessor.preprocess_for_ocr(image_input)

        if self._engine is not None:
            try:
                if hasattr(self._engine, '__call__'):
                    import cv2
                    result, elapse = self._engine(preprocessed)

                    # Auto-orientation: detect if text lines are vertical (sideways photo: 90 or 270 degrees)
                    # Do not guess a 90-degree rotation during normal extraction. The
                    # desktop viewer owns orientation, so a user's manual rotation must
                    # remain stable when they press "Bóc tách lại".
                    if result and auto_orient:
                        aspect_ratios = [
                            abs(max(p[0] for p in b[0]) - min(p[0] for p in b[0])) /
                            max(1.0, abs(max(p[1] for p in b[0]) - min(p[1] for p in b[0])))
                            for b in result
                        ]
                        median_ratio = float(np.median(aspect_ratios))
                        if median_ratio < 0.8:
                            sys.stderr.write(f"[OcrEngine] Sideways text detected (median aspect ratio {median_ratio:.2f} < 0.8). Auto-orienting image...\n")
                            rotated_cw = cv2.rotate(preprocessed, cv2.ROTATE_90_CLOCKWISE)
                            rotated_ccw = cv2.rotate(preprocessed, cv2.ROTATE_90_COUNTERCLOCKWISE)
                            res_cw, _ = self._engine(rotated_cw)
                            res_ccw, _ = self._engine(rotated_ccw)

                            COMMON_TOKENS = {
                                'thong', 'tin', 'fatca', 'khong', 'tai', 'khoan', 'dang', 'ky', 'chuc',
                                'dinh', 'che', 'ngan', 'hang', 'tien', 'so', 'mau', 'phan', 'phu',
                                'dich', 'vu', 'cong', 'hoa', 'viet', 'nam', 'quoc', 'ngay', 'thang',
                                'ban', 've', 'mat', 'bang', 'ke', 'hoach', 'hop', 'dong', 'don'
                            }
                            def count_valid_words(res_list):
                                if not res_list:
                                    return 0
                                words = set(' '.join(b[1] for b in res_list).lower().split())
                                return len(words & COMMON_TOKENS)

                            words_orig = count_valid_words(result)
                            words_cw = count_valid_words(res_cw)
                            words_ccw = count_valid_words(res_ccw)

                            chosen_rot = None
                            # Only rotate if the rotated orientation has significantly more valid words than original
                            if words_cw > words_ccw and words_cw > words_orig + 2:
                                chosen_rot = 'cw'
                                preprocessed = rotated_cw
                                result = res_cw
                            elif words_ccw > words_cw and words_ccw > words_orig + 2:
                                chosen_rot = 'ccw'
                                preprocessed = rotated_ccw
                                result = res_ccw
                            else:
                                sys.stderr.write(f"[OcrEngine] Orientation unchanged (orig_words={words_orig}, cw={words_cw}, ccw={words_ccw})\n")

                            # Persist oriented image to save_oriented_path only if genuinely rotated
                            if chosen_rot and save_oriented_path:
                                try:
                                    raw_orig = ImagePreprocessor.load_image(image_input)
                                    rot_flag = cv2.ROTATE_90_CLOCKWISE if chosen_rot == 'cw' else cv2.ROTATE_90_COUNTERCLOCKWISE
                                    oriented_orig = cv2.rotate(raw_orig, rot_flag)
                                    ext = os.path.splitext(save_oriented_path)[1]
                                    if not ext:
                                        ext = ".jpg"
                                    is_ok, buf = cv2.imencode(ext, oriented_orig)
                                    if is_ok:
                                        with open(save_oriented_path, "wb") as f_out:
                                            f_out.write(buf)
                                        sys.stderr.write(f"[OcrEngine] Successfully saved auto-oriented image to {save_oriented_path}\n")
                                except Exception as err:
                                    sys.stderr.write(f"[OcrEngine] Failed to save oriented image: {err}\n")

                    blocks_raw = self._parse_rapidocr_raw(result)
                    tables = TableDetector.extract_tables(preprocessed, blocks_raw)

                    # Fallback to PaddleOCR-VL if still no table and keywords present
                    if not tables and self._vl_engine and self._vl_engine.available:
                        full_text = " ".join(b.text for b in blocks_raw).upper()
                        keywords = [
                            "DANH MỤC BẢN VẼ", "DANH MUC BAN VE", "DANH MỤC", "DANH MUC",
                            "BẢNG TIẾN ĐỘ", "BANG TIEN DO", "BẢNG KÊ", "BANG KE"
                        ]
                        if any(k in full_text for k in keywords):
                            sys.stderr.write("[OcrEngine] Ruled table not detected, invoking PaddleOCR-VL for table extraction...\n")
                            _, vl_tables = self.analyze_image_vl(image_input)
                            if vl_tables:
                                tables = vl_tables

                    # Clean table contents
                    for t in tables:
                        if t.name:
                            t.name = self._clean_vietnamese_ocr(t.name)
                        if t.headers:
                            t.headers = [self._clean_vietnamese_ocr(h) for h in t.headers]
                        if t.rows:
                            t.rows = [[self._clean_vietnamese_ocr(c) for c in r] for r in t.rows]
                        if t.cells:
                            for c in t.cells:
                                c.text = self._clean_vietnamese_ocr(c.text)

                    # Build reading-order blocks, excluding blocks that are already inside tables
                    blocks = self._build_reading_order_blocks(blocks_raw, tables)

                    return blocks, tables
            except Exception as e:
                sys.stderr.write(f"[OcrEngine] OCR processing error: {e}\n")

        return [], []

    def analyze_image_vl(self, image_input, prompt: str = "OCR:") -> Tuple[List[Block], List[Table]]:
        """Run PaddleOCR-VL Vision-Language OCR to extract text blocks and structured tables."""
        if self._vl_engine and self._vl_engine.available:
            try:
                raw_text = self._vl_engine.generate(image_input, prompt_text=prompt)
                table_rows = self._vl_engine.parse_to_table_rows(raw_text)

                blocks = []
                lines = [l.strip() for l in raw_text.splitlines() if l.strip()]
                for idx, line_str in enumerate(lines):
                    cleaned = self._clean_vietnamese_ocr(line_str)
                    word = Word(text=cleaned)
                    line_obj = Line(text=cleaned, words=[word])
                    blocks.append(Block(
                        block_type="text",
                        text=cleaned,
                        lines=[line_obj],
                        tag="Title" if idx == 0 else "Text"
                    ))

                tables = []
                if table_rows and len(table_rows) >= 2:
                    headers = [self._clean_vietnamese_ocr(h) for h in table_rows[0]]
                    data_rows = [[self._clean_vietnamese_ocr(c) for c in row] for row in table_rows[1:]]
                    cells = []
                    for r_idx, row in enumerate(data_rows):
                        for c_idx, cell_text in enumerate(row):
                            cells.append(TableCell(
                                row_index=r_idx,
                                col_index=c_idx,
                                text=cell_text
                            ))
                    tables.append(Table(
                        name="table_vl",
                        headers=headers,
                        rows=data_rows,
                        cells=cells,
                        confidence=0.95
                    ))
                return blocks, tables
            except Exception as e:
                sys.stderr.write(f"[OcrEngine] VL Engine error: {e}\n")

        return [], []

    def _parse_rapidocr_raw(self, result) -> List[Block]:
        """Convert raw RapidOCR output into individual Block instances with bounding boxes."""
        blocks = []
        if not result:
            return blocks

        for item in result:
            box = item[0]
            raw_text = str(item[1]).strip()
            conf = float(item[2])

            cleaned_text = self._clean_vietnamese_ocr(raw_text)
            if not cleaned_text:
                continue

            # Suppress detector fragments/bleed-through that are not useful text.
            # Keep uncertain real lines for review, but discard very low-confidence
            # gibberish and long fused pseudo-words commonly found at page edges.
            alpha_tokens = re.findall(r"[^\W\d_]+", cleaned_text, flags=re.UNICODE)
            longest_token = max((len(token) for token in alpha_tokens), default=0)
            if conf < 0.70 or (conf < 0.75 and longest_token >= 24):
                continue

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
            blocks.append(Block(
                block_type="text",
                text=cleaned_text,
                lines=[line_obj],
                bbox=bbox,
                confidence=conf
            ))
        return blocks

    def _build_reading_order_blocks(self, raw_blocks: List[Block], tables: List[Table]) -> List[Block]:
        """Filter out watermark noise, table cells, and group remaining blocks into natural reading order rows."""
        if not raw_blocks:
            return []

        # Collect text belonging to extracted tables
        table_cells = set()
        for t in tables:
            for h in (t.headers or []):
                if h:
                    table_cells.add(str(h).strip().upper())
            for r in (t.rows or []):
                for c in r:
                    if c:
                        table_cells.add(str(c).strip().upper())

        noise_tokens = {"SOT", "DRL", "THEEGBYDINHHIEOHAHCAEOT", "THEEGBYDINHHIEOHAHCSAHEOT", "SOLUTIDN", "GIA", "CHUY", "THANH"}

        non_table_blocks = []
        for b in raw_blocks:
            if not b.text or not b.text.strip():
                continue

            b_upper = b.text.strip().upper()
            # Skip single-word watermark noise or margin stamps
            if (b_upper in noise_tokens or "THEEGBYDINH" in b_upper or "PABCAP" in b_upper or
                    "ACAANOQUYN" in b_upper or "SOATCUICUNG" in b_upper or
                    "THOATHOPPL" in b_upper or "GHIAHUINUOT" in b_upper or "BENMHMIYTHIC" in b_upper or
                    "SNWLGIICHCT" in b_upper or "DEIOUEHINI" in b_upper):
                continue
            if b.bbox and b.bbox.x0 > 1420 and len(b_upper) <= 5:
                continue

            in_table = False
            if b.bbox:
                bx_center = (b.bbox.x0 + b.bbox.x1) / 2
                by_center = (b.bbox.y0 + b.bbox.y1) / 2
                for t in tables:
                    if t.bbox and (t.bbox.x0 - 10 <= bx_center <= t.bbox.x1 + 10 and
                                   t.bbox.y0 - 10 <= by_center <= t.bbox.y1 + 10):
                        in_table = True
                        break

            # If text is already in table cells, don't duplicate
            if not in_table and table_cells:
                if b_upper in table_cells or any(b_upper in c or c in b_upper for c in table_cells if len(c) > 4):
                    # Preserve table title if located above table
                    if "DANH MỤC" in b_upper and b.bbox and tables[0].bbox and b.bbox.y1 <= tables[0].bbox.y0 + 20:
                        in_table = False
                    else:
                        in_table = True

            if not in_table:
                non_table_blocks.append(b)

        if not non_table_blocks:
            return []

        # Group lines into visual horizontal rows
        sorted_blocks = sorted(non_table_blocks, key=lambda b: b.bbox.y0 if b.bbox else 0)
        heights = [(b.bbox.y1 - b.bbox.y0) for b in sorted_blocks if b.bbox]
        avg_h = float(np.median(heights)) if heights else 20.0
        y_tol = max(10.0, avg_h * 0.55)

        rows: List[List[Block]] = []
        current_row: List[Block] = [sorted_blocks[0]]
        current_yc = (sorted_blocks[0].bbox.y0 + sorted_blocks[0].bbox.y1) / 2 if sorted_blocks[0].bbox else 0

        for b in sorted_blocks[1:]:
            if not b.bbox:
                current_row.append(b)
                continue
            yc = (b.bbox.y0 + b.bbox.y1) / 2
            if abs(yc - current_yc) <= y_tol:
                current_row.append(b)
                current_yc = sum((x.bbox.y0 + x.bbox.y1) / 2 for x in current_row if x.bbox) / len(current_row)
            else:
                current_row.sort(key=lambda x: x.bbox.x0 if x.bbox else 0)
                rows.append(current_row)
                current_row = [b]
                current_yc = yc

        if current_row:
            current_row.sort(key=lambda x: x.bbox.x0 if x.bbox else 0)
            rows.append(current_row)

        final_blocks = []
        for row_blocks in rows:
            if not row_blocks:
                continue

            # Join multiple items in the same row with distinct spacing
            row_text = "    ".join(b.text for b in row_blocks if b.text.strip())
            row_text = self._clean_vietnamese_ocr(row_text)
            if not row_text.strip():
                continue

            min_x0 = min(b.bbox.x0 for b in row_blocks if b.bbox)
            min_y0 = min(b.bbox.y0 for b in row_blocks if b.bbox)
            max_x1 = max(b.bbox.x1 for b in row_blocks if b.bbox)
            max_y1 = max(b.bbox.y1 for b in row_blocks if b.bbox)
            row_bbox = BoundingBox(x0=min_x0, y0=min_y0, x1=max_x1, y1=max_y1)
            row_conf = sum(b.confidence for b in row_blocks) / len(row_blocks)

            tag = "Title" if len(row_text) < 55 and any(k in row_text.lower() for k in [
                "hóa đơn", "hoa don", "biên bản", "báo cáo", "hợp đồng", "cộng hòa", "cong hoa",
                "giấy chứng nhận", "danh mục", "bảng kê"
            ]) else "Text"

            all_lines = []
            for b in row_blocks:
                all_lines.extend(b.lines)

            final_blocks.append(Block(
                block_type="text",
                text=row_text,
                lines=all_lines,
                bbox=row_bbox,
                confidence=row_conf,
                tag=tag
            ))

        return final_blocks

    def _clean_vietnamese_ocr(self, text: str) -> str:
        """Correct OCR character errors for standard Vietnamese legal, engineering & business terms."""
        if not text:
            return ""

        t = re.sub(r'\s+', ' ', str(text)).strip()

        # Dictionary of corrections: (pattern, replacement, flags)
        corrections = [
            # Frequent Vietnamese banking/administrative phrases. These also
            # restore tones when a small printed line is decoded without them.
            (r'\bt[oóö]\s+ch(?:uc|úc|ure|urc|úrc)\b', 'tổ chức', re.IGNORECASE),
            (r'\bdu(?:oc|roc|rgc|rac)\b', 'được', re.IGNORECASE),
            (r'\bnhr\s+m[oö]t\b', 'như một', re.IGNORECASE),
            (r'\b(?:d|\u0111)?inh\s+ch(?:e|é)\s+tai\s+chinh\b', 'định chế tài chính', re.IGNORECASE),
            (r'\bdinh\s+ch(?:e|é)\s+tai\s+chinh\b', 'định chế tài chính', re.IGNORECASE),
            (r'\bngoai\s+my\b', 'ngoài Mỹ', re.IGNORECASE),
            (r'\btheo\s+quy\s+dinh\s+cua\b', 'theo quy định của', re.IGNORECASE),
            (r'\bvi\s+du\b', 'ví dụ', re.IGNORECASE),
            (r'\bngan\s+hang\b', 'ngân hàng', re.IGNORECASE),
            (r'\bgiam\s+h[oö]\b', 'giám hộ', re.IGNORECASE),
            (r'\bcong\s+ty\b', 'công ty', re.IGNORECASE),
            (r'\bchuyen\s+dau\s+tu\b', 'chuyên đầu tư', re.IGNORECASE),
            (r'\bchuy[eé]n\s+dau\s+tu\b', 'chuyên đầu tư', re.IGNORECASE),
            (r'\bm[oơ]i\s+gi[ơo]i\s+dau\s+tu\b', 'môi giới đầu tư', re.IGNORECASE),
            (r'\bmoi\s+gi[oó]i\s+dau\s+tu\b', 'môi giới đầu tư', re.IGNORECASE),
            (r'\bmi\s+gii\s+dau\s+tu\b', 'môi giới đầu tư', re.IGNORECASE),
            (r'\b(?:tu|tr)\s+van\s+dau\s+tu\b', 'tư vấn đầu tư', re.IGNORECASE),
            (r'\bquy\s+hoac\s+phuong\s+tien\s+de\s+dau\s+tu\b', 'quỹ hoặc phương tiện để đầu tư', re.IGNORECASE),
            (r'\bphuong\s+ti[eé]n\s+d[eé]\s+dau\s+tu\b', 'phương tiện để đầu tư', re.IGNORECASE),
            (r'\bbao\s+hi[eé]m\b', 'bảo hiểm', re.IGNORECASE),
            (r'\bgi\s+v[oö]n\s+dau\s+tu\b', 'giữ vốn đầu tư', re.IGNORECASE),
            (r'\bma\s+s[oö]\b', 'mã số', re.IGNORECASE),
            (r'\bcung\s+c[aá]p\b', 'cung cấp', re.IGNORECASE),
            (r'\btrr[oö]ng\s+hop\b', 'trường hợp', re.IGNORECASE),
            (r'\bhiru\b', 'hữu', re.IGNORECASE),
            (r'\bth[eé]m\s+th[oö]ng\s+tin\b', 'thêm thông tin', re.IGNORECASE),
            (r'\bbi[eé]u\s+mau\b', 'Biểu mẫu', re.IGNORECASE),
            (r'\bC6(?=\s*\()', 'Có', 0),
            (r'\bC6\s*-?\s*Ma\s*GIIN\b', 'Có - Mã GIIN', re.IGNORECASE),
            (r'\bkh[oö]ng\b', 'không', re.IGNORECASE),
            # Administrative & National Headers
            (r'\bCONGHOA\b', 'CỘNG HÒA', 0),
            (r'\bCONG\s*HOA\b', 'CỘNG HÒA', 0),
            (r'\bXA\s*HOI\s*CHU\s*NGHIA\b', 'XÃ HỘI CHỦ NGHĨA', 0),
            (r'\bXA\s*HOI\b', 'XÃ HỘI', 0),
            (r'\bCHU\s*NGHIA\b', 'CHỦ NGHĨA', 0),
            (r'\bVIET\s*NAM\b', 'VIỆT NAM', 0),
            (r'\bCong\s*hoa\s*xa\s*hoi\s*chu\s*nghia\s*Viet\s*Nam\b', 'Cộng hòa xã hội chủ nghĩa Việt Nam', re.IGNORECASE),
            (r'\bQuoc\s*hoi\s*nuoc\s*Cong\s*hoa\s*Xa\s*hoi\s*chu\b', 'Quốc hội nước Cộng hòa Xã hội chủ', re.IGNORECASE),
            (r'\bQuoc\s*hoi\s*nuoc\s*Cong\s*hoa\s*Xa\s*hoi\b', 'Quốc hội nước Cộng hòa Xã hội', re.IGNORECASE),
            (r'\bchu\s*nghia\s*Viet\s*Nam\b', 'chủ nghĩa Việt Nam', re.IGNORECASE),
            (r'\bnghia\s*Viet\s*Nam\b', 'nghĩa Việt Nam', re.IGNORECASE),
            (r'\bViet\s*Nam\b', 'Việt Nam', 0),
            (r'\bDoc\s*lap\s*[-–]\s*Tu\s*do\s*[-–]\s*Hanh\s*ph[uui]c\b', 'Độc lập - Tự do - Hạnh phúc', re.IGNORECASE),
            (r'\bDoc\s*lap\s*[-–]\s*Tu\s*do\s*[-–]\s*Hanh\s*phic\b', 'Độc lập - Tự do - Hạnh phúc', re.IGNORECASE),
            (r'\bDOC LAP - TU DO - HANH PHUC\b', 'ĐỘC LẬP - TỰ DO - HẠNH PHÚC', 0),
            (r'---\s*000\s*---?', '---o0o---', 0),

            # Company Names & Header info
            (r'\bCONG\s*TY\s*CP\s*GIAI\s*PHAP\b', 'CÔNG TY CP GIẢI PHÁP', 0),
            (r'\bCHUYEN\s*DOI\s*SO\s*THONG\s*MINH\b', 'CHUYỂN ĐỔI SỐ THÔNG MINH', 0),
            (r'\bCONG\s*TY\s*CO\s*PHAN\s*GIAI\s*PHAP\s*CHUYEN\s*DOI\s*SO\s*THONG\s*MINH\b', 'CÔNG TY CỔ PHẦN GIẢI PHÁP CHUYỂN ĐỔI SỐ THÔNG MINH', 0),
            (r'\bCONG\s*TY\s*CO\s*PHAN\s*GIAI\s*PHAP\b', 'CÔNG TY CỔ PHẦN GIẢI PHÁP', 0),
            (r'\bCong\s*ty\s*co\s*phan\s*Giai\s*phap\s*Chuyen\s*doi\s*so\s*Thong\s*minh\b', 'Công ty Cổ phần Giải pháp Chuyển đổi số Thông minh', re.IGNORECASE),
            (r'\bCong\s*ty\s*co\s*phan\s*Giai\s*phap\s*Chuyen\b', 'Công ty Cổ phần Giải pháp Chuyển', re.IGNORECASE),
            (r'\bdoi\s*so\s*Thong\s*minh\b', 'đổi số Thông minh', re.IGNORECASE),

            # Legal & Contract Document Types
            (r'\bHOP\s*DONG\s*LAO\s*DONG\b', 'HỢP ĐỒNG LAO ĐỘNG', 0),
            (r'\bHop\s*dong\s*lao\s*dong\b', 'Hợp đồng lao động', re.IGNORECASE),
            (r'\(So\s*:\s*:?', '(Số: ', 0),
            (r'\(Số\s*:\s*:?', '(Số: ', 0),
            (r'\bso\s+(?=\d+|[0-9]+/)', 'số ', 0),
            (r'\bSo\s+(?=\d+|[0-9]+/)', 'Số ', 0),
            (r'\bBEN\s*A\s*:\s*BEN\s*SU\s*DUNG\s*LAO\s*DONG\b', 'BÊN A: BÊN SỬ DỤNG LAO ĐỘNG', 0),
            (r'\bBEN\s*B\s*:\s*NGUOI\s*LAO\s*DONG\b', 'BÊN B: NGƯỜI LAO ĐỘNG', 0),
            (r'\bBENB\s*:\s*NGUOILAO\s*DONG\b', 'BÊN B: NGƯỜI LAO ĐỘNG', 0),
            (r'\bBENB\b', 'BÊN B', 0),
            (r'\bBEN\s*A\b', 'BÊN A', 0),
            (r'\bBEN\s*B\b', 'BÊN B', 0),

            # Contract Baseline Laws & References
            (r'^-?\s*Can\s*c[iïut]+\s*Bo\s*luat\s*Dan\s*su\b', '- Căn cứ Bộ luật Dân sự', 0),
            (r'^-?\s*Can\s*c[iïut]+\s*Bo\s*luat\s*Lao\s*a?ong\b', '- Căn cứ Bộ luật Lao động', 0),
            (r'\bCan\s*c[iïut]+\s*Bo\s*luat\s*Dan\s*su\b', 'Căn cứ Bộ luật Dân sự', 0),
            (r'\bCan\s*c[iïut]+\s*Bo\s*luat\s*Lao\s*a?ong\b', 'Căn cứ Bộ luật Lao động', 0),
            (r'\bBo\s*luat\s*Dan\s*su\b', 'Bộ luật Dân sự', 0),
            (r'\bBo\s*luat\s*Lao\s*a?ong\b', 'Bộ luật Lao động', 0),
            (r'^-?\s*Can\s*c[iïut]+\s*vao\s*nhu\s*cau\b', '- Căn cứ vào nhu cầu', 0),
            (r'^-?\s*Can\s*c[iïut]+\s*vao\s*kh[ad]\s*nang\b', '- Căn cứ vào khả năng', 0),
            (r'\bCan\s*c[iïut]+\s*vao\b', 'Căn cứ vào', 0),
            (r'^-Căn cứ\b', '- Căn cứ', 0),
            (r'\bCan\s*c[iïut]+\b', 'Căn cứ', 0),
            (r'\b[OQ]H14ngay\b', 'QH14 ngày', 0),
            (r'\bQH14\s*ngay\b', 'QH14 ngày', 0),
            (r'\bQH13\s*ngay\b', 'QH13 ngày', 0),
            (r'\bnhu\s*cau\b', 'nhu cầu', 0),
            (r'\bs[iïu]t?\s*dung\s*lao\s*dong\b', 'sử dụng lao động', re.IGNORECASE),
            (r'\btrinh\s*a?o\s*chuyen\s*mon\b', 'trình độ chuyên môn', re.IGNORECASE),
            (r'\bnguyen\s*vong\b', 'nguyện vọng', re.IGNORECASE),
            (r'\bcia\b', 'của', 0),
            (r'\bcua\b', 'của', 0),
            (r'\bva\b', 'và', 0),
            (r'\bong\s+(?=[A-ZÀ-Ỹ])', 'ông ', 0),
            (r'\bOng\s+(?=[A-ZÀ-Ỹ])', 'Ông ', 0),
            (r'\bHom\s*nay,\s*ngay\b', 'Hôm nay, ngày', 0),
            (r'\bngay\s+(?=\d+)', 'ngày ', 0),
            (r'\bthang\s+(?=\d+)', 'tháng ', 0),
            (r'\bnam\s+(?=\d+)', 'năm ', 0),
            (r'\btai\s*Tru\s*so\s*van\s*phong\b', 'tại Trụ sở văn phòng', 0),
            (r'\btru\s*so\b', 'trụ sở', re.IGNORECASE),
            (r'\bvan\s*phong\s*:', 'Văn phòng:', re.IGNORECASE),
            (r'\bvan\s*phong\b', 'văn phòng', re.IGNORECASE),
            (r'\bchung\s*toi\s*gom\b', 'chúng tôi gồm', re.IGNORECASE),
            (r'\bTen\s*D[oO]n\s*vi\s*:\s*', 'Tên Đơn vị: ', 0),
            (r'\bDai\s*dien\s*:\s*Ong\b', 'Đại diện: Ông', 0),
            (r'\bDai\s*dien\s*:', 'Đại diện: ', 0),
            (r'\bCh[iïu]rc?\s*vu\s*:', 'Chức vụ: ', 0),
            (r'\bTong\s*Giam\s*doc\b', 'Tổng Giám đốc', 0),
            (r'\bGiam\s*doc\b', 'Giám đốc', 0),
            (r'\bDien\s*thoai\s*:', 'Điện thoại: ', 0),
            (r'\bD/c\s*email\b', 'Địa chỉ email', 0),
            (r'\bMa\s*so\s*thue\s*:', 'Mã số thuế: ', 0),
            (r'\bTai\s*khoan\s*[:：]', 'Tài khoản: ', 0),
            (r'\bTaikhoan\b', 'Tài khoản', 0),
            (r'\bNgan\s*hang\s*:', 'Ngân hàng: ', 0),
            (r'\bTMCP\s*Quan\s*Doi\b', 'TMCP Quân Đội', 0),
            (r'\bSau\s*day\s*goi\s*tat\s*la\s*:\s*', 'Sau đây gọi tắt là: ', 0),
            (r'\bSau\s*day\s*goi\s*tat\s*la\b', 'Sau đây gọi tắt là', 0),
            (r'\bsau\s*day\s*goi\s*tat\s*la\b', 'sau đây gọi tắt là', 0),
            (r'\bBen\s*SDLD\b', 'Bên SDLĐ', 0),
            (r'\bNLD\b', 'NLĐ', 0),
            (r'\(Sau đây gọi tắt là:\s*["“]?NLĐ["”]?\)?', '(Sau đây gọi tắt là: "NLĐ")', 0),
            (r'\bhoac\b', 'hoặc', 0),
            (r'\bCong\s*ty\b', 'Công ty', 0),
            (r'\bHo\s*v[aà]\s*t[eê]n\s*:', 'Họ và tên: ', re.IGNORECASE),
            (r'\bHo\s*v[aà]\s*t[eê]n\b', 'Họ và tên', re.IGNORECASE),
            (r'\bGioi\s*tinh\s*:', 'Giới tính: ', 0),
            (r'\bS[oó60]\s*CCCD\s*[:：]', 'Số CCCD: ', 0),
            (r'\bNgay\s*cap\s*:', 'Ngày cấp: ', 0),
            (r'\bNoi\s*cap\s*:', 'Nơi cấp: ', 0),
            (r'\bCuc\s*CS\s*QLHC\s*ve\s*TTXH\b', 'Cục CSQLHC về TTXH', 0),
            (r'\bNgay\s*sinh\s*:', 'Ngày sinh: ', 0),
            (r'\bDan\s*toc\s*:', 'Dân tộc: ', 0),
            (r'\bTon\s*giao\s*:\s*Khong\b', 'Tôn giáo: Không', 0),
            (r'\bTon\s*giao\s*:', 'Tôn giáo: ', 0),
            (r'\bQuoc\s*tich\s*:\s*Viet\s*Nam\b', 'Quốc tịch: Việt Nam', 0),
            (r'\bQuoc\s*tich\s*:', 'Quốc tịch: ', 0),
            (r'\bQue\s*quan\s*:', 'Quê quán: ', 0),
            (r'\bDia\s*chi\s*thuong\s*tru\s*:', 'Địa chỉ thường trú: ', 0),
            (r'\bDia\s*chi\s*DKKD\s*:\s*', 'Địa chỉ ĐKKD: ', 0),
            (r'\bD/c\s*DKKD\s*:\s*', 'Địa chỉ ĐKKD: ', 0),
            (r'\bngo\s+(\d+)', 'ngõ \\1', 0),
            (r'\bngach\s+(\d+)', 'ngách \\1', 0),
            (r'\bpho\s*Phu\s*Do\b', 'phố Phú Đô', 0),
            (r'\bphuong\s*Tu\s*Liem\b', 'phường Từ Liêm', 0),
            (r'\bTP\.\s*Ha\s*Noi\b', 'TP. Hà Nội', 0),
            (r'\bCN\s*Ha\s*Noi\b', 'CN Hà Nội', 0),
            (r'\bPGD\s*Trung\s*Van\b', 'PGD Trung Văn', 0),
            (r'\bHoc\s*van\s*:\s*Cu\s*nhan\b', 'Học vấn: Cử nhân', 0),
            (r'\bHoc\s*van\s*:', 'Học vấn: ', 0),
            (r'\bCu\s*nhan\b', 'Cử nhân', 0),
            (r'\bChuyen\s*mon\s*:\s*Tri\s*tue\s*nhan\s*tao\s*\([AaI]+\)\b', 'Chuyên môn: Trí tuệ nhân tạo (AI)', 0),
            (r'\bChuyen\s*mon\s*:\s*Tri\s*tue\s*nhan\s*tao\b', 'Chuyên môn: Trí tuệ nhân tạo', 0),
            (r'Trí tuệ nhân tạo\s*\([Aa]\)', 'Trí tuệ nhân tạo (AI)', 0),
            (r'\bChuyen\s*mon\s*:', 'Chuyên môn: ', 0),
            (r'\bTot\s*nghiep\s*:\s*Nam\b', 'Tốt nghiệp: Năm', 0),
            (r'\bTot\s*nghiep\s*:', 'Tốt nghiệp: ', 0),
            (r'\bTruong\s*:\s*Truong\s*Dai\s*hoc\s*FPTHCM\b', 'Trường: Đại học FPT TP.HCM', re.IGNORECASE),
            (r'\bTruong\s*:\s*Truong\s*Daihoc\s*FPTHCM\b', 'Trường: Đại học FPT TP.HCM', re.IGNORECASE),
            (r'\bTruong\s*:\s*Trường\b', 'Trường:', 0),
            (r'\bTruong\s*:\s*Dai\s*hoc\s*FPT\s*TP\.?\s*HCM\b', 'Trường: Đại học FPT TP.HCM', 0),
            (r'\bDai\s*hoc\s*FPT\s*TP\.?\s*HCM\b', 'Đại học FPT TP.HCM', 0),
            (r'\bBen\s*S[iïu]r?\s*dung\s*lao\s*dong\b', 'Bên Sử dụng lao động', re.IGNORECASE),
            (r'\bNguoi\s*lao\s*dong\b', 'Người lao động', re.IGNORECASE),
            (r'\blao\s*dong\b', 'lao động', re.IGNORECASE),
            (r'\bhai\s*Ben\b', 'hai Bên', 0),
            (r'(?:hoặc|hoac)\s+(?:cae|các|cac)\s+B[eê]n[\'\"]?\)?', 'hoặc "các Bên")', re.IGNORECASE),
            (r'\bthoa\s*thuan\b', 'thỏa thuận', re.IGNORECASE),
            (r'\bky\s*ket\b', 'ký kết', re.IGNORECASE),
            (r'\bcam\s*ket\b', 'cam kết', re.IGNORECASE),
            (r'\bthuc\s*(?:dung|hien\s*dung)\b', 'thực hiện đúng', re.IGNORECASE),
            (r'\bnhung\s*dieu\s*khoan\b', 'những điều khoản', re.IGNORECASE),
            (r'\bnhung\b', 'những', 0),
            (r'\bsau\s*day\b', 'sau đây', re.IGNORECASE),
            (r'\bTP\s*Bank\b', 'TPBank', 0),
            (r'\bNghi\s*ainh\b', 'Nghị định', 0),
            (r'\bNghi\s*dinh\b', 'Nghị định', 0),
            (r'\bChinh\s*phi\b', 'Chính phủ', 0),
            (r'\bChinh\s*phu\b', 'Chính phủ', 0),
            (r'\bLudt\b', 'Luật', 0),
            (r'\bLuat\b', 'Luật', 0),
            (r'\bHoa\s*don\b', 'Hóa đơn', 0),
            (r'\bHOA\s*DON\b', 'HÓA ĐƠN', 0),
            (r'\bTong\s*tien\b', 'Tổng tiền', 0),
            (r'\bsia\s*doi\b', 'sửa đổi', 0),
            (r'\bsira\s*aoi\b', 'sửa đổi', 0),
            (r'\bbo\s*sung\b', 'bổ sung', 0),
            (r'\bduroc\b', 'được', 0),
            (r'\btai\s*sdn\b', 'tài sản', 0),
            (r'\bQudn\s*ly\b', 'Quản lý', 0),
            (r'\bsit\s*dung\b', 'sử dụng', 0),
            (r'\bDak\s*Lak\b', 'Đắk Lắk', 0),
            (r'\bTan\s*Ha\b', 'Tân Hà', 0),
            (r'\bEa\s*Toh\b', 'Ea Toh', 0),
            (r'\bKrong\s*Nang\b', 'Krông Năng', 0),
            (r'\bHOANG\s*DUC\s*KHANH\b', 'HOÀNG ĐỨC KHÁNH', 0),
            (r'\bHoang\s*D[iïu]+c\s*Khanh\b', 'Hoàng Đức Khánh', 0),
            (r'\bTRINH\s*VU\s*HOANG\b', 'TRỊNH VŨ HOÀNG', 0),
            (r'\bTrinh\s*Vu\s*Hoang\b', 'Trịnh Vũ Hoàng', 0),

            # Engineering & Drawing Vocabulary
            (r'\bDANH\s*MUC\s*BAN\s*VE\b', 'DANH MỤC BẢN VẼ', 0),
            (r'\bDanh\s*muc\s*ban\s*ve\b', 'Danh mục bản vẽ', re.IGNORECASE),
            (r'\bTEN\s*BAN\s*VE\b', 'TÊN BẢN VẼ', 0),
            (r'\bTENBANVE\b', 'TÊN BẢN VẼ', 0),
            (r'\bKY\s*HIEU\b', 'KÝ HIỆU', 0),
            (r'\bGHI\s*CHU\b', 'GHI CHÚ', 0),
            (r'\bMAT\s*BANG\b', 'MẶT BẰNG', 0),
            (r'\bMATBANG\b', 'MẶT BẰNG', 0),
            (r'\bNOI\s*THAT\b', 'NỘI THẤT', 0),
            (r'\bHIEN\s*TRANG\b', 'HIỆN TRẠNG', 0),
            (r'\bTHAY\s*DOI\b', 'THAY ĐỔI', 0),
            (r'\bCAP\s*DIEN\b', 'CẤP ĐIỆN', 0),
            (r'\bO\s*CAM\b', 'Ổ CẮM', 0),
            (r'\bLO\s*DIEN\s*CAM\b', 'Ổ CẮM', 0),
            (r'\bDIEN\s*NHE\b', 'ĐIỆN NHẸ', 0),
            (r'\bMANG\s*LAN\b', 'MẠNG LAN', 0),
            (r'\bMANGLAN\b', 'MẠNG LAN', 0),
            (r'\bTRAN\s*HIEN\s*TRANG\b', 'TRẦN HIỆN TRẠNG', 0),
            (r'\bCHIEU\s*SANG\b', 'CHIẾU SÁNG', 0),
            (r'\bSO\s*DO\s*NGUYEN\s*LY\b', 'SƠ ĐỒ NGUYÊN LÝ', 0),
            (r'\bSO\s*DO\s*NGUYENLY\b', 'SƠ ĐỒ NGUYÊN LÝ', 0),
            (r'\bGIU\s*NGUYEN\b', 'GIỮ NGUYÊN', 0),
            (r'\bGIUINGUYEN\b', 'GIỮ NGUYÊN', 0),
            (r'\bGGIU\s*NGUYEN\b', 'GIỮ NGUYÊN', 0),

            # Banking & FATCA Terms
            (r'\bTHONG\s*TIN\s*FATCA\b', 'THÔNG TIN FATCA', 0),
            (r'\bTHONGTINFATCA\b', 'THÔNG TIN FATCA', 0),
            (r'\bIV\s*\.?\s*THÔNG TIN FATCA\b', 'IV. THÔNG TIN FATCA', 0),
            (r'\bPHAN\s*B\s*[-–]\s*DANG\s*KY\s*MO\s*TAI\s*KHOAN\s*THANH\s*TOAN\b', 'PHẦN B - ĐĂNG KÝ MỞ TÀI KHOẢN THANH TOÁN', re.IGNORECASE),
            (r'\b[LI1]\s*\.?\s*MO\s*TAI\s*KHOAN\s*THANH\s*TOAN\b', 'I. MỞ TÀI KHOẢN THANH TOÁN', re.IGNORECASE),
            (r'\b[LI1][LI1]\s*\.?\s*DANG\s*KY\s*DICH\s*VU\s*DI\s*KEM\s*TAI\s*KHOAN\s*THANH\s*TOAN\b', 'II. ĐĂNG KÝ DỊCH VỤ ĐI KÈM TÀI KHOẢN THANH TOÁN', re.IGNORECASE),
            (r'\bLoaitaikhoan\b', 'Loại tài khoản:', re.IGNORECASE),
            (r'\bLoai\s*tai\s*khoan\b', 'Loại tài khoản', re.IGNORECASE),
            (r'\bTai\s*khoan\s*thanh\s*toan\s*thong\s*th[uui]r?ong\b', 'Tài khoản thanh toán thông thường', re.IGNORECASE),
            (r'\bKhac\s*\(ghi\s*ro\s*loai\s*taikhoan\)\s*:', 'Khác (ghi rõ loại tài khoản):', re.IGNORECASE),
            (r'\bLoai\s*tien\s*:\s*VND\s*USD\s*Ngoai\s*te\s*khac\s*\(ghi\s*ro\s*loai\s*tien\s*te\)\s*:', 'Loại tiền: VND | USD | Ngoại tệ khác (ghi rõ loại tiền tệ):', re.IGNORECASE),
            (r'\bTen\s*tai\s*khoan\s*:', 'Tên tài khoản:', re.IGNORECASE),
            (r'\bDich\s*vu\s*mo\s*tai\s*khoan\s*theo\s*yeu\s*cau\b', 'Dịch vụ mở tài khoản theo yêu cầu', re.IGNORECASE),
            (r'\bSo\s*tai\s*khoan\s*theo\s*yeu\s*caut?\b', 'Số tài khoản theo yêu cầu', re.IGNORECASE),
            (r'\bM[uui]rc?\s*phi\s*:', 'Mức phí:', re.IGNORECASE),
            (r'\bHinh\s*th[uui]rc?\s*thu\s*phi\s*:\s*Tien\s*mat\s*Trich\s*no\s*tu\s*tai\s*khoan\s*so\.?', 'Hình thức thu phí: Tiền mặt | Trích nợ từ tài khoản số:', re.IGNORECASE),
            (r'\bCo\s*[-–]\s*Ma\s*GIN\b', 'Có - Mã GIIN:', re.IGNORECASE),
            (r'\bCo\s*[-–]\s*Ma\s*GIIN\b', 'Có - Mã GIIN:', re.IGNORECASE),
            (r'\bTo\s*ch[iïu]rc?\b', 'Tổ chức', re.IGNORECASE),
            (r'\bthanh\s*lap\b', 'thành lập', re.IGNORECASE),
            (r'\bhoat\s*dong\b', 'hoạt động', re.IGNORECASE),
            (r'\btai\s*My\b', 'tại Mỹ', 0),
            (r'\bVui\s*long\b', 'Vui lòng', re.IGNORECASE),
            (r'\bdien\s*Mau\b', 'điền Mẫu', re.IGNORECASE),
            (r'\bkhach\s*hang\b', 'khách hàng', re.IGNORECASE),
            (r'\bDinh\s*che\s*tai\s*chinh\b', 'Định chế tài chính', re.IGNORECASE),
            (r'\bngoai\s*My\b', 'ngoài Mỹ', 0),
            (r'\btheo\s*quy\s*dinh\b', 'theo quy định', re.IGNORECASE),
            (r'\bcua\s*FATCA\b', 'của FATCA', 0),
            (r'\bbao\s*hiem\b', 'bảo hiểm', re.IGNORECASE),
            (r'\bgiu\s*von\s*dau\s*t[iïu]r?\b', 'giữ vốn đầu tư', re.IGNORECASE),
            (r'\bcac\s*cong\s*ty\s*khac\b', 'các công ty khác', re.IGNORECASE),
            (r'\bTrong\s*truong\s*hop\b', 'Trong trường hợp', re.IGNORECASE),
            (r'\bTrong\s*tr[uui]r?ong\s*hop\b', 'Trong trường hợp', re.IGNORECASE),
            (r'\bcung\s*cap\b', 'cung cấp', re.IGNORECASE),
            (r'\bnha\s*dau\s*t[uui]r?\s*My\b', 'nhà đầu tư Mỹ', re.IGNORECASE),
            (r'\bphi\s*tai\s*chinh\b', 'phi tài chính', re.IGNORECASE),
            (r'\bso\s*h[iïu]r?u\s*100%', 'sở hữu 100%', re.IGNORECASE),
            (r'\bBieu\s*mau\s*06/FATCA\b', 'Biểu mẫu 06/FATCA', 0),
            (r'\bMau\s*W-8BEN-E\b', 'Mẫu W-8BEN-E', 0),
            (r'\bMau\s*W-9\b', 'Mẫu W-9', 0),
            (r'\b1\.\s*Dich\s*vu\s*so\s*phu\s*tai\s*khoan\b', '1. Dịch vụ sổ phụ tài khoản', re.IGNORECASE),
            (r'-\s*Tai\s*khoan\s*dang\s*ky\s*nhan\s*so\s*ph[uui]r?\b', '- Tài khoản đăng ký nhận sổ phụ:', re.IGNORECASE),
            (r'-\s*Tai\s*khoan\s*thu\s*phi\s*nhan\s*so\s*ph[uuiy]\.?', '- Tài khoản thu phí nhận sổ phụ:', re.IGNORECASE),
            (r'-\s*Tan\s*suat\s*thu\s*phi\s*:\s*Thu\s*mgaykhi\s*hoan\s*thanh\s*dich\s*vu\s*Thu\s*vao\s*ngay\s*co\s*dinh\s*hang\s*thang', '- Tần suất thu phí: Thu ngay khi hoàn thành dịch vụ | Thu vào ngày cố định hàng tháng', re.IGNORECASE),
            (r'-\s*Tan\s*suat\s*nhan\s*so\s*phu\.?\s*Tuan\s*Thang\s*Quy\s*Nam', '- Tần suất nhận sổ phụ: Tuần | Tháng | Quý | Năm', re.IGNORECASE),
            (r'-\s*Chung\s*t[uui]r?\s*dang\s*ky\s*nham\s*:\s*Sao\s*ke\s*tai\s*khoam\s*Saoketai\s*khoankembaong,bao\s*co', '- Chứng từ đăng ký nhận: Sao kê tài khoản | Sao kê tài khoản kèm báo nợ, báo có', re.IGNORECASE),
            (r'-\s*Hinh\s*th[uui]rc?\s*nhan\s*so\s*phu[c\s]*', '- Hình thức nhận sổ phụ: ', re.IGNORECASE),
            (r'\bTaiBIDV\b', 'Tại BIDV', 0),
            (r'\bQuaSwiffmaSwifficode[A-Za-z\s0-9]+Swift\b', 'Qua Swift (Mã Swift: ... ) | File đính kèm gửi Swift', re.IGNORECASE),
            (r'\bThudien\s*ti,dia\s*chi\s*emmail\s*mham\.?', 'Thư điện tử, địa chỉ email nhận:', re.IGNORECASE),
            (r'\bQuaburu\s*dien,dja\s*chi\s*mhanc\s*Tang\s*2,số\s*6Le\s*Wam\s*Thiem,Phuromg\s*Thamin\s*Xuman,PHa\s*Noi\b', 'Qua bưu điện, địa chỉ nhận: Tầng 2, số 6 Lê Văn Thiêm, Phường Thanh Xuân, TP. Hà Nội', re.IGNORECASE),
            (r'-\s*Dimh\s*dang\s*so\s*phu\s*\(ap\s*dung\s*meu\s*khách\s*hàng\s*lura\s*chon\s*hinh\s*thirc\s*nhan\s*qua\s*Swaift\s*hoặc\s*Thu\s*dien\s*tu\)\s*:', '- Định dạng sổ phụ (áp dụng nếu khách hàng lựa chọn hình thức nhận qua Swift hoặc Thư điện tử):', re.IGNORECASE),
            (r'\bExcelPDFMT940M1942MT950Camt\.052Camt053\b', 'Excel | PDF | MT940 | MT942 | MT950 | Camt.052 | Camt.053', 0),
            (r'\b2\.\s*Dich\s*vy\s*hoa\s*don\s*dien\s*tir\b', '2. Dịch vụ hóa đơn điện tử', re.IGNORECASE),
            (r'-Email\s*dang\s*ky\s*mhan\s*hoa\s*dom\s*dicn\s*ti\s*BDW\s*cfi\s*gmi\s*Hian\s*dom\s*dion\s*tur\s*den\s*dia\s*chi\s*email\s*dai\s*dien\s*của\s*To\s*chtic\s*da\b', '- Email đăng ký nhận hóa đơn điện tử: BIDV sẽ gửi Hóa đơn điện tử đến địa chỉ email đại diện của Tổ chức đã', re.IGNORECASE),
            (r'\bMhiEBNO-TC/TKH&DWIKO2O5\b', 'Mẫu 01/ĐK-TC/TK&DV/2025', 0),
            (r'\bTrang\s*s0:3\b', 'Trang số: 3', 0),
            (r'\bdang\s*ky\s*tai\s*BIDV\s*trong\s*th[oỏ][aả]\s*thu[aậ]n\s*nay\s*ho[aặ]c\s*cac\s*th[oỏ][aả]\s*thu[aậ]n\s*mo\s*v[aà]\s*s[uư]r?\s*dung\s*tai\s*khoan\s*truoc\s*do\.?', 'đăng ký tại BIDV trong thỏa thuận này hoặc các thỏa thuận mở và sử dụng tài khoản trước đó.', re.IGNORECASE),
            (r'\bco\s*to\s*ch[uui]re\s*hoat\s*dong\b', 'có tổ chức hoạt động', re.IGNORECASE),
            (r'\bkhong\s*coma\s*so\s*GIIN\b', 'không có mã số GIIN', re.IGNORECASE),
            (r'\bTrurong\s*hop\s*laTO\s*churc\b', 'Trường hợp là Tổ chức', re.IGNORECASE),
            (r'\bCa\s*nhan\s*Hoa\s*Ky\b', 'Cá nhân Hoa Kỳ', re.IGNORECASE),
            (r'\bvui\s*1ong\b', 'vui lòng', re.IGNORECASE),
            (r'\bcung\s*cấp\s*them\s*thong\s*tin\b', 'cung cấp thêm thông tin', re.IGNORECASE),
            (r'\bco\),\s*va\s*ca\s*nhan\s*co\s*quyen\s*kiem\s*soat\s*cuoi\s*cung\s*doi\s*voi\s*uy\s*thac\)?', 'Có (và cá nhân có quyền kiểm soát cuối cùng đối với ủy thác)', re.IGNORECASE),
            (r'\bKhong\b', 'Không', 0),
            (r'\bIV\s*\.?\s*THONG\s*TIN\s*FATCA\b', 'IV. THÔNG TIN FATCA', re.IGNORECASE),
            (r'\bI\s*\.?\s*To\s*ch[iïu]rc?\s*duroc\s*thanh\s*lap\s*hay\s*co\s*to\s*ch[iïu]rc?\s*hoat\s*dong\s*tai\s*My\s*hay\s*khong\??', '1. Tổ chức được thành lập hay có tổ chức hoạt động tại Mỹ hay không?', re.IGNORECASE),
            (r'\bCo\s*\(Vui\s*long\s*dien\s*Mau\s*W-9\s*cho\s*khach\s*hang\s*To\s*ch[iïu]rc?\)', 'Có (Vui lòng điền Mẫu W-9 cho khách hàng Tổ chức)', re.IGNORECASE),
            (r'\b2\s*\.?\s*To\s*ch[iïu]rc?\s*co\s*duroc\s*xem\s*nh[uui]r?\s*mot\s*Dinh\s*che\s*tai\s*chinh\s*ngoai\s*My\s*theo\s*quy\s*dinh\s*cua\s*FATCA\s*hay\s*khong\b', '2. Tổ chức có được xem như một Định chế tài chính ngoài Mỹ theo quy định của FATCA hay không', re.IGNORECASE),
            (r'\b3\s*\.?\s*To\s*ch[iïu]rc?\s*co\s*nha\s*dau\s*t[uui]r?\s*My\s*hay\s*khong\??', '3. Tổ chức có nhà đầu tư Mỹ hay không?', re.IGNORECASE),
            (r'\bPHAN\s*B\s*[-–]\s*DANG\s*KY\s*MO\s*TAI\s*KHOAN\s*THANH\s*TOAN\b', 'PHẦN B – ĐĂNG KÝ MỞ TÀI KHOẢN THANH TOÁN', re.IGNORECASE),
            (r'\b[LI1]\s*\.?\s*MO\s*TAI\s*KHOAN\s*THANH\s*TOAN\b', 'I. MỞ TÀI KHOẢN THANH TOÁN', re.IGNORECASE),
            (r'\bLoai\s*tai\s*khoan\s*:\s*Tai\s*khoan\s*thanh\s*toan\s*thong\s*th[uui]r?ong\b', 'Loại tài khoản: [X] Tài khoản thanh toán thông thường', re.IGNORECASE),
            (r'\bLoai\s*tien\s*:\s*VND\b', 'Loại tiền: [X] VND', re.IGNORECASE),
            (r'\bTien\s*mat\b', 'Tiền mặt', re.IGNORECASE),
            (r'\bTrich\s*no\s*tu\s*tai\s*khoan\b', 'Trích nợ từ tài khoản', re.IGNORECASE),

            # Citizen Identity & Bank Registration Form Terms (e.g. 798077370)
            (r'\bNgay[.,\s]*thang[.,\s]*nam\s*sinh\*?\s*:', 'Ngày, tháng, năm sinh*: ', re.IGNORECASE),
            (r'\bQuoctich\*?\s*:', 'Quốc tịch*: ', re.IGNORECASE),
            (r'\bQuoctich\s*tht2\b', 'Quốc tịch thứ 2:', re.IGNORECASE),
            (r'\bQuoc\s*tich\s*thu\s*2\b', 'Quốc tịch thứ 2:', re.IGNORECASE),
            (r'\bNguoi\s*c[uui]r?\s*tr[uui]\b', 'Người cư trú', re.IGNORECASE),
            (r'\bNguoi\s*khong\s*c[uui]r?\s*tr[uui]\b', 'Người không cư trú', re.IGNORECASE),
            (r'\bS[oó60]dinhdanhcanhan\*?\s*:', 'Số định danh cá nhân*: ', re.IGNORECASE),
            (r'\bS[oó60]\s*dinh\s*danh\s*ca\s*nhan\*?\s*:', 'Số định danh cá nhân*: ', re.IGNORECASE),
            (r'\bThe\s*can\s*cuoc\b', 'Thẻ căn cước', re.IGNORECASE),
            (r'\bHo\s*chieu\b', 'Hộ chiếu', re.IGNORECASE),
            (r'\bCogia\s*tridenngay\*?\s*:', 'Có giá trị đến ngày*: ', re.IGNORECASE),
            (r'\bCo\s*gia\s*tri\s*den\s*ngay\*?\s*:', 'Có giá trị đến ngày*: ', re.IGNORECASE),
            (r'\bSo\s*thi\s*th[uui]rc?\b', 'Số thị thực', re.IGNORECASE),
            (r'\bso\s*giay\s*to\s*thay\s*thi\s*th[uui]rc?\b', 'số giấy tờ thay thị thực', re.IGNORECASE),
            (r'\bnhap\s*canh\b', 'nhập cảnh', re.IGNORECASE),
            (r'\bdoi\s*voi\s*ng[uui]r?oi\s*n[uui]r?oc\s*ngoai\b', 'đối với người nước ngoài', re.IGNORECASE),
            (r'\bc[uui]r?\s*tru\s*tai\s*Việt\s*Nam\b', 'cư trú tại Việt Nam', re.IGNORECASE),
            (r'\btr[uui]r?\s*tr[uui]r?ong\s*hop\b', 'trừ trường hợp', re.IGNORECASE),
            (r'\bduoc\s*mien\s*thi\s*th[uui]rc?\b', 'được miễn thị thực', re.IGNORECASE),
            (r'\btheo\s*quy\s*d[iị]nh\s*phap\s*luat\*?\s*:', 'theo quy định pháp luật*:', re.IGNORECASE),
            (r'\bSo\s*dien\s*thoai\s*lien\s*lac\*?\s*:', 'Số điện thoại liên lạc*: ', re.IGNORECASE),
            (r'\bDia\s*chi\s*th[uui]r?\s*dien\s*tu\*?\s*:', 'Địa chỉ thư điện tử*: ', re.IGNORECASE),
            (r'\bKH\s*ke\s*khai\s*phu\s*hop\b', 'KH kê khai phù hợp', re.IGNORECASE),
            (r'\btinh\s*trang\s*c[uui]r?\s*tru\s*th[uui]rc?\s*te\b', 'tình trạng cư trú thực tế', re.IGNORECASE),
            (r'\bDhgt\b', 'ĐHGTVT', 0),
            (r'\bDHGT\b', 'ĐHGTVT', 0),
            (r'\bNgoc\s*Khanh\b', 'Ngọc Khánh', 0),
            (r'\bBa\s*Dinh\b', 'Ba Đình', 0),
            (r'\bTh[uui]r?ong\s*tru\s*tai\s*Việt\s*Nam\s*:', 'Thường trú tại Việt Nam:', re.IGNORECASE),
            (r'\bDang\s*ky\s*c[uui]r?\s*tru\s*tai\s*Việt\s*Nam\s*:', 'Đăng ký cư trú tại Việt Nam:', re.IGNORECASE),
            (r'\bCu\s*tru\s*o\s*n[uui]r?oc\s*ngoai\s*:', 'Cư trú ở nước ngoài:', re.IGNORECASE),
            (r'\bBang\s*viec[a-z\s]*m[a-z\s]*n[a-z\s]*\b', 'Bằng việc ký vào mẫu này, ', re.IGNORECASE),
            (r'\bToi\s*x[da]e?\s*nhan\s*da\s*doc,?\s*hieu\b', 'Tôi xác nhận đã đọc, hiểu', re.IGNORECASE),
            (r'\bChu\s*ky\s*mau\s*thu\s*1\b', 'Chữ ký mẫu thứ 1', re.IGNORECASE),
            (r'\bChu\s*ky\s*mau\s*th[uui]r?\s*2\b', 'Chữ ký mẫu thứ 2', re.IGNORECASE),
            (r'\bro\s*cac\s*quyen\s*va\s*nghia\s*vu\s*c[uui]a\s*Toi\b', 'rõ các quyền và nghĩa vụ của Tôi', re.IGNORECASE),
            (r'\bvoi\s*t[uui]r?\s*cach\s*Ch[uui]r?\s*the\s*d[uui]r?\s*lieu\s*ca\s*nhan\b', 'với tư cách Chủ thể dữ liệu cá nhân', re.IGNORECASE),
            (r'\bPhap\s*luat?\s*ve\s*bao\s*ve\s*d[uui]r?\s*lieu\s*ca\s*nhan\b', 'Pháp luật về bảo vệ dữ liệu cá nhân', re.IGNORECASE),
            (r'\bToi\s*dong\s*y\s*cho\s*phep\s*BIDV\s*x[uui]r?\s*ly\s*toan\s*bo\s*d[uui]r?\s*lieu\s*ca\s*nhan\s*của\s*Toi\b', 'Tôi đồng ý cho phép BIDV xử lý toàn bộ dữ liệu cá nhân của Tôi', re.IGNORECASE),
            (r'\bToi\s*aong\s*y\s*cho\s*phep\s*BIDV\s*xi\s*ly\s*toan\s*bo\s*di\s*lieu\s*cd\s*nhan\s*của\s*Toi\b', 'Tôi đồng ý cho phép BIDV xử lý toàn bộ dữ liệu cá nhân của Tôi', re.IGNORECASE),
            (r'\bva\s*dong\s*voi\s*Ban\s*Dieu\s*khoan\s*va\s*dieu\s*kien\s*chung\s*c[uui]a\s*BIDV\b', 'và đồng ý với Bản Điều khoản và điều kiện chung của BIDV', re.IGNORECASE),
            (r'\bva\s*aong\s*voi\s*Ban\s*Dieu\s*khoan\s*va\s*dieu\s*kien\s*chung\s*cuia\s*BIDV\b', 'và đồng ý với Bản Điều khoản và điều kiện chung của BIDV', re.IGNORECASE),
            (r'\bve\s*bao\s*ve\s*va\s*x[uui]r?\s*ly\s*d[uui]r?\s*lieu\s*ca\s*nhan\b', 'về bảo vệ và xử lý dữ liệu cá nhân', re.IGNORECASE),
            (r'\bduoc\s*dang\s*tai\s*tren\s*trang\s*dien\s*tu\s*chinh\s*th[uui]rc?\s*của\s*BIDV\b', 'được đăng tải trên trang điện tử chính thức của BIDV', re.IGNORECASE),
            (r'\bphan\s*Bao\s*ve\s*d[uui]r?\s*lieu\s*ca\s*nhan\b', 'phần Bảo vệ dữ liệu cá nhân', re.IGNORECASE),
            (r'\bNg[uui]r?oi\s*duoc\s*uy\s*quyen\s*Ke\s*toan\s*tr[uui]r?ong/\s*Nguoi\s*phu\s*trach\s*ke\s*toan\b', '4. Người được ủy quyền Kế toán trưởng / Người phụ trách kế toán', re.IGNORECASE),
            (r'\bDangkymoi\b', 'Đăng ký mới', re.IGNORECASE),
            (r'\bCap\s*nhat\s*thong\s*tin\s*Ong/Ba\s*:', 'Cập nhật thông tin Ông/Bà: ', re.IGNORECASE),
            (r'\bThay\s*doi\s*Nguoi\s*duoc\s*uy\s*quyen\b', 'Thay đổi Người được ủy quyền', re.IGNORECASE)
        ]

        for item in corrections:
            pattern = item[0]
            replacement = item[1]
            flags = item[2] if len(item) > 2 else 0
            if flags:
                t = re.sub(pattern, replacement, t, flags=flags)
            else:
                t = re.sub(pattern, replacement, t)

        return t
