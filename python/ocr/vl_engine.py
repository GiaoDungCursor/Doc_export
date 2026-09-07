import os
import sys
import time
import re
from pathlib import Path
from typing import List, Dict, Any, Optional, Tuple
import numpy as np
from PIL import Image
import onnxruntime as ort

class PaddleOCRVLEngine:
    """
    PaddleOCR-VL-1.5 Vision-Language ONNX Engine:
    - 4-bit quantized vision encoder and language decoder optimized for CPU.
    - Understands complex document layout, table structures (OTSL format & Markdown), and sequential lists.
    """
    _instance = None

    def __init__(self, model_dir: Optional[str] = None):
        if model_dir is None:
            base_dir = Path(__file__).resolve().parent.parent.parent
            model_dir = str(base_dir / "PaddleOCR-VL-1.5-ONNX")

        self.model_dir = Path(model_dir)
        self.onnx_dir = self.model_dir / "onnx"

        self.vision_path = self.onnx_dir / "vision_encoder_q4.onnx"
        self.embed_path = self.onnx_dir / "embedding.onnx"
        self.decoder_path = self.onnx_dir / "decoder_q4.onnx"

        self.available = (
            self.vision_path.exists() and
            self.embed_path.exists() and
            self.decoder_path.exists()
        )

        self._initialized = False
        self.tokenizer = None
        self.image_processor = None
        self.processor = None
        self.vision_sess = None
        self.embed_sess = None
        self.decoder_sess = None

    @classmethod
    def get_instance(cls, model_dir: Optional[str] = None) -> "PaddleOCRVLEngine":
        if cls._instance is None:
            cls._instance = cls(model_dir)
        return cls._instance

    def initialize(self):
        if self._initialized or not self.available:
            return

        sys.stderr.write("[PaddleOCRVL] Loading tokenizer and ONNX models...\n")
        sys.path.insert(0, str(self.model_dir))
        try:
            from processing_paddleocr_vl import PaddleOCRVLProcessor
            from image_processing_paddleocr_vl import PaddleOCRVLImageProcessor
            from transformers import AutoTokenizer

            self.tokenizer = AutoTokenizer.from_pretrained(str(self.model_dir))
            self.image_processor = PaddleOCRVLImageProcessor.from_pretrained(str(self.model_dir))
            self.processor = PaddleOCRVLProcessor(
                image_processor=self.image_processor,
                tokenizer=self.tokenizer
            )

            opts = ort.SessionOptions()
            opts.intra_op_num_threads = min(os.cpu_count() or 4, 8)

            self.vision_sess = ort.InferenceSession(
                str(self.vision_path), opts, providers=["CPUExecutionProvider"]
            )
            self.embed_sess = ort.InferenceSession(
                str(self.embed_path), opts, providers=["CPUExecutionProvider"]
            )
            self.decoder_sess = ort.InferenceSession(
                str(self.decoder_path), opts, providers=["CPUExecutionProvider"]
            )
            self._initialized = True
            sys.stderr.write("[PaddleOCRVL] Engine initialized successfully.\n")
        except Exception as e:
            sys.stderr.write(f"[PaddleOCRVL] Initialization error: {e}\n")
            self._initialized = False

    def generate(
        self,
        image_input,
        prompt_text: str = "OCR:",
        max_new_tokens: int = 512,
        repetition_penalty: float = 1.15
    ) -> str:
        """Run vision-language OCR generation on an image."""
        if not self._initialized:
            self.initialize()
        if not self._initialized:
            return ""

        # Load and resize PIL Image
        if isinstance(image_input, str):
            img = Image.open(image_input).convert("RGB")
        elif isinstance(image_input, np.ndarray):
            import cv2
            if len(image_input.shape) == 3 and image_input.shape[2] == 3:
                rgb = cv2.cvtColor(image_input, cv2.COLOR_BGR2RGB)
                img = Image.fromarray(rgb)
            else:
                img = Image.fromarray(image_input).convert("RGB")
        elif isinstance(image_input, Image.Image):
            img = image_input.convert("RGB")
        else:
            return ""

        # Limit maximum side for responsive inference on CPU
        max_side = 768
        w, h = img.size
        if max(w, h) > max_side:
            scale = max_side / max(w, h)
            img = img.resize((int(w * scale), int(h * scale)), Image.Resampling.LANCZOS)

        messages = [
            {
                "role": "user",
                "content": [
                    {"type": "image"},
                    {"type": "text", "text": prompt_text}
                ]
            }
        ]
        prompt = self.tokenizer.apply_chat_template(
            messages, tokenize=False, add_generation_prompt=True
        )
        inputs = self.processor(images=img, text=prompt, return_tensors="pt")

        input_ids = inputs["input_ids"].numpy().astype(np.int64)
        pixel_values = inputs["pixel_values"].unsqueeze(0).numpy().astype(np.float32)
        image_grid_thw = inputs["image_grid_thw"].numpy().astype(np.int64)

        # 1. Vision Encoder
        vision_out = self.vision_sess.run(
            ["image_embeds"],
            {"pixel_values": pixel_values, "image_grid_thw": image_grid_thw}
        )[0]

        # 2. Embedding Layer
        text_embeds = self.embed_sess.run(
            ["embeddings"], {"input_ids": input_ids}
        )[0]

        # Replace image tokens (100295) with vision embeddings
        mask = (input_ids[0] == 100295)
        text_embeds[0, mask, :] = vision_out

        # 3. Decoder Prefill
        seq_len = input_ids.shape[1]
        attn_mask = np.ones((1, seq_len), dtype=np.int64)
        decoder_inputs = {
            "inputs_embeds": text_embeds,
            "attention_mask": attn_mask
        }
        for i in range(18):
            decoder_inputs[f"past_key_values.{i}.key"] = np.zeros((1, 2, 0, 128), dtype=np.float32)
            decoder_inputs[f"past_key_values.{i}.value"] = np.zeros((1, 2, 0, 128), dtype=np.float32)

        decoder_out = self.decoder_sess.run(None, decoder_inputs)
        logits = decoder_out[-1]
        next_token = int(np.argmax(logits[0, -1, :]))

        generated_tokens = [next_token]
        past_key_values = decoder_out[:-1]
        eos_token_id = self.tokenizer.eos_token_id
        stop_tokens = {eos_token_id, 100257, 100258, 2}

        # 4. Autoregressive Loop with Repetition Detection
        for step in range(max_new_tokens):
            if next_token in stop_tokens:
                break

            # Repetition detection: if same sequence repeats, stop early
            if len(generated_tokens) > 24:
                last_6 = generated_tokens[-6:]
                last_24 = generated_tokens[-24:]
                if last_24.count(last_6[0]) >= 8:
                    break

            next_input_ids = np.array([[next_token]], dtype=np.int64)
            next_embed = self.embed_sess.run(
                ["embeddings"], {"input_ids": next_input_ids}
            )[0]

            total_seq = seq_len + step + 1
            step_attn_mask = np.ones((1, total_seq), dtype=np.int64)
            step_inputs = {
                "inputs_embeds": next_embed,
                "attention_mask": step_attn_mask
            }
            for i in range(18):
                step_inputs[f"past_key_values.{i}.key"] = past_key_values[2 * i]
                step_inputs[f"past_key_values.{i}.value"] = past_key_values[2 * i + 1]

            step_out = self.decoder_sess.run(None, step_inputs)
            past_key_values = step_out[:-1]
            step_logits = step_out[-1][0, -1, :].copy()

            # Apply repetition penalty
            for token_id in set(generated_tokens[-20:]):
                if step_logits[token_id] > 0:
                    step_logits[token_id] /= repetition_penalty
                else:
                    step_logits[token_id] *= repetition_penalty

            next_token = int(np.argmax(step_logits))
            generated_tokens.append(next_token)

        raw_output = self.tokenizer.decode(generated_tokens, skip_special_tokens=True)
        return self._post_process_text(raw_output)

    def parse_to_table_rows(self, text: str) -> List[List[str]]:
        """
        Parse OTSL table tokens, markdown tables, or numbered lists into rows of cells.
        """
        rows: List[List[str]] = []
        clean_text = self._post_process_text(text)

        # 1. OTSL Table format: contains <nl> and <fcel>/<lcel>
        if "<nl>" in clean_text or "<fcel>" in clean_text:
            raw_rows = clean_text.split("<nl>")
            for r in raw_rows:
                # split by cell tags
                cells = re.split(r"<(?:fcel|lcel|ucel|xcel|ecel)>", r)
                cleaned_cells = [c.strip() for c in cells if c.strip()]
                if cleaned_cells:
                    rows.append(cleaned_cells)
            if rows:
                return rows

        # 2. Markdown Table format: lines with '|'
        lines = [line.strip() for line in clean_text.splitlines() if line.strip()]
        md_table_lines = [l for l in lines if l.startswith("|") and l.endswith("|")]
        if len(md_table_lines) >= 2:
            for line in md_table_lines:
                # Skip markdown separator |---|---|
                if re.match(r"^\|[\s\-:|]+\|$", line):
                    continue
                parts = [p.strip() for p in line.strip("|").split("|")]
                rows.append(parts)
            if rows:
                return rows

        # 3. Numbered items / key-value format (e.g. STT \n TÊN BẢN VẼ \n 1 \n MẶT BẰNG...)
        # Detect if text has sequential numbers 1, 2, 3...
        numbered_pattern = re.compile(r"^(\d{1,3})\s*[\.\:\-\)]?\s*(.+)$")
        header_candidates = []
        item_rows = []

        i = 0
        while i < len(lines):
            line = lines[i]
            # Check if line is just a number
            if re.match(r"^\d{1,3}$", line) and i + 1 < len(lines):
                stt = line
                content = lines[i + 1]
                item_rows.append([stt, content])
                i += 2
                continue

            m = numbered_pattern.match(line)
            if m:
                item_rows.append([m.group(1), m.group(2).strip()])
            else:
                if not item_rows and len(line) < 40 and not line.upper().startswith("DANH MỤC"):
                    header_candidates.append(line)
            i += 1

        if item_rows:
            headers = ["STT", "NỘI DUNG / TÊN HẠNG MỤC"]
            if len(header_candidates) >= 2:
                headers = header_candidates[:2]
            return [headers] + item_rows

        # Fallback: each line as a row
        return [[l] for l in lines]

    def _post_process_text(self, text: str) -> str:
        """Fix recurring diacritic misrecognitions for Vietnamese construction/admin documents."""
        replacements = [
            (r"\bMỸ BẢNG\b", "MẶT BẰNG"),
            (r"\bMỸ\s+BẰNG\b", "MẶT BẰNG"),
            (r"\bBẢN\s+VĂ\b", "BẢN VẼ"),
            (r"\bTỒN\s+BẢN\s+VĂ\b", "TÊN BẢN VẼ"),
            (r"\bTỒN\b", "TÊN"),
            (r"\bTHẮY\s+ĐỀI\b", "THAY ĐỔI"),
            (r"\bDIỀN\b", "ĐIỆN"),
            (r"\bDIỂN\b", "ĐIỆN"),
            (r"\bTRÁN\b", "TRẦN"),
            (r"\bTRANG\b", "TRẠNG"),
            (r"\bHIỆN\s+TRANG\b", "HIỆN TRẠNG"),
            (r"\bÔ\s+CÁM\b", "Ổ CẮM"),
            (r"\bNHE\s+NHE\b.*", ""),
        ]
        res = text
        for pat, repl in replacements:
            res = re.sub(pat, repl, res, flags=re.IGNORECASE)
        return res.strip()
