from typing import Dict, Any, List, Optional
import os
import re

class TemplateEngine:
    """
    Template mapping engine:
    Takes structured data, template schema, and mappings to produce final context for exporters.
    """

    @staticmethod
    def map_context(document_dict: Dict[str, Any], schema: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """
        Combines fields and tables into a flat & hierarchical context for template filling.
        """
        fields = document_dict.get("fields", {})
        metadata = document_dict.get("metadata", {})
        tables = document_dict.get("tables", [])

        context: Dict[str, Any] = {}

        # Stable Vietnamese administrative constants. OCR should only provide
        # variable content; these values must not depend on scan quality.
        context.update({
            "country_name": "CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM",
            "national_header": "CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM",
            "national_motto": "Độc lập - Tự do - Hạnh phúc",
            "motto": "Độc lập - Tự do - Hạnh phúc",
        })

        # 1. Direct fields
        for k, v in fields.items():
            context[k] = v

        # Rebuild volatile administrative fields from raw text at render time.
        # This prevents an old extraction record from duplicating headers/body.
        raw_text = document_dict.get("raw_text", "") or ""
        derived = TemplateEngine._derive_vietnamese_administrative(raw_text)
        context.update(derived)
        if not context.get("content"):
            context["content"] = raw_text.strip()
        if not context.get("title"):
            context["title"] = TemplateEngine._derive_generic_title(document_dict.get("pages", []), raw_text)

        # 2. Metadata fields
        for k, v in metadata.items():
            context[f"meta_{k}"] = v

        # 3. Tables
        if tables and len(tables) > 0:
            context["table"] = tables[0]
            context["items"] = []
            headers = tables[0].get("headers", [])
            for row in tables[0].get("rows", []):
                item_dict = {}
                for idx, col_val in enumerate(row):
                    h_name = headers[idx] if idx < len(headers) else f"col_{idx}"
                    item_dict[h_name] = col_val
                    item_dict[f"col_{idx}"] = col_val
                context["items"].append(item_dict)

        # 4. Apply schema mapping aliases if defined
        if schema and "mappings" in schema:
            for template_var, doc_path in schema["mappings"].items():
                value = TemplateEngine._resolve_path(context, doc_path)
                if value is not None:
                    context[template_var] = value

        return context

    @staticmethod
    def _resolve_path(context: Dict[str, Any], path: str):
        current: Any = context
        for part in str(path).split("."):
            if not isinstance(current, dict) or part not in current:
                return None
            current = current[part]
        return current

    @staticmethod
    def _derive_vietnamese_administrative(text: str) -> Dict[str, Any]:
        lower = text.lower()
        if not text or not (
            "cộng hòa xã hội chủ nghĩa việt nam" in lower
            or "cong hoa xa hoi chu nghia viet nam" in lower
        ):
            return {}
        lines = [re.sub(r'\s+', ' ', line).strip() for line in text.splitlines() if line.strip()]
        result: Dict[str, Any] = {}

        authority = next((line for line in lines[:8] if line.isupper()
                          and "CỘNG HÒA" not in line and "ĐỘC LẬP" not in line), "")
        if authority:
            result["issuing_authority"] = authority

        number = re.search(r'(?im)^\s*số\s*[:：]?\s*([^\n\r]+)', text)
        if number:
            result["document_number"] = number.group(1).strip()

        recipient = re.search(r'(?im)^\s*kính\s+gửi\s*[:：]\s*(.+)$', text)
        if recipient:
            result["recipient"] = recipient.group(1).strip()

        place = re.search(r'(?im)^\s*([^,\n]{2,40}),\s*ngày\b', text)
        if place:
            result["place"] = place.group(1).strip()
        date = re.search(r'(?i)(\d{1,2})\s+tháng\s+(\d{1,2})\s+năm\s+(\d{4})', text)
        if date:
            result.update({"day": date.group(1), "month": date.group(2), "year": date.group(3)})
        else:
            year = re.search(r'(?i)ngày\s*.*?tháng\s*.*?năm\s*(\d{4})', text)
            if year:
                result["year"] = year.group(1)

        subject_index = next((i for i, line in enumerate(lines) if re.match(r'(?i)^v\s*/\s*v\b', line)), -1)
        if subject_index >= 0:
            parts = [lines[subject_index]]
            for line in lines[subject_index + 1:subject_index + 4]:
                if re.match(r'(?i)^(cộng hòa|độc lập|.+ngày\s+.*tháng)', line):
                    break
                parts.append(line)
            result["title"] = " ".join(parts)

        recipient_index = next((i for i, line in enumerate(lines) if "kính gửi" in line.lower()), -1)
        if recipient_index >= 0:
            result["content"] = "\n".join(lines[recipient_index + 1:]).strip()
        return result

    @staticmethod
    def _derive_generic_title(pages: List[Dict[str, Any]], raw_text: str) -> str:
        candidates = []
        if pages:
            for block in pages[0].get("blocks", []):
                text = str(block.get("text", "")).strip()
                if block.get("tag") != "Title" or not (4 <= len(text) <= 180):
                    continue
                letters = [c for c in text if c.isalpha()]
                ratio = sum(c.isupper() for c in letters) / max(1, len(letters))
                if ratio >= 0.75 and not re.match(r'^\s*(?:trang\s+)?\d+\s*$', text, re.I):
                    candidates.append(text)
        if candidates:
            return " ".join(candidates[:4])
        return next((line.strip() for line in raw_text.splitlines() if len(line.strip()) >= 5), "Tài liệu")
