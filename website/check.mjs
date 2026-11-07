import assert from 'node:assert/strict';
import { readFile, stat } from 'node:fs/promises';

const root = new URL('./build/', import.meta.url);
const pages = ['index.html', 'getting-started.html', 'installation.html', 'cli.html', 'java.html', 'integrations.html'];
for (const page of pages) {
  const html = await readFile(new URL(page, root), 'utf8');
  assert.equal([...html.matchAll(/<h1[ >]/g)].length, 1, page);
  assert.match(html, /<main id="content">/);
  for (const [, target] of html.matchAll(/(?:href|src|srcset)="([^"]+)"/g)) {
    if (target.startsWith('https://')) continue;
    if (target.startsWith('#')) {
      assert.ok(html.includes('id="' + target.slice(1) + '"'), page + ' ' + target);
    } else {
      assert.ok((await stat(new URL(target, root))).isFile(), page + ' ' + target);
    }
  }
}
assert.match(await readFile(new URL('installation.html', root), 'utf8'), /Public binaries and published library coordinates are pending/);
console.log('Verified ' + pages.length + ' pages and their local navigation and assets');
