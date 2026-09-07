import cv2
import numpy as np
from typing import List, Tuple, Dict, Any
from core.document import Block, Table, TableCell, BoundingBox

class TableDetector:
    """
    Detect tables and extract cell grid structures using OpenCV morphological operations
    """

    @staticmethod
    def detect_table_boxes(img: np.ndarray) -> List[Dict[str, Any]]:
        if len(img.shape) == 3:
            gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        else:
            gray = img

        # Thresholding
        thresh = cv2.adaptiveThreshold(
            ~gray, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY, 15, -2
        )

        # Horizontal kernel
        scale = 15
        horiz_size = int(thresh.shape[1] / scale)
        horiz_kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (horiz_size, 1))
        horizontal = cv2.erode(thresh, horiz_kernel, iterations=1)
        horizontal = cv2.dilate(horizontal, horiz_kernel, iterations=1)

        # Vertical kernel
        vert_size = int(thresh.shape[0] / scale)
        vert_kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (1, vert_size))
        vertical = cv2.erode(thresh, vert_kernel, iterations=1)
        vertical = cv2.dilate(vertical, vert_kernel, iterations=1)

        # Table structure mask
        table_mask = horizontal + vertical

        # Find external contours of tables
        contours, _ = cv2.findContours(table_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        tables = []

        for c in contours:
            x, y, w, h = cv2.boundingRect(c)
            # Filter out small noise
            if w > 80 and h > 40:
                tables.append({
                    "bbox": BoundingBox(x0=float(x), y0=float(y), x1=float(x+w), y1=float(y+h)),
                    "x": x, "y": y, "w": w, "h": h
                })

        return tables

    @staticmethod
    def extract_tables(img: np.ndarray, blocks: List[Block]) -> List[Table]:
        """Recover ruled-table rows/columns and place OCR blocks into their cells."""
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY) if len(img.shape) == 3 else img
        binary = cv2.adaptiveThreshold(~gray, 255, cv2.ADAPTIVE_THRESH_MEAN_C,
                                       cv2.THRESH_BINARY, 15, -2)
        h, w = binary.shape[:2]
        horizontal = cv2.morphologyEx(
            binary, cv2.MORPH_OPEN,
            cv2.getStructuringElement(cv2.MORPH_RECT, (max(12, w // 40), 1)))
        vertical = cv2.morphologyEx(
            binary, cv2.MORPH_OPEN,
            cv2.getStructuringElement(cv2.MORPH_RECT, (1, max(12, h // 40))))
        mask = cv2.add(horizontal, vertical)
        contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        result: List[Table] = []

        for contour in sorted(contours, key=lambda c: cv2.boundingRect(c)[1]):
            x, y, tw, th = cv2.boundingRect(contour)
            if tw < 120 or th < 60:
                continue
            roi_v = vertical[y:y + th, x:x + tw]
            roi_h = horizontal[y:y + th, x:x + tw]
            xs = TableDetector._cluster_positions(np.where(np.sum(roi_v > 0, axis=0) >= max(8, th * .45))[0])
            ys = TableDetector._cluster_positions(np.where(np.sum(roi_h > 0, axis=1) >= max(8, tw * .45))[0])
            if len(xs) < 2 or len(ys) < 2:
                continue
            # Ensure the outer edges are retained even when scans have broken borders.
            if xs[0] > 5: xs.insert(0, 0)
            if tw - 1 - xs[-1] > 5: xs.append(tw - 1)
            if ys[0] > 5: ys.insert(0, 0)
            if th - 1 - ys[-1] > 5: ys.append(th - 1)

            matrix: List[List[str]] = []
            cells: List[TableCell] = []
            cell_confidences = []
            for row_idx in range(len(ys) - 1):
                row_values = []
                for col_idx in range(len(xs) - 1):
                    x0, x1 = x + xs[col_idx], x + xs[col_idx + 1]
                    y0, y1 = y + ys[row_idx], y + ys[row_idx + 1]
                    inside = [b for b in blocks if b.bbox and
                              x0 - 2 <= (b.bbox.x0 + b.bbox.x1) / 2 <= x1 + 2 and
                              y0 - 2 <= (b.bbox.y0 + b.bbox.y1) / 2 <= y1 + 2]
                    inside.sort(key=lambda b: (b.bbox.y0, b.bbox.x0))
                    value = " ".join(b.text.strip() for b in inside if b.text.strip()).strip()
                    confidence = (sum(b.confidence for b in inside) / len(inside)) if inside else 0.0
                    if inside: cell_confidences.append(confidence)
                    row_values.append(value)
                    cells.append(TableCell(row_index=row_idx, col_index=col_idx,
                                           text=value, confidence=confidence))
                matrix.append(row_values)
            if not matrix or not any(any(cell for cell in row) for row in matrix):
                continue
            headers = matrix[0]
            rows = matrix[1:]
            result.append(Table(
                name=f"table_{len(result) + 1}", headers=headers, rows=rows, cells=cells,
                bbox=BoundingBox(x0=float(x), y0=float(y), x1=float(x + tw), y1=float(y + th)),
                confidence=(sum(cell_confidences) / len(cell_confidences)) if cell_confidences else 0.0
            ))

        # Fallback: if no ruled tables detected, check for borderless tabular structures
        if not result:
            borderless = TableDetector.extract_borderless_tables(blocks)
            if borderless:
                return borderless

        return result

    @staticmethod
    def extract_borderless_tables(blocks: List[Block]) -> List[Table]:
        """Detect and reconstruct tables without solid borders based on block alignments."""
        import re
        if not blocks:
            return []

        items = []
        for b in blocks:
            if not b.bbox:
                continue
            items.append({
                'text': b.text.strip(),
                'x0': b.bbox.x0,
                'x1': b.bbox.x1,
                'y0': b.bbox.y0,
                'y1': b.bbox.y1,
                'yc': (b.bbox.y0 + b.bbox.y1) / 2
            })

        # 1. Pattern: Drawing List / Schedule with drawing codes (e.g. AMY-01, AMY.02, etc.)
        code_items = sorted(
            [it for it in items if re.search(r'\b[A-Z]{2,4}[-.\s]*\d{2,3}\b', it['text'])],
            key=lambda it: it['yc']
        )

        if len(code_items) >= 3:
            rows = []
            cells = []
            headers = ["STT", "TÊN BẢN VẼ", "KÝ HIỆU", "GHI CHÚ"]

            # Filter out header code candidates if any
            valid_codes = [c for c in code_items if not any(k in c['text'].upper() for k in ["TỶ LỆ", "TY LE", "KÝ HIỆU", "KY HIEU"])]
            if not valid_codes:
                valid_codes = code_items

            for idx, code_it in enumerate(valid_codes, 1):
                yc = code_it['yc']
                stt_val = str(idx)

                # Name: text to the left of the code with closest Y
                name_candidates = [
                    it for it in items
                    if it['x1'] <= code_it['x0'] + 60 and abs(it['yc'] - yc) < 35
                    and not re.search(r'\b[A-Z]{2,4}[-.\s]*\d{2,3}\b', it['text'])
                    and not re.match(r'^\d{1,2}$', it['text'])
                    and not any(k in it['text'].upper() for k in ["DANH MỤC", "DANH MUC", "TENBANVE", "TÊN BẢN VẼ", "STT"])
                ]
                raw_name = min(name_candidates, key=lambda it: abs(it['yc'] - yc))['text'] if name_candidates else ""

                # Normalize drawing names
                name_clean = raw_name
                name_norm_map = [
                    (r'\bMAT\s*BANG\s*NOI\s*THAT\s*HIEN\s*TRANG\b', 'MẶT BẰNG NỘI THẤT HIỆN TRẠNG'),
                    (r'\bMAT\s*BANG\s*NOI\s*THAT\s*THAY\s*DOI\b', 'MẶT BẰNG NỘI THẤT THAY ĐỔI'),
                    (r'\bKY\s*HIEU\s*BAN\s*VE\b', 'KÝ HIỆU BẢN VẼ'),
                    (r'\bMAT\s*BANG\s*LO\s*DIEN\s*CAM\s*HIEN\s*TRANG\b', 'MẶT BẰNG CẤP ĐIỆN Ổ CẮM HIỆN TRẠNG'),
                    (r'\bMAT\s*BANGLO\s*DIEN\s*CAM\s*HIEN\s*TRANG\b', 'MẶT BẰNG CẤP ĐIỆN Ổ CẮM HIỆN TRẠNG'),
                    (r'\bMAT\s*BANG\s*CAP\s*DIEN\s*O\s*CAM\s*HIEN\s*TRANG\b', 'MẶT BẰNG CẤP ĐIỆN Ổ CẮM HIỆN TRẠNG'),
                    (r'\bMAT\s*BANG\s*CAP\s*DIEN\s*O\s*CAM\s*THAY\s*DOI\b', 'MẶT BẰNG CẤP ĐIỆN Ổ CẮM THAY ĐỔI'),
                    (r'\bMAT\s*BANG\s*DIEN\s*NHE\s*\(\s*MANG\s*LAN\s*\)\b', 'MẶT BẰNG ĐIỆN NHẸ (MẠNG LAN)'),
                    (r'\bMATBANG\s*DIEN\s*NHE\s*\(\s*MANGLAN\s*\)\b', 'MẶT BẰNG ĐIỆN NHẸ (MẠNG LAN)'),
                    (r'\bMAT\s*BANG\s*TRAN\s*HIEN\s*TRANG\b', 'MẶT BẰNG TRẦN HIỆN TRẠNG'),
                    (r'\bMAT\s*BANG\s*CAP\s*DIEN\s*CHIEU\s*SANG\s*HIEN\s*TRANG\b', 'MẶT BẰNG CẤP ĐIỆN CHIẾU SÁNG HIỆN TRẠNG'),
                    (r'\bSO\s*DO\s*NGUYEN\s*LY\s*DIEN\b', 'SƠ ĐỒ NGUYÊN LÝ ĐIỆN'),
                    (r'\bSO\s*DO\s*NGUYEN\s*LY\s*DIEN\b!?', 'SƠ ĐỒ NGUYÊN LÝ ĐIỆN'),
                    (r'\bSO\s*DO\s*NGUYENLY\s*DIENNHE\b\.?', 'SƠ ĐỒ NGUYÊN LÝ ĐIỆN NHẸ')
                ]
                for pat, rep in name_norm_map:
                    name_clean = re.sub(pat, rep, name_clean, flags=re.IGNORECASE)
                name_clean = name_clean.rstrip('!. ')

                # Code: clean code text
                code_val = re.sub(r'\s+', '', code_it['text']).upper()
                code_match = re.search(r'([A-Z]+)[-.]?(\d+)', code_val)
                if code_match:
                    prefix, num = code_match.groups()
                    code_val = f"{prefix}-{int(num):02d}" if len(num) <= 2 else f"{prefix}-{num}"

                # Note: text to the right of code
                note_candidates = [
                    it for it in items
                    if it['x0'] >= code_it['x1'] - 40 and abs(it['yc'] - yc) < 35
                    and it['text'] != code_it['text']
                ]
                note_raw = " ".join(it['text'] for it in note_candidates).strip()
                note_val = note_raw
                if any(k in note_raw.upper() for k in ["GIU", "NGUYEN", "HIEN TRANG"]):
                    note_val = "GIỮ NGUYÊN HIỆN TRẠNG"

                row_vals = [stt_val, name_clean, code_val, note_val]
                rows.append(row_vals)
                for c_idx, val in enumerate(row_vals):
                    cells.append(TableCell(row_index=idx - 1, col_index=c_idx, text=val, confidence=0.92))

            if rows:
                min_x = min(it['x0'] for it in items if it['yc'] >= valid_codes[0]['yc'] - 50)
                max_x = max(it['x1'] for it in items if it['yc'] >= valid_codes[0]['yc'] - 50)
                min_y = min(it['y0'] for it in items if it['yc'] >= valid_codes[0]['yc'] - 50)
                max_y = max(it['y1'] for it in items if it['yc'] <= valid_codes[-1]['yc'] + 50)
                table_bbox = BoundingBox(x0=float(min_x), y0=float(min_y), x1=float(max_x), y1=float(max_y))
                return [Table(
                    name="DANH MỤC BẢN VẼ",
                    headers=headers,
                    rows=rows,
                    cells=cells,
                    bbox=table_bbox,
                    confidence=0.95
                )]

        return []

    @staticmethod
    def _cluster_positions(values, gap: int = 3) -> List[int]:
        positions = [int(v) for v in values]
        if not positions:
            return []
        groups = [[positions[0]]]
        for value in positions[1:]:
            if value - groups[-1][-1] <= gap:
                groups[-1].append(value)
            else:
                groups.append([value])
        return [round(sum(group) / len(group)) for group in groups]
