import { copyFile, mkdir, stat, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const output = join(root, 'website/build');
const source = 'https://github.com/tuprel/tuprel';
const pages = [
  {
    slug: 'getting-started', title: 'Getting started', summary: 'Create a schema, validate it and generate a typed Java client.',
    body: '<p>Run the CLI in a fresh project directory with Java 21. The quickstart uses the source-built launcher and can be run without a database.</p>',
    example: 'tuprel init\ntuprel validate\ntuprel generate\ntuprel generate --check',
    links: [['Reproduce the quickstart', 'docs/getting-started/quickstart.md'], ['Write a schema', 'docs/guides/schema.md']],
  },
  {
    slug: 'installation', title: 'Installation', summary: 'Build the Development Preview from source with Java 21.',
    body: '<p>The available installation path uses the Gradle wrapper. Build the launcher, then invoke it by its full path from your project.</p><p>Public binaries and published library coordinates are pending.</p>',
    example: './gradlew :tuprel-cli:installDist --dependency-verification strict',
    links: [['Installation for Bash and PowerShell', 'docs/getting-started/installation.md']],
  },
  {
    slug: 'cli', title: 'CLI reference', summary: 'Look up commands, options, exit codes and database configuration.',
    body: '<p>Inspect the current launcher with <code>tuprel --help</code>. Use <code>--check</code> to verify formatting and generated sources. Migration preview and status have their own safety and configuration contracts.</p>',
    links: [['Commands and options', 'docs/reference/cli.md'], ['Migration workflows and limits', 'docs/guides/migrations.md']],
  },
  {
    slug: 'java', title: 'Java API', summary: 'Use a generated client with your PostgreSQL DataSource.',
    body: '<p>The generated client offers typed filters, ordering, relations and transactions. The Java guides show where database access occurs and how transactions are scoped.</p>',
    links: [['Java API reference', 'docs/reference/java-api.md'], ['Java client guide', 'docs/guides/java-client.md'],
      ['Queries', 'docs/guides/crud-and-queries.md'], ['Relations', 'docs/guides/relations.md'], ['Transactions', 'docs/guides/transactions.md']],
  },
  {
    slug: 'integrations', title: 'Integration guides', summary: 'Explore the Gradle playground and current setup options.',
    body: '<p>The playground is a runnable Java project with generated sources and a dedicated PostgreSQL setup. The source build includes development integrations for Spring Boot and Gradle. Maven artifact resolution is available as a staging test.</p>',
    links: [['Run the Java playground', 'examples/tuprel-playground/README.md'],
      ['Spring Boot development starter', 'tuprel-spring-boot-starter/README.md'],
      ['Gradle development plugin', 'tuprel-gradle-plugin/README.md'],
      ['Maven staging fixture', 'test-support/distribution/README.md'],
      ['Check supported features', 'docs/product/SUPPORTED_FEATURES.md']],
  },
];

const escape = (text) => text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
const home = 'index.html';
const nav = (selected) => pages.map(({ slug, title }) =>
  '<a href="' + slug + '.html"' + (slug === selected ? ' aria-current="page"' : '') + '>' + title + '</a>').join('\n');
const shell = (title, selected, content) => `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <meta name="color-scheme" content="light dark">
  <title>${escape(title)}</title>
  <link rel="stylesheet" href="style.css">
</head>
<body>
  <a class="skip" href="#content">Skip to content</a>
  <header>
    <a class="wordmark" href="${home}">TUPREL</a>
    <nav aria-label="Documentation">${nav(selected)}</nav>
  </header>
  <main id="content">${content}</main>
  <footer><a href="${source}">Source on GitHub</a><a href="${source}/blob/main/CONTRIBUTING.md">Contribute</a><a href="${source}/blob/main/SECURITY.md">Security</a></footer>
</body>
</html>
`;

await mkdir(output, { recursive: true });
await copyFile(join(root, 'website/style.css'), join(output, 'style.css'));
for (const [label, path] of [
  ['light', 'docs/assets/readme/workflow-light.svg'],
  ['dark', 'docs/assets/readme/workflow-dark.svg'],
  ['mobile-light', 'docs/assets/readme/workflow-mobile-light.svg'],
  ['mobile-dark', 'docs/assets/readme/workflow-mobile-dark.svg'],
]) {
  await copyFile(join(root, path), join(output, 'workflow-' + label + '.svg'));
}
for (const page of pages) {
  for (const [, path] of page.links) {
    if (!(await stat(join(root, path))).isFile()) throw new Error('Missing documentation source ' + path);
  }
  const links = page.links.map(([label, path]) => `<li><a href="${source}/blob/main/${path}">${label}</a></li>`).join('');
  const example = page.example ? `<pre><code>${escape(page.example)}</code></pre>` : '';
  await writeFile(join(output, page.slug + '.html'), shell(page.title + ' · Tuprel', page.slug,
    `<article><p class="kicker">Documentation</p><h1>${page.title}</h1><p class="lead">${page.summary}</p>
    ${page.body}${example}<h2>Read the guide</h2><ul class="links">${links}</ul></article>`));
}

const cards = pages.map(({ slug, title, summary }) =>
  `<li><a href="${slug}.html"><strong>${title}</strong><span>${summary}</span></a></li>`).join('');
await writeFile(join(output, home), shell('Tuprel · Type-safe Java for PostgreSQL', '', `
  <section class="hero" aria-labelledby="intro">
    <p class="kicker">Java 21 / PostgreSQL / Development Preview</p>
    <h1 id="intro">Define the schema.<br>Own the query.</h1>
    <p class="lead">Generate a typed Java client from <code>schema.tuprel</code> and keep queries, relations and transactions explicit.</p>
    <div class="actions"><a href="getting-started.html">Get started</a><a href="installation.html">Installation</a></div>
    <picture>
      <source media="(max-width: 540px) and (prefers-color-scheme: dark)" srcset="workflow-mobile-dark.svg">
      <source media="(max-width: 540px)" srcset="workflow-mobile-light.svg">
      <source media="(prefers-color-scheme: dark)" srcset="workflow-dark.svg">
      <img src="workflow-light.svg" alt="A Tuprel schema generates a typed Java client that connects to PostgreSQL.">
    </picture>
  </section>
  <section aria-labelledby="guide-list"><h2 id="guide-list">Documentation</h2><ul class="cards">${cards}</ul></section>
`));
console.log('Built Tuprel documentation in ' + output);
