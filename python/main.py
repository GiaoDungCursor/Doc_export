import sys
import os
import json
import logging

# Reconfigure standard streams to UTF-8
if hasattr(sys.stdin, 'reconfigure'):
    sys.stdin.reconfigure(encoding='utf-8', errors='replace')
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
if hasattr(sys.stderr, 'reconfigure'):
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from core.document import Document
from core.extraction import ExtractionPipeline
from core.validation import DocumentValidator
from templates.excel import ExcelTemplateParser
from templates.word import WordTemplateParser
from templates.engine import TemplateEngine
from templates.schema import TemplateSchemaCompiler
from templates.auto_adapter import WordTemplateAutoAdapter
from exporters.excel import ExcelExporter
from exporters.word import WordExporter

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s", stream=sys.stderr)
logger = logging.getLogger("python-sidecar")

extraction_pipeline = ExtractionPipeline()

def clean_surrogates(obj):
    if isinstance(obj, str):
        return obj.encode('utf-8', 'replace').decode('utf-8')
    elif isinstance(obj, dict):
        return {clean_surrogates(k): clean_surrogates(v) for k, v in obj.items()}
    elif isinstance(obj, list):
        return [clean_surrogates(item) for item in obj]
    return obj

def handle_ping(req_id: str, payload: dict) -> dict:
    return {
        "id": req_id,
        "success": True,
        "data": {
            "status": "ok",
            "message": "Python Sidecar is ready"
        }
    }

def handle_extract(req_id: str, payload: dict) -> dict:
    file_path = payload.get("path")
    if not file_path:
        return {"id": req_id, "success": False, "error": "Missing 'path' parameter in input"}

    doc_type = payload.get("document_type", "generic")
    doc = extraction_pipeline.process(file_path, doc_type=doc_type)
    validated_doc = DocumentValidator.validate(doc)

    return {
        "id": req_id,
        "success": True,
        "data": validated_doc.to_canonical_dict()
    }

def handle_validate(req_id: str, payload: dict) -> dict:
    doc_dict = payload.get("document")
    schema = payload.get("schema")
    if not doc_dict:
        return {"id": req_id, "success": False, "error": "Missing 'document' parameter in input"}

    doc = Document.model_validate(doc_dict)
    validated = DocumentValidator.validate(doc, schema=schema)
    return {
        "id": req_id,
        "success": True,
        "data": validated.to_canonical_dict()
    }

def handle_inspect_template(req_id: str, payload: dict) -> dict:
    template_path = payload.get("path")
    if not template_path or not os.path.exists(template_path):
        return {"id": req_id, "success": False, "error": f"Template file not found: {template_path}"}

    ext = os.path.splitext(template_path)[1].lower()
    if ext in [".xlsx", ".xlsm"]:
        placeholders = ExcelTemplateParser.find_placeholders(template_path)
        schema = TemplateSchemaCompiler.compile(template_path, "excel", placeholders)
        return {"id": req_id, "success": True, "data": schema}
    elif ext in [".docx", ".doc"]:
        placeholders = WordTemplateParser.find_placeholders(template_path)
        adaptation = {"adapted": False, "adaptations": []}
        if ext == ".docx" and not placeholders:
            adaptation = WordTemplateAutoAdapter.adapt(template_path)
            placeholders = WordTemplateParser.find_placeholders(template_path)
        schema = TemplateSchemaCompiler.compile(template_path, "word", placeholders)
        schema["auto_adapted"] = adaptation["adapted"]
        schema["adaptations"] = adaptation["adaptations"]
        return {"id": req_id, "success": True, "data": schema}
    else:
        return {"id": req_id, "success": False, "error": f"Unsupported template format: {ext}"}

