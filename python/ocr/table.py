import cv2
import numpy as np
from typing import List, Tuple, Dict, Any
from core.document import Table, TableCell, BoundingBox

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
