"""Office Studio AI MCP stdio server for Antigravity and other local MCP clients."""

import hashlib
import json
import logging
import os
import re
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

ROOT = Path(os.environ.get("OFFICE_STUDIO_ROOT", Path(__file__).resolve().parents[1])).resolve()
PYTHON_DIR = ROOT / "python"
if str(PYTHON_DIR) not in sys.path:
    sys.path.insert(0, str(PYTHON_DIR))

from core.extraction import ExtractionPipeline
from core.validation import DocumentValidator
from exporters.excel import ExcelExporter
from exporters.word import WordExporter
from templates.engine import TemplateEngine
from templates.excel import ExcelTemplateParser
from templates.schema import TemplateSchemaCompiler
from templates.word import WordTemplateParser

logging.basicConfig(level=logging.INFO, stream=sys.stderr,
                    format="%(asctime)s [OfficeStudioMCP] %(levelname)s %(message)s")
LOGGER = logging.getLogger("office-studio-mcp")

SUPPORTED_INPUTS = {".pdf", ".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp"}
TEMPLATE_EXTENSIONS = {".docx": "word", ".xlsx": "excel", ".xlsm": "excel"}


def _json_text(value: Any) -> Dict[str, Any]:
    return {"content": [{"type": "text", "text": json.dumps(value, ensure_ascii=False, indent=2)}]}


def _template_id(path: Path) -> str:
    return "tpl-file-" + hashlib.sha256(str(path).lower().encode("utf-8")).hexdigest()[:12]


def _template_roots() -> List[Path]:
    configured = os.environ.get("OFFICE_STUDIO_TEMPLATES")
    roots = [Path(configured)] if configured else []
    roots.extend([ROOT / "app-data" / "templates" / "vietnam", ROOT / "app-data" / "templates"])
    unique = []
    for root in roots:
        resolved = root.expanduser().resolve()
        if resolved not in unique and resolved.is_dir():
            unique.append(resolved)
    return unique


def discover_templates(output_format: Optional[str] = None) -> List[Dict[str, Any]]:
    found: Dict[str, Dict[str, Any]] = {}
    for root in _template_roots():
        for path in root.rglob("*"):
            kind = TEMPLATE_EXTENSIONS.get(path.suffix.lower())
            if not path.is_file() or not kind or (output_format and kind != output_format):
                continue
            resolved = path.resolve()
            key = str(resolved).lower()
            found[key] = {
                "id": _template_id(resolved),
                "name": resolved.stem.replace("_", " "),
                "type": kind,
                "path": str(resolved),
            }
    return sorted(found.values(), key=lambda item: (item["type"], item["name"].lower()))


def _normalize(text: str) -> str:
    replacements = str.maketrans("àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ",
                                 "aaaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyyd")
    return re.sub(r"[^a-z0-9]+", " ", text.lower().translate(replacements)).strip()


CATEGORY_TERMS = {
    "cong van": ("cong van", "v v", "kinh gui"),
    "bao cao": ("bao cao",),
    "quyet dinh": ("quyet dinh",),
    "to trinh": ("to trinh",),
    "bien ban": ("bien ban",),
    "thong bao": ("thong bao",),
    "ke hoach": ("ke hoach",),
    "giay moi": ("giay moi",),
    "hoa don": ("hoa don", "ma so thue", "tong tien"),
    "phieu thu": ("phieu thu", "nguoi nop tien"),
    "hop dong": ("hop dong",),
}


