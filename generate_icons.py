from PIL import Image
import os
import sys

# Ukuran standar icon Android per density
SIZES = {
    "mipmap-mdpi":    48,
    "mipmap-hdpi":    72,
    "mipmap-xhdpi":   96,
    "mipmap-xxhdpi":  144,
    "mipmap-xxxhdpi": 192,
}

def main():
    if len(sys.argv) < 2:
        print("Usage: python3 generate_icons.py <source_image>")
        sys.exit(1)

    src_path = sys.argv[1]
    if not os.path.exists(src_path):
        print(f"[!] Source image tidak ditemukan: {src_path}")
        sys.exit(1)

    src = Image.open(src_path).convert("RGBA")

    # Resize jadi square, crop center kalau rasio gak 1:1
    w, h = src.size
    side = min(w, h)
    left = (w - side) // 2
    top = (h - side) // 2
    src = src.crop((left, top, left + side, top + side))

    for folder, size in SIZES.items():
        out_dir = f"android/app/src/main/res/{folder}"
        os.makedirs(out_dir, exist_ok=True)

        img = src.resize((size, size), Image.LANCZOS)
        img.save(os.path.join(out_dir, "ic_launcher.png"), "PNG")

        # Round icon: sama isinya, Android yang akan masking
        img.save(os.path.join(out_dir, "ic_launcher_round.png"), "PNG")

        print(f"[+] {folder}: ic_launcher.png + ic_launcher_round.png ({size}x{size})")

    print("[✓] Selesai generate icons.")

if __name__ == "__main__":
    main()