"""
Generate high-resolution, pixel-perfect modern app icons for Office Studio AI.
Outputs:
- java/src/main/resources/icons/app-icon.png
- java/src/main/resources/icons/app-icon-{size}.png
- java/src/main/resources/icons/app-icon.ico
- scripts/icons/app-icon.ico
"""
import os
import math
from PIL import Image, ImageDraw, ImageFont, ImageFilter

def create_app_icon(size=512):
    # Create high-res canvas with RGBA
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    scale = size / 512.0
    margin = 32 * scale
    radius = 96 * scale

    # 1. Background Rounded Squircle with rich gradient
    # Gradient from Deep Blue (#1e293b / #1e3a8a) to Indigo (#3b82f6) to Teal/Cyan (#06b6d4)
    bg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    bg_draw = ImageDraw.Draw(bg)
    
    # Draw rounded rectangle for app tile background
    x0, y0 = margin, margin
    x1, y1 = size - margin, size - margin
    
    # Draw soft outer shadow
    shadow_offset = 12 * scale
    shadow_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    shadow_draw = ImageDraw.Draw(shadow_img)
    shadow_draw.rounded_rectangle(
        [x0 + 4 * scale, y0 + shadow_offset, x1 - 4 * scale, y1 + shadow_offset],
        radius=radius,
        fill=(15, 23, 42, 100)
    )
    shadow_img = shadow_img.filter(ImageFilter.GaussianBlur(16 * scale))
    img.alpha_composite(shadow_img)

    # Base gradient on background tile
    tile = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    tile_draw = ImageDraw.Draw(tile)
    tile_draw.rounded_rectangle([x0, y0, x1, y1], radius=radius, fill=(30, 58, 138, 255))
    
    # Create vertical/diagonal gradient overlay
    grad = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    for y in range(int(y0), int(y1)):
        t = (y - y0) / (y1 - y0)
        # Interpolate color: #1e3a8a (30, 58, 138) -> #2563eb (37, 99, 235) -> #0284c7 (2, 132, 199)
        if t < 0.6:
            sub_t = t / 0.6
            r = int(30 * (1 - sub_t) + 37 * sub_t)
            g = int(58 * (1 - sub_t) + 99 * sub_t)
            b = int(138 * (1 - sub_t) + 235 * sub_t)
        else:
            sub_t = (t - 0.6) / 0.4
            r = int(37 * (1 - sub_t) + 2 * sub_t)
            g = int(99 * (1 - sub_t) + 132 * sub_t)
            b = int(235 * (1 - sub_t) + 199 * sub_t)
        
        row = Image.new("RGBA", (size, 1), (r, g, b, 255))
        grad.paste(row, (0, y))
    
    # Mask gradient to rounded rect
    tile_mask = Image.new("L", (size, size), 0)
    tile_mask_draw = ImageDraw.Draw(tile_mask)
    tile_mask_draw.rounded_rectangle([x0, y0, x1, y1], radius=radius, fill=255)
    
    # Apply gradient tile
    img.paste(grad, (0, 0), tile_mask)

    # Add subtle border highlight
    tile_draw_border = ImageDraw.Draw(img)
    tile_draw_border.rounded_rectangle([x0, y0, x1, y1], radius=radius, outline=(255, 255, 255, 45), width=int(2.5 * scale))

    # 2. Main Document Shape in the center
    doc_w = 230 * scale
    doc_h = 300 * scale
    doc_x0 = (size - doc_w) / 2.0 - 15 * scale
    doc_y0 = (size - doc_h) / 2.0 + 8 * scale
    doc_x1 = doc_x0 + doc_w
    doc_y1 = doc_y0 + doc_h
    fold_size = 56 * scale

    # Document shadow
    doc_shadow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    doc_shadow_draw = ImageDraw.Draw(doc_shadow)
    doc_shadow_draw.rounded_rectangle([doc_x0, doc_y0 + 8 * scale, doc_x1, doc_y1 + 8 * scale], radius=16 * scale, fill=(10, 20, 40, 120))
    doc_shadow = doc_shadow.filter(ImageFilter.GaussianBlur(10 * scale))
    img.alpha_composite(doc_shadow)

    # Document body (white paper)
    doc_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    doc_draw = ImageDraw.Draw(doc_img)

    # Document polygon with top-right fold
    doc_poly = [
        (doc_x0 + 16 * scale, doc_y0),
        (doc_x1 - fold_size, doc_y0),
        (doc_x1, doc_y0 + fold_size),
        (doc_x1, doc_y1 - 16 * scale),
        (doc_x1 - 16 * scale, doc_y1),
        (doc_x0 + 16 * scale, doc_y1),
        (doc_x0, doc_y1 - 16 * scale),
        (doc_x0, doc_y0 + 16 * scale)
    ]
    doc_draw.polygon(doc_poly, fill=(255, 255, 255, 250))
    
    # Corner rounded corners
    doc_draw.ellipse([doc_x0, doc_y0, doc_x0 + 32 * scale, doc_y0 + 32 * scale], fill=(255, 255, 255, 250))
    doc_draw.ellipse([doc_x0, doc_y1 - 32 * scale, doc_x0 + 32 * scale, doc_y1], fill=(255, 255, 255, 250))
    doc_draw.ellipse([doc_x1 - 32 * scale, doc_y1 - 32 * scale, doc_x1, doc_y1], fill=(255, 255, 255, 250))

    # Folded corner flap
    fold_poly = [
        (doc_x1 - fold_size, doc_y0),
        (doc_x1 - fold_size, doc_y0 + fold_size),
        (doc_x1, doc_y0 + fold_size)
    ]
    doc_draw.polygon(fold_poly, fill=(226, 232, 240, 255))
    doc_draw.line([(doc_x1 - fold_size, doc_y0), (doc_x1 - fold_size, doc_y0 + fold_size), (doc_x1, doc_y0 + fold_size)], fill=(203, 213, 225, 255), width=int(2 * scale))

    # Document content lines (Word/Excel document representation)
    line_x0 = doc_x0 + 32 * scale
    line_x1 = doc_x1 - 32 * scale
    
    # Top title bar (Blue/Indigo)
    doc_draw.rounded_rectangle([line_x0, doc_y0 + 42 * scale, line_x0 + 80 * scale, doc_y0 + 56 * scale], radius=4 * scale, fill=(37, 99, 235, 255))
    
    # Content lines
    line_y = doc_y0 + 80 * scale
    doc_draw.rounded_rectangle([line_x0, line_y, line_x1 - 20 * scale, line_y + 12 * scale], radius=4 * scale, fill=(148, 163, 184, 255))
    
    line_y += 24 * scale
    doc_draw.rounded_rectangle([line_x0, line_y, line_x1 - 50 * scale, line_y + 12 * scale], radius=4 * scale, fill=(203, 213, 225, 255))

    # Excel / Table grid preview lines
    grid_y = line_y + 32 * scale
    grid_w = line_x1 - line_x0
    grid_h = 74 * scale
    doc_draw.rounded_rectangle([line_x0, grid_y, line_x1, grid_y + grid_h], radius=6 * scale, outline=(203, 213, 225, 255), width=int(2 * scale), fill=(248, 250, 252, 255))
    # Grid inner lines
    doc_draw.line([(line_x0 + grid_w * 0.4, grid_y), (line_x0 + grid_w * 0.4, grid_y + grid_h)], fill=(226, 232, 240, 255), width=int(1.5 * scale))
    doc_draw.line([(line_x0 + grid_w * 0.72, grid_y), (line_x0 + grid_w * 0.72, grid_y + grid_h)], fill=(226, 232, 240, 255), width=int(1.5 * scale))
    doc_draw.line([(line_x0, grid_y + grid_h * 0.48), (line_x1, grid_y + grid_h * 0.48)], fill=(226, 232, 240, 255), width=int(1.5 * scale))

    # Small table data bars
    doc_draw.rounded_rectangle([line_x0 + 8 * scale, grid_y + 8 * scale, line_x0 + grid_w * 0.32, grid_y + 18 * scale], radius=3 * scale, fill=(59, 130, 246, 200))
    doc_draw.rounded_rectangle([line_x0 + 8 * scale, grid_y + grid_h * 0.58, line_x0 + grid_w * 0.28, grid_y + grid_h * 0.58 + 10 * scale], radius=3 * scale, fill=(148, 163, 184, 200))

    img.alpha_composite(doc_img)

    # 3. Laser Scanner OCR Beam across the document
    laser_y = doc_y0 + 130 * scale
    laser = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    laser_draw = ImageDraw.Draw(laser)
    # Cyan glow beam
    laser_draw.line([(doc_x0 - 15 * scale, laser_y), (doc_x1 + 15 * scale, laser_y)], fill=(6, 182, 212, 180), width=int(6 * scale))
    laser_draw.line([(doc_x0 - 5 * scale, laser_y), (doc_x1 + 5 * scale, laser_y)], fill=(255, 255, 255, 255), width=int(2 * scale))
    laser = laser.filter(ImageFilter.GaussianBlur(2 * scale))
    img.alpha_composite(laser)

    # 4. Floating AI Badge in bottom right (Emerald & Cyan with "AI" and sparkles)
    badge_size = 145 * scale
    badge_x = size - margin - badge_size - 10 * scale
    badge_y = size - margin - badge_size - 10 * scale

    # Badge Shadow
    badge_shadow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    badge_shadow_draw = ImageDraw.Draw(badge_shadow)
    badge_shadow_draw.ellipse([badge_x - 4 * scale, badge_y + 6 * scale, badge_x + badge_size + 4 * scale, badge_y + badge_size + 14 * scale], fill=(15, 23, 42, 140))
    badge_shadow = badge_shadow.filter(ImageFilter.GaussianBlur(10 * scale))
    img.alpha_composite(badge_shadow)

    # Badge Circle with Emerald/Teal Gradient
    badge_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    badge_draw = ImageDraw.Draw(badge_img)
    badge_draw.ellipse([badge_x, badge_y, badge_x + badge_size, badge_y + badge_size], fill=(16, 185, 129, 255))
    
    # Inner glowing border
    badge_draw.ellipse([badge_x, badge_y, badge_x + badge_size, badge_y + badge_size], outline=(255, 255, 255, 220), width=int(3.5 * scale))
    
    # AI Text or Sparkle inside badge
    # Draw stylized 4-point AI sparkle stars
    def draw_star(cx, cy, r_outer, r_inner, color):
        points = []
        for i in range(8):
            angle = i * math.pi / 4
            r = r_outer if i % 2 == 0 else r_inner
            px = cx + r * math.cos(angle)
            py = cy + r * math.sin(angle)
            points.append((px, py))
        badge_draw.polygon(points, fill=color)

    # Center AI Lightning / Sparkle Icon
    center_x = badge_x + badge_size / 2.0
    center_y = badge_y + badge_size / 2.0
    
    # 4-point glowing star
    draw_star(center_x, center_y, 44 * scale, 14 * scale, (255, 255, 255, 255))
    
    # Smaller sparkle on top right
    draw_star(badge_x + badge_size * 0.82, badge_y + badge_size * 0.22, 16 * scale, 5 * scale, (255, 255, 255, 240))

    img.alpha_composite(badge_img)

    return img