def recommend_template(document: Dict[str, Any], output_format: str,
                       requested: Optional[str] = None) -> Tuple[Optional[Dict[str, Any]], List[Dict[str, Any]]]:
    templates = discover_templates(output_format)
    if requested:
        needle = _normalize(requested)
        exact = next((item for item in templates if requested in (item["id"], item["path"])), None)
        if exact:
            return exact, templates
        named = next((item for item in templates if needle in _normalize(item["name"])), None)
        if named:
            return named, templates

    content = _normalize((document.get("document_type") or "") + " " + (document.get("raw_text") or ""))
    scored = []
    for item in templates:
        haystack = _normalize(item["name"] + " " + item["path"])
        score = 0
        for category, terms in CATEGORY_TERMS.items():
            document_match = any(term in content for term in terms)
            template_match = category in haystack or any(term in haystack for term in terms)
            if document_match and template_match:
                score += 100 + sum(10 for term in terms if term in content)
        if "vietnam" in _normalize(item["path"]):
            score += 5
        scored.append((score, item))
    scored.sort(key=lambda pair: (-pair[0], pair[1]["name"].lower()))
    return (scored[0][1] if scored and scored[0][0] > 5 else None), templates


def _resolve_source(arguments: Dict[str, Any]) -> Path:
    raw_path = arguments.get("source_path")
    if not raw_path:
        raise ValueError("source_path is required. Save the chat attachment to a local file first.")
    path = Path(raw_path).expanduser().resolve()
    if not path.is_file():
        raise FileNotFoundError(f"Input file not found: {path}")
    if path.suffix.lower() not in SUPPORTED_INPUTS:
        raise ValueError(f"Unsupported input format: {path.suffix}")
    return path


def _default_output(source: Path, output_format: str) -> Path:
    output_dir = Path(os.environ.get("OFFICE_STUDIO_EXPORTS", ROOT / "app-data" / "exports")).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    suffix = ".docx" if output_format == "word" else ".xlsx"
    return output_dir / f"{source.stem}_MCP_{stamp}{suffix}"


def _export(document: Dict[str, Any], output_format: str, output_path: Path,
            template: Optional[Dict[str, Any]]) -> Dict[str, Any]:
    used_template = None
    warning = None
    if template:
        template_path = template["path"]
        if output_format == "word":
            placeholders = WordTemplateParser.find_placeholders(template_path)
        else:
            placeholders = ExcelTemplateParser.find_placeholders(template_path)
        if placeholders:
            schema = TemplateSchemaCompiler.compile(template_path, output_format, placeholders)
            context = TemplateEngine.map_context(document, schema)
            if output_format == "word":
                WordExporter.export(template_path, str(output_path), context)
            else:
                ExcelExporter.export(template_path, str(output_path), context)
            used_template = template
        else:
            warning = f"Template {template['name']} has no placeholders; exported with the structured default layout."
    if not used_template:
        if output_format == "word":
            WordExporter.export_full_document(document, str(output_path))
        else:
            ExcelExporter.export_full_document(document, str(output_path))
    return {"template": used_template, "warning": warning}


def tool_ocr_map_export(arguments: Dict[str, Any]) -> Dict[str, Any]:
    source = _resolve_source(arguments)
    output_format = str(arguments.get("output_format") or "word").lower()
    if output_format not in {"word", "excel"}:
        raise ValueError("output_format must be 'word' or 'excel'")
    pipeline = ExtractionPipeline(cache_dir=str(ROOT / "app-data" / "cache"))
    doc = pipeline.process(str(source), doc_type=str(arguments.get("document_type") or "generic"),
                           force_ocr=bool(arguments.get("force_ocr", False)))
    document = DocumentValidator.validate(doc).to_canonical_dict()
    vision_text = str(arguments.get("vision_text") or "").strip()
    vision_fields = arguments.get("vision_fields") or {}
    if vision_text:
        document["raw_text"] = vision_text
        document.setdefault("fields", {})["content"] = vision_text
    if isinstance(vision_fields, dict):
        document.setdefault("fields", {}).update({k: v for k, v in vision_fields.items() if v is not None})
    template, candidates = recommend_template(document, output_format, arguments.get("template"))
    output_path = Path(arguments.get("output_path") or _default_output(source, output_format)).expanduser().resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    export_info = _export(document, output_format, output_path, template)
    return {
        "success": True,
        "output_path": str(output_path),
        "document_type": document.get("document_type"),
        "status": document.get("status"),
        "confidence": document.get("confidence"),
        "selected_template": export_info["template"],
        "template_candidates": candidates[:8],
        "warning": export_info["warning"],
        "fields": document.get("fields", {}),
        "review_required": document.get("status") == "NEEDS_REVIEW" or not bool(vision_text),
        "text_source": "gemini_vision+local_layout" if vision_text else "local_ocr",
    }


