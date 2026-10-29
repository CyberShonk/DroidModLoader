#!/usr/bin/env python3
"""Check required documentation, local links, anchors and unfinished text offline."""

import argparse
from html.parser import HTMLParser
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit


REQUIRED = (
    "README.md", "CONTRIBUTING.md", "docs/user-guide.md", "docs/troubleshooting.md",
    "releases/changelog.md", "releases/templates/release-notes-template.md", "LICENSE",
)
README_LINKS = ("docs/user-guide.md", "docs/troubleshooting.md", "releases/changelog.md", "CONTRIBUTING.md")
INLINE_LINK = re.compile(r'\]\(\s*(<[^>\n]+>|(?:\\.|[^\s()\\]|\([^()\n]*\))+)(?:\s+[\'"][^\n]*?[\'"])?\s*\)')
DEFINITION = re.compile(r'^ {0,3}\[([^\]]+)\]:\s*(<[^>\n]+>|\S+)')
REFERENCE = re.compile(r'\[([^\]\n]+)\]\[([^\]\n]*)\]')
PLACEHOLDER = re.compile(r'\{\{\s*[A-Z][A-Z0-9_]*\s*\}\}|\b(?:TODO|TBD|FIXME|YYYY-MM-DD)\b')


def visible_lines(text):
    """Exclude fenced examples and inline code without changing line numbers."""
    fence = None
    for number, raw in enumerate(text.splitlines(), 1):
        if fence:
            if re.fullmatch(r' {0,3}' + re.escape(fence[0]) + '{' + str(len(fence)) + r',}\s*', raw):
                fence = None
            continue
        opening = re.match(r' {0,3}(`{3,}|~{3,})', raw)
        if opening:
            fence = opening[1]
            continue
        yield number, re.sub(r'(`+).*?\1', '', raw)


class HtmlLinks(HTMLParser):
    def __init__(self):
        super().__init__()
        self.targets = []
        self.anchors = set()

    def handle_starttag(self, tag, attrs):
        for name, value in attrs:
            if value and name in ('href', 'src'):
                self.targets.append((self.getpos()[0], value))
            if value and (name == 'id' or tag == 'a' and name == 'name'):
                self.anchors.add(value)


def anchors(text):
    result = set()
    fence = None
    # Heading code spans contribute text to their anchor, unlike link examples.
    for raw in text.splitlines():
        if fence:
            if re.fullmatch(r' {0,3}' + re.escape(fence[0]) + '{' + str(len(fence)) + r',}\s*', raw):
                fence = None
            continue
        opening = re.match(r' {0,3}(`{3,}|~{3,})', raw)
        if opening:
            fence = opening[1]
            continue
        heading = re.match(r'^ {0,3}#{1,6}\s+(.+?)(?:\s+#+)?\s*$', raw)
        if heading:
            label = re.sub(r'<[^>]*>', '', heading[1])
            label = re.sub(r'!?\[([^\]]+)\]\([^)]*\)', r'\1', label)
            slug = re.sub(r'[^\w\- ]', '', label.lower()).replace(' ', '-')
            candidate = slug
            suffix = 0
            while candidate in result:
                suffix += 1
                candidate = f'{slug}-{suffix}'
            result.add(candidate)
    parser = HtmlLinks()
    parser.feed('\n'.join(line for _, line in visible_lines(text)))
    return result | parser.anchors


def documentation_files(root):
    files = {root / name for name in REQUIRED if name.endswith('.md')}
    for folder in ('docs', 'releases/notes', 'releases/templates', '.github'):
        files.update((root / folder).rglob('*.md'))
    return sorted(files)


def check(root):
    root = root.resolve()
    errors = []
    for name in REQUIRED:
        if not (root / name).is_file():
            errors.append(f'{name}: missing required documentation file')
        elif not (root / name).resolve().is_relative_to(root):
            errors.append(f'{name}: documentation file leaves the repository')
    readme = root / 'README.md'
    if readme.is_file() and readme.resolve().is_relative_to(root):
        for target in README_LINKS:
            if target not in readme.read_text(encoding='utf-8'):
                errors.append(f'README.md: missing required link: {target}')
    anchor_cache = {}
    for path in documentation_files(root):
        if not path.is_file():
            continue
        display = path.relative_to(root)
        if not path.resolve().is_relative_to(root):
            errors.append(f'{display}: documentation file leaves the repository')
            continue
        text = path.read_text(encoding='utf-8')
        lines = list(visible_lines(text))
        definitions = {}
        targets = []
        for number, line in lines:
            definition = DEFINITION.match(line)
            if definition:
                key = ' '.join(definition[1].lower().split())
                if key in definitions:
                    errors.append(f'{display}:{number}: duplicate link reference: {key}')
                definitions[key] = definition[2].strip('<>')
                targets.append((number, definitions[key]))
            targets.extend((number, match[1].strip('<>')) for match in INLINE_LINK.finditer(line))
            if 'templates' not in display.parts and PLACEHOLDER.search(line):
                errors.append(f'{display}:{number}: unfinished placeholder')
        for number, line in lines:
            for match in REFERENCE.finditer(line):
                key = ' '.join((match[2] or match[1]).lower().split())
                if key not in definitions:
                    errors.append(f'{display}:{number}: undefined link reference: {key}')
        parser = HtmlLinks()
        # Keep blank lines to preserve HTML line numbers while ignoring examples.
        by_number = dict(lines)
        parser.feed('\n'.join(by_number.get(n, '') for n in range(1, len(text.splitlines()) + 1)))
        targets.extend(parser.targets)
        for number, target in targets:
            try:
                url = urlsplit(target)
                if url.scheme or url.netloc:
                    continue  # External availability is a separate review, not a network check.
                local = unquote(url.path)
                destination = (root / local.lstrip('/') if local.startswith('/') else path.parent / local) if local else path
                destination = destination.resolve()
                if not destination.is_relative_to(root):
                    raise ValueError('link leaves the repository')
                if not destination.exists():
                    raise ValueError('local link target does not exist')
                if url.fragment and destination.suffix.lower() == '.md':
                    if destination not in anchor_cache:
                        anchor_cache[destination] = anchors(destination.read_text(encoding='utf-8'))
                    if unquote(url.fragment) not in anchor_cache[destination]:
                        raise ValueError('heading or anchor does not exist')
            except (OSError, UnicodeError, ValueError) as error:
                errors.append(f'{display}:{number}: {error}: {target}')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    try:
        errors = check(args.root)
    except (OSError, UnicodeError) as error:
        errors = [str(error)]
    if errors:
        print('\n'.join(errors))
        return 1
    print('Documentation files, local links, anchors and placeholders checked. External links and instructions need review.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
