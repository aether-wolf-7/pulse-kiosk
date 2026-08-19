"""Render docs/INSTALACAO_TABLETS.md to a printable PDF for the client.

The markdown stays the single source of truth; this only styles it. Uses
Edge in headless mode so no extra dependency beyond `markdown` is needed.

    python scripts/docs/build_guide_pdf.py
"""

import subprocess
import sys
from pathlib import Path

import markdown

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "docs" / "INSTALACAO_TABLETS.md"
HTML = ROOT / "docs" / "INSTALACAO_TABLETS.html"
PDF = ROOT / "docs" / "Pulse-Kiosk-Instalacao-Tablets.pdf"

EDGE_CANDIDATES = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
]

CSS = """
@page { size: A4; margin: 18mm 16mm; }
* { box-sizing: border-box; }
body {
  font-family: "Segoe UI", Calibri, Arial, sans-serif;
  font-size: 11.5pt; line-height: 1.55; color: #1c1c1c; margin: 0;
}
h1 {
  font-size: 21pt; margin: 0 0 2mm; color: #4a3d85;
  border-bottom: 3px solid #4a3d85; padding-bottom: 3mm;
}
h2 {
  font-size: 14.5pt; margin: 9mm 0 3mm; color: #4a3d85;
  page-break-after: avoid; break-after: avoid;
}
h2 + p, h2 + ol, h2 + ul { page-break-before: avoid; }
p, li { orphans: 3; widows: 3; }
ol, ul { padding-left: 6mm; }
li { margin-bottom: 2.5mm; }
strong { color: #000; }
hr { border: 0; border-top: 1px solid #ddd; margin: 7mm 0; }
blockquote {
  margin: 4mm 0; padding: 3.5mm 5mm;
  background: #fdf6e3; border-left: 4px solid #d9a441;
  page-break-inside: avoid; break-inside: avoid;
}
blockquote p { margin: 0 0 2mm; }
blockquote p:last-child { margin-bottom: 0; }
code {
  background: #eee; padding: 0.5mm 1.5mm; border-radius: 2px;
  font-family: Consolas, monospace; font-size: 10pt;
}
a { color: #4a3d85; word-break: break-all; }
.footer {
  margin-top: 10mm; padding-top: 3mm; border-top: 1px solid #ddd;
  font-size: 9pt; color: #777;
}
"""


def main():
    text = SRC.read_text(encoding="utf-8")
    body = markdown.markdown(text, extensions=["tables", "sane_lists"])
    html = f"""<!doctype html>
<html lang="pt-br"><head><meta charset="utf-8">
<title>Pulse Kiosk - Instalacao dos tablets</title>
<style>{CSS}</style></head><body>
{body}
<div class="footer">Pulse Kiosk &middot; kiosk.pulsefitness.com.br &middot;
duvidas: fale com o Joao</div>
</body></html>"""
    HTML.write_text(html, encoding="utf-8")

    edge = next((p for p in EDGE_CANDIDATES if Path(p).exists()), None)
    if not edge:
        print("Edge not found; open the HTML and print to PDF manually:", HTML)
        return 1

    if PDF.exists():
        PDF.unlink()
    subprocess.run(
        [edge, "--headless", "--disable-gpu", "--no-pdf-header-footer",
         f"--print-to-pdf={PDF}", HTML.as_uri()],
        check=True, timeout=180,
    )
    if not PDF.exists():
        print("PDF was not produced")
        return 1
    print(f"PDF: {PDF}  ({round(PDF.stat().st_size/1024)} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
