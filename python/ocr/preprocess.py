import cv2
import numpy as np
from PIL import Image, ImageOps
import os
from typing import Union, Tuple

class ImagePreprocessor:
    """
    OpenCV-based image preprocessing pipeline:
    - Grayscale conversion
    - Noise reduction (Gaussian & Bilateral)
    - Contrast enhancement (CLAHE)
    - Auto-deskewing (MinAreaRect & Hough lines)
    - Adaptive Thresholding (Otsu & Sauvola style)
    - Unicode-safe file path loader for Windows
    """

    @classmethod
    def load_image(cls, image_input: Union[str, np.ndarray, Image.Image]) -> np.ndarray:
        if isinstance(image_input, str):
            if not os.path.exists(image_input):
                raise FileNotFoundError(f"Image not found at {image_input}")
            # Apply the camera/phone EXIF orientation once before OCR. OpenCV ignores
            # this metadata and can otherwise display/OCR the same pixels differently.
            try:
                with Image.open(image_input) as source:
                    pil_img = ImageOps.exif_transpose(source).convert("RGB")
                    img = cv2.cvtColor(np.array(pil_img), cv2.COLOR_RGB2BGR)
            except Exception:
                img = None

            # Unicode-safe OpenCV fallback for formats Pillow cannot decode.
            try:
                if img is None:
                    with open(image_input, "rb") as f:
                        file_bytes = np.frombuffer(f.read(), dtype=np.uint8)
                        img = cv2.imdecode(file_bytes, cv2.IMREAD_COLOR)
            except Exception:
                pass

            if img is None:
                # Fallback to PIL
                try:
                    pil_img = ImageOps.exif_transpose(Image.open(image_input)).convert("RGB")
                    img = np.array(pil_img)
                    img = cv2.cvtColor(img, cv2.COLOR_RGB2BGR)
                except Exception as ex:
                    raise ValueError(f"Could not load image from path: {image_input}") from ex
            return img
        elif isinstance(image_input, Image.Image):
            rgb = np.array(ImageOps.exif_transpose(image_input).convert("RGB"))
            return cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
        elif isinstance(image_input, np.ndarray):
            if len(image_input.shape) == 2:
                return cv2.cvtColor(image_input, cv2.COLOR_GRAY2BGR)
            return image_input
        else:
            raise TypeError(f"Unsupported image type: {type(image_input)}")

    @classmethod
    def to_grayscale(cls, img: np.ndarray) -> np.ndarray:
        if len(img.shape) == 3:
            return cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        return img

    @classmethod
    def denoise(cls, gray: np.ndarray) -> np.ndarray:
        return cv2.fastNlMeansDenoising(gray, None, h=10, templateWindowSize=7, searchWindowSize=21)

    @classmethod
    def enhance_contrast(cls, gray: np.ndarray) -> np.ndarray:
        clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
        return clahe.apply(gray)

    @classmethod
    def deskew(cls, gray: np.ndarray) -> Tuple[np.ndarray, float]:
        thresh = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)[1]
        coords = np.column_stack(np.where(thresh > 0))
        if len(coords) < 100:
            return gray, 0.0

        angle = cv2.minAreaRect(coords)[-1]
        if angle < -45:
            angle = -(90 + angle)
        else:
            angle = -angle

        if abs(angle) < 0.5 or abs(angle) > 45:
            return gray, 0.0

        (h, w) = gray.shape[:2]
        center = (w // 2, h // 2)
        M = cv2.getRotationMatrix2D(center, angle, 1.0)
        rotated = cv2.warpAffine(gray, M, (w, h), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
        return rotated, angle

    @classmethod
    def binarize(cls, gray: np.ndarray) -> np.ndarray:
        return cv2.adaptiveThreshold(
            gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 21, 11
        )

    @classmethod
    def preprocess_for_ocr(cls, image_input: Union[str, np.ndarray, Image.Image]) -> np.ndarray:
        """Load an OCR-ready image without destroying small Vietnamese marks.

        RapidOCR performs its own resize and normalization. Converting a phone
        photo to grayscale and applying CLAHE here made thin tone marks disappear
        or merge with the glyph, especially on small form text.
        """
        img = cls.load_image(image_input)
        return img
