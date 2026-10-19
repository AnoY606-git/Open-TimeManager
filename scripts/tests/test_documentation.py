import html
import re
import unittest
from collections import Counter
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[2]
MARKDOWN_LINK = re.compile(r'!?\[[^\]\n]*\]\(([^\s)]+)(?:\s+"[^"]*")?\)')


def without_fenced_code(text: str) -> str:
    lines = []
    fence = None
    for line in text.splitlines():
        match = re.match(r"^\s*(`{3,}|~{3,})", line)
        if match:
            marker = match.group(1)
            if fence is None:
                fence = marker
            elif marker[0] == fence[0] and len(marker) >= len(fence):
                fence = None
        elif fence is None:
            lines.append(line)
    return "\n".join(lines)


class HtmlReferences(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.targets = []
        self.anchors = set()
        self.images_without_alt = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        for name in ("href", "src"):
            if values.get(name):
                self.targets.append(values[name])
        if values.get("id"):
            self.anchors.add(values["id"])
        if tag == "a" and values.get("name"):
            self.anchors.add(values["name"])
        if tag == "img" and not (values.get("alt") or "").strip():
            self.images_without_alt.append(values.get("src"))


def anchors(text: str) -> set[str]:
    plain = without_fenced_code(text)
    parser = HtmlReferences()
    parser.feed(plain)
    result = set(parser.anchors)
    counts = Counter()
    for heading in re.findall(r"^#{1,6}\s+(.+?)\s*#*\s*$", plain, re.MULTILINE):
        label = html.unescape(re.sub(r"<[^>]*>", "", heading)).lower()
        slug = re.sub(r"[^\w\- ]", "", label).replace(" ", "-")
        suffix = f"-{counts[slug]}" if counts[slug] else ""
        counts[slug] += 1
        result.add(slug + suffix)
    return result


class DocumentationTest(unittest.TestCase):
    def test_public_markdown_local_links_and_anchors_exist(self) -> None:
        documents = sorted([
            *ROOT.glob("*.md"),
            *(ROOT / "docs").rglob("*.md"),
            *(ROOT / ".github").glob("*.md"),
        ])
        for path in documents:
            relative = path.relative_to(ROOT).as_posix()
            text = without_fenced_code(path.read_text(encoding="utf-8"))
            parser = HtmlReferences()
            parser.feed(text)
            targets = [*MARKDOWN_LINK.findall(text), *parser.targets]
            for target in targets:
                with self.subTest(document=relative, target=target):
                    url = urlsplit(html.unescape(target))
                    if url.scheme or url.netloc:
                        continue
                    destination = (path.parent / unquote(url.path)).resolve() if url.path else path
                    self.assertTrue(destination.exists(), "Broken local documentation link")
                    if destination.suffix == ".md" and url.fragment:
                        self.assertIn(
                            unquote(url.fragment),
                            anchors(destination.read_text(encoding="utf-8")),
                            "Missing documentation heading",
                        )

    def test_readme_galleries_include_all_localized_screens(self) -> None:
        screens = (
            "timer", "history", "reports", "settings", "timer-dark",
            "help", "default-project", "backup-warning", "notification", "monthly-plan",
        )
        for language, filename in (("ru", "README.md"), ("en", "README.en.md")):
            with self.subTest(language=language):
                parser = HtmlReferences()
                parser.feed((ROOT / filename).read_text(encoding="utf-8"))
                self.assertEqual(parser.images_without_alt, [])
                images = [target for target in parser.targets if target.startswith("docs/screenshots/")]
                self.assertCountEqual(images, [f"docs/screenshots/{language}-{screen}.png" for screen in screens])


if __name__ == "__main__":
    unittest.main()
