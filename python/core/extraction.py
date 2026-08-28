import os
import sys
import re
import shutil
import uuid
from typing import Dict, Any, Optional
from core.document import Document, DocumentMetadata, DocumentField, DocumentPage, Table, BoundingBox
from core.normalize import DataNormalizer
from core.text_layout import normalize_document_pages
from pdf.processor import PdfProcessor
from ocr.engine import OcrEngine

class ExtractionPipeline:
    """
    Intelligent Hybrid Document Extraction Pipeline:
    1. PyMuPDF extracts digital vector text, fonts, tables & renders page preview images in <0.1s.
    2. PaddleOCR v4 (Deep Learning OCR) runs for scanned pages, images (PNG/JPG), or when text layer is absent.
    3. OpenCV preprocessing (deskew, noise filter, CLAHE contrast).
    4. Heuristic & Pattern-based field extraction with BoundingBox links.
    5. Data normalization.
    """

    def __init__(self, cache_dir: str = "app-data/cache"):
        self.ocr_engine = OcrEngine()
        self.cache_dir = cache_dir
        os.makedirs(self.cache_dir, exist_ok=True)

    def process(self, file_path: str, doc_type: str = "generic", force_ocr: bool = False) -> Document:
        if not os.path.exists(file_path):
            raise FileNotFoundError(f"File not found: {file_path}")

        file_ext = os.path.splitext(file_path)[1].lower()
        filename = os.path.basename(file_path)
        file_size = os.path.getsize(file_path)

        metadata = DocumentMetadata(
            source_path=file_path,
            filename=filename,
            file_type=file_ext.replace(".", ""),
            file_size=file_size
        )

        pages = []
        raw_text_parts = []
        tables = []

        if file_ext == ".pdf":
            # 1. Fast PyMuPDF Layout Parsing & Page Image Cache
            pages = PdfProcessor.process_pdf(file_path, cache_dir=self.cache_dir)
            metadata.page_count = len(pages)

            for p_idx, page in enumerate(pages):
                tables.extend(page.tables)

                # Check if page has digital text
                has_digital_text = bool(page.text and len(page.text.strip()) > 20 and len(page.blocks) > 0)

                if force_ocr or not has_digital_text:
                    # Run PaddleOCR v4 on scanned page
                    if page.image_path and os.path.exists(page.image_path):
                        ocr_blocks = self.ocr_engine.process_image(page.image_path)
                        if ocr_blocks:
                            sys.stderr.write(f"[PaddleOCR v4] Scanned page {p_idx + 1}: extracted {len(ocr_blocks)} text blocks\n")
                            page.blocks = ocr_blocks
                            ocr_text = "\n".join([b.text for b in ocr_blocks])
                            page.text = ocr_text
                            raw_text_parts.append(ocr_text)
                        else:
                            raw_text_parts.append(page.text)
                    else:
                        raw_text_parts.append(page.text)
                else:
                    # Fast digital path
                    raw_text_parts.append(page.text)

        elif file_ext in [".png", ".jpg", ".jpeg", ".tiff", ".bmp"]:
            # Image files always run full PaddleOCR v4
            img_cache_name = f"img_{uuid.uuid4().hex[:12]}{file_ext}"
            cached_img_path = os.path.abspath(os.path.join(self.cache_dir, img_cache_name))
            shutil.copyfile(file_path, cached_img_path)

            ocr_blocks = self.ocr_engine.process_image(file_path)
            sys.stderr.write(f"[PaddleOCR v4] Image {filename}: extracted {len(ocr_blocks)} text blocks\n")

            ocr_text = "\n".join([b.text for b in ocr_blocks])
            raw_text_parts.append(ocr_text)
            page = DocumentPage(
                page_number=1,
                image_path=cached_img_path,
                text=ocr_text,
                blocks=ocr_blocks
            )
            pages.append(page)
            metadata.page_count = 1

        full_raw_text = normalize_document_pages(pages)

        # Infer document type
        inferred_type = doc_type
        text_lower = full_raw_text.lower()
        if inferred_type == "generic":
            has_national_header = (
                "cộng hòa xã hội chủ nghĩa việt nam" in text_lower
                or "cong hoa xa hoi chu nghia viet nam" in text_lower
            )
            invoice_patterns = [
                r'hóa\s+đơn\s+(?:giá\s+trị\s+gia\s+tăng|bán\s+hàng)', r'số\s+hóa\s+đơn',
                r'mã\s+số\s+thuế', r'người\s+mua', r'tổng\s+(?:tiền|thanh\s+toán)',
                r'invoice\s+(?:no|number)', r'tax\s+(?:code|id)', r'total\s+amount'
            ]
            invoice_score = sum(bool(re.search(pattern, text_lower)) for pattern in invoice_patterns)
            if invoice_score >= 2:
                inferred_type = "invoice"
            elif re.search(r'(?im)^\s*(quyết định|quyet dinh)\s*$', full_raw_text): inferred_type = "decision"
            elif re.search(r'(?im)^\s*(tờ trình|to trinh)\s*$', full_raw_text): inferred_type = "proposal"
            elif re.search(r'(?im)^\s*(biên bản|bien ban)\s*$', full_raw_text): inferred_type = "minutes"
            elif any(k in text_lower for k in ["phiếu thu", "phieu thu"]): inferred_type = "receipt"
            elif re.search(r'(?im)^\s*(báo cáo|bao cao)\s*$', full_raw_text): inferred_type = "report"
            elif re.search(r'(?im)^\s*(thông báo|thong bao)\s*$', full_raw_text): inferred_type = "notice"
            elif re.search(r'(?im)^\s*(kế hoạch|ke hoach)\s*$', full_raw_text): inferred_type = "plan"
            elif re.search(r'(?im)^\s*(giấy mời|giay moi)\s*$', full_raw_text): inferred_type = "invitation"
            elif has_national_header and ("kính gửi" in text_lower or "kinh gui" in text_lower): inferred_type = "official"
            elif any(k in text_lower for k in ["công văn", "cong van"]): inferred_type = "official"
            else:
                contract_patterns = [r'(?im)^\s*(hợp đồng|hop dong|contract)\b', r'số\s+hợp\s+đồng',
                                     r'\bbên\s+a\b', r'\bbên\s+b\b', r'contract\s+(?:no|number)']
                if sum(bool(re.search(pattern, text_lower)) for pattern in contract_patterns) >= 2:
                    inferred_type = "contract"
                elif any(k in text_lower for k in ["báo cáo", "bao cao", "report"]): inferred_type = "report"

        # Extract structured fields with bounding box mapping
        fields = self._extract_fields(pages, full_raw_text, inferred_type)

        # Compute confidence
        conf_scores = [f.confidence for f in fields.values()]
        avg_conf = sum(conf_scores) / len(conf_scores) if conf_scores else 0.95

        doc = Document(
            document_type=inferred_type,
            metadata=metadata,
            pages=pages,
            fields=fields,
            tables=tables,
            confidence=avg_conf,
            status="EXTRACTED",
            raw_text=full_raw_text
        )

        return doc

    def _extract_fields(self, pages, text: str, doc_type: str) -> Dict[str, DocumentField]:
        fields: Dict[str, DocumentField] = {}
        lines_with_bbox = []
        for p in pages:
            for b in p.blocks:
                for l in b.lines:
                    lines_with_bbox.append((l.text.strip(), l.bbox, p.page_number))

        lines = [line.strip() for line in text.splitlines() if line.strip()]

        def find_line_bbox(matched_str):
            for l_txt, bbox, p_num in lines_with_bbox:
                if matched_str.lower() in l_txt.lower() or l_txt.lower() in matched_str.lower():
                    return bbox, p_num
            return None, 1

        if doc_type == "invoice":
            for line in lines:
                m = re.search(r'(?:so\s*hoa\s*don|s[oóòõọôốồỗộ]\s*h[oóòõọôốồỗộaáàãạ]\s*đ[oơớờỡợeêi]n|s[oóòõọôốồỗộ]\s*[:#]|invoice\s*(?:no\.?|number))\s*[:#]?\s*([A-Za-z0-9\-_/]+)', line, re.IGNORECASE)
                if m:
                    bbox, p_num = find_line_bbox(line)
                    fields["invoice_number"] = DocumentField(
                        name="invoice_number", label="Số hóa đơn", value=m.group(1).strip(),
                        raw_value=line, confidence=0.98, source_bbox=bbox, page_number=p_num
                    )
                    break

            for line in lines:
                m = re.search(r'(?:ngay|ng[aàáãạâầấẫậ]y|date|l[aậ]p\s*ng[aàáãạ]y)\s*[:]?\s*(\d{1,2}[/-]\d{1,2}[/-]\d{4}|[^\n\r]+)', line, re.IGNORECASE)
                if m:
                    d_str, ok = DataNormalizer.normalize_date(m.group(1).strip())
                    if ok:
                        bbox, p_num = find_line_bbox(line)
                        fields["date"] = DocumentField(
                            name="date", label="Ngày lập", value=d_str,
                            raw_value=line, data_type="date", confidence=0.96, source_bbox=bbox, page_number=p_num
                        )
                        break

            for line in lines:
                m = re.search(r'(?:ma\s*so\s*thue|m[aãáà]s[oóòõọôốồỗộ]\s*thu[eêếềễệ]|mst|tax\s*code|vat\s*code)\s*[:]?\s*([\d\-]+)', line, re.IGNORECASE)
                if m:
                    tax_code = DataNormalizer.normalize_tax_id(m.group(1).strip())
                    bbox, p_num = find_line_bbox(line)
                    fields["tax_code"] = DocumentField(
                        name="tax_code", label="Mã số thuế", value=tax_code,
                        raw_value=line, confidence=0.98, source_bbox=bbox, page_number=p_num
                    )
                    break

            for line in lines:
                m = re.search(r'(?:don\s*vi\s*ban|đ[oơớờỡợ]?n\s*v[iị]\s*b[aáàãạ]n|nh[aàáãạ]s*cung\s*c[aấầẩẫậ]p|seller|supplier|cong\s*ty\s*ban|c[oô]ng\s*ty\s*b[aáàãạ]n)\s*[:]?\s*(.+)', line, re.IGNORECASE)
                if m:
                    bbox, p_num = find_line_bbox(line)
                    fields["supplier"] = DocumentField(
                        name="supplier", label="Đơn vị bán / Nhà cung cấp", value=m.group(1).strip(),
                        raw_value=line, confidence=0.95, source_bbox=bbox, page_number=p_num
                    )
                    break

            for line in lines:
                m = re.search(r'(?:nguoi\s*mua|ng[uưừứửữự][oơờớởỡợ]i\s*mua|khach\s*hang|kh[aáàãạ]ch\s*h[aàáãạ]ng|customer|buyer|don\s*vi\s*mua|đ[oơớờỡợ]?n\s*v[iị]\s*mua)\s*[:]?\s*(.+)', line, re.IGNORECASE)
                if m:
                    bbox, p_num = find_line_bbox(line)
                    fields["customer"] = DocumentField(
                        name="customer", label="Khách hàng / Người mua", value=m.group(1).strip(),
                        raw_value=line, confidence=0.93, source_bbox=bbox, page_number=p_num
                    )
                    break

            for line in lines:
                m = re.search(r'(?:tong\s*(?:thanh\s*toan|cong|tien)|t[oôổ]ng\s*(?:thanh\s*to[aáàãạ]n|c[oộ]ng|ti[eêềếễệ]n)|total\s*amount)\s*[:]?\s*([\d.,]+)', line, re.IGNORECASE)
                if m:
                    num_val, ok = DataNormalizer.normalize_number(m.group(1))
                    if ok:
                        bbox, p_num = find_line_bbox(line)
                        fields["total_amount"] = DocumentField(
                            name="total_amount", label="Tổng tiền thanh toán", value=num_val,
                            raw_value=line, data_type="number", confidence=0.98, source_bbox=bbox, page_number=p_num
                        )
                        break

            for line in lines:
                m = re.search(r'(?:cong\s*tien\s*hang|c[oộ]ng\s*ti[eêềếễệ]n\s*h[aàáãạ]ng|tien\s*hang|ti[eêềếễệ]n\s*h[aàáãạ]ng|subtotal)\s*[:]?\s*([\d.,]+)', line, re.IGNORECASE)
                if m:
                    num_val, ok = DataNormalizer.normalize_number(m.group(1))
                    if ok:
                        bbox, p_num = find_line_bbox(line)
                        fields["subtotal"] = DocumentField(
                            name="subtotal", label="Tiền hàng (Subtotal)", value=num_val,
                            raw_value=line, data_type="number", confidence=0.95, source_bbox=bbox, page_number=p_num
                        )
                        break

            for line in lines:
                m = re.search(r'(?:tien\s*thue|ti[eêềếễệ]n\s*thu[eêếềễệ]\s*gtgt|ti[eêềếễệ]n\s*thu[eêếềễệ]|tax\s*amount|vat\s*amount)\s*[:]?\s*([\d.,]+)', line, re.IGNORECASE)
                if m:
                    num_val, ok = DataNormalizer.normalize_number(m.group(1))
                    if ok:
                        bbox, p_num = find_line_bbox(line)
                        fields["tax_amount"] = DocumentField(
                            name="tax_amount", label="Tiền thuế GTGT", value=num_val,
                            raw_value=line, data_type="number", confidence=0.95, source_bbox=bbox, page_number=p_num
                        )
                        break

        elif doc_type == "contract":
            # Contract specific fields
            for line in lines:
                m = re.search(r'(?:so\s*h[o\u00f3\u00f2\u00f5\u1ecd\u00f4\u1ed1\u1ed3\u1ed7\u1ed9a\u00e1\u00e0\u00e3\u1ea1]\s*\u0111[o\u01a1\u1edb\u1edd\u1ee1\u1ee3e\u00eai]ng|s[o\u00f3\u00f2\u00f5\u1ecd\u00f4\u1ed1\u1ed3\u1ed7\u1ed9]\s*[:#]|contract\s*no)\s*[:#]?\s*([A-Za-z0-9\-_/]+)', line, re.IGNORECASE)
                if m:
                    bbox, p_num = find_line_bbox(line)
                    fields["contract_number"] = DocumentField(
                        name="contract_number", label="Số hợp đồng", value=m.group(1).strip(),
                        raw_value=line, confidence=0.95, source_bbox=bbox, page_number=p_num
                    )
                    break
            fields["title"] = DocumentField(
                name="title", label="Tên hợp đồng", value=lines[0] if lines else "Hợp đồng", confidence=0.9
            )

        elif doc_type in {"official", "decision", "report", "proposal", "minutes", "receipt",
                        "notice", "plan", "invitation"}:
            title_line = ""
            if doc_type == "official":
                subject_index = next((i for i, line in enumerate(lines)
                                      if re.match(r'(?i)^\s*v\s*/\s*v\b', line)), -1)
                if subject_index >= 0:
                    subject_parts = [lines[subject_index]]
                    for line in lines[subject_index + 1:subject_index + 4]:
                        if re.match(r'(?i)^(cộng hòa|độc lập|.+ngày\s+.*tháng)', line): break
                        subject_parts.append(line)
                    title_line = " ".join(subject_parts).strip()
            if not title_line:
                title_line = next((line for line in lines if re.match(
                    r'(?i)^\s*(công văn|quyết định|báo cáo(?:\s+chuyên đề)?|tờ trình|biên bản|phiếu thu|thông báo|kế hoạch|giấy mời)\s*$', line)), "")
            if not title_line:
                recipient_index = next((i for i, line in enumerate(lines) if "kính gửi" in line.lower()), -1)
                candidates = lines[max(0, recipient_index - 3):recipient_index] if recipient_index > 0 else lines[:10]
                title_line = next((line for line in reversed(candidates) if not re.match(
                    r'(?i)^(số\s*:|cộng hòa|độc lập|.+ngày\s+\d*\s*tháng|bộ\s+|sở\s+|ủy ban)', line)),
                    lines[0] if lines else "Văn bản")

            fixed_header_patterns = (
                r'(?i)^cộng hòa xã hội chủ nghĩa việt nam$',
                r'(?i)^độc lập\s*[-–]\s*tự do\s*[-–]\s*hạnh phúc$',
            )
            recipient_index = next((i for i, line in enumerate(lines) if "kính gửi" in line.lower()), -1)
            title_index = lines.index(title_line) if title_line in lines else -1
            body_start = recipient_index + 1 if recipient_index >= 0 else title_index + 1
            content_lines = lines[body_start:] if body_start > 0 else list(lines)
            content_lines = [line for line in content_lines if not any(re.match(pattern, line.strip())
                             for pattern in fixed_header_patterns)]
            if title_line in content_lines:
                content_lines.remove(title_line)
            fields["title"] = DocumentField(name="title", label="Tiêu đề", value=title_line, confidence=0.92)
            fields["content"] = DocumentField(name="content", label="Nội dung",
                value="\n".join(content_lines).strip(), confidence=0.90)

            number_match = re.search(r'(?im)^\s*số\s*[:：]?\s*([^\n\r]+)', text)
            if number_match:
                fields["document_number"] = DocumentField(name="document_number", label="Số văn bản",
                    value=number_match.group(1).strip(), confidence=0.96)

            date_match = re.search(r'(?i)(\d{1,2})\s+tháng\s+(\d{1,2})\s+năm\s+(\d{4})', text)
            if date_match:
                day, month, year = date_match.groups()
                fields["day"] = DocumentField(name="day", label="Ngày", value=day, confidence=0.95)
                fields["month"] = DocumentField(name="month", label="Tháng", value=month, confidence=0.95)
                fields["year"] = DocumentField(name="year", label="Năm", value=year, confidence=0.95)
                fields["date"] = DocumentField(name="date", label="Ngày lập", value=f"{day}/{month}/{year}", confidence=0.95)
            else:
                year_match = re.search(r'(?i)ngày\s*.*?tháng\s*.*?năm\s*(\d{4})', text)
                if year_match:
                    fields["year"] = DocumentField(name="year", label="Năm",
                        value=year_match.group(1), confidence=0.90)

            place_match = re.search(r'(?im)^\s*([^,\n]{2,40}),\s*ngày\b', text)
            if place_match:
                fields["place"] = DocumentField(name="place", label="Địa danh",
                    value=place_match.group(1).strip(), confidence=0.92)

            recipient_match = re.search(r'(?im)^\s*kính\s+gửi\s*[:：]\s*(.+)$', text)
            if recipient_match:
                fields["recipient"] = DocumentField(name="recipient", label="Nơi nhận",
                    value=recipient_match.group(1).strip(), confidence=0.94)

            authority = next((line for line in lines[:8] if line.isupper() and
                              "CỘNG HÒA" not in line and "ĐỘC LẬP" not in line), "")
            if authority:
                fields["issuing_authority"] = DocumentField(name="issuing_authority", label="Cơ quan ban hành",
                    value=authority, confidence=0.88)

            # Reconcile fields against content markers as PDF text layers can
            # interleave the two header columns into a single physical line.
            from templates.engine import TemplateEngine
            administrative = TemplateEngine._derive_vietnamese_administrative(text)
            labels = {
                "issuing_authority": "Cơ quan ban hành", "document_number": "Số văn bản",
                "recipient": "Nơi nhận", "place": "Địa danh", "day": "Ngày",
                "month": "Tháng", "year": "Năm", "title": "Tiêu đề", "content": "Nội dung",
            }
            for name, value in administrative.items():
                if name in labels and value not in (None, ""):
                    fields[name] = DocumentField(name=name, label=labels[name], value=value, confidence=0.94)

        else:
            title_candidates = []
            if pages:
                for block in pages[0].blocks:
                    value = (block.text or "").strip()
                    if block.tag == "Title" and 4 <= len(value) <= 180:
                        letters = [c for c in value if c.isalpha()]
                        uppercase_ratio = sum(c.isupper() for c in letters) / max(1, len(letters))
                        if uppercase_ratio >= 0.75 and not re.match(r'^\s*(trang\s+)?\d+\s*$', value, re.I):
                            title_candidates.append(value)
            inferred_title = " ".join(title_candidates[:4]).strip()
            fields["title"] = DocumentField(
                name="title", label="Tiêu đề tài liệu",
                value=inferred_title or (lines[0] if lines else "Tài liệu"), confidence=0.88
            )
            fields["content"] = DocumentField(
                name="content", label="Nội dung", value=text.strip(), confidence=0.90
            )

        # Common labelled fields used by Vietnamese personnel/achievement forms.
        # Values remain editable in Document Parsing before template export.
        form_patterns = {
            "full_name": ("Họ tên", r'(?im)^\s*-?\s*họ\s+tên[^:]*:\s*([^\n]+)'),
            "birth_date": ("Ngày sinh", r'(?im)^\s*-?\s*sinh\s+ngày[^:]*:\s*(.+?)(?=\s+giới\s+tính\s*:|$)'),
            "gender": ("Giới tính", r'(?im)giới\s+tính\s*:\s*([^\n]+)'),
            "hometown": ("Quê quán", r'(?im)^\s*-?\s*quê\s+quán\s*\d*\s*:\s*([^\n]+)'),
            "residence": ("Trú quán", r'(?im)^\s*-?\s*trú\s+quán\s*:\s*([^\n]+)'),
            "organization": ("Đơn vị công tác", r'(?im)^\s*-?\s*đơn\s+vị\s+công\s+tác\s*:\s*([^\n]+)'),
            "position": ("Chức vụ", r'(?im)^\s*-?\s*chức\s+vụ[^:]*:\s*([^\n]+)'),
            "professional_qualification": ("Trình độ chuyên môn", r'(?im)^\s*-?\s*trình\s+độ\s+chuyên\s+môn[^:]*:\s*([^\n]+)'),
            "academic_qualification": ("Học hàm, học vị", r'(?im)^\s*-?\s*học\s+hàm[^:]*:\s*([^\n]+)'),
            "assigned_duties": ("Quyền hạn, nhiệm vụ", r'(?im)^\s*1\.\s*quyền\s+hạn[^:]*:\s*([^\n]+)'),
            "achievements": ("Thành tích đạt được", r'(?im)^\s*2\.\s*thành\s+tích[^:]*:\s*([^\n]+)'),
        }
        for name, (label, pattern) in form_patterns.items():
            match = re.search(pattern, text)
            if not match:
                continue
            value = match.group(1).strip(" .…\t")
            if value:
                fields[name] = DocumentField(name=name, label=label, value=value, confidence=0.90)

        return fields
