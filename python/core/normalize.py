import re
import datetime
from typing import Any, Optional, Tuple

class DataNormalizer:
    """
    Data normalization pipeline:
    - Standardizes date formats into ISO YYYY-MM-DD
    - Normalizes numeric and currency values (e.g. 1.250.000,00 -> 1250000.0)
    - Normalizes tax codes, invoice numbers, telephone numbers
    - Cleans OCR artifacts, surrogates & whitespace
    """

    @staticmethod
    def clean_text(text: Optional[str]) -> str:
        if not text:
            return ""
        # Remove surrogates and unprintable control chars
        text = text.encode('utf-8', 'replace').decode('utf-8')
        text = re.sub(r'[\r\t\f\v]', ' ', text)
        text = re.sub(r' +', ' ', text)
        return text.strip()

    @staticmethod
    def normalize_date(text: Optional[str]) -> Tuple[Optional[str], bool]:
        if not text:
            return None, False

        clean = text.strip()

        # Vietnamese style: Ngày 25 tháng 08 năm 2026
        vn_match = re.search(r'ng[àa]y\s*(\d{1,2})\s*th[áa]ng\s*(\d{1,2})\s*n[ăa]m\s*(\d{4})', clean, re.IGNORECASE)
        if vn_match:
            d, m, y = int(vn_match.group(1)), int(vn_match.group(2)), int(vn_match.group(3))
            try:
                dt = datetime.date(y, m, d)
                return dt.strftime("%Y-%m-%d"), True
            except ValueError:
                pass

        # Standard formats: DD/MM/YYYY or DD-MM-YYYY
        dmy_match = re.search(r'\b(\d{1,2})[/-](\d{1,2})[/-](\d{4})\b', clean)
        if dmy_match:
            d, m, y = int(dmy_match.group(1)), int(dmy_match.group(2)), int(dmy_match.group(3))
            try:
                dt = datetime.date(y, m, d)
                return dt.strftime("%Y-%m-%d"), True
            except ValueError:
                pass

        # ISO format: YYYY-MM-DD or YYYY/MM/DD
        ymd_match = re.search(r'\b(\d{4})[/-](\d{1,2})[/-](\d{1,2})\b', clean)
        if ymd_match:
            y, m, d = int(ymd_match.group(1)), int(ymd_match.group(2)), int(ymd_match.group(3))
            try:
                dt = datetime.date(y, m, d)
                return dt.strftime("%Y-%m-%d"), True
            except ValueError:
                pass

        return clean, False

    @staticmethod
    def normalize_number(text: Optional[str]) -> Tuple[Optional[float], bool]:
        if text is None:
            return None, False

        if isinstance(text, (int, float)):
            return float(text), True

        clean = str(text).strip()
        # Remove currency symbols (VND, đ, $, €)
        clean = re.sub(r'[^\d.,\-]', '', clean)
        if not clean:
            return None, False

        try:
            if '.' in clean and ',' in clean:
                if clean.rfind('.') < clean.rfind(','):
                    clean = clean.replace('.', '').replace(',', '.')
                else:
                    clean = clean.replace(',', '')
            elif ',' in clean:
                parts = clean.split(',')
                if len(parts) == 2 and len(parts[1]) <= 2:
                    clean = clean.replace(',', '.')
                else:
                    clean = clean.replace(',', '')
            elif '.' in clean:
                parts = clean.split('.')
                if len(parts) > 2:
                    clean = clean.replace('.', '')
                elif len(parts) == 2 and len(parts[1]) == 3:
                    clean = clean.replace('.', '')

            val = float(clean)
            return val, True
        except ValueError:
            return None, False

    @staticmethod
    def normalize_tax_id(text: Optional[str]) -> Optional[str]:
        if not text:
            return None
        match = re.search(r'\b(\d{10}(?:-\d{3})?)\b', text)
        return match.group(1) if match else text.strip()
