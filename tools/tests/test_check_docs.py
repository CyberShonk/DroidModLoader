"""Documentation checks use local fixtures, never network requests."""

import importlib.util
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location('check_docs', Path(__file__).resolve().parents[1] / 'check-docs.py')
DOCS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DOCS)


class CheckDocsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='dml-docs-test-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        for name in DOCS.REQUIRED:
            self.write(name, '# Fixture\n')
        self.write('README.md', '# Fixture\n' + '\n'.join(f'[Guide]({p})' for p in DOCS.README_LINKS))

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')

    def errors(self):
        return '\n'.join(DOCS.check(self.root))

    def test_required_files_and_readme_links(self):
        self.assertEqual('', self.errors())
        (self.root / 'LICENSE').unlink()
        self.assertIn('missing required', self.errors())
        self.write('README.md', '# No navigation\n')
        self.assertIn('missing required link', self.errors())

    def test_relative_links_images_html_and_fragments(self):
        self.write('docs/user-guide.md', '# A `file` & folder\n\n## Repeat\n\n## Repeat\n<a id="custom"></a>\n')
        self.write('docs/troubleshooting.md', '[Guide](user-guide.md#a-file--folder)\n'
                   '[Again](user-guide.md#repeat-1)\n<a href="user-guide.md#custom">Custom</a>\n'
                   '![Image](../assets/a%20b.png)\n<img src="../assets/a%20b.png">\n')
        self.write('assets/a b.png', 'fixture')
        self.assertEqual('', self.errors())
        self.write('docs/troubleshooting.md', '[Bad](user-guide.md#not-there)\n[Lost](missing.md)\n')
        self.assertIn('heading or anchor does not exist', self.errors())
        self.assertIn('local link target does not exist', self.errors())

    def test_reference_links_titles_encoded_paths_and_parentheses(self):
        self.write('docs/a (b).md', '# Reference\n')
        self.write('docs/user-guide.md', '[Read][guide]\n[guide]: <a (b).md#reference> "Title"\n'
                   '[Inline](a%20(b).md#reference "Title")\n[guide][]\n')
        self.assertEqual('', self.errors())
        self.write('docs/user-guide.md', '[Read][missing]\n')
        self.assertIn('undefined link reference', self.errors())

    def test_duplicate_reference_is_reported(self):
        self.write('docs/user-guide.md', '[Ref]: ../README.md\n[ref]: ../README.md\n')
        self.assertIn('duplicate link reference', self.errors())

    def test_examples_and_template_placeholders_are_not_finished_content(self):
        self.write('docs/user-guide.md', '# Examples\n`[example](missing.md) TODO`\n'
                   '```markdown\n[example](missing.md)\nTODO {{EXAMPLE}}\n```\n'
                   '~~~~\n[example](missing.md)\n~~~\n[example](missing.md)\n~~~~\n')
        self.write('releases/templates/release-notes-template.md', '# Draft\nTODO {{VERSION}} YYYY-MM-DD\n')
        self.assertEqual('', self.errors())
        self.write('releases/notes/v0.8.0-beta.md', '# Release\n{{RELEASE_URL}}\n')
        self.assertIn('unfinished placeholder', self.errors())

    def test_unfinished_prose_is_reported(self):
        for token in ('TODO', 'TBD', 'FIXME', '{{VERSION}}', 'YYYY-MM-DD'):
            with self.subTest(token=token):
                self.write('docs/user-guide.md', '# Guide\n' + token)
                self.assertIn('unfinished placeholder', self.errors())

    def test_external_targets_are_not_fetched(self):
        self.write('docs/user-guide.md', '[External](https://example.invalid/never-fetched)\n'
                   '[Mail](mailto:example@example.invalid)\n[CDN](//example.invalid/file)\n')
        self.assertEqual('', self.errors())

    def test_local_link_cannot_leave_repository(self):
        self.write('docs/user-guide.md', '[Outside](../../outside.md)\n')
        self.assertIn('link leaves the repository', self.errors())

    def test_symlink_cannot_hide_outside_document_or_target(self):
        outside = tempfile.TemporaryDirectory(prefix='dml-docs-outside-')
        self.addCleanup(outside.cleanup)
        external = Path(outside.name) / 'external.md'
        external.write_text('# Outside\n')
        (self.root / 'docs/outside.md').symlink_to(external)
        self.write('docs/user-guide.md', '[Outside](outside.md)\n')
        self.assertIn('documentation file leaves the repository', self.errors())
        self.assertIn('link leaves the repository', self.errors())

    def test_issue_templates_are_included(self):
        self.write('.github/ISSUE_TEMPLATE/bug.md', '[Help](../../missing.md)\n')
        self.assertIn('local link target does not exist', self.errors())

    def test_heading_code_punctuation_and_duplicate_anchors(self):
        self.assertEqual({'read-file', 'read-file-1', 'café'},
                         DOCS.anchors('# Read `file`!\n## Read file\n# Café\n'))
        self.write('docs/user-guide.md', '# Guide\n[Self](#guide)\n')
        self.assertEqual('', self.errors())


if __name__ == '__main__':
    unittest.main()
