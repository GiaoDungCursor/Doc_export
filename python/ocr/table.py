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
        return result

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
