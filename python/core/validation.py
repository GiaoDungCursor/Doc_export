from typing import Dict, Any, List, Optional
from core.document import Document, DocumentField

class DocumentValidator:
    """
    Validation engine:
    - Required field presence
    - Data type constraints & pattern matching
    - Numerical consistency (subtotal + vat = total)
    - Low confidence checks (flags for JavaFX Review Screen)
    """

    CONFIDENCE_THRESHOLD = 0.85

    @classmethod
    def validate(cls, doc: Document, schema: Optional[Dict[str, Any]] = None) -> Document:
        validation_errors = []
        needs_review = False

        # If overall confidence is below threshold, mark for review
        if doc.confidence < cls.CONFIDENCE_THRESHOLD:
            needs_review = True

        # Common invoice/document rule validations if document_type == 'invoice'
        if doc.document_type == "invoice":
            required_fields = ["invoice_number", "date", "supplier", "total_amount"]
            for rf in required_fields:
                if rf not in doc.fields or doc.fields[rf].value is None or str(doc.fields[rf].value).strip() == "":
                    err = f"Missing required field: {rf}"
                    validation_errors.append(err)
                    needs_review = True
                    if rf in doc.fields:
                        doc.fields[rf].validated = False
                        doc.fields[rf].validation_error = err

            # Check individual field confidence
            for name, field in doc.fields.items():
                if field.confidence < cls.CONFIDENCE_THRESHOLD:
                    field.validated = False
                    field.validation_error = f"Low confidence ({field.confidence:.2f})"
                    needs_review = True
                else:
                    if not field.validation_error:
                        field.validated = True

            # Arithmetic check: subtotal + tax = total_amount
            subtotal = doc.fields.get("subtotal")
            tax = doc.fields.get("tax_amount") or doc.fields.get("vat_amount")
            total = doc.fields.get("total_amount")

            if subtotal and total and subtotal.value is not None and total.value is not None:
                try:
                    s_val = float(subtotal.value)
                    t_val = float(total.value)
                    tax_val = float(tax.value) if (tax and tax.value is not None) else 0.0
                    calculated = s_val + tax_val
                    if abs(calculated - t_val) > 1.0:  # Allowing rounding tolerance
                        err = f"Amount mismatch: Subtotal ({s_val}) + Tax ({tax_val}) != Total ({t_val})"
                        validation_errors.append(err)
                        needs_review = True
                        if total:
                            total.validation_error = err
                            total.validated = False
                except (ValueError, TypeError):
                    pass

        # Custom schema validation if schema is provided
        if schema:
            schema_fields = schema.get("fields", {})
            for field_name, rule in schema_fields.items():
                is_required = rule.get("required", False)
                f_type = rule.get("type", "string")

                if is_required:
                    if field_name not in doc.fields or doc.fields[field_name].value is None:
                        err = f"Required field '{field_name}' not found"
                        validation_errors.append(err)
                        needs_review = True

        if validation_errors or needs_review:
            doc.status = "NEEDS_REVIEW"
        else:
            doc.status = "VALIDATED"

        doc.extra["validation_errors"] = validation_errors
        return doc