TOOLS = [
    {
        "name": "office_studio_status",
        "description": "Check whether the local Office Studio AI OCR MCP server is ready.",
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "list_office_templates",
        "description": "List Vietnamese Word and Excel templates available to Office Studio AI.",
        "inputSchema": {
            "type": "object",
            "properties": {"output_format": {"type": "string", "enum": ["word", "excel"]}},
            "additionalProperties": False,
        },
    },
    {
        "name": "ocr_map_export",
        "description": "OCR a local image/PDF, choose the most suitable Vietnamese template, map fields, and export Word or Excel in one operation. Return a local output path and review status.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "source_path": {"type": "string", "description": "Absolute path of the local image or PDF."},
                "output_format": {"type": "string", "enum": ["word", "excel"], "default": "word"},
                "output_path": {"type": "string", "description": "Optional absolute .docx/.xlsx destination."},
                "template": {"type": "string", "description": "Optional template id, name, or absolute path."},
                "document_type": {"type": "string", "default": "generic"},
                "force_ocr": {"type": "boolean", "default": False},
                "vision_text": {"type": "string", "description": "Optional exact transcription read by Gemini from the attached image. Preferred for Vietnamese spelling; local OCR still supplies layout."},
                "vision_fields": {"type": "object", "description": "Optional fields identified by Gemini, for example title, document_number, issuing_authority, recipient and content.", "additionalProperties": True},
            },
            "required": ["source_path"],
            "additionalProperties": False,
        },
    },
]


def handle_request(request: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    method = request.get("method")
    request_id = request.get("id")
    if request_id is None:
        return None
    if method == "initialize":
        requested = (request.get("params") or {}).get("protocolVersion") or "2024-11-05"
        return {"jsonrpc": "2.0", "id": request_id, "result": {
            "protocolVersion": requested,
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "office-studio-ai", "version": "1.0.1"},
        }}
    if method == "ping":
        return {"jsonrpc": "2.0", "id": request_id, "result": {}}
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": request_id, "result": {"tools": TOOLS}}
    if method == "tools/call":
        params = request.get("params") or {}
        name, arguments = params.get("name"), params.get("arguments") or {}
        try:
            if name == "office_studio_status":
                result = {"ready": True, "root": str(ROOT), "templates": len(discover_templates())}
            elif name == "list_office_templates":
                result = {"templates": discover_templates(arguments.get("output_format"))}
            elif name == "ocr_map_export":
                result = tool_ocr_map_export(arguments)
            else:
                raise ValueError(f"Unknown tool: {name}")
            return {"jsonrpc": "2.0", "id": request_id, "result": _json_text(result)}
        except Exception as exc:
            LOGGER.exception("Tool %s failed", name)
            return {"jsonrpc": "2.0", "id": request_id, "result": {
                "content": [{"type": "text", "text": str(exc)}], "isError": True,
            }}
    return {"jsonrpc": "2.0", "id": request_id,
            "error": {"code": -32601, "message": f"Method not found: {method}"}}


def main() -> None:
    if hasattr(sys.stdin, "reconfigure"):
        sys.stdin.reconfigure(encoding="utf-8", errors="replace")
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            response = handle_request(json.loads(line))
        except Exception as exc:
            response = {"jsonrpc": "2.0", "id": None,
                        "error": {"code": -32700, "message": f"Parse error: {exc}"}}
        if response is not None:
            sys.stdout.write(json.dumps(response, ensure_ascii=False, separators=(",", ":")) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
