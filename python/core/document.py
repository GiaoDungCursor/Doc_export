from typing import List, Dict, Any, Optional
from pydantic import BaseModel, Field
import json
import uuid
import datetime

class BoundingBox(BaseModel):
    x0: float = 0.0
    y0: float = 0.0
    x1: float = 0.0
    y1: float = 0.0

class Word(BaseModel):
    text: str
    bbox: Optional[BoundingBox] = None
    confidence: float = 1.0

class Line(BaseModel):
    text: str
    words: List[Word] = Field(default_factory=list)
    bbox: Optional[BoundingBox] = None
    confidence: float = 1.0

class Block(BaseModel):
    block_type: str = "text"  # text, title, table, image, header, invoice_field
    text: str = ""
    lines: List[Line] = Field(default_factory=list)
    bbox: Optional[BoundingBox] = None
    confidence: float = 1.0
    tag: Optional[str] = "Text"

class TableCell(BaseModel):
    row_index: int
    col_index: int
    row_span: int = 1
    col_span: int = 1
    text: str = ""
    confidence: float = 1.0

class Table(BaseModel):
    id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    name: Optional[str] = "table"
    headers: List[str] = Field(default_factory=list)
    rows: List[List[str]] = Field(default_factory=list)
    cells: List[TableCell] = Field(default_factory=list)
    bbox: Optional[BoundingBox] = None
    confidence: float = 1.0

class DocumentField(BaseModel):
    name: str
    label: Optional[str] = None
    value: Any = None
    raw_value: Optional[str] = None
    data_type: str = "string"  # string, number, date, boolean
    confidence: float = 1.0
    validated: bool = False
    validation_error: Optional[str] = None
    source_bbox: Optional[BoundingBox] = None
    page_number: int = 1

class DocumentPage(BaseModel):
    page_number: int
    width: float = 0.0
    height: float = 0.0
    image_path: Optional[str] = None
    text: str = ""
    blocks: List[Block] = Field(default_factory=list)
    tables: List[Table] = Field(default_factory=list)

class DocumentMetadata(BaseModel):
    id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    source_path: str
    filename: str
    file_type: str = "pdf"  # pdf, png, jpg, tiff
    file_size: int = 0
    created_at: str = Field(default_factory=lambda: datetime.datetime.now(datetime.timezone.utc).isoformat())
    page_count: int = 1

class Document(BaseModel):
    id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    document_type: str = "generic"  # invoice, contract, receipt, report, generic
    metadata: DocumentMetadata
    pages: List[DocumentPage] = Field(default_factory=list)
    fields: Dict[str, DocumentField] = Field(default_factory=dict)
    tables: List[Table] = Field(default_factory=list)
    confidence: float = 1.0
    status: str = "NEW"  # NEW, PROCESSING, EXTRACTED, VALIDATED, NEEDS_REVIEW, EXPORTED, ERROR
    raw_text: str = ""
    extra: Dict[str, Any] = Field(default_factory=dict)

    def to_canonical_dict(self) -> Dict[str, Any]:
        """Output clean structured dictionary for templates, workbench UI, and application layer"""
        fields_dict = {}
        for k, v in self.fields.items():
            fields_dict[k] = v.value

        tables_data = []
        for t in self.tables:
            table_dict = {
                "name": t.name,
                "headers": t.headers,
                "rows": t.rows
            }
            tables_data.append(table_dict)

        pages_data = []
        for p in self.pages:
            pages_data.append(p.model_dump())

        return {
            "id": self.id,
            "document_type": self.document_type,
            "metadata": self.metadata.model_dump(),
            "pages": pages_data,
            "fields": fields_dict,
            "field_details": {k: v.model_dump() for k, v in self.fields.items()},
            "tables": tables_data,
            "confidence": self.confidence,
            "status": self.status,
            "raw_text": self.raw_text
        }