def main():
    os.makedirs("java/src/main/resources/icons", exist_ok=True)
    os.makedirs("scripts/icons", exist_ok=True)
    os.makedirs("release", exist_ok=True)

    master_icon = create_app_icon(512)
    master_icon.save("java/src/main/resources/icons/app-icon-512.png", format="PNG")
    master_icon.save("java/src/main/resources/icons/app-icon.png", format="PNG")
    master_icon.save("scripts/icons/app-icon.png", format="PNG")

    sizes = [256, 128, 64, 48, 32, 16]
    ico_images = []
    for s in sizes:
        resized = master_icon.resize((s, s), Image.Resampling.LANCZOS)
        resized.save(f"java/src/main/resources/icons/app-icon-{s}.png", format="PNG")
        ico_images.append(resized)

    # Save multi-size .ico
    master_icon.resize((256, 256), Image.Resampling.LANCZOS).save(
        "java/src/main/resources/icons/app-icon.ico",
        format="ICO",
        sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)],
        append_images=ico_images
    )
    master_icon.resize((256, 256), Image.Resampling.LANCZOS).save(
        "scripts/icons/app-icon.ico",
        format="ICO",
        sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)],
        append_images=ico_images
    )
    print("App icons generated successfully!")

if __name__ == "__main__":
    main()
