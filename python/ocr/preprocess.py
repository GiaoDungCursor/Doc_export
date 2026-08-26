import cv2
import numpy as np
from PIL import Image
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
            # Unicode-safe image loading for Windows paths with Vietnamese characters
            try:
                with open(image_input, "rb") as f:
                    file_bytes = np.frombuffer(f.read(), dtype=np.uint8)
                    img = cv2.imdecode(file_bytes, cv2.IMREAD_COLOR)
            except Exception as e:
                img = None

            if img is None:
                # Fallback to PIL
                try:
                    pil_img = Image.open(image_input).convert("RGB")
                    img = np.array(pil_img)
                    img = cv2.cvtColor(img, cv2.COLOR_RGB2BGR)
                except Exception as ex:
                    raise ValueError(f"Could not load image from path: {image_input}") from ex
            return img
        elif isinstance(image_input, Image.Image):
            rgb = np.array(image_input.convert("RGB"))
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
        """Complete preprocessing pipeline before OCR"""
        img = cls.load_image(image_input)
        gray = cls.to_grayscale(img)
        gray, angle = cls.deskew(gray)
        gray = cls.enhance_contrast(gray)
        return gray
