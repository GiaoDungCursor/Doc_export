import re

_NUMBER_ONLY = re.compile(r"^\s*(\d+(?:\.\d+)*[.)])\s*$")
_ROMAN_HEADING = re.compile(r"^([IVXLCDM]+\.\s*)([a-zà-ỹ])", re.IGNORECASE)
_STRUCTURAL = re.compile(r"^(?:[IVXLCDM]+\.|\d+(?:\.\d+)*[.)]\s+|[-–•]\s+)", re.IGNORECASE)


def normalize_block_text(text: str) -> str:
    """Collapse visual OCR wraps while retaining real headings and paragraphs."""
    source = [re.sub(r"\s+", " ", line).strip() for line in (text or "").splitlines()]
    source = [line for line in source if line]
    if not source:
        return ""
    merged, index = [], 0
    while index < len(source):
        match = _NUMBER_ONLY.match(source[index])
        if match and index + 1 < len(source):
            merged.append(f"{match.group(1)} {source[index + 1]}")
            index += 2
        else:
            merged.append(source[index])
            index += 1
    logical, pending = [], ""
    for line in merged:
        if pending and _STRUCTURAL.match(line):
            logical.append(pending)
            pending = line
        else:
            pending = f"{pending} {line}".strip()
        if pending.endswith((".", "!", "?", ":", ";")):
            logical.append(pending)
            pending = ""
    if pending:
        logical.append(pending)
    result = "\n".join(logical)
    return _ROMAN_HEADING.sub(lambda m: m.group(1) + m.group(2).upper(), result)


def normalize_document_pages(pages) -> str:
    page_texts = []
    for page in pages:
        if getattr(page, "extraction_method", "unknown") == "native_text":
            # Preserve the PDF text layer byte-for-byte at the Unicode string level.
            # Positioned blocks remain available for click-to-highlight mapping.
            if page.text:
                page_texts.append(page.text)
            continue
        for block in page.blocks:
            block.text = normalize_block_text(block.text)
        page.text = ("\n".join(block.text for block in page.blocks if block.text)
                     if page.blocks else normalize_block_text(page.text))
        if page.text:
            page_texts.append(page.text)
    return "\n".join(page_texts)
