import hashlib
from pathlib import Path
from typing import Dict, List


class TemplateSchemaCompiler:
    """Build a stable, reviewable schema for an inspected Office template."""

    VERSION = 1
    ALIASES = {
        "document_no": "document_number",
        "number": "document_number",
        "agency": "issuing_authority",
        "authority": "issuing_authority",
        "to": "recipient",
        "subject": "title",
        "body": "content",
        "location": "place",
        "signer": "signer_name",
        "position": "signer_title",
        "seller": "supplier",
        "buyer": "customer",
        "amount": "total_amount",
    }
    CANONICAL_FIELDS = {
        "archive_code", "authority_code", "chairperson", "conclusion", "content", "customer",
        "date", "day", "document_number", "invoice_number", "issuing_authority", "month",
        "participants", "place", "recipient", "secretary", "signer_name", "signer_title",
        "summary", "supplier", "tax_code", "time", "title", "total_amount", "year",
        "address", "amount_in_words", "attachments", "buyer_address", "buyer_tax_code",
        "items", "payer", "reason", "receipt_number", "seller_address", "subtotal", "tax_amount",
        "parent_authority", "urgency", "objectives", "implementation", "difficulties", "solutions",
        "author", "supervisor", "introduction", "methodology", "results", "references",
        "full_name", "birth_date", "gender", "hometown", "residence", "organization",
        "position", "professional_qualification", "academic_qualification", "assigned_duties",
        "achievements", "commendation_year", "commendation_title", "commendation_decision",
        "award_year", "award_form", "award_decision",
    }

    @classmethod
    def fingerprint(cls, template_path: str) -> str:
        digest = hashlib.sha256()
        with open(template_path, "rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()

    @classmethod
    def compile(cls, template_path: str, template_type: str, placeholders: List[str]) -> Dict:
        mappings = {}
        unmapped = []
        for name in placeholders:
            target = cls.ALIASES.get(name, name)
            if target in cls.CANONICAL_FIELDS:
                mappings[name] = target
            else:
                unmapped.append(name)
        return {
            "schema_version": cls.VERSION,
            "template_fingerprint": cls.fingerprint(template_path),
            "template_type": template_type,
            "template_name": Path(template_path).name,
            "placeholders": placeholders,
            "mappings": mappings,
            "unmapped": unmapped,
            "status": "needs_review" if unmapped else "auto_mapped",
            "approved": False,
        }