def handle_export(req_id: str, payload: dict) -> dict:
    template_path = payload.get("template_path")
    output_path = payload.get("output_path")
    doc_dict = payload.get("document", {})
    schema = payload.get("schema", {})
    mode = payload.get("mode", "template")

    if not output_path:
        return {"id": req_id, "success": False, "error": "Missing 'output_path'"}

    # Full Document Export Mode (Direct Word/Excel without template)
    if mode == "full_word" or template_path == "FULL_WORD" or (not template_path and output_path.endswith(".docx")):
        out = WordExporter.export_full_document(doc_dict, output_path)
        return {"id": req_id, "success": True, "data": {"output_path": out, "type": "word"}}

    if mode == "full_excel" or template_path == "FULL_EXCEL" or (not template_path and output_path.endswith(".xlsx")):
        out = ExcelExporter.export_full_document(doc_dict, output_path)
        return {"id": req_id, "success": True, "data": {"output_path": out, "type": "excel"}}

    if not template_path or not os.path.exists(template_path):
        if output_path.endswith(".docx"):
            out = WordExporter.export_full_document(doc_dict, output_path)
            return {"id": req_id, "success": True, "data": {"output_path": out, "type": "word"}}
        else:
            out = ExcelExporter.export_full_document(doc_dict, output_path)
            return {"id": req_id, "success": True, "data": {"output_path": out, "type": "excel"}}

    expected_fingerprint = schema.get("template_fingerprint") if schema else None
    if expected_fingerprint:
        actual_fingerprint = TemplateSchemaCompiler.fingerprint(template_path)
        if expected_fingerprint != actual_fingerprint:
            return {"id": req_id, "success": False,
                    "error": "Template changed after schema approval; inspect and approve the schema again"}
    if schema and schema.get("unmapped") and not schema.get("approved"):
        return {"id": req_id, "success": False,
                "error": "Template schema has unmapped placeholders; review and approve it before export"}

    context = TemplateEngine.map_context(doc_dict, schema)
    ext = os.path.splitext(template_path)[1].lower()

    if ext in [".xlsx", ".xlsm"]:
        out = ExcelExporter.export(template_path, output_path, context)
        return {"id": req_id, "success": True, "data": {"output_path": out, "type": "excel"}}
    elif ext in [".docx", ".doc"]:
        out = WordExporter.export(template_path, output_path, context)
        return {"id": req_id, "success": True, "data": {"output_path": out, "type": "word"}}
    else:
        return {"id": req_id, "success": False, "error": f"Unsupported template format: {ext}"}

DISPATCHER = {
    "ping": handle_ping,
    "extract_document": handle_extract,
    "validate_document": handle_validate,
    "inspect_template": handle_inspect_template,
    "export_document": handle_export
}

def main():
    while True:
        try:
            line = sys.stdin.readline()
            if not line:
                break

            line = line.strip()
            if not line:
                continue

            try:
                request = json.loads(line)
            except Exception as json_err:
                err_resp = {
                    "id": "unknown",
                    "success": False,
                    "error": f"Invalid JSON format: {str(json_err)}"
                }
                print(json.dumps(err_resp, ensure_ascii=False), flush=True)
                continue

            req_id = request.get("id", "req-unknown")
            action = request.get("action")
            # Support both 'payload' and 'input' key from Java IpcRequest
            payload = request.get("payload") if request.get("payload") is not None else request.get("input", {})

            handler = DISPATCHER.get(action)
            if not handler:
                resp = {
                    "id": req_id,
                    "success": False,
                    "error": f"Unknown action: {action}"
                }
            else:
                try:
                    resp = handler(req_id, payload)
                except Exception as ex:
                    logger.error(f"Error handling action '{action}': {ex}", exc_info=True)
                    resp = {
                        "id": req_id,
                        "success": False,
                        "error": str(ex)
                    }

            clean_resp = clean_surrogates(resp)
            print(json.dumps(clean_resp, ensure_ascii=False), flush=True)

        except Exception as e:
            logger.error(f"Fatal error in sidecar loop: {e}", exc_info=True)

if __name__ == "__main__":
    main()
