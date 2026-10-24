#!/usr/bin/env python3
"""Builds the GitHub Pages site: the landing page from site/ and the wiki from docs/ and the changelog.

The site uses the font, background and pixel art of the plugin's own web interface (src/main/resources/web), so both
look the same. Every page in docs/ becomes a wiki page with a sidebar, a search over every heading and links between
the pages rewritten from .md to .html. Run by .github/workflows/pages.yml; locally:

    pip install markdown==3.7
    python3 scripts/build_site.py _site        # then open _site/index.html
"""

import html
import json
import os
import re
import shutil
import sys

import markdown

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = "https://github.com/nurkert/photo-maps"
BLOB = f"{REPO}/blob/main/"
WEB = os.path.join(ROOT, "src/main/resources/web")
ASSETS = ["monocraft.ttf", "monocraft-LICENSE.txt", "background.png", "icon.png", "camera.png", "selfie.png",
          "gallery.png", "map.png", "ink.png", "empty_map.png"]

# the sidebar: groups of (page, title, source file, short description)
PAGES = [
    ("Start", [
        ("index", "Overview", None, "What PhotoMaps is and where everything is written down"),
        ("getting-started", "Getting started", "docs/getting-started.md",
         "Installing, crafting a photo code, uploading and hanging a picture"),
        ("web-interface", "The web interface", "docs/web-interface.md",
         "Choosing, cutting and sending a picture on the phone"),
        ("commands", "Commands and permissions", "docs/commands.md", "Every command and permission"),
    ]),
    ("Server", [
        ("configuration", "Configuration", "docs/configuration.md", "Every option in config.yml, texts and files"),
        ("security", "Security and HTTPS", "docs/security.md",
         "Certificates, Let's Encrypt, reverse proxies and how uploads are protected"),
        ("bedrock", "Bedrock and Geyser", "docs/bedrock.md", "Setup, proxies, what Bedrock players get"),
    ]),
    ("Project", [
        ("changelog", "Changelog", "CHANGELOG.md", "Every release and what changed"),
        ("development", "Development", "docs/development.md", "Building, generated art, smoke tests, releasing"),
    ]),
]
ALL = [page for _, pages in PAGES for page in pages]
DOC_PAGES = {source: page for page, _, source, _ in ALL if source}


def slugify(value, separator="-"):
    """GitHub's heading anchors, so links like configuration.md#texts keep working."""
    value = re.sub(r"<[^>]+>", "", value).strip().lower()
    value = re.sub(r"[^\w\- ]", "", value)
    return value.replace(" ", separator)


def read(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as file:
        return file.read()


def rewrite_link(target, source_dir):
    """A link as the wiki needs it: other pages as .html, images from the site, everything else on GitHub."""
    if re.match(r"^(https?:|mailto:|#)", target) or re.fullmatch(r"[\w-]+\.html(#.*)?", target):
        return target
    path, _, anchor = target.partition("#")
    resolved = os.path.normpath(os.path.join(source_dir, path)) if path else ""
    if resolved in DOC_PAGES:
        return f"{DOC_PAGES[resolved]}.html" + (f"#{anchor}" if anchor else "")
    if resolved.startswith("images/"):
        return "../" + resolved
    return BLOB + resolved + (f"#{anchor}" if anchor else "")


def outdent_fences(text):
    """Python-Markdown cannot nest a fenced code block in a list item: such a block moves out of the list."""

    def outdent(match):
        indent = match.group(1)
        return "\n".join(line[len(indent):] if line.startswith(indent) else line for line in match.group(0).split("\n"))

    return re.sub(r"(?m)^( +)```[^\n]*\n.*?^\1```", outdent, text, flags=re.S)


def render(text, source_dir):
    md = markdown.Markdown(
        extensions=["tables", "fenced_code", "sane_lists", "toc", "md_in_html"],
        extension_configs={"toc": {"slugify": slugify, "permalink": "#", "permalink_title": "Link to this section"}},
    )
    body = md.convert(outdent_fences(text))
    body = re.sub(
        r'(href|src)="([^"]+)"',
        lambda m: f'{m.group(1)}="{html.escape(rewrite_link(html.unescape(m.group(2)), source_dir), quote=True)}"',
        body,
    )
    # long keys like web.https.keystore-password may break after their dots
    body = re.sub(r"<code>([^<]*)</code>",
                  lambda m: "<code>" + re.sub(r"(?<=\w)([./])(?=\w)", r"\1<wbr>", m.group(1)) + "</code>", body)
    body = re.sub(r"<pre><code([^>]*)>(.*?)</code></pre>",
                  lambda m: f"<pre><code{m.group(1)}>" + m.group(2).replace("<wbr>", "") + "</code></pre>",
                  body, flags=re.S)
    body = re.sub(r"<table>.*?</table>", lambda m: f'<div class="table">{m.group(0)}</div>', body, flags=re.S)
    return body, md.toc_tokens


def headings(tokens, page, title):
    found = []
    for token in tokens:
        found.append({"p": page, "t": title, "h": re.sub(r"<[^>]+>", "", token["name"]), "a": token["id"]})
        found.extend(headings(token["children"], page, title))
    return found


def sidebar(current):
    groups = []
    for group, pages in PAGES:
        links = "".join(
            f'<li><a href="{page}.html"{" aria-current=page" if page == current else ""}>{html.escape(title)}</a></li>'
            for page, title, _, _ in pages
        )
        groups.append(f'<p class="nav-group">{group}</p><ul>{links}</ul>')
    return "".join(groups)


def neighbours(page):
    names = [p for p, _, _, _ in ALL]
    index = names.index(page)
    parts = []
    if index > 0:
        prev = ALL[index - 1]
        parts.append(f'<a class="mc-button" href="{prev[0]}.html" rel="prev">&lt; {html.escape(prev[1])}</a>')
    if index < len(ALL) - 1:
        nxt = ALL[index + 1]
        parts.append(f'<a class="mc-button next" href="{nxt[0]}.html" rel="next">{html.escape(nxt[1])} &gt;</a>')
    return f'<nav class="pager" aria-label="More pages">{"".join(parts)}</nav>'


def template(page, title, description, body, source):
    source_link = f'<a href="{BLOB}{source}">{source}</a>' if source else f'<a href="{BLOB}docs">docs/</a>'
    edit = f' · <a href="{REPO}/edit/main/{source}">Improve this page</a>' if source else ""
    page_title = "PhotoMaps Wiki" if page == "index" else f"{title} · PhotoMaps Wiki"
    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{html.escape(page_title)}</title>
  <meta name="description" content="{html.escape(description, quote=True)}">
  <meta name="theme-color" content="#1d1610">
  <link rel="icon" href="../assets/icon.png">
  <link rel="preload" href="../assets/monocraft.ttf" as="font" type="font/ttf" crossorigin>
  <link rel="stylesheet" href="../style.css">
</head>
<body class="wiki">
  <header class="bar">
    <a class="brand shadow" href="../"><img src="../assets/icon.png" alt="" width="32" height="32">PhotoMaps</a>
    <nav class="bar-links">
      <a class="mc-button small" href="index.html">Wiki</a>
      <a class="mc-button small" href="{REPO}">GitHub</a>
    </nav>
  </header>
  <div class="wiki-layout">
    <aside class="panel wiki-nav">
      <label class="sr" for="wiki-search">Search the wiki</label>
      <input class="mc-input" id="wiki-search" type="search" placeholder="Search..." autocomplete="off" spellcheck="false">
      <ul class="results" id="wiki-results" hidden></ul>
      <nav aria-label="Wiki pages">{sidebar(page)}</nav>
    </aside>
    <main class="panel wiki-page" id="content">
      <article class="prose">
        {body}
      </article>
      {neighbours(page)}
      <p class="source">Written down in {source_link}{edit}</p>
    </main>
  </div>
  <footer class="footer shadow">PhotoMaps is open source under the <a href="{BLOB}LICENSE">GNU GPL v3.0</a>. Not an official Minecraft product, not affiliated with Mojang or Microsoft.</footer>
  <script src="search-index.js" defer></script>
  <script src="wiki.js" defer></script>
</body>
</html>
"""


def overview():
    cards = "".join(
        f'<a class="card" href="{page}.html"><span class="card-title">{html.escape(title)}</span>'
        f'<span class="card-text">{html.escape(description)}</span></a>'
        for _, pages in PAGES for page, title, _, description in pages if page != "index"
    )
    return (
        "# PhotoMaps Wiki\n\n"
        "Craft a photo code, scan it with your phone and hang your photo on the wall. This wiki is built from the "
        "documentation in the repository, so it always matches the latest version.\n\n"
        f'<div class="cards" markdown="0">{cards}</div>\n'
    )


def build_wiki(out):
    wiki = os.path.join(out, "wiki")
    os.makedirs(wiki, exist_ok=True)
    index = []
    for page, title, source, description in ALL:
        text, source_dir = (overview(), "") if source is None else (read(source), os.path.dirname(source))
        body, tokens = render(text, source_dir)
        index.extend(headings(tokens, page, title))
        with open(os.path.join(wiki, f"{page}.html"), "w", encoding="utf-8") as file:
            file.write(template(page, title, description, body, source))
    with open(os.path.join(wiki, "search-index.js"), "w", encoding="utf-8") as file:
        file.write("window.WIKI_INDEX = " + json.dumps(index, ensure_ascii=False, separators=(",", ":")) + ";\n")
    print(f"Wiki: {len(ALL)} pages, {len(index)} headings")


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: build_site.py <site directory>")
    out = sys.argv[1]
    if os.path.exists(out):
        shutil.rmtree(out)
    shutil.copytree(os.path.join(ROOT, "site"), out)
    shutil.copytree(os.path.join(ROOT, "images"), os.path.join(out, "images"))
    os.makedirs(os.path.join(out, "assets"), exist_ok=True)
    for name in ASSETS:
        shutil.copy(os.path.join(WEB, name), os.path.join(out, "assets", name))
    build_wiki(out)
    print(f"Site in {out}")


if __name__ == "__main__":
    main()
